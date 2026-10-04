package dev.oreslang.runtime;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Oreslang synchronization primitives.
 *
 * <p>{@link Local} is an actor/private-domain mutex. It deliberately does not
 * use a JVM lock: the creating semantic execution domain owns it and recursive
 * acquisition is rejected. Shared actors may migrate JVM worker threads without
 * changing that domain. {@link Shared} is an explicit same-process
 * shared-memory capability backed by a JVM synchronizer with poisoning and
 * acquire/release ordering.</p>
 */
public final class OresMutex {
    private OresMutex() { }

    private static long saturatedNanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    public static <T> Local<T> local(T value) {
        return new Local<>(value);
    }

    public static <T> Shared<T> shared(T value) {
        if (ActorRuntime.currentActorKind() == ActorRuntime.ActorKind.PRIVATE) {
            throw new SecurityException("private actors cannot create SharedMutex<T>");
        }
        IsolatePolicy actorPolicy = ActorRuntime.currentActorPolicy();
        if (actorPolicy != null) {
            actorPolicy.require(
                    IsolatePolicy.Capability.SHARED_MEMORY,
                    "SharedMutex.new");
        }
        return new Shared<>(value);
    }

    /**
     * Runtime-owned future for a lexical MutexGuard.
     *
     * <p>Callers may observe or cancel it, but may not forge completion,
     * timeout-complete it, or obtrude a value. Otherwise a queued lock request
     * could be detached from the mutex's domain/permit accounting.</p>
     */
    public static final class GuardFuture<T> extends CompletableFuture<Guard<T>> {
        private GuardFuture() { }

        private boolean completeFromRuntime(Guard<T> guard) {
            return super.complete(guard);
        }

        private boolean failFromRuntime(Throwable failure) {
            return super.completeExceptionally(failure);
        }

        @Override
        public boolean complete(Guard<T> value) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }

        @Override
        public boolean completeExceptionally(Throwable ex) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }

        @Override
        public CompletableFuture<Guard<T>> completeAsync(
                java.util.function.Supplier<? extends Guard<T>> supplier) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }

        @Override
        public CompletableFuture<Guard<T>> completeAsync(
                java.util.function.Supplier<? extends Guard<T>> supplier,
                java.util.concurrent.Executor executor) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }

        @Override
        public CompletableFuture<Guard<T>> orTimeout(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("use Oreslang timed lock acquisition, not GuardFuture.orTimeout");
        }

        @Override
        public CompletableFuture<Guard<T>> completeOnTimeout(
                Guard<T> value,
                long timeout,
                TimeUnit unit) {
            throw new UnsupportedOperationException("use Oreslang timed lock acquisition, not GuardFuture.completeOnTimeout");
        }

        @Override
        public void obtrudeValue(Guard<T> value) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }

        @Override
        public void obtrudeException(Throwable ex) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }
    }

    /**
     * Runtime-owned aggregate contract used to inspect values protected by
     * SharedMutex at actor-transport boundaries. Implementations must expose
     * every transitively reachable child that can carry capabilities/state.
     */
    public interface SharedState {
        Iterable<?> sharedStateChildren();
    }

    public sealed interface Lock<T> permits Local, Shared {
        Guard<T> lock();
        Optional<Guard<T>> tryLock();
        Optional<Guard<T>> lockFor(Duration timeout);
        CompletableFuture<Guard<T>> lockAsync();
        CompletableFuture<Guard<T>> lockAsyncFor(Duration timeout);
        <R> R withLock(Function<? super T, ? extends R> body);
        boolean isPoisoned();
    }

    /**
     * Linear lock capability. Oreslang lowering owns guard release; guest code
     * may release early, but the mutex itself has no public unlock operation.
     */
    public interface Guard<T> extends AutoCloseable {
        T value();
        boolean released();
        void release();

        /**
         * Releases after an abnormal critical-section exit. Shared mutexes are
         * poisoned; actor-local mutexes simply release because their state is
         * confined to the failing actor/private domain.
         */
        void fail();

        @Override
        default void close() {
            release();
        }
    }

    public static final class RecursiveLockException extends IllegalStateException {
        public RecursiveLockException(String kind) {
            super(kind + " is non-reentrant; recursive acquisition is forbidden");
        }
    }

    public static final class WrongMutexDomainException extends IllegalStateException {
        public WrongMutexDomainException(String message) {
            super(message);
        }
    }

    public static final class PoisonedMutexException extends IllegalStateException {
        public PoisonedMutexException() {
            super("SharedMutex is poisoned because a previous critical section exited abnormally; call recover(...)");
        }
    }

    public static final class LockTimeoutException extends IllegalStateException {
        public LockTimeoutException(Duration timeout) {
            super("mutex acquisition timed out after " + timeout);
        }
    }

    public static final class DeadlockDetectedException extends IllegalStateException {
        public DeadlockDetectedException() {
            super("SharedMutex wait would create a cross-mutex deadlock cycle");
        }
    }

    /**
     * Actor/private-domain mutex. There is no host lock and therefore no
     * blocking path. Ownership follows the actor execution domain, not the
     * transient JVM worker Thread. Outside actor execution, Thread identity is
     * used as the local domain.
     */
    public static final class Local<T> implements Lock<T> {
        private final T value;
        private final Object ownerDomain;
        private boolean held;

        private Local(T value) {
            this.value = value;
            this.ownerDomain = ActorRuntime.currentExecutionDomain();
        }

        private void requireOwnerDomain() {
            if (!Objects.equals(ActorRuntime.currentExecutionDomain(), ownerDomain)) {
                throw new WrongMutexDomainException(
                        "Mutex<T> is actor/private-domain state and cannot be accessed from another actor/execution domain; use SharedMutex<T>");
            }
        }

        @Override
        public Guard<T> lock() {
            requireOwnerDomain();
            if (held) throw new RecursiveLockException("Mutex<T>");
            held = true;
            return new LocalGuard();
        }

        @Override
        public Optional<Guard<T>> tryLock() {
            requireOwnerDomain();
            if (held) return Optional.empty();
            held = true;
            return Optional.of(new LocalGuard());
        }

        @Override
        public Optional<Guard<T>> lockFor(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
            return tryLock();
        }

        @Override
        public CompletableFuture<Guard<T>> lockAsync() {
            GuardFuture<T> future = new GuardFuture<>();
            try {
                future.completeFromRuntime(lock());
            } catch (Throwable failure) {
                future.failFromRuntime(failure);
            }
            return future;
        }

        @Override
        public CompletableFuture<Guard<T>> lockAsyncFor(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
            return lockAsync();
        }

        @Override
        public <R> R withLock(Function<? super T, ? extends R> body) {
            Objects.requireNonNull(body, "body");
            Guard<T> guard = lock();
            try {
                R result = body.apply(value);
                guard.release();
                return result;
            } catch (RuntimeException | Error failure) {
                guard.fail();
                throw failure;
            }
        }

        @Override
        public boolean isPoisoned() {
            return false;
        }

        private final class LocalGuard implements Guard<T> {
            private boolean released;

            @Override
            public T value() {
                requireOwnerDomain();
                if (released) throw new IllegalStateException("MutexGuard has been released");
                return value;
            }

            @Override public boolean released() { return released; }

            @Override
            public void release() {
                requireOwnerDomain();
                if (released) return;
                released = true;
                held = false;
            }

            @Override
            public void fail() {
                release();
            }
        }
    }

    /**
     * Explicit same-process shared-memory mutex. This is intentionally not a
     * distributed lock and must not be serialized across OS-process/Graal
     * isolate boundaries.
     */
    public static final class Shared<T> implements Lock<T> {
        private static final int MAX_ASYNC_WAITERS = 8_192;
        private static final int MAX_GLOBAL_ASYNC_WAITERS = 32_768;
        private static final AtomicInteger GLOBAL_ASYNC_WAITERS = new AtomicInteger();
        private static final ConcurrentHashMap<Object, Shared<?>> WAITING_ON =
                new ConcurrentHashMap<>();
        private static final ScheduledExecutorService ASYNC_TIMEOUTS =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform()
                                .daemon(true)
                                .name("ores-shared-mutex-timeouts")
                                .factory());

        private final T value;
        private final Semaphore permit = new Semaphore(1, true);
        private final AtomicBoolean poisoned = new AtomicBoolean();
        private final AtomicInteger asyncWaiters = new AtomicInteger();
        private final AtomicReference<Object> currentOwnerDomain = new AtomicReference<>();
        private final Object asyncQueueLock = new Object();
        private final ArrayDeque<AsyncWaiter> asyncQueue = new ArrayDeque<>();
        private boolean preferAsyncHandoff = true;
        /*
         * Runtime ownership is publication-aware. A send may need to reserve
         * ownership before mailbox admission so a receiver can never observe an
         * unbound SharedMutex, but a failed admission must not permanently bind
         * the handle. pendingPublications + publishedToRuntime provide that
         * two-phase contract.
         */
        private ActorRuntime owningRuntime;
        private int pendingPublications;
        private boolean publishedToRuntime;
        private final Set<Object> activeDomains = ConcurrentHashMap.newKeySet();

        private Shared(T value) {
            this.value = value;
        }

        synchronized boolean bindToRuntime(ActorRuntime runtime) {
            Objects.requireNonNull(runtime, "runtime");
            if (owningRuntime == null) {
                owningRuntime = runtime;
            } else if (owningRuntime != runtime) {
                return false;
            }
            publishedToRuntime = true;
            return true;
        }

        synchronized boolean reserveRuntimePublication(ActorRuntime runtime) {
            Objects.requireNonNull(runtime, "runtime");
            if (owningRuntime == null) {
                owningRuntime = runtime;
            } else if (owningRuntime != runtime) {
                return false;
            }
            pendingPublications++;
            return true;
        }

        synchronized void commitRuntimePublication(ActorRuntime runtime) {
            Objects.requireNonNull(runtime, "runtime");
            if (owningRuntime != runtime || pendingPublications <= 0) {
                throw new IllegalStateException("SharedMutex publication commit without matching reservation");
            }
            pendingPublications--;
            publishedToRuntime = true;
        }

        synchronized void abortRuntimePublication(ActorRuntime runtime) {
            Objects.requireNonNull(runtime, "runtime");
            if (owningRuntime != runtime || pendingPublications <= 0) {
                throw new IllegalStateException("SharedMutex publication abort without matching reservation");
            }
            pendingPublications--;
            if (pendingPublications == 0 && !publishedToRuntime) {
                owningRuntime = null;
            }
        }

        void inspectForTransport(java.util.function.Consumer<? super T> inspection) {
            Objects.requireNonNull(inspection, "inspection");
            final boolean acquired;
            try {
                acquired = permit.tryAcquire(0L, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException(
                        "SharedMutex transport inspection interrupted");
            }
            if (!acquired) {
                throw new IllegalStateException(
                        "SharedMutex cannot be published while locked or contended; retry after the current critical section completes");
            }
            try {
                inspection.accept(value);
            } finally {
                releasePermitOrHandoff();
            }
        }

        private void requireActorAccess() {
            if (ActorRuntime.currentActorKind() == ActorRuntime.ActorKind.PRIVATE) {
                throw new SecurityException("private actors cannot access SharedMutex<T>");
            }
            IsolatePolicy actorPolicy = ActorRuntime.currentActorPolicy();
            if (actorPolicy != null) {
                actorPolicy.require(
                        IsolatePolicy.Capability.SHARED_MEMORY,
                        "SharedMutex operation");
            }

            ActorRuntime current = ActorRuntime.currentActorRuntime();
            if (current != null && !bindToRuntime(current)) {
                throw new WrongMutexDomainException(
                        "SharedMutex belongs to another ActorRuntime");
            }
        }

        private void rejectBlockingActorAcquisition() {
            requireActorAccess();
            if (ActorRuntime.inActorExecution()) {
                throw new WrongMutexDomainException(
                        "blocking SharedMutex.lock()/lock_for()/with_lock() is forbidden during actor execution; use try_lock() or await lock_async()");
            }
        }

        /**
         * Reserve the semantic execution domain before waiting. This prevents a
         * single actor from queueing a second acquisition behind itself and
         * deadlocking, even when the actor migrates JVM workers.
         */
        private Object reserveDomain(boolean tryOnly) {
            requireActorAccess();
            Object domain = ActorRuntime.currentExecutionDomain();
            if (activeDomains.add(domain)) return domain;
            if (tryOnly) return null;
            throw new RecursiveLockException("SharedMutex<T>");
        }

        private void releaseDomain(Object domain) {
            if (domain != null) activeDomains.remove(domain);
        }

        private void beginWait(Object domain) {
            Shared<?> existing = WAITING_ON.putIfAbsent(domain, this);
            if (existing != null) {
                if (existing == this) {
                    throw new RecursiveLockException("SharedMutex<T>");
                }
                throw new IllegalStateException(
                        "execution domain is already waiting on another SharedMutex");
            }

            if (wouldCreateDeadlock(domain)) {
                WAITING_ON.remove(domain, this);
                throw new DeadlockDetectedException();
            }
        }

        private void endWait(Object domain) {
            if (domain != null) WAITING_ON.remove(domain, this);
        }

        private boolean wouldCreateDeadlock(Object requesterDomain) {
            Object owner = currentOwnerDomain.get();
            Set<Object> seen = new HashSet<>();
            while (owner != null) {
                if (Objects.equals(owner, requesterDomain)) return true;
                if (!seen.add(owner)) return false;
                Shared<?> ownerWait = WAITING_ON.get(owner);
                if (ownerWait == null) return false;
                owner = ownerWait.currentOwnerDomain.get();
            }
            return false;
        }

        private void markOwner(Object domain) {
            if (!currentOwnerDomain.compareAndSet(null, domain)) {
                throw new IllegalStateException("SharedMutex permit acquired while another owner is recorded");
            }
        }

        private void clearOwner(Object domain) {
            if (domain == null) return;
            if (!currentOwnerDomain.compareAndSet(domain, null)) {
                Object recorded = currentOwnerDomain.get();
                if (recorded != null) {
                    throw new IllegalStateException(
                            "SharedMutex owner-domain accounting mismatch");
                }
            }
        }

        private int asyncWaiterLimit() {
            IsolatePolicy policy = ActorRuntime.currentActorPolicy();
            return policy == null
                    ? MAX_ASYNC_WAITERS
                    : Math.min(MAX_ASYNC_WAITERS, policy.maxMailboxMessages());
        }

        private static boolean reserveWaiter(AtomicInteger counter, int limit) {
            while (true) {
                int current = counter.get();
                if (current >= limit) return false;
                if (counter.compareAndSet(current, current + 1)) return true;
            }
        }

        private boolean reserveAsyncWaiter(int limit) {
            return reserveWaiter(asyncWaiters, limit);
        }

        private static void releaseWaiter(AtomicInteger counter, String scope) {
            int remaining = counter.decrementAndGet();
            if (remaining < 0) {
                counter.incrementAndGet();
                throw new IllegalStateException("SharedMutex " + scope + " async waiter accounting underflow");
            }
        }

        private void releaseAsyncWaiter() {
            releaseWaiter(asyncWaiters, "per-mutex");
        }

        private static void releaseGlobalAsyncWaiter() {
            releaseWaiter(GLOBAL_ASYNC_WAITERS, "global");
        }

        private static <T> GuardFuture<T> failedGuardFuture(Throwable failure) {
            GuardFuture<T> future = new GuardFuture<>();
            future.failFromRuntime(failure);
            return future;
        }

        private final class AsyncWaiter {
            private final Object ownerDomain;
            private final boolean enforceOwnerDomain;
            private final GuardFuture<T> future;

            private AsyncWaiter(
                    Object ownerDomain,
                    boolean enforceOwnerDomain,
                    GuardFuture<T> future) {
                this.ownerDomain = ownerDomain;
                this.enforceOwnerDomain = enforceOwnerDomain;
                this.future = future;
            }
        }

        /**
         * Releases the physical permit or directly transfers it to a queued
         * async waiter. Direct handoff avoids one virtual thread per waiter.
         *
         * <p>When both blocking host waiters and async waiters exist, alternate
         * preference so neither class can monopolize release handoff.</p>
         */
        private void releasePermitOrHandoff() {
            while (true) {
                AsyncWaiter waiter = null;
                synchronized (asyncQueueLock) {
                    while (!asyncQueue.isEmpty()) {
                        AsyncWaiter candidate = asyncQueue.removeFirst();
                        if (!candidate.future.isDone()) {
                            waiter = candidate;
                            break;
                        }
                        // Cancellation/completion can race with dequeue. Once
                        // removed from the queue, this path owns wait/domain cleanup.
                        endWait(candidate.ownerDomain);
                        releaseDomain(candidate.ownerDomain);
                    }

                    if (waiter == null) {
                        permit.release();
                        return;
                    }

                    if (permit.hasQueuedThreads() && !preferAsyncHandoff) {
                        asyncQueue.addFirst(waiter);
                        preferAsyncHandoff = true;
                        permit.release();
                        return;
                    }
                    if (permit.hasQueuedThreads()) preferAsyncHandoff = false;
                }

                if (poisoned.get()) {
                    endWait(waiter.ownerDomain);
                    releaseDomain(waiter.ownerDomain);
                    waiter.future.failFromRuntime(new PoisonedMutexException());
                    continue;
                }

                endWait(waiter.ownerDomain);
                markOwner(waiter.ownerDomain);
                SharedGuard guard = new SharedGuard(
                        waiter.ownerDomain,
                        waiter.enforceOwnerDomain);
                if (waiter.future.completeFromRuntime(guard)) return;

                // Cancellation won after dequeue but before completion.
                clearOwner(waiter.ownerDomain);
                releaseDomain(waiter.ownerDomain);
            }
        }

        private Guard<T> checkedGuardAfterAcquire(Object ownerDomain, boolean enforceOwnerDomain) {
            endWait(ownerDomain);
            if (poisoned.get()) {
                releaseDomain(ownerDomain);
                releasePermitOrHandoff();
                throw new PoisonedMutexException();
            }
            try {
                markOwner(ownerDomain);
            } catch (RuntimeException | Error failure) {
                releaseDomain(ownerDomain);
                releasePermitOrHandoff();
                throw failure;
            }
            return new SharedGuard(ownerDomain, enforceOwnerDomain);
        }

        @Override
        public Guard<T> lock() {
            rejectBlockingActorAcquisition();
            Object ownerDomain = reserveDomain(false);
            try {
                beginWait(ownerDomain);
                permit.acquire();
            } catch (InterruptedException interrupted) {
                endWait(ownerDomain);
                releaseDomain(ownerDomain);
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("SharedMutex lock wait interrupted");
            } catch (RuntimeException | Error failure) {
                endWait(ownerDomain);
                releaseDomain(ownerDomain);
                throw failure;
            }
            return checkedGuardAfterAcquire(ownerDomain, true);
        }

        @Override
        public Optional<Guard<T>> tryLock() {
            Object ownerDomain = reserveDomain(true);
            if (ownerDomain == null) return Optional.empty();
            try {
                if (!permit.tryAcquire(0L, TimeUnit.NANOSECONDS)) {
                    releaseDomain(ownerDomain);
                    return Optional.empty();
                }
                return Optional.of(checkedGuardAfterAcquire(ownerDomain, true));
            } catch (InterruptedException interrupted) {
                releaseDomain(ownerDomain);
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException(
                        "SharedMutex try_lock interrupted");
            }
        }

        @Override
        public Optional<Guard<T>> lockFor(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
            rejectBlockingActorAcquisition();
            if (timeout.isZero()) return tryLock();
            Object ownerDomain = reserveDomain(true);
            if (ownerDomain == null) return Optional.empty();
            try {
                beginWait(ownerDomain);
                if (!permit.tryAcquire(saturatedNanos(timeout), TimeUnit.NANOSECONDS)) {
                    endWait(ownerDomain);
                    releaseDomain(ownerDomain);
                    return Optional.empty();
                }
                return Optional.of(checkedGuardAfterAcquire(ownerDomain, true));
            } catch (InterruptedException interrupted) {
                endWait(ownerDomain);
                releaseDomain(ownerDomain);
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("SharedMutex lock wait interrupted");
            } catch (RuntimeException | Error failure) {
                endWait(ownerDomain);
                releaseDomain(ownerDomain);
                throw failure;
            }
        }

        @Override
        public CompletableFuture<Guard<T>> lockAsync() {
            Object ownerDomain = reserveDomain(false);
            int waiterLimit = asyncWaiterLimit();
            if (!reserveAsyncWaiter(waiterLimit)) {
                releaseDomain(ownerDomain);
                return failedGuardFuture(new IllegalStateException(
                        "SharedMutex async waiter limit exceeded: " + waiterLimit));
            }
            if (!reserveWaiter(GLOBAL_ASYNC_WAITERS, MAX_GLOBAL_ASYNC_WAITERS)) {
                releaseAsyncWaiter();
                releaseDomain(ownerDomain);
                return failedGuardFuture(new IllegalStateException(
                        "SharedMutex process-wide async waiter limit exceeded: " + MAX_GLOBAL_ASYNC_WAITERS));
            }

            boolean enforceOwnerDomain = ActorRuntime.inActorExecution();
            GuardFuture<T> future = new GuardFuture<>();
            AsyncWaiter waiter = new AsyncWaiter(
                    ownerDomain,
                    enforceOwnerDomain,
                    future);

            future.whenComplete((ignored, failure) -> {
                boolean removed;
                synchronized (asyncQueueLock) {
                    removed = asyncQueue.remove(waiter);
                }
                if (removed) {
                    endWait(ownerDomain);
                    releaseDomain(ownerDomain);
                }
                releaseAsyncWaiter();
                releaseGlobalAsyncWaiter();
            });

            boolean acquired = false;
            boolean poisonedNow = false;
            Throwable waitFailure = null;
            synchronized (asyncQueueLock) {
                if (poisoned.get()) {
                    poisonedNow = true;
                } else {
                    boolean waitRegistered = false;
                    try {
                        // The timed zero-duration form honors a fair
                        // Semaphore's queue order. Untimed tryAcquire() is
                        // explicitly allowed to barge ahead of queued host
                        // waiters, which would violate our mixed-waiter
                        // fairness contract.
                        if (permit.tryAcquire(0L, TimeUnit.NANOSECONDS)) {
                            acquired = true;
                        } else {
                            beginWait(ownerDomain);
                            waitRegistered = true;
                            asyncQueue.addLast(waiter);
                        }
                    } catch (InterruptedException interrupted) {
                        if (waitRegistered) endWait(ownerDomain);
                        Thread.currentThread().interrupt();
                        waitFailure = new java.util.concurrent.CancellationException(
                                "SharedMutex async acquisition interrupted");
                    } catch (RuntimeException | Error failure) {
                        if (waitRegistered) endWait(ownerDomain);
                        waitFailure = failure;
                    }
                }
            }

            if (waitFailure != null) {
                releaseDomain(ownerDomain);
                future.failFromRuntime(waitFailure);
            } else if (poisonedNow) {
                releaseDomain(ownerDomain);
                future.failFromRuntime(new PoisonedMutexException());
            } else if (acquired) {
                try {
                    markOwner(ownerDomain);
                    SharedGuard guard = new SharedGuard(
                            ownerDomain,
                            enforceOwnerDomain);
                    if (!future.completeFromRuntime(guard)) {
                        guard.releaseFromRuntime();
                    }
                } catch (RuntimeException | Error failure) {
                    releaseDomain(ownerDomain);
                    releasePermitOrHandoff();
                    future.failFromRuntime(failure);
                }
            }

            return future;
        }

        @Override
        public CompletableFuture<Guard<T>> lockAsyncFor(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");

            CompletableFuture<Guard<T>> pending = lockAsync();
            if (!(pending instanceof GuardFuture<?>)) return pending;

            @SuppressWarnings("unchecked")
            GuardFuture<T> future = (GuardFuture<T>) pending;
            if (future.isDone()) return future;

            long timeoutNanos = saturatedNanos(timeout);
            if (timeoutNanos == 0L) {
                future.failFromRuntime(new LockTimeoutException(timeout));
                return future;
            }

            final java.util.concurrent.ScheduledFuture<?> timeoutTask;
            try {
                timeoutTask = ASYNC_TIMEOUTS.schedule(
                        () -> future.failFromRuntime(new LockTimeoutException(timeout)),
                        timeoutNanos,
                        TimeUnit.NANOSECONDS);
            } catch (RuntimeException schedulingFailure) {
                future.failFromRuntime(schedulingFailure);
                return future;
            }
            future.whenComplete((ignored, failure) -> timeoutTask.cancel(false));
            return future;
        }

        @Override
        public <R> R withLock(Function<? super T, ? extends R> body) {
            Objects.requireNonNull(body, "body");
            Guard<T> guard = lock();
            try {
                R result = body.apply(value);
                guard.release();
                return result;
            } catch (RuntimeException | Error failure) {
                guard.fail();
                throw failure;
            }
        }

        public <R> R recover(Function<? super T, ? extends R> repair) {
            Objects.requireNonNull(repair, "repair");
            Object ownerDomain = reserveDomain(false);
            boolean acquired = false;
            boolean waiting = false;
            try {
                if (ActorRuntime.inActorExecution()) {
                    // Recovery is expected to happen after the poisoning guard
                    // released its permit. Never block an actor if another
                    // recovery attempt is already in progress.
                    acquired = permit.tryAcquire();
                    if (!acquired) {
                        throw new WrongMutexDomainException(
                                "SharedMutex recovery is busy; actor recovery never blocks, retry from a later mailbox turn");
                    }
                } else {
                    try {
                        beginWait(ownerDomain);
                        waiting = true;
                        permit.acquire();
                        acquired = true;
                        endWait(ownerDomain);
                        waiting = false;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.util.concurrent.CancellationException(
                                "SharedMutex recovery wait interrupted");
                    }
                }

                markOwner(ownerDomain);
                if (!poisoned.get()) {
                    throw new IllegalStateException(
                            "SharedMutex is not poisoned; recover(...) is only for repairing poisoned state");
                }
                try {
                    R result = repair.apply(value);
                    poisoned.set(false);
                    return result;
                } catch (RuntimeException | Error failure) {
                    poisoned.set(true);
                    throw failure;
                }
            } finally {
                if (waiting) endWait(ownerDomain);
                if (acquired) {
                    clearOwner(ownerDomain);
                    releasePermitOrHandoff();
                }
                releaseDomain(ownerDomain);
            }
        }

        @Override
        public boolean isPoisoned() {
            requireActorAccess();
            return poisoned.get();
        }

        /** Runtime transport inspects the protected payload for explicit capability/reference validation. */
        Object transportValue() {
            return value;
        }

        private final class SharedGuard implements Guard<T> {
            private final Object ownerDomain;
            private final boolean enforceOwnerDomain;
            private final AtomicBoolean released = new AtomicBoolean();

            private SharedGuard(Object ownerDomain, boolean enforceOwnerDomain) {
                this.ownerDomain = ownerDomain;
                this.enforceOwnerDomain = enforceOwnerDomain;
            }

            private void requireOwnerDomain() {
                if (enforceOwnerDomain
                        && !Objects.equals(ActorRuntime.currentExecutionDomain(), ownerDomain)) {
                    throw new WrongMutexDomainException(
                            "SharedMutex guard belongs to another actor/execution domain");
                }
            }

            @Override
            public T value() {
                requireOwnerDomain();
                if (released()) throw new IllegalStateException("MutexGuard has been released");
                return value;
            }

            @Override public boolean released() { return released.get(); }

            @Override
            public void release() {
                requireOwnerDomain();
                releaseFromRuntime();
            }

            private void releaseFromRuntime() {
                if (!released.compareAndSet(false, true)) return;
                clearOwner(ownerDomain);
                releaseDomain(ownerDomain);
                releasePermitOrHandoff();
            }

            @Override
            public void fail() {
                requireOwnerDomain();
                if (!released.compareAndSet(false, true)) return;
                poisoned.set(true);
                clearOwner(ownerDomain);
                releaseDomain(ownerDomain);
                releasePermitOrHandoff();
            }
        }
    }
}
