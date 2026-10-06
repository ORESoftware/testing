package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Scheduler affinity for ordinary Oreslang tasks.
 *
 * <p>An Oreslang task owns one scheduler for its complete logical lifetime.
 * Physical carrier-thread identity is intentionally not stable: after an
 * {@code await}, the continuation may resume on any carrier owned by the same
 * scheduler.</p>
 *
 * <p>Futures do not own schedulers. A suspended task records the scheduler that
 * was executing it when it reached {@code await}; Future completion only makes
 * that task runnable again. Producer, timer, I/O, JNI, and other completion
 * threads never execute the task continuation directly.</p>
 *
 * <p>The compiler lowers an async callable to {@link Task}: a small resumable
 * state machine. Returning {@link Await} suspends the task. Returning
 * {@link Done} completes it.</p>
 */
public final class OresScheduler implements AutoCloseable {
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final AtomicLong NEXT_DISPATCH_ID = new AtomicLong();
    private static final ThreadLocal<OresScheduler> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_DISPATCH_ID = new ThreadLocal<>();
    private static final ThreadLocal<Object> CURRENT_TASK_DOMAIN = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SCHEDULER_CARRIER = new ThreadLocal<>();
    private static final int DEFAULT_QUEUE_CAPACITY = 65_536;

    @FunctionalInterface
    interface TurnExecutor {
        void execute(Runnable turn);
    }

    /** One compiler-generated async state-machine turn. */
    @FunctionalInterface
    public interface Task<T> {
        Step<T> resume(Resume resume) throws Exception;
    }

    /** Result of one resumable task turn. */
    public sealed interface Step<T> permits Done, Await { }

    /** The async task has produced its final value. */
    public record Done<T>(T value) implements Step<T> { }

    /**
     * The async task is suspended on a Future. The scheduler, not the Future,
     * owns the continuation placement.
     */
    public record Await<T>(OresFuture<?> future) implements Step<T> {
        public Await {
            Objects.requireNonNull(future, "future");
        }
    }

    /**
     * Input delivered to a compiler-generated state machine when it starts or
     * resumes after an await.
     */
    public record Resume(boolean initial, Object value, Throwable failure) {
        private static Resume initialResume() {
            return new Resume(true, null, null);
        }

        private static Resume completed(Object value, Throwable failure) {
            return new Resume(false, value, failure);
        }
    }

    private final String name;
    private final int parallelism;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final TurnExecutor turnExecutor;
    private final Set<TaskRunner<?>> tasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Create a user-owned scheduler with exactly {@code parallelism} carrier
     * threads and a bounded ready queue.
     */
    public OresScheduler(int parallelism) {
        this(parallelism, DEFAULT_QUEUE_CAPACITY, Runnable::run);
    }

    public OresScheduler(int parallelism, int queueCapacity) {
        this(parallelism, queueCapacity, Runnable::run);
    }

    private OresScheduler(
            int parallelism,
            int queueCapacity,
            TurnExecutor turnExecutor) {
        if (parallelism <= 0) {
            throw new IllegalArgumentException("scheduler parallelism must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("scheduler queue capacity must be positive");
        }

        this.name = "ores-user-scheduler-" + NEXT_ID.incrementAndGet();
        this.parallelism = parallelism;
        this.turnExecutor = Objects.requireNonNull(turnExecutor, "turnExecutor");

        AtomicInteger carrierId = new AtomicInteger();
        ThreadFactory factory = task -> Thread.ofPlatform()
                .daemon(true)
                .name(name + "-carrier-" + carrierId.getAndIncrement())
                .unstarted(() -> {
                    SCHEDULER_CARRIER.set(Boolean.TRUE);
                    try {
                        task.run();
                    } finally {
                        SCHEDULER_CARRIER.remove();
                    }
                });
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
        pool.prestartAllCoreThreads();
        this.executor = pool;
        this.ownedExecutor = pool;
    }

    private OresScheduler(
            String name,
            int parallelism,
            Executor executor,
            ExecutorService ownedExecutor,
            TurnExecutor turnExecutor) {
        this.name = Objects.requireNonNull(name, "name");
        if (parallelism <= 0) {
            throw new IllegalArgumentException("scheduler parallelism must be positive");
        }
        this.parallelism = parallelism;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownedExecutor = ownedExecutor;
        this.turnExecutor = Objects.requireNonNull(turnExecutor, "turnExecutor");
    }

    /**
     * Runtime-owned scheduler facade over an existing VM executor. Closing the
     * facade cancels its tasks but does not shut down the shared VM executor.
     */
    static OresScheduler runtimeOwned(
            String name,
            int parallelism,
            Executor executor) {
        return runtimeOwned(name, parallelism, executor, Runnable::run);
    }

    /**
     * Runtime-owned scheduler whose physical carrier dispatch is distinct from
     * guest-turn admission. The carrier executor owns only where the task runs;
     * the turn executor owns the entered language/context boundary.
     *
     * <p>This separation is critical for await completion publication:
     * {@link TaskRunner#afterCarrierTurn()} runs after {@code turnExecutor}
     * returns, so a terminal Future cannot become externally visible until the
     * guest turn has completely left its Truffle context.</p>
     */
    static OresScheduler runtimeOwned(
            String name,
            int parallelism,
            Executor executor,
            TurnExecutor turnExecutor) {
        return new OresScheduler(
                name,
                parallelism,
                executor,
                null,
                turnExecutor);
    }

    /**
     * Context-owned scheduler with private carriers. Each guest turn is wrapped
     * by the owning language context before scheduler binding is installed.
     */
    static OresScheduler managed(
            int parallelism,
            TurnExecutor turnExecutor) {
        return new OresScheduler(
                parallelism,
                DEFAULT_QUEUE_CAPACITY,
                turnExecutor);
    }

    /** True only on private carriers owned by user-created OresSchedulers. */
    public static boolean isSchedulerCarrierThread() {
        return Boolean.TRUE.equals(SCHEDULER_CARRIER.get());
    }

    public String name() {
        return name;
    }

    public int parallelism() {
        return parallelism;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Scheduler currently executing this Ores task turn, if any. */
    public static OresScheduler current() {
        return CURRENT.get();
    }

    public static OresScheduler requireCurrent() {
        OresScheduler scheduler = CURRENT.get();
        if (scheduler == null) {
            throw new IllegalStateException(
                    "operation requires an executing OresScheduler task");
        }
        return scheduler;
    }

    /**
     * Stable logical execution-domain token for the currently running
     * scheduler task, or {@code null} outside a scheduler task.
     *
     * <p>The token survives await/resume carrier migration and is intentionally
     * distinct for concurrent tasks sharing the same OresScheduler.</p>
     */
    public static Object currentTaskDomain() {
        return CURRENT_TASK_DOMAIN.get();
    }

    /**
     * Identifier for the current scheduler dispatch turn, or {@code 0} outside
     * an OresScheduler dispatch. A continuation resumed after {@code await}
     * always observes a different dispatch id, even when the scheduler chooses
     * the same physical carrier thread immediately.
     */
    public static long currentDispatchId() {
        Long id = CURRENT_DISPATCH_ID.get();
        return id == null ? 0L : id;
    }

    public static <T> Step<T> done(T value) {
        return new Done<>(value);
    }

    public static <T> Step<T> await(OresFuture<?> future) {
        return new Await<>(Objects.requireNonNull(future, "future"));
    }

    /**
     * Start one compiler-lowered async task on this scheduler.
     *
     * <p>The returned Future represents the whole task. Awaiting it from another
     * scheduler does not move the waiter onto this scheduler.</p>
     */
    public <T> OresFuture<T> start(Task<T> task) {
        Objects.requireNonNull(task, "task");
        ensureOpen();

        TaskRunner<T> runner = new TaskRunner<>(task);
        tasks.add(runner);
        try {
            runner.scheduleInitial();
        } catch (RuntimeException | Error failure) {
            runner.failBeforeStart(failure);
        }
        return runner.completion;
    }

    /**
     * Runtime/host bridge for non-suspending work. Source-level synchronous
     * lambdas may lower to this path.
     */
    public <T> OresFuture<T> startSync(Callable<? extends T> task) {
        Objects.requireNonNull(task, "task");
        AtomicBoolean entered = new AtomicBoolean();
        return start(resume -> {
            if (!resume.initial() || !entered.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "synchronous scheduler task was resumed more than once");
            }
            return done(task.call());
        });
    }

    /**
     * Bind an already-admitted VM CONTROL/root turn to this scheduler without
     * hopping threads. Used by the legacy root-task bridge while source-level
     * main/async lowering moves to {@link #start(Task)}.
     */
    void runBound(Runnable turn) {
        Objects.requireNonNull(turn, "turn");
        ensureOpen();
        OresScheduler prior = CURRENT.get();
        Long priorDispatch = CURRENT_DISPATCH_ID.get();
        CURRENT.set(this);
        CURRENT_DISPATCH_ID.set(NEXT_DISPATCH_ID.incrementAndGet());
        try {
            turn.run();
        } finally {
            if (priorDispatch == null) {
                CURRENT_DISPATCH_ID.remove();
            } else {
                CURRENT_DISPATCH_ID.set(priorDispatch);
            }
            if (prior == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(prior);
            }
        }
    }

    private void executeTurn(Runnable turn, Runnable afterTurn,
            java.util.function.Consumer<Throwable> admissionFailure) {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(afterTurn, "afterTurn");
        ensureOpen();
        executor.execute(() -> {
            boolean priorCarrier = Boolean.TRUE.equals(SCHEDULER_CARRIER.get());
            SCHEDULER_CARRIER.set(Boolean.TRUE);
            try {
                turnExecutor.execute(() -> runBound(turn));
            } catch (RuntimeException | Error failure) {
                // Context admission can fail before runTurn starts. Settle the
                // owning task instead of leaving the host waiting forever.
                admissionFailure.accept(failure);
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                if (failure instanceof ThreadDeath fatal) throw fatal;
                if (failure instanceof LinkageError fatal) throw fatal;
            } finally {
                if (priorCarrier) {
                    SCHEDULER_CARRIER.set(Boolean.TRUE);
                } else {
                    SCHEDULER_CARRIER.remove();
                }
                afterTurn.run();
            }
        });
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new RejectedExecutionException(
                    "OresScheduler " + name + " is closed");
        }
    }

    @Override
    public void close() {
        if (CURRENT.get() == this) {
            throw new IllegalStateException(
                    "OresScheduler cannot be closed from one of its own task turns; "
                            + "close it from an outside/root scheduler task");
        }
        if (!closed.compareAndSet(false, true)) return;

        for (TaskRunner<?> task : Set.copyOf(tasks)) {
            task.cancelFromSchedulerClose();
        }
        tasks.clear();

        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
            if (CURRENT.get() != this) {
                try {
                    if (!ownedExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "OresScheduler " + name + " carriers did not terminate");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException(
                            "interrupted while closing OresScheduler " + name);
                }
            }
        }
    }

    private final class TaskRunner<T> implements BiConsumer<Object, Throwable> {
        private static final int NEW = 0;
        private static final int QUEUED = 1;
        private static final int RUNNING = 2;
        private static final int WAITING = 3;
        private static final int TERMINAL = 4;

        private final Task<T> task;
        private final java.util.Map<String, dev.oreslang.interop.MixedInteropBridge.Invoker>
                interopScope = dev.oreslang.interop.MixedInteropBridge.capture();
        private final AtomicInteger phase = new AtomicInteger(NEW);
        private final AtomicBoolean executing = new AtomicBoolean();
        private final AtomicReference<Resume> pendingResume =
                new AtomicReference<>(Resume.initialResume());
        private final AtomicReference<TerminalOutcome<T>> terminalOutcome =
                new AtomicReference<>();
        private final AtomicReference<OresFuture.RuntimeWaiterRegistration<?>>
                activeAwaitRegistration = new AtomicReference<>();
        private final OresFuture<T> completion;

        private TaskRunner(Task<T> task) {
            this.task = task;
            this.completion = new OresFuture<>(this::cancelFromFuture);
        }

        private void scheduleInitial() {
            if (!phase.compareAndSet(NEW, QUEUED)) {
                throw new IllegalStateException("scheduler task was already started");
            }
            enqueueTurn();
        }

        private void enqueueTurn() {
            try {
                executeTurn(() -> {
                    try (var scope = dev.oreslang.interop.MixedInteropBridge.open(interopScope)) {
                        runTurn();
                    }
                }, this::afterCarrierTurn, this::failTerminal);
            } catch (RuntimeException | Error rejected) {
                failTerminal(rejected);
                throw rejected;
            }
        }

        private void runTurn() {
            if (!phase.compareAndSet(QUEUED, RUNNING)) return;
            if (!executing.compareAndSet(false, true)) {
                failTerminal(new IllegalStateException(
                        "OresScheduler task execution lease violation"));
                return;
            }

            Object priorTaskDomain = CURRENT_TASK_DOMAIN.get();
            CURRENT_TASK_DOMAIN.set(this);
            try {
                detachActiveAwaitRegistration();
                Resume resume = pendingResume.getAndSet(null);
                if (resume == null) {
                    failTerminal(new IllegalStateException(
                            "scheduler resumed a task without an await result"));
                    return;
                }

                final Step<T> step;
                try {
                    step = Objects.requireNonNull(
                            task.resume(resume),
                            "OresScheduler task returned null Step");
                } catch (Throwable failure) {
                    failTerminal(failure);
                    if (failure instanceof VirtualMachineError fatal) throw fatal;
                    if (failure instanceof ThreadDeath fatal) throw fatal;
                    if (failure instanceof LinkageError fatal) throw fatal;
                    return;
                }

                if (step instanceof Done<?> done) {
                    @SuppressWarnings("unchecked")
                    T value = (T) done.value();
                    finish(value);
                    return;
                }

                if (step instanceof Await<?> await) {
                    armAwait(await.future());
                    return;
                }

                failTerminal(new IllegalStateException(
                        "unknown OresScheduler task step " + step.getClass().getName()));
            } finally {
                if (priorTaskDomain == null) {
                    CURRENT_TASK_DOMAIN.remove();
                } else {
                    CURRENT_TASK_DOMAIN.set(priorTaskDomain);
                }
            }
        }

        /**
         * Runs only after the scheduler binding has been removed and the
         * carrier has completely unwound the logical guest turn. Publishing a
         * task Future earlier can let a host close its Polyglot Context while
         * this carrier is still executing guest continuation code.
         */
        private void afterCarrierTurn() {
            executing.set(false);
            publishTerminalIfReady();
            scheduleReadyResume();
        }

        private void armAwait(OresFuture<?> awaited) {
            if (!phase.compareAndSet(RUNNING, WAITING)) {
                return;
            }

            try {
                // Hot path: terminal Futures are immutable. Observe their
                // already-published terminal state directly and enqueue the
                // continuation for a fresh dispatch without allocating a
                // waiter, queue node, registration, or capturing callback.
                //
                // This deliberately does NOT resume inline. executing is still
                // true until afterCarrierTurn(), so deliverAwaitCompletion()
                // can only make the task ready; the scheduler re-enters it
                // through a later dispatch after the current stack unwinds.
                Object terminal = awaited.runtimeTerminalStateOrNull();
                if (terminal != null) {
                    deliverAwaitCompletion(
                            OresFuture.runtimeTerminalValue(terminal),
                            OresFuture.runtimeTerminalFailure(terminal));
                    return;
                }

                // Pending path: TaskRunner itself is the reusable callback
                // target, avoiding a new captured lambda for every await. The
                // detachable registration remains per suspension so scheduler
                // close/task cancellation can sever retention safely.
                OresFuture.RuntimeWaiterRegistration<?> registration =
                        awaited.whenCompleteRuntimeCancellable(this);

                OresFuture.RuntimeWaiterRegistration<?> previous =
                        activeAwaitRegistration.getAndSet(registration);
                if (previous != null) previous.detach();

                // Settlement may race between the terminal observation above
                // and waiter publication. whenCompleteRuntimeCancellable()
                // handles that race and may call accept(...) synchronously.
                // If it did, clear the already-claimed registration now.
                if (phase.get() != WAITING || pendingResume.get() != null) {
                    detachActiveAwaitRegistration();
                }
            } catch (RuntimeException | Error registrationFailure) {
                failTerminal(registrationFailure);
            }
        }

        @Override
        public void accept(Object value, Throwable failure) {
            deliverAwaitCompletion(value, failure);
        }

        private void deliverAwaitCompletion(Object value, Throwable failure) {
            if (phase.get() == TERMINAL) return;

            Resume resume = Resume.completed(
                    value,
                    failure == null ? null : OresFuture.unwrap(failure));
            if (!pendingResume.compareAndSet(null, resume)) {
                failTerminal(new IllegalStateException(
                        "await delivered more than one resume to the same task"));
                return;
            }

            if (phase.get() == TERMINAL) {
                pendingResume.compareAndSet(resume, null);
                return;
            }
            scheduleReadyResume();
        }

        /**
         * Producer completion may happen on any thread. It may only make this
         * task runnable. The continuation itself executes after the prior turn
         * released its execution lease and only through this scheduler.
         */
        private void scheduleReadyResume() {
            if (executing.get()) return;
            if (pendingResume.get() == null) return;
            if (!phase.compareAndSet(WAITING, QUEUED)) return;

            try {
                enqueueTurn();
            } catch (RejectedExecutionException rejected) {
                // enqueueTurn already failed the task.
            }
        }

        private void detachActiveAwaitRegistration() {
            OresFuture.RuntimeWaiterRegistration<?> registration =
                    activeAwaitRegistration.getAndSet(null);
            if (registration != null) registration.detach();
        }

        private void finish(T value) {
            detachActiveAwaitRegistration();
            if (!phase.compareAndSet(RUNNING, TERMINAL)) {
                return;
            }
            terminalOutcome.set(new TerminalSuccess<>(value));
            tasks.remove(this);
            // afterCarrierTurn() publishes only after the carrier has fully
            // unwound runBound(...).
        }

        private void failTerminal(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            detachActiveAwaitRegistration();
            int observed;
            do {
                observed = phase.get();
                if (observed == TERMINAL) return;
            } while (!phase.compareAndSet(observed, TERMINAL));

            terminalOutcome.compareAndSet(null, new TerminalFailure<>(failure));
            tasks.remove(this);
            if (!executing.get()) {
                publishTerminalIfReady();
            }
        }

        private void publishTerminalIfReady() {
            if (phase.get() != TERMINAL || completion.isDone()) return;
            TerminalOutcome<T> outcome = terminalOutcome.get();
            if (outcome instanceof TerminalSuccess<?> success) {
                @SuppressWarnings("unchecked")
                T value = (T) success.value();
                completion.completeFromRuntime(value);
            } else if (outcome instanceof TerminalFailure<?> failure) {
                completion.failFromRuntime(failure.failure());
            }
        }

        private void failBeforeStart(Throwable failure) {
            failTerminal(failure);
        }

        private void cancelFromFuture() {
            detachActiveAwaitRegistration();
            int observed;
            do {
                observed = phase.get();
                if (observed == TERMINAL) return;
            } while (!phase.compareAndSet(observed, TERMINAL));
            tasks.remove(this);
            pendingResume.set(null);
        }

        private void cancelFromSchedulerClose() {
            completion.cancel(false);
        }
    }

    private sealed interface TerminalOutcome<T>
            permits TerminalSuccess, TerminalFailure { }

    private record TerminalSuccess<T>(T value) implements TerminalOutcome<T> { }

    private record TerminalFailure<T>(Throwable failure)
            implements TerminalOutcome<T> {
        private TerminalFailure {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
