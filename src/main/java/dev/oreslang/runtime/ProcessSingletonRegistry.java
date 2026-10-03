package dev.oreslang.runtime;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Local process-runtime backend for Oreslang singleton modules.
 *
 * A singleton module has exactly one state cell and one serial mailbox when
 * all callers share this host runtime heap. A spawned Graal isolate has its own
 * heap and statics, so this backend must not be used there as a substitute for
 * the trusted host/process singleton coordinator. Importers never receive the state object; generated/runtime
 * proxies submit typed operations to the handle. Arguments and results cross
 * the boundary through {@link ActorRuntime#freeze(Object)} so writable aliases
 * cannot escape the owning actor.
 *
 * Cells intentionally outlive individual ordinary Graal Contexts that share
 * this runtime heap. A spawned Graal isolate has its own heap/statics, so this
 * local backend is NOT sufficient for cross-isolate OS-process singleton
 * identity. Adversarial isolate policy therefore fails closed; production
 * isolate deployments must route the same protocol through a trusted
 * supervisor-owned coordinator.
 */
public final class ProcessSingletonRegistry {
    private static final int MAILBOX_CAPACITY = 8_192;
    private static final int MAX_PROCESS_SINGLETONS = 4_096;
    private static final int MAX_KEY_CHARS = 2_048;
    private static final Duration DEFAULT_WALL_TIME = Duration.ofMinutes(10);
    private static final Duration DEFAULT_INITIALIZATION_WALL_TIME = Duration.ofSeconds(30);
    private static final ScheduledThreadPoolExecutor TIMEOUTS = timeoutExecutor();

    private static final Map<String, Cell> CELLS = new ConcurrentHashMap<>();
    private static final Object REGISTRY_LOCK = new Object();

    /*
     * Each singleton actor is serial. Tracking outstanding singleton-to-
     * singleton calls lets us reject a wait cycle before two mailboxes can
     * deadlock one another.
     */
    private static final Object WAIT_GRAPH_LOCK = new Object();
    private static final Map<String, Map<String, Integer>> WAIT_GRAPH = new HashMap<>();

    private static final ThreadLocal<String> CURRENT_CELL = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_DEADLINE_NANOS = new ThreadLocal<>();
    private static final ThreadLocal<Request> CURRENT_REQUEST = new ThreadLocal<>();

    private ProcessSingletonRegistry() { }

    private static ScheduledThreadPoolExecutor timeoutExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "ores-process-singleton-timeouts");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    @FunctionalInterface
    public interface Operation<S> {
        Object apply(S state, List<Object> arguments) throws Exception;
    }

    public static <S> Handle<S> getOrCreate(String key, Supplier<? extends S> stateFactory) {
        return getOrCreate(key, DEFAULT_INITIALIZATION_WALL_TIME, stateFactory);
    }

    public static <S> Handle<S> getOrCreate(
            String key,
            Duration initializationWallTime,
            Supplier<? extends S> stateFactory) {
        String normalized = Objects.requireNonNull(key, "key").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("singleton key cannot be blank");
        if (normalized.length() > MAX_KEY_CHARS) {
            throw new IllegalArgumentException("singleton key exceeds " + MAX_KEY_CHARS + " characters");
        }
        Objects.requireNonNull(initializationWallTime, "initializationWallTime");
        if (initializationWallTime.isZero() || initializationWallTime.isNegative()) {
            throw new IllegalArgumentException("initializationWallTime must be positive");
        }
        Objects.requireNonNull(stateFactory, "stateFactory");

        Cell cell;
        synchronized (REGISTRY_LOCK) {
            Cell existing = CELLS.get(normalized);
            if (existing == null || existing.retryableInitializationFailure()) {
                if (existing == null && CELLS.size() >= MAX_PROCESS_SINGLETONS) {
                    throw new IllegalStateException("process singleton limit exceeded: " + MAX_PROCESS_SINGLETONS);
                }
                cell = new Cell(normalized, initializationWallTime, stateFactory);
                CELLS.put(normalized, cell);
            } else {
                cell = existing;
            }
        }
        cell.ensureStarted();
        return new Handle<>(cell);
    }

    public static int processSingletonCount() {
        return CELLS.size();
    }

    /**
     * The local static backend is truly process-global only while callers share
     * the host runtime heap. Spawned Graal isolates require a host coordinator
     * and a cross-isolate request/reply bridge.
     */
    public static void requireBackendFor(boolean graalIsolated) {
        if (graalIsolated) {
            throw new IllegalStateException(
                    "OS-process singleton requires the trusted host coordinator when Graal isolation is enabled; "
                            + "the isolate-local static registry is not process-global");
        }
    }

    /**
     * Compiler/runtime-injected cooperative budget check. Singleton function
     * entries and loop safepoints call this so a request cannot consume its
     * caller's wall-time budget indefinitely.
     */
    public static void checkExecutionBudget() {
        Request request = CURRENT_REQUEST.get();
        if (request != null && request.reply.isCancelled()) {
            throw new ExecutionTerminated("singleton call was cancelled for "
                    + diagnosticId(CURRENT_CELL.get()));
        }

        Long deadline = CURRENT_DEADLINE_NANOS.get();
        if (deadline == null) return;
        if (System.nanoTime() - deadline >= 0) {
            throw new ExecutionTerminated("singleton call wall-time budget exceeded for "
                    + diagnosticId(CURRENT_CELL.get()));
        }
    }

    /**
     * Linearizes a singleton state commit against explicit caller cancellation.
     * If cancellation wins first, the commit is rejected. If commit wins first,
     * a later cancellation cannot retroactively roll back already-published
     * state.
     */
    public static void commitIfActive(Runnable commit) {
        Objects.requireNonNull(commit, "commit");
        Request request = CURRENT_REQUEST.get();
        if (request == null) {
            checkExecutionBudget();
            commit.run();
            return;
        }

        synchronized (request.reply.commitLock()) {
            checkExecutionBudget();
            commit.run();
        }
    }

    public static final class Handle<S> {
        private final Cell cell;

        private Handle(Cell cell) {
            this.cell = cell;
        }

        public UUID instanceId() {
            return cell.instanceId;
        }

        /**
         * Host/default request/reply actor call.
         */
        public CompletionStage<Object> call(List<?> arguments, Operation<S> operation) {
            return call(arguments, MAILBOX_CAPACITY, DEFAULT_WALL_TIME, operation);
        }

        /**
         * Policy-bounded request/reply actor call. Queue admission is capped by
         * both the process ceiling and the caller's mailbox policy. The timeout
         * starts at enqueue, so queueing time is part of the wall-time budget.
         */
        public CompletionStage<Object> call(
                List<?> arguments,
                int callerMailboxLimit,
                Duration callerWallTime,
                Operation<S> operation) {
            Objects.requireNonNull(arguments, "arguments");
            Objects.requireNonNull(callerWallTime, "callerWallTime");
            Objects.requireNonNull(operation, "operation");
            if (callerMailboxLimit <= 0) throw new IllegalArgumentException("callerMailboxLimit must be positive");
            if (callerWallTime.isZero() || callerWallTime.isNegative()) {
                throw new IllegalArgumentException("callerWallTime must be positive");
            }

            Throwable terminal = cell.terminalFailure;
            if (terminal != null) return CompletableFuture.failedFuture(terminal);

            Object frozenGraph = ActorRuntime.freeze(arguments);
            if (!(frozenGraph instanceof List<?> frozenList)) {
                throw new IllegalStateException("singleton argument transport did not freeze to a list");
            }
            @SuppressWarnings("unchecked")
            List<Object> frozen = (List<Object>) frozenList;

            String caller = CURRENT_CELL.get();
            WaitEdge waitEdge;
            try {
                waitEdge = caller == null ? null : registerWaitEdge(caller, cell.key);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }

            ReplyFuture reply = new ReplyFuture();
            if (waitEdge != null) reply.whenComplete((ignored, failure) -> waitEdge.close());

            long deadline = effectiveDeadline(callerWallTime);
            if (expired(deadline)) {
                if (waitEdge != null) waitEdge.close();
                return CompletableFuture.failedFuture(
                        new TimeoutException("singleton call inherited an expired parent deadline"));
            }
            Request request = new Request(
                    frozen,
                    (state, args) -> operation.apply(cast(state), args),
                    reply,
                    deadline);

            terminal = cell.terminalFailure;
            if (terminal != null) {
                reply.completeExceptionally(terminal);
                return reply;
            }

            int admissionLimit = Math.min(callerMailboxLimit, MAILBOX_CAPACITY);
            if (!cell.reserveQueuedSlot(admissionLimit)) {
                reply.completeExceptionally(new IllegalStateException(
                        "singleton mailbox limit exceeded for " + cell.diagnosticId
                                + " (caller limit " + admissionLimit + ")"));
                return reply;
            }
            if (!cell.mailbox.offer(request)) {
                cell.releaseQueuedSlot();
                reply.completeExceptionally(new IllegalStateException(
                        "singleton mailbox process ceiling exceeded for " + cell.diagnosticId));
                return reply;
            }

            // Terminal failure can race the pre-enqueue check. If the actor has
            // already exited, remove this late request immediately rather than
            // leaving it stranded until the queue timeout fires.
            terminal = cell.terminalFailure;
            if (terminal != null && cell.mailbox.remove(request)) {
                cell.releaseQueuedSlot();
                request.failBeforeRun(terminal);
                return reply;
            }

            /*
             * A queued request may time out promptly, but a running request is
             * never completed by the timer thread. Once RUNNING, deadline
             * enforcement belongs exclusively to the serial actor and its
             * cooperative safepoints/transaction commit.
             */
            long timeoutDelayNanos = Math.max(1L, deadline - System.nanoTime());
            ScheduledFuture<?> timeoutTask = TIMEOUTS.schedule(() -> {
                if (!request.expireQueued(cell.diagnosticId)) return;
                if (cell.mailbox.remove(request)) cell.releaseQueuedSlot();
            }, timeoutDelayNanos, TimeUnit.NANOSECONDS);
            request.timeoutTask(timeoutTask);
            reply.whenComplete((ignored, failure) -> {
                request.cancelTimeoutTask();
                if (reply.isCancelled() && request.cancelQueued()) {
                    if (cell.mailbox.remove(request)) cell.releaseQueuedSlot();
                }
            });
            return reply;
        }

        @SuppressWarnings("unchecked")
        private S cast(Object state) {
            return (S) state;
        }
    }

    @FunctionalInterface
    private interface ErasedOperation {
        Object apply(Object state, List<Object> arguments) throws Exception;
    }

    private static final class ReplyFuture extends CompletableFuture<Object> {
        private final Object commitLock = new Object();

        private Object commitLock() {
            return commitLock;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (commitLock) {
                return super.cancel(mayInterruptIfRunning);
            }
        }
    }

    private static final class Request {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int TIMED_OUT = 2;
        private static final int DONE = 3;

        private final List<Object> arguments;
        private final ErasedOperation operation;
        private final ReplyFuture reply;
        private final long deadlineNanos;
        private final AtomicInteger phase = new AtomicInteger(QUEUED);
        private volatile ScheduledFuture<?> timeoutTask;

        private Request(
                List<Object> arguments,
                ErasedOperation operation,
                ReplyFuture reply,
                long deadlineNanos) {
            this.arguments = arguments;
            this.operation = operation;
            this.reply = reply;
            this.deadlineNanos = deadlineNanos;
        }

        private boolean begin() {
            return phase.compareAndSet(QUEUED, RUNNING);
        }

        private boolean expireQueued(String diagnosticId) {
            if (!phase.compareAndSet(QUEUED, TIMED_OUT)) return false;
            reply.completeExceptionally(
                    new TimeoutException("singleton call expired in mailbox for " + diagnosticId));
            return true;
        }

        private void finish() {
            phase.compareAndSet(RUNNING, DONE);
        }

        private boolean cancelQueued() {
            return phase.compareAndSet(QUEUED, DONE);
        }

        private void failBeforeRun(Throwable failure) {
            if (phase.compareAndSet(QUEUED, DONE)) {
                reply.completeExceptionally(failure);
            }
        }

        private void timeoutTask(ScheduledFuture<?> task) {
            this.timeoutTask = task;
            // A very fast request can complete between scheduling and this
            // assignment. Cancel here as well as in the completion callback so
            // completed requests do not retain delayed timeout tasks.
            if (reply.isDone()) task.cancel(false);
        }

        private void cancelTimeoutTask() {
            ScheduledFuture<?> task = timeoutTask;
            if (task != null) task.cancel(false);
        }
    }

    private static final class Cell {
        private final String key;
        private final String diagnosticId;
        private final UUID instanceId = UUID.randomUUID();
        private final BlockingQueue<Request> mailbox = new LinkedBlockingQueue<>(MAILBOX_CAPACITY);
        private final AtomicInteger queuedRequests = new AtomicInteger();
        private final AtomicBoolean started = new AtomicBoolean();
        private final Duration initializationWallTime;
        private volatile Supplier<?> stateFactory;
        private volatile Throwable terminalFailure;
        private volatile boolean initialized;

        private Cell(String key, Duration initializationWallTime, Supplier<?> stateFactory) {
            this.key = key;
            this.diagnosticId = diagnosticId(key);
            this.initializationWallTime = initializationWallTime;
            this.stateFactory = stateFactory;
        }

        private boolean retryableInitializationFailure() {
            return terminalFailure != null
                    && !initialized
                    && !(terminalFailure instanceof VirtualMachineError);
        }

        private boolean reserveQueuedSlot(int admissionLimit) {
            while (true) {
                int current = queuedRequests.get();
                if (current >= admissionLimit || current >= MAILBOX_CAPACITY) return false;
                if (queuedRequests.compareAndSet(current, current + 1)) return true;
            }
        }

        private void releaseQueuedSlot() {
            int remaining = queuedRequests.decrementAndGet();
            if (remaining < 0) {
                queuedRequests.incrementAndGet();
                throw new IllegalStateException("singleton queued-request accounting underflow for " + diagnosticId);
            }
        }

        private void ensureStarted() {
            if (!started.compareAndSet(false, true)) return;
            Thread.ofVirtual()
                    .name("ores-process-singleton-" + diagnosticId
                            + "-" + instanceId.toString().substring(0, 8))
                    .start(this::run);
        }

        private void run() {
            Object state;
            Supplier<?> factory = stateFactory;
            stateFactory = null; // do not retain the first isolate/evaluator after initialization
            String initializationPreviousCell = CURRENT_CELL.get();
            Long initializationPreviousDeadline = CURRENT_DEADLINE_NANOS.get();
            CURRENT_CELL.set(key);
            CURRENT_DEADLINE_NANOS.set(effectiveDeadline(initializationWallTime));
            try {
                checkExecutionBudget();
                state = Objects.requireNonNull(factory.get(),
                        "singleton state factory returned null for " + diagnosticId);
                checkExecutionBudget();
                initialized = true;
            } catch (Throwable failure) {
                failTerminal(failure);
                return;
            } finally {
                restoreThreadLocal(CURRENT_CELL, initializationPreviousCell);
                restoreThreadLocal(CURRENT_DEADLINE_NANOS, initializationPreviousDeadline);
            }

            while (true) {
                Request request;
                try {
                    request = mailbox.take();
                    releaseQueuedSlot();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failTerminal(new CancellationException("singleton actor interrupted: " + diagnosticId));
                    return;
                }

                if (!request.begin()) continue;
                if (request.reply.isDone()) {
                    request.finish();
                    continue;
                }
                Throwable failure = terminalFailure;
                if (failure != null) {
                    request.reply.completeExceptionally(failure);
                    request.finish();
                    continue;
                }
                if (expired(request.deadlineNanos)) {
                    request.reply.completeExceptionally(
                            new TimeoutException("singleton call expired in mailbox for " + diagnosticId));
                    request.finish();
                    continue;
                }

                String previousCell = CURRENT_CELL.get();
                Long previousDeadline = CURRENT_DEADLINE_NANOS.get();
                Request previousRequest = CURRENT_REQUEST.get();
                CURRENT_CELL.set(key);
                CURRENT_DEADLINE_NANOS.set(request.deadlineNanos);
                CURRENT_REQUEST.set(request);
                try {
                    checkExecutionBudget();
                    @SuppressWarnings("unchecked")
                    List<Object> ownedArguments =
                            (List<Object>) ActorRuntime.materializeFrozen(request.arguments);
                    Object result = request.operation.apply(state, ownedArguments);
                    checkExecutionBudget();
                    if (!request.reply.isDone()) request.reply.complete(ActorRuntime.freeze(result));
                } catch (VirtualMachineError fatal) {
                    request.reply.completeExceptionally(fatal);
                    failTerminal(fatal);
                    return;
                } catch (Throwable operationFailure) {
                    request.reply.completeExceptionally(operationFailure);
                } finally {
                    request.finish();
                    restoreThreadLocal(CURRENT_CELL, previousCell);
                    restoreThreadLocal(CURRENT_DEADLINE_NANOS, previousDeadline);
                    restoreThreadLocal(CURRENT_REQUEST, previousRequest);
                }
            }
        }

        private void failTerminal(Throwable failure) {
            terminalFailure = Objects.requireNonNull(failure);
            Request queued;
            while ((queued = mailbox.poll()) != null) {
                releaseQueuedSlot();
                queued.failBeforeRun(failure);
            }
        }
    }

    private static WaitEdge registerWaitEdge(String caller, String target) {
        if (caller.equals(target)) {
            throw new IllegalStateException("reentrant singleton mailbox call would deadlock: "
                    + diagnosticId(caller));
        }
        synchronized (WAIT_GRAPH_LOCK) {
            if (pathExists(target, caller)) {
                throw new IllegalStateException(
                        "singleton wait cycle rejected before deadlock: "
                                + diagnosticId(caller) + " -> " + diagnosticId(target));
            }
            WAIT_GRAPH.computeIfAbsent(caller, ignored -> new HashMap<>())
                    .merge(target, 1, Integer::sum);
        }
        return new WaitEdge(caller, target);
    }

    private static boolean pathExists(String start, String wanted) {
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (current.equals(wanted)) return true;
            Map<String, Integer> outgoing = WAIT_GRAPH.get(current);
            if (outgoing != null) pending.addAll(outgoing.keySet());
        }
        return false;
    }

    private static void removeWaitEdge(String caller, String target) {
        synchronized (WAIT_GRAPH_LOCK) {
            Map<String, Integer> outgoing = WAIT_GRAPH.get(caller);
            if (outgoing == null) return;
            Integer count = outgoing.get(target);
            if (count == null) return;
            if (count <= 1) outgoing.remove(target);
            else outgoing.put(target, count - 1);
            if (outgoing.isEmpty()) WAIT_GRAPH.remove(caller);
        }
    }

    private static final class WaitEdge implements AutoCloseable {
        private final String caller;
        private final String target;
        private final AtomicBoolean closed = new AtomicBoolean();

        private WaitEdge(String caller, String target) {
            this.caller = caller;
            this.target = target;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) removeWaitEdge(caller, target);
        }
    }

    private static String diagnosticId(String key) {
        if (key == null) return "unknown";
        return Integer.toUnsignedString(key.hashCode(), 16);
    }

    private static long effectiveDeadline(Duration duration) {
        long delta;
        try {
            delta = duration.toNanos();
        } catch (ArithmeticException overflow) {
            delta = Long.MAX_VALUE / 4;
        }
        /*
         * nanoTime() is an arbitrary signed origin and may wrap. Deadline
         * comparisons use subtraction, which is safe as long as each interval
         * stays below 2^63 ns; clamp pathological host policies accordingly.
         */
        delta = Math.max(1L, Math.min(delta, Long.MAX_VALUE / 4));
        long now = System.nanoTime();

        Long inheritedDeadline = CURRENT_DEADLINE_NANOS.get();
        if (inheritedDeadline != null) {
            long remaining = inheritedDeadline - now;
            if (remaining <= 0) return now;
            delta = Math.min(delta, remaining);
        }
        return now + delta;
    }

    private static boolean expired(long deadline) {
        return System.nanoTime() - deadline >= 0;
    }

    private static <T> void restoreThreadLocal(ThreadLocal<T> local, T previous) {
        if (previous == null) local.remove();
        else local.set(previous);
    }
}
