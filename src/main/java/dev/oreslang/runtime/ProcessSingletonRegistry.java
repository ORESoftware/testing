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

    private ProcessSingletonRegistry() { }

    @FunctionalInterface
    public interface Operation<S> {
        Object apply(S state, List<Object> arguments) throws Exception;
    }

    public static <S> Handle<S> getOrCreate(String key, Supplier<? extends S> stateFactory) {
        String normalized = Objects.requireNonNull(key, "key").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("singleton key cannot be blank");
        if (normalized.length() > MAX_KEY_CHARS) {
            throw new IllegalArgumentException("singleton key exceeds " + MAX_KEY_CHARS + " characters");
        }
        Objects.requireNonNull(stateFactory, "stateFactory");

        Cell cell;
        synchronized (REGISTRY_LOCK) {
            Cell existing = CELLS.get(normalized);
            if (existing == null || existing.retryableInitializationFailure()) {
                if (existing == null && CELLS.size() >= MAX_PROCESS_SINGLETONS) {
                    throw new IllegalStateException("process singleton limit exceeded: " + MAX_PROCESS_SINGLETONS);
                }
                cell = new Cell(normalized, stateFactory);
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
        Long deadline = CURRENT_DEADLINE_NANOS.get();
        if (deadline == null) return;
        if (System.nanoTime() - deadline >= 0) {
            throw new CancellationException("singleton call wall-time budget exceeded for "
                    + diagnosticId(CURRENT_CELL.get()));
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

            List<Object> frozen = new ArrayList<>(arguments.size());
            for (Object argument : arguments) frozen.add(ActorRuntime.freeze(argument));

            String caller = CURRENT_CELL.get();
            WaitEdge waitEdge;
            try {
                waitEdge = caller == null ? null : registerWaitEdge(caller, cell.key);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }

            CompletableFuture<Object> reply = new CompletableFuture<>();
            if (waitEdge != null) reply.whenComplete((ignored, failure) -> waitEdge.close());

            long deadline = deadlineAfter(callerWallTime);
            Request request = new Request(
                    List.copyOf(frozen),
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

            long timeoutMillis;
            try {
                timeoutMillis = Math.max(1L, callerWallTime.toMillis());
            } catch (ArithmeticException overflow) {
                timeoutMillis = Long.MAX_VALUE;
            }
            reply.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
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

    private record Request(
            List<Object> arguments,
            ErasedOperation operation,
            CompletableFuture<Object> reply,
            long deadlineNanos) { }

    private static final class Cell {
        private final String key;
        private final String diagnosticId;
        private final UUID instanceId = UUID.randomUUID();
        private final BlockingQueue<Request> mailbox = new LinkedBlockingQueue<>(MAILBOX_CAPACITY);
        private final AtomicInteger queuedRequests = new AtomicInteger();
        private final AtomicBoolean started = new AtomicBoolean();
        private volatile Supplier<?> stateFactory;
        private volatile Throwable terminalFailure;
        private volatile boolean initialized;

        private Cell(String key, Supplier<?> stateFactory) {
            this.key = key;
            this.diagnosticId = diagnosticId(key);
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
            try {
                state = Objects.requireNonNull(factory.get(),
                        "singleton state factory returned null for " + key);
                initialized = true;
            } catch (Throwable failure) {
                failTerminal(failure);
                return;
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

                if (request.reply.isDone()) continue;
                Throwable failure = terminalFailure;
                if (failure != null) {
                    request.reply.completeExceptionally(failure);
                    continue;
                }
                if (expired(request.deadlineNanos)) {
                    request.reply.completeExceptionally(
                            new TimeoutException("singleton call expired in mailbox for " + diagnosticId));
                    continue;
                }

                String previousCell = CURRENT_CELL.get();
                Long previousDeadline = CURRENT_DEADLINE_NANOS.get();
                CURRENT_CELL.set(key);
                CURRENT_DEADLINE_NANOS.set(request.deadlineNanos);
                try {
                    checkExecutionBudget();
                    Object result = request.operation.apply(state, request.arguments);
                    checkExecutionBudget();
                    if (!request.reply.isDone()) request.reply.complete(ActorRuntime.freeze(result));
                } catch (VirtualMachineError fatal) {
                    request.reply.completeExceptionally(fatal);
                    failTerminal(fatal);
                    return;
                } catch (Throwable operationFailure) {
                    request.reply.completeExceptionally(operationFailure);
                } finally {
                    restoreThreadLocal(CURRENT_CELL, previousCell);
                    restoreThreadLocal(CURRENT_DEADLINE_NANOS, previousDeadline);
                }
            }
        }

        private void failTerminal(Throwable failure) {
            terminalFailure = Objects.requireNonNull(failure);
            Request queued;
            while ((queued = mailbox.poll()) != null) {
                releaseQueuedSlot();
                queued.reply.completeExceptionally(failure);
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

    private static long deadlineAfter(Duration duration) {
        long delta;
        try {
            delta = duration.toNanos();
        } catch (ArithmeticException overflow) {
            delta = Long.MAX_VALUE / 4;
        }
        /*
         * nanoTime() is an arbitrary signed origin and may wrap. Deadline
         * comparisons use subtraction, which is safe as long as the interval
         * stays below 2^63 ns; clamp pathological host policies accordingly.
         */
        delta = Math.max(1L, Math.min(delta, Long.MAX_VALUE / 4));
        return System.nanoTime() + delta;
    }

    private static boolean expired(long deadline) {
        return deadline != Long.MAX_VALUE && System.nanoTime() - deadline >= 0;
    }

    private static <T> void restoreThreadLocal(ThreadLocal<T> local, T previous) {
        if (previous == null) local.remove();
        else local.set(previous);
    }
}
