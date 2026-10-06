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
import java.util.function.Consumer;
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
public final class OresFuture<T> implements Future<T>, Awaitable<T> {
    private static final Object PENDING = new Object();

    /**
     * Single-shot completion capability used to adapt callback-only APIs.
     *
     * <p>Calling resolve/reject/cancel settles the target Future but never
     * resumes Oreslang guest code inline. Awaiting code is still resumed only
     * by its owning scheduler after the suspending turn has unwound.</p>
     */
    public interface Callback<T> {
        void resolve(T value);
        void reject(Throwable failure);
        void cancel();
        boolean isDone();

        default void complete(Throwable failure, T value) {
            if (failure == null) resolve(value);
            else reject(failure);
        }
    }

    public static final class AlreadySettledException extends IllegalStateException {
        public AlreadySettledException(String message) {
            super(message);
        }
    }

    private record Success<T>(T value) { }
    private record Failure(Throwable failure) { }
    private record Cancelled(CancellationException failure) { }

    /**
     * Runtime-only detachable completion waiter.
     *
     * <p>The waiter is also its own registration handle. Keeping the callback,
     * exactly-once claim bit, owner, and detach operation in one object avoids
     * allocating a second registration wrapper and captured removal lambda for
     * every genuinely suspending await.</p>
     *
     * <p>Detaching never changes the Future's producer/cancellation state. It
     * only prevents this runtime continuation waiter from retaining or being
     * invoked after its owning scheduler/task has been cancelled.</p>
     */
    static final class RuntimeWaiterRegistration<T> {
        private final OresFuture<T> owner;
        private final BiConsumer<? super T, ? super Throwable> callback;
        private final AtomicBoolean claimed = new AtomicBoolean();

        private RuntimeWaiterRegistration(
                OresFuture<T> owner,
                BiConsumer<? super T, ? super Throwable> callback) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.callback = Objects.requireNonNull(callback, "callback");
        }

        boolean detach() {
            if (!claimed.compareAndSet(false, true)) return false;
            owner.waiters.remove(this);
            return true;
        }
    }

    private final AtomicReference<Runnable> cancelHook;
    private final AtomicBoolean cancelHookRun = new AtomicBoolean();
    private final AtomicReference<Object> state = new AtomicReference<>(PENDING);
    private final ConcurrentLinkedQueue<RuntimeWaiterRegistration<T>> waiters = new ConcurrentLinkedQueue<>();

    public OresFuture() {
        this(() -> { });
    }

    OresFuture(Runnable cancelHook) {
        this.cancelHook = new AtomicReference<>(
                Objects.requireNonNull(cancelHook, "cancelHook"));
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

    /**
     * Adapt a single-shot callback registration API into an Ores Future.
     *
     * <p>The registrar may invoke the callback synchronously. That only settles
     * the Future; it cannot re-enter an awaiting Oreslang frame. A second
     * callback settlement is rejected deterministically.</p>
     */
    public static <T> OresFuture<T> fromCallback(
            Consumer<? super Callback<T>> registrar) {
        Objects.requireNonNull(registrar, "registrar");
        OresFuture<T> future = new OresFuture<>();
        AtomicBoolean callbackClaimed = new AtomicBoolean();

        Callback<T> completion = new Callback<>() {
            private boolean claim(String operation) {
                if (!callbackClaimed.compareAndSet(false, true)) {
                    throw new AlreadySettledException(
                            "callback Future already settled; duplicate " + operation);
                }

                // Consumer cancellation may legitimately win before a foreign
                // callback arrives. The first late producer callback is then a
                // no-op rather than an exception escaping onto the producer's
                // thread. Marking the callback claimed still diagnoses any
                // subsequent duplicate callback invocation.
                return !future.isCancelled();
            }

            @Override
            public void resolve(T value) {
                if (!claim("resolve")) return;
                if (!future.completeFromRuntime(value) && !future.isCancelled()) {
                    throw new AlreadySettledException(
                            "callback Future was already settled before resolve");
                }
            }

            @Override
            public void reject(Throwable failure) {
                Objects.requireNonNull(failure, "failure");
                if (!claim("reject")) return;
                if (!future.failFromRuntime(failure) && !future.isCancelled()) {
                    throw new AlreadySettledException(
                            "callback Future was already settled before reject");
                }
            }

            @Override
            public void cancel() {
                if (!claim("cancel")) return;
                if (!future.cancel(false) && !future.isCancelled()) {
                    throw new AlreadySettledException(
                            "callback Future was already settled before cancel");
                }
            }

            @Override
            public boolean isDone() {
                return callbackClaimed.get() || future.isDone();
            }
        };

        try {
            registrar.accept(completion);
        } catch (Throwable failure) {
            // Promise-style constructor semantics: a registrar failure rejects
            // only if the callback has not already won the single-shot race.
            if (callbackClaimed.compareAndSet(false, true)) {
                future.failFromRuntime(failure);
            } else if (failure instanceof VirtualMachineError fatal) {
                throw fatal;
            } else if (failure instanceof ThreadDeath fatal) {
                throw fatal;
            } else if (failure instanceof LinkageError fatal) {
                throw fatal;
            }
        }
        return future;
    }

    @Override
    public OresFuture<T> getAwaited() {
        return this;
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
                return;
            }

            Throwable terminalFailure = unwrap(failure);
            boolean cancelled = stage instanceof Future<?> hostFuture
                    ? hostFuture.isCancelled()
                    : terminalFailure instanceof CancellationException;

            if (cancelled) {
                result.cancel(false);
            } else {
                result.failFromRuntime(terminalFailure);
            }
        });
        return result;
    }

    /**
     * Chain a callback-only operation after this Future on an explicit Ores
     * scheduler. Guest registrar code executes only as a scheduler turn.
     *
     * <p>If the callback fires synchronously, the dependent Future is already
     * settled when this turn returns Await, but TaskRunner still requires the
     * current turn to unwind before the continuation can be dispatched.</p>
     */
    public <U> OresFuture<U> attachCallback(
            OresScheduler scheduler,
            java.util.function.BiConsumer<? super T, ? super Callback<U>> registrar) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(registrar, "registrar");
        OresFuture<T> source = this;
        AtomicReference<OresFuture<U>> dependentRef = new AtomicReference<>();

        OresFuture<U> task = scheduler.start(new OresScheduler.Task<>() {
            private int pc;

            @Override
            public OresScheduler.Step<U> resume(OresScheduler.Resume resume) {
                if (pc == 0) {
                    if (!resume.initial()) {
                        throw new IllegalStateException(
                                "callback chain started with a non-initial resume");
                    }
                    pc = 1;
                    return OresScheduler.await(source);
                }

                if (pc == 1) {
                    if (resume.failure() != null) {
                        throw propagate(resume.failure());
                    }
                    @SuppressWarnings("unchecked")
                    T value = (T) resume.value();
                    OresFuture<U> dependent = OresFuture.fromCallback(
                            callback -> registrar.accept(value, callback));
                    dependentRef.set(dependent);
                    pc = 2;
                    return OresScheduler.await(dependent);
                }

                if (pc == 2) {
                    if (resume.failure() != null) {
                        throw propagate(resume.failure());
                    }
                    @SuppressWarnings("unchecked")
                    U value = (U) resume.value();
                    pc = 3;
                    return OresScheduler.done(value);
                }

                throw new IllegalStateException(
                        "callback Future chain resumed after completion");
            }

            private RuntimeException propagate(Throwable failure) {
                Throwable unwrapped = OresFuture.unwrap(failure);
                if (unwrapped instanceof RuntimeException runtime) return runtime;
                if (unwrapped instanceof Error error) throw error;
                return new RuntimeException(unwrapped);
            }
        });

        AtomicReference<RuntimeWaiterRegistration<U>> taskWaiter =
                new AtomicReference<>();

        Runnable detach = () -> {
            RuntimeWaiterRegistration<U> registration =
                    taskWaiter.getAndSet(null);
            if (registration != null) registration.detach();
        };

        OresFuture<U> exposed = new OresFuture<>(() -> {
            detach.run();
            task.cancel(true);

            // The callback-produced Future belongs to this chain. Marking it
            // cancelled prevents a late foreign callback from reviving work;
            // OresFuture.fromCallback safely drops the first such late callback.
            OresFuture<U> dependent = dependentRef.getAndSet(null);
            if (dependent != null) dependent.cancel(false);

            // The source may be shared by other consumers, so chain
            // cancellation deliberately never cancels it.
        });

        RuntimeWaiterRegistration<U> registration =
                task.whenCompleteRuntimeCancellable((value, failure) -> {
                    try {
                        if (exposed.isDone()) return;

                        OresFuture<U> dependent = dependentRef.get();
                        if (failure == null) {
                            exposed.completeFromRuntime(value);
                        } else if (task.isCancelled()
                                || source.isCancelled()
                                || (dependent != null && dependent.isCancelled())) {
                            exposed.cancel(false);
                        } else {
                            exposed.failFromRuntime(OresFuture.unwrap(failure));
                        }
                    } finally {
                        detach.run();
                        dependentRef.set(null);
                    }
                });

        taskWaiter.set(registration);
        if (exposed.isDone()) {
            detach.run();
            dependentRef.set(null);
        }

        return exposed;
    }

    boolean completeFromRuntime(T value) {
        boolean completed = settle(new Success<>(value));
        if (completed) cancelHook.set(null);
        return completed;
    }

    boolean failFromRuntime(Throwable failure) {
        boolean completed = settle(
                new Failure(Objects.requireNonNull(failure, "failure")));
        if (completed) cancelHook.set(null);
        return completed;
    }

    /**
     * Runtime-only completion subscription.
     *
     * <p>Callbacks registered here must be scheduler plumbing only: transition a
     * dependent Future, enqueue a continuation, or release runtime accounting.
     * They must never execute Oreslang guest code directly.</p>
     */
    void whenCompleteRuntime(BiConsumer<? super T, ? super Throwable> callback) {
        whenCompleteRuntimeCancellable(callback);
    }

    RuntimeWaiterRegistration<T> whenCompleteRuntimeCancellable(
            BiConsumer<? super T, ? super Throwable> callback) {
        Objects.requireNonNull(callback, "callback");
        RuntimeWaiterRegistration<T> waiter =
                new RuntimeWaiterRegistration<>(this, callback);
        waiters.add(waiter);

        Object observed = state.get();
        if (observed != PENDING) {
            // A registration racing with (or following) settlement must not
            // leave an already-claimed callback strongly retained in the
            // pending waiter queue. Removing before notification is race-safe:
            // if settle() already polled it, remove is a no-op and the claimed
            // bit still guarantees exactly-once callback delivery.
            waiters.remove(waiter);
            notifyWaiter(waiter, observed);
        }
        return waiter;
    }

    int pendingRuntimeWaiterCount() {
        return waiters.size();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        CancellationException cancelled =
                new CancellationException("OresFuture was cancelled");
        if (!settle(new Cancelled(cancelled))) return false;

        Runnable hook = cancelHook.getAndSet(null);
        if (hook != null && cancelHookRun.compareAndSet(false, true)) {
            try {
                hook.run();
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
     * Allocation-free runtime observation used by scheduler await fast paths.
     *
     * <p>A non-null value is the Future's immutable terminal state object. A
     * null result means the Future was still pending at the observation point.
     * Callers that observe pending must still register normally, because
     * settlement may race immediately after this load.</p>
     */
    Object runtimeTerminalStateOrNull() {
        Object observed = state.get();
        return observed == PENDING ? null : observed;
    }

    /**
     * Decode a terminal state previously returned by
     * {@link #runtimeTerminalStateOrNull()} without allocating a wrapper.
     */
    static Object runtimeTerminalValue(Object terminal) {
        if (terminal instanceof Success<?> success) return success.value();
        if (terminal instanceof Failure || terminal instanceof Cancelled) return null;
        throw new IllegalArgumentException("terminal Future state required");
    }

    /**
     * Decode the failure/cancellation of a terminal state previously returned
     * by {@link #runtimeTerminalStateOrNull()} without allocating a wrapper.
     */
    static Throwable runtimeTerminalFailure(Object terminal) {
        if (terminal instanceof Success<?>) return null;
        if (terminal instanceof Failure failed) return failed.failure();
        if (terminal instanceof Cancelled cancelled) return cancelled.failure();
        throw new IllegalArgumentException("terminal Future state required");
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

        RuntimeWaiterRegistration<T> waiter;
        while ((waiter = waiters.poll()) != null) {
            notifyWaiter(waiter, terminal);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private void notifyWaiter(RuntimeWaiterRegistration<T> waiter, Object terminal) {
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
