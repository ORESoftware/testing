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
    private static final ThreadLocal<OresScheduler> CURRENT = new ThreadLocal<>();
    private static final int DEFAULT_QUEUE_CAPACITY = 65_536;

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
    private final Set<TaskRunner<?>> tasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Create a user-owned scheduler with exactly {@code parallelism} carrier
     * threads and a bounded ready queue.
     */
    public OresScheduler(int parallelism) {
        this(parallelism, DEFAULT_QUEUE_CAPACITY);
    }

    public OresScheduler(int parallelism, int queueCapacity) {
        if (parallelism <= 0) {
            throw new IllegalArgumentException("scheduler parallelism must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("scheduler queue capacity must be positive");
        }

        this.name = "ores-user-scheduler-" + NEXT_ID.incrementAndGet();
        this.parallelism = parallelism;

        ThreadFactory factory = Thread.ofPlatform()
                .daemon(true)
                .name(name + "-carrier-", 0)
                .factory();
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
            ExecutorService ownedExecutor) {
        this.name = Objects.requireNonNull(name, "name");
        if (parallelism <= 0) {
            throw new IllegalArgumentException("scheduler parallelism must be positive");
        }
        this.parallelism = parallelism;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownedExecutor = ownedExecutor;
    }

    /**
     * Runtime-owned scheduler facade over an existing VM executor. Closing the
     * facade cancels its tasks but does not shut down the shared VM executor.
     */
    static OresScheduler runtimeOwned(
            String name,
            int parallelism,
            Executor executor) {
        return new OresScheduler(name, parallelism, executor, null);
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
        CURRENT.set(this);
        try {
            turn.run();
        } finally {
            if (prior == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(prior);
            }
        }
    }

    private void executeTurn(Runnable turn) {
        ensureOpen();
        executor.execute(() -> runBound(turn));
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new RejectedExecutionException(
                    "OresScheduler " + name + " is closed");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        for (TaskRunner<?> task : Set.copyOf(tasks)) {
            task.cancelFromSchedulerClose();
        }
        tasks.clear();

        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    private final class TaskRunner<T> {
        private static final int NEW = 0;
        private static final int QUEUED = 1;
        private static final int RUNNING = 2;
        private static final int WAITING = 3;
        private static final int TERMINAL = 4;

        private final Task<T> task;
        private final AtomicInteger phase = new AtomicInteger(NEW);
        private final AtomicBoolean executing = new AtomicBoolean();
        private final AtomicReference<Resume> pendingResume =
                new AtomicReference<>(Resume.initialResume());
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
                executeTurn(this::runTurn);
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

            try {
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
                executing.set(false);
                scheduleReadyResume();
            }
        }

        private void armAwait(OresFuture<?> awaited) {
            if (!phase.compareAndSet(RUNNING, WAITING)) {
                return;
            }

            try {
                awaited.whenCompleteRuntime((value, failure) -> {
                    Resume resume = Resume.completed(
                            value,
                            failure == null ? null : OresFuture.unwrap(failure));
                    if (!pendingResume.compareAndSet(null, resume)) {
                        failTerminal(new IllegalStateException(
                                "await delivered more than one resume to the same task"));
                        return;
                    }
                    scheduleReadyResume();
                });
            } catch (RuntimeException | Error registrationFailure) {
                failTerminal(registrationFailure);
            }
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

        private void finish(T value) {
            if (!phase.compareAndSet(RUNNING, TERMINAL)) {
                return;
            }
            tasks.remove(this);
            completion.completeFromRuntime(value);
        }

        private void failTerminal(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            int observed;
            do {
                observed = phase.get();
                if (observed == TERMINAL) return;
            } while (!phase.compareAndSet(observed, TERMINAL));

            tasks.remove(this);
            completion.failFromRuntime(failure);
        }

        private void failBeforeStart(Throwable failure) {
            failTerminal(failure);
        }

        private void cancelFromFuture() {
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
}
