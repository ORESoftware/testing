package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Context-owned scheduler for ordinary Oreslang async callables.
 *
 * <p>The language contract is deliberately task/future based rather than
 * thread based: callers receive an Oreslang-owned {@link OresFuture}; the backing carrier
 * is an implementation detail. The compatibility execution bridge uses virtual threads so
 * an async callable never consumes an actor dispatcher worker while it is
 * blocked in host/runtime code. A future compiler can replace this with
 * continuation/state-machine lowering without changing source semantics.</p>
 */
public final class AsyncRuntime implements AutoCloseable {
    private static final long CLOSE_WAIT_MILLIS = 500L;
    private static final ThreadLocal<Boolean> ASYNC_CARRIER =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    @FunctionalInterface
    public interface TurnExecutor {
        void execute(Runnable turn);

        static TurnExecutor direct() {
            return Runnable::run;
        }
    }

    @FunctionalInterface
    public interface Task<T> {
        T run() throws Exception;
    }

    private final TurnExecutor turnExecutor;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentHashMap<OresFuture<?>, TaskExit> taskExits = new ConcurrentHashMap<>();

    private static final class TaskExit {
        // 0: not entered, 1: running, 2: guest turn exited/cancelled before entry.
        private final AtomicInteger state = new AtomicInteger();
        private final CountDownLatch exited = new CountDownLatch(1);
        private volatile Thread thread;
        private volatile OresFuture<?> completion;
    }

    public AsyncRuntime() {
        this(TurnExecutor.direct());
    }

    public AsyncRuntime(TurnExecutor turnExecutor) {
        this.turnExecutor = Objects.requireNonNull(turnExecutor, "turnExecutor");
        ThreadFactory factory = Thread.ofVirtual().name("ores-async-", 0L).factory();
        this.executor = Executors.newThreadPerTaskExecutor(factory);
    }

    /**
     * True only while a host-owned async carrier is entering/executing guest
     * code. Guest source cannot toggle this marker or create these carriers.
     */
    public static boolean isAsyncCarrierThread() {
        return Boolean.TRUE.equals(ASYNC_CARRIER.get());
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Schedule one async callable and return immediately with a composable
     * future. Cancellation is propagated to the backing task and therefore
     * interrupts the current virtual carrier when possible.
     */
    public <T> OresFuture<T> submit(Task<T> task) {
        Objects.requireNonNull(task, "task");
        if (closed.get()) throw new RejectedExecutionException("async runtime is closed");

        AtomicReference<Future<?>> backing = new AtomicReference<>();
        TaskExit exit = new TaskExit();
        OresFuture<T> completion = new OresFuture<>(() -> {
            Future<?> carrier = backing.getAndSet(null);
            if (carrier != null) carrier.cancel(true);
            if (exit.state.compareAndSet(0, 2)) {
                taskExits.remove(exit.completion, exit);
                exit.exited.countDown();
            }
        });
        exit.completion = completion;
        taskExits.put(completion, exit);
        final Future<?> scheduled;
        try {
            scheduled = executor.submit(() -> {
                if (!exit.state.compareAndSet(0, 1)) {
                    taskExits.remove(completion, exit);
                    return;
                }
                exit.thread = Thread.currentThread();
                try { runTask(task, completion); }
                finally {
                    // TurnExecutor has returned, including its context leave.
                    exit.state.set(2);
                    taskExits.remove(completion, exit);
                    exit.exited.countDown();
                }
            });
        } catch (RejectedExecutionException rejected) {
            taskExits.remove(completion, exit);
            exit.state.set(2);
            exit.exited.countDown();
            throw new RejectedExecutionException("async runtime is closed", rejected);
        }
        backing.set(scheduled);
        if (completion.isCancelled()) {
            scheduled.cancel(true);
            if (exit.state.compareAndSet(0, 2)) exit.exited.countDown();
            if (exit.state.get() == 2) taskExits.remove(completion, exit);
        }
        completion.whenCompleteRuntime((value, failure) -> {
            // Cancellation delivers waiters before invoking the cancellation hook.
            // Leave the carrier attached until that hook has interrupted it.
            if (!completion.isCancelled()) backing.set(null);
        });

        // Close may race submission after the initial check.
        if (closed.get()) completion.cancel(true);
        return completion;
    }

    /** Cancellation settles a Future before its carrier leaves the guest turn. */
    void awaitTaskExit(OresFuture<?> task) {
        TaskExit exit = taskExits.get(task);
        if (exit == null || exit.thread == Thread.currentThread()) return;
        boolean interrupted = false;
        for (;;) {
            try { exit.exited.await(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private <T> void runTask(Task<T> task, OresFuture<T> completion) {
        if (completion.isCancelled()) return;

        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ASYNC_CARRIER.set(Boolean.TRUE);
        try {
            turnExecutor.execute(() -> {
                if (completion.isCancelled()) return;
                try {
                    value.set(task.run());
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
        } catch (Throwable thrown) {
            failure.compareAndSet(null, thrown);
        } finally {
            ASYNC_CARRIER.remove();
        }

        if (completion.isCancelled()) return;
        Throwable thrown = failure.get();
        if (thrown == null) completion.completeFromRuntime(value.get());
        else completion.failFromRuntime(thrown);
    }

    /**
     * Blocking bridge used only where the interpreter has not yet lowered an
     * await to a continuation. It preserves interruption/cancellation and
     * unwraps the original failure instead of leaking CompletionException.
     *
     * <p>An actor carrier is never allowed to park here. Actor await must be
     * lowered to mailbox-turn suspension/resumption.</p>
     */
    public static <T> T await(OresFuture<T> future) {
        Objects.requireNonNull(future, "future");
        if (ActorRuntime.inActorExecution() && !future.isDone()) {
            throw new IllegalStateException(
                    "await would block an actor dispatcher carrier; actor continuation lowering must suspend/resume the mailbox turn");
        }
        return blockingGet(future);
    }

    /**
     * Host-interop bridge only. CompletionStage is normalized immediately into
     * OresFuture so host callback scheduling never becomes Oreslang semantics.
     */
    public static <T> T await(CompletionStage<T> stage) {
        return await(OresFuture.from(Objects.requireNonNull(stage, "stage")));
    }

    private static <T> T blockingGet(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            CancellationException cancelled = new CancellationException("await interrupted");
            cancelled.initCause(interrupted);
            throw cancelled;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();

        boolean interrupted = false;
        try {
            if (!executor.awaitTermination(CLOSE_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(
                        "async runtime did not terminate all tasks after cancellation");
            }
        } catch (InterruptedException waitInterrupted) {
            interrupted = true;
            throw new IllegalStateException(
                    "interrupted while closing async runtime",
                    waitInterrupted);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

}
