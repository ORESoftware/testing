package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Context-owned scheduler for ordinary Oreslang async callables.
 *
 * <p>The language contract is deliberately task/future based rather than
 * thread based: callers receive a {@link CompletionStage}; the backing carrier
 * is an implementation detail. The initial interpreter uses virtual threads so
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
    public <T> CompletableFuture<T> submit(Task<T> task) {
        Objects.requireNonNull(task, "task");
        if (closed.get()) throw new RejectedExecutionException("async runtime is closed");

        TaskFuture<T> completion = new TaskFuture<>();
        final Future<?> scheduled;
        try {
            scheduled = executor.submit(() -> runTask(task, completion));
        } catch (RejectedExecutionException rejected) {
            throw new RejectedExecutionException("async runtime is closed", rejected);
        }
        completion.attach(scheduled);

        // Close may race submission after the initial check.
        if (closed.get()) completion.cancel(true);
        return completion;
    }

    private <T> void runTask(Task<T> task, TaskFuture<T> completion) {
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
        if (thrown == null) completion.complete(value.get());
        else completion.completeExceptionally(thrown);
    }

    /**
     * Blocking bridge used only where the interpreter has not yet lowered an
     * await to a continuation. It preserves interruption/cancellation and
     * unwraps the original failure instead of leaking CompletionException.
     *
     * <p>An actor carrier is never allowed to park here. Actor await must be
     * lowered to mailbox-turn suspension/resumption.</p>
     */
    public static <T> T await(CompletionStage<T> stage) {
        Objects.requireNonNull(stage, "stage");
        CompletableFuture<T> future = stage.toCompletableFuture();
        if (ActorRuntime.inActorExecution()
                && !ActorRuntime.isActorBlockingImplementationThread()
                && !future.isDone()) {
            throw new IllegalStateException(
                    "await would block a bounded actor dispatcher carrier; "
                            + "source-level suspension must run on the actor's "
                            + "virtual implementation turn");
        }

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

    private static final class TaskFuture<T> extends CompletableFuture<T> {
        private final AtomicReference<Future<?>> task = new AtomicReference<>();

        private void attach(Future<?> scheduled) {
            if (!task.compareAndSet(null, scheduled)) {
                scheduled.cancel(true);
                throw new IllegalStateException("async task carrier already attached");
            }
            if (isCancelled()) scheduled.cancel(true);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            Future<?> scheduled = task.get();
            if (cancelled && scheduled != null) {
                scheduled.cancel(mayInterruptIfRunning);
            }
            return cancelled;
        }
    }
}
