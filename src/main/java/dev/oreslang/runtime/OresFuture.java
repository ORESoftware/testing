package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Oreslang's runtime-owned Future primitive.
 *
 * <p>This is deliberately <em>not</em> a {@link CompletableFuture}. Oreslang
 * Future values do not expose a callback surface that can accidentally execute
 * guest code on an I/O, timer, JNI, or producer-completion thread. Runtime
 * components may register enqueue-only waiters through
 * {@link #whenCompleteRuntime(BiConsumer)}; actor/root schedulers decide when
 * the captured Oreslang continuation actually runs.</p>
 *
 * <p>Completion authority belongs to the runtime operation that created the
 * Future. Guest code may observe state, await, or request cancellation, but it
 * cannot forge a value/failure.</p>
 */
public final class OresFuture<T> implements Future<T> {
    private static final Object PENDING = new Object();

    private record Success<T>(T value) { }
    private record Failure(Throwable failure) { }
    private record Cancelled(CancellationException failure) { }

    private static final class Waiter<T> {
        private final BiConsumer<? super T, ? super Throwable> callback;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private Waiter(BiConsumer<? super T, ? super Throwable> callback) {
            this.callback = callback;
        }
    }

    private final Runnable cancelHook;
    private final AtomicBoolean cancelHookRun = new AtomicBoolean();
    private final AtomicReference<Object> state = new AtomicReference<>(PENDING);
    private final ConcurrentLinkedQueue<Waiter<T>> waiters = new ConcurrentLinkedQueue<>();

    public OresFuture() {
        this(() -> { });
    }

    OresFuture(Runnable cancelHook) {
        this.cancelHook = Objects.requireNonNull(cancelHook, "cancelHook");
    }

    public static <T> OresFuture<T> completed(T value) {
        OresFuture<T> future = new OresFuture<>();
        future.completeFromRuntime(value);
        return future;
    }

    public static <T> OresFuture<T> failed(Throwable failure) {
        OresFuture<T> future = new OresFuture<>();
        future.failFromRuntime(Objects.requireNonNull(failure, "failure"));
        return future;
    }

    @SuppressWarnings("unchecked")
    public static <T> OresFuture<T> from(OresFuture<? extends T> future) {
        return (OresFuture<T>) Objects.requireNonNull(future, "future");
    }

    /**
     * Host-interop adapter. The host CompletionStage may complete on any thread;
     * its callback only settles this OresFuture. It does not run guest code.
     */
    public static <T> OresFuture<T> from(CompletionStage<? extends T> stage) {
        Objects.requireNonNull(stage, "stage");
        Runnable cancelHook = stage instanceof Future<?> cancellable
                ? () -> cancellable.cancel(true)
                : () -> { };
        OresFuture<T> result = new OresFuture<>(cancelHook);
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                result.completeFromRuntime(value);
            } else {
                result.failFromRuntime(unwrap(failure));
            }
        });
        return result;
    }

    boolean completeFromRuntime(T value) {
        return settle(new Success<>(value));
    }

    boolean failFromRuntime(Throwable failure) {
        return settle(new Failure(Objects.requireNonNull(failure, "failure")));
    }

    /**
     * Runtime-only completion subscription.
     *
     * <p>Callbacks registered here must be scheduler plumbing only: transition a
     * dependent Future, enqueue a continuation, or release runtime accounting.
     * They must never execute Oreslang guest code directly.</p>
     */
    void whenCompleteRuntime(BiConsumer<? super T, ? super Throwable> callback) {
        Objects.requireNonNull(callback, "callback");
        Waiter<T> waiter = new Waiter<>(callback);
        waiters.add(waiter);

        Object observed = state.get();
        if (observed != PENDING) {
            notifyWaiter(waiter, observed);
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        CancellationException cancelled =
                new CancellationException("OresFuture was cancelled");
        if (!settle(new Cancelled(cancelled))) return false;

        if (cancelHookRun.compareAndSet(false, true)) {
            try {
                cancelHook.run();
            } catch (RuntimeException | Error ignored) {
                // Cancellation state is already authoritative. A host
                // cancellation hook cannot roll it back or poison waiter
                // delivery.
            }
        }
        return true;
    }

    @Override
    public boolean isCancelled() {
        return state.get() instanceof Cancelled;
    }

    @Override
    public boolean isDone() {
        return state.get() != PENDING;
    }

    /**
     * Read-only compatibility observation used by runtime/tests. As with
     * CompletableFuture, cancellation is also an exceptional terminal state.
     */
    public boolean isCompletedExceptionally() {
        Object observed = state.get();
        return observed instanceof Failure || observed instanceof Cancelled;
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        Object observed = awaitState(0L, null);
        return reportGet(observed);
    }

    @Override
    public T get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0) throw new IllegalArgumentException("timeout must be non-negative");
        Object observed = awaitState(timeout, unit);
        if (observed == PENDING) {
            throw new TimeoutException("OresFuture did not complete before timeout");
        }
        return reportGet(observed);
    }

    /**
     * Host/embedder blocking bridge. Oreslang actor/root lowering must use the
     * scheduler suspension ABI rather than calling join on a carrier.
     */
    public T join() {
        Object observed = state.get();
        boolean interrupted = false;
        if (observed == PENDING) {
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            whenCompleteRuntime((value, failure) -> done.countDown());
            for (;;) {
                try {
                    done.await();
                    break;
                } catch (InterruptedException interruption) {
                    interrupted = true;
                }
            }
            observed = state.get();
        }
        if (interrupted) Thread.currentThread().interrupt();
        return reportJoin(observed);
    }

    /**
     * Runtime callback adapters use this to compose an Ores Future with APIs
     * that still require CompletionStage. Guest/language code should never
     * receive the returned stage.
     */
    CompletionStage<T> asCompletionStage() {
        CompletableFuture<T> bridge = new CompletableFuture<>();
        whenCompleteRuntime((value, failure) -> {
            if (failure == null) bridge.complete(value);
            else bridge.completeExceptionally(failure);
        });
        return bridge;
    }

    private boolean settle(Object terminal) {
        if (!state.compareAndSet(PENDING, terminal)) return false;

        Waiter<T> waiter;
        while ((waiter = waiters.poll()) != null) {
            notifyWaiter(waiter, terminal);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private void notifyWaiter(Waiter<T> waiter, Object terminal) {
        if (!waiter.claimed.compareAndSet(false, true)) return;
        try {
            if (terminal instanceof Success<?> success) {
                waiter.callback.accept((T) success.value(), null);
            } else if (terminal instanceof Failure failed) {
                waiter.callback.accept(null, failed.failure());
            } else if (terminal instanceof Cancelled cancelled) {
                waiter.callback.accept(null, cancelled.failure());
            } else {
                throw new IllegalStateException("attempted to notify waiter from pending Future");
            }
        } catch (RuntimeException | Error ignored) {
            // Runtime waiter failures must not stop delivery to other waiters or
            // mutate the settled Future. Scheduler plumbing owns its own
            // failure path.
        }
    }

    private Object awaitState(long timeout, TimeUnit unit) throws InterruptedException {
        Object observed = state.get();
        if (observed != PENDING) return observed;

        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        whenCompleteRuntime((value, failure) -> done.countDown());

        if (unit == null) {
            done.await();
        } else if (!done.await(timeout, unit)) {
            return state.get() == PENDING ? PENDING : state.get();
        }
        return state.get();
    }

    @SuppressWarnings("unchecked")
    private T reportGet(Object terminal) throws ExecutionException {
        if (terminal instanceof Success<?> success) return (T) success.value();
        if (terminal instanceof Failure failed) throw new ExecutionException(failed.failure());
        if (terminal instanceof Cancelled cancelled) throw cancelled.failure();
        throw new IllegalStateException("Future is still pending");
    }

    @SuppressWarnings("unchecked")
    private T reportJoin(Object terminal) {
        if (terminal instanceof Success<?> success) return (T) success.value();
        if (terminal instanceof Failure failed) {
            throw new CompletionException(failed.failure());
        }
        if (terminal instanceof Cancelled cancelled) throw cancelled.failure();
        throw new IllegalStateException("Future is still pending");
    }

    /**
     * Completion remains runtime-owned. These methods intentionally exist so
     * Java interop receives an explicit failure instead of silently gaining a
     * completion capability.
     */
    public boolean complete(T value) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    public boolean completeExceptionally(Throwable ex) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    public CompletableFuture<T> completeAsync(
            Supplier<? extends T> supplier,
            java.util.concurrent.Executor executor) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    public CompletableFuture<T> orTimeout(long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException(
                "OresFuture completion is runtime-owned; use an Oreslang timeout combinator");
    }

    public CompletableFuture<T> completeOnTimeout(T value, long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException(
                "OresFuture completion is runtime-owned; use an Oreslang timeout combinator");
    }

    public void obtrudeValue(T value) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    public void obtrudeException(Throwable ex) {
        throw new UnsupportedOperationException("OresFuture completion is runtime-owned");
    }

    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
