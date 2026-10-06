package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresMutex;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;

final class MutexRuntimeTest {

    private static final class BlockingSingleElementList extends AbstractList<Object> {
        private final Object value;
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicBoolean blocked = new AtomicBoolean();

        private BlockingSingleElementList(
                Object value,
                CountDownLatch entered,
                CountDownLatch release) {
            this.value = value;
            this.entered = entered;
            this.release = release;
        }

        @Override
        public Object get(int index) {
            if (index != 0) throw new IndexOutOfBoundsException(index);
            if (blocked.compareAndSet(false, true)) {
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release transport validation");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException();
                }
            }
            return value;
        }

        @Override
        public int size() {
            return 1;
        }
    }

    @Test
    void localMutexIsNonReentrantAndExecutionDomainConfined() throws Exception {
        var mutex = OresMutex.local(new int[]{0});
        var guard = mutex.lock();
        guard.value()[0] = 7;

        assertThrows(OresMutex.RecursiveLockException.class, mutex::lock);
        assertTrue(mutex.tryLock().isEmpty(), "try_lock should report busy rather than recurse");

        AtomicReference<Throwable> otherThreadFailure = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                mutex.tryLock();
            } catch (Throwable failure) {
                otherThreadFailure.set(failure);
            }
        });
        thread.join();

        assertInstanceOf(OresMutex.WrongMutexDomainException.class, otherThreadFailure.get());
        guard.release();

        var again = mutex.lock();
        assertEquals(7, again.value()[0]);
        again.release();
    }

    @Test
    void actorLocalMutexPersistsAcrossMessagesInOneSemanticDomain() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Integer> observed = new AtomicReference<>();

            var ref = runtime.<Integer>spawn(() -> {
                var mutex = OresMutex.local(new int[]{0});
                return (message, context) -> mutex.withLock(value -> {
                    value[0] += message;
                    if (value[0] == 3) {
                        observed.set(value[0]);
                        done.countDown();
                    }
                    return null;
                });
            });

            ref.send(1);
            ref.send(2);

            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(3, observed.get());
        }
    }

    @Test
    void actorLocalMutexCannotBeUsedByAnotherActorEvenInSameProcess() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch created = new CountDownLatch(1);
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<OresMutex.Local<int[]>> local = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            var owner = runtime.<String>spawn(() -> (message, context) -> {
                var mutex = OresMutex.local(new int[]{0});
                mutex.withLock(value -> {
                    value[0] = 41;
                    return null;
                });
                local.set(mutex);
                created.countDown();
            });

            var other = runtime.<String>spawn(() -> (message, context) -> {
                try {
                    assertTrue(created.await(2, TimeUnit.SECONDS));
                    assertThrows(OresMutex.WrongMutexDomainException.class, () -> local.get().tryLock());
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    checked.countDown();
                }
            });

            owner.send("create");
            other.send("check");

            assertTrue(checked.await(3, TimeUnit.SECONDS));
            assertNull(failure.get());
        }
    }

    @Test
    void sharedMutexSerializesRealJvmThreads() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < 6; w++) {
            workers.add(Thread.ofPlatform().start(() -> {
                for (int i = 0; i < 500; i++) {
                    mutex.withLock(value -> {
                        value[0]++;
                        return null;
                    });
                }
            }));
        }
        for (Thread worker : workers) worker.join();
        assertEquals(3000, mutex.withLock(value -> value[0]).intValue());
    }

    @Test
    void recoveryCannotBeUsedAsAnOrdinaryLock() {
        var mutex = OresMutex.shared(new int[]{0});

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> mutex.recover(value -> {
                    value[0] = 99;
                    return null;
                }));

        assertTrue(error.getMessage().contains("not poisoned"));
        assertFalse(mutex.isPoisoned());
        assertEquals(0, mutex.withLock(value -> value[0]).intValue());
    }

    @Test
    void sharedMutexPoisonsAndRequiresExplicitRecovery() {
        var mutex = OresMutex.shared(new int[]{0});

        assertThrows(IllegalStateException.class, () -> mutex.withLock(value -> {
            value[0] = 99;
            throw new IllegalStateException("boom");
        }));

        assertTrue(mutex.isPoisoned());
        assertThrows(OresMutex.PoisonedMutexException.class, mutex::lock);

        mutex.recover(value -> {
            value[0] = 0;
            return null;
        });

        assertFalse(mutex.isPoisoned());
        assertEquals(0, mutex.withLock(value -> value[0]).intValue());
    }

    @Test
    void asyncMutexAcquisitionUsesTaggedGuardFutures() {
        var local = OresMutex.local(new int[]{1});
        var localFuture = local.lockAsync();
        assertInstanceOf(OresMutex.GuardFuture.class, localFuture);
        var localGuard = localFuture.join();
        localGuard.release();

        var shared = OresMutex.shared(new int[]{2});
        var sharedFuture = shared.lockAsync();
        assertInstanceOf(OresMutex.GuardFuture.class, sharedFuture);
        var sharedGuard = sharedFuture.join();
        sharedGuard.release();
    }

    @Test
    void sharedTryLockReportsBusyAndCancelledAsyncWaitDoesNotLeakPermit() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lock();

        assertTrue(mutex.tryLock().isEmpty());

        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> waitingRef = new AtomicReference<>();
        CountDownLatch queued = new CountDownLatch(1);
        Thread requester = Thread.ofPlatform().start(() -> {
            waitingRef.set(mutex.lockAsync());
            queued.countDown();
        });
        requester.join();

        assertTrue(queued.await(1, TimeUnit.SECONDS));
        var waiting = waitingRef.get();
        assertNotNull(waiting);
        assertFalse(waiting.isDone());
        assertTrue(waiting.cancel(true));

        guard.release();

        var next = mutex.lock();
        next.release();
        assertTrue(waiting.isCancelled());
    }

    @Test
    void sharedMutexRejectsSameDomainAsyncReentryBeforeDeadlock() {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lockAsync().join();

        assertThrows(OresMutex.RecursiveLockException.class, mutex::lockAsync);

        guard.release();
        var next = mutex.lock();
        next.release();
    }

    @Test
    void releasedGuardsCannotExposeProtectedValues() {
        var local = OresMutex.local(new int[]{1});
        var localGuard = local.lock();
        assertEquals(1, localGuard.value()[0]);
        localGuard.release();
        assertThrows(IllegalStateException.class, localGuard::value);

        var shared = OresMutex.shared(new int[]{2});
        var sharedGuard = shared.lock();
        assertEquals(2, sharedGuard.value()[0]);
        sharedGuard.release();
        assertThrows(IllegalStateException.class, sharedGuard::value);
    }

    @Test
    void sharedMutexCannotBeClosureCapturedAcrossActorRuntimes() throws Exception {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var shared = OresMutex.shared(new int[]{0});
            CountDownLatch bound = new CountDownLatch(1);
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            var owner = runtimeA.<OresMutex.Shared<int[]>>spawnShared(() -> (mutex, context) -> {
                // The send itself binds the SharedMutex to runtimeA.
                assertFalse(mutex.isPoisoned());
                bound.countDown();
            });
            owner.send(shared);
            assertTrue(bound.await(2, TimeUnit.SECONDS));

            var foreign = runtimeB.<String>spawnShared(() -> (message, context) -> {
                try {
                    shared.tryLock();
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    checked.countDown();
                }
            });
            foreign.send("check");

            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertInstanceOf(OresMutex.WrongMutexDomainException.class, failure.get());
        }
    }

    @Test
    void actorCanRecoverPoisonedSharedMutexWithoutBlocking() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var shared = OresMutex.shared(new int[]{0});
            CountDownLatch poisoned = new CountDownLatch(1);
            CountDownLatch recovered = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            var poisoner = runtime.<OresMutex.Shared<int[]>>spawnShared(() -> (mutex, context) -> {
                try {
                    var guard = mutex.lockAsync().join();
                    guard.value()[0] = 17;
                    guard.fail();
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    poisoned.countDown();
                }
            });

            poisoner.send(shared);
            assertTrue(poisoned.await(2, TimeUnit.SECONDS));
            assertTrue(shared.isPoisoned());

            var repairer = runtime.<OresMutex.Shared<int[]>>spawnShared(() -> (mutex, context) -> {
                try {
                    mutex.recover(value -> {
                        value[0] = 0;
                        return null;
                    });
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    recovered.countDown();
                }
            });

            repairer.send(shared);
            assertTrue(recovered.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertFalse(shared.isPoisoned());
            assertEquals(0, shared.withLock(value -> value[0]).intValue());
        }
    }

    @Test
    void actorUsesAsyncAcquisitionForSharedMutex() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            var ref = runtime.<OresMutex.Shared<int[]>>spawnShared(() -> (mutex, context) -> {
                try {
                    assertThrows(OresMutex.WrongMutexDomainException.class, mutex::lock);
                    var guard = mutex.lockAsync().join();
                    guard.value()[0]++;
                    guard.release();
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    done.countDown();
                }
            });

            var shared = OresMutex.shared(new int[]{0});
            ref.send(shared);

            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(1, shared.withLock(value -> value[0]).intValue());
        }
    }

    @Test
    void sharedMutexBindsOnlyAfterAuthorizedRuntimePublication() {
        IsolatePolicy strict = IsolatePolicy.strictFaas();
        var shared = OresMutex.shared(new int[]{0});

        try (ActorRuntime denied = new ActorRuntime(strict);
             ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var deniedRef = denied.<OresMutex.Shared<int[]>>spawnPrivate(
                    strict, factoryContext -> (message, context) -> { });
            var refA = runtimeA.<OresMutex.Shared<int[]>>spawnShared(
                    () -> (message, context) -> { });
            var refB = runtimeB.<OresMutex.Shared<int[]>>spawnShared(
                    () -> (message, context) -> { });

            assertThrows(SecurityException.class, () -> deniedRef.send(shared));
            assertDoesNotThrow(() -> refA.send(shared));

            IllegalArgumentException crossRuntime = assertThrows(
                    IllegalArgumentException.class,
                    () -> refB.send(shared));
            assertTrue(crossRuntime.getMessage().contains("ActorRuntime"));
        }
    }

    @Test
    void strictActorPolicyRejectsSharedMemoryHandles() {
        IsolatePolicy strict = IsolatePolicy.strictFaas();
        try (ActorRuntime runtime = new ActorRuntime(strict)) {
            var ref = runtime.<OresMutex.Shared<int[]>>spawnPrivate(
                    strict, factoryContext -> (message, context) -> { });
            var shared = OresMutex.shared(new int[]{0});
            assertThrows(SecurityException.class, () -> ref.send(shared));
        }
    }

    @Test
    void strictPrivateActorRejectsCapturedSharedMutexBeforeStartup() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IsolatePolicy strict = IsolatePolicy.strictFaas();
            var shared = OresMutex.shared(new int[]{0});

            SecurityException failure = assertThrows(
                    SecurityException.class,
                    () -> runtime.<String>spawnPrivate(strict, factoryContext -> {
                        shared.tryLock();
                        return (message, context) -> { };
                    }));

            assertTrue(failure.getMessage().contains("stateless")
                    || failure.getMessage().contains("captured host state"));
        }
    }

    @Test
    void strictActorCannotCreateSharedMutexDirectly() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IsolatePolicy strict = IsolatePolicy.strictFaas();

            var ref = runtime.<String>spawnPrivate(
                    strict,
                    factoryContext -> (message, context) -> OresMutex.shared(new int[]{0}));

            ref.send("check");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (ref.failure().isEmpty() && System.nanoTime() < deadline) Thread.sleep(2);

            assertTrue(ref.failure().isPresent());
            assertInstanceOf(SecurityException.class, ref.failure().orElseThrow());
        }
    }


@Test
    void guardFutureCannotBeForgedOrTimeoutCompletedByCallers() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lock();

        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> waitingRef = new AtomicReference<>();
        Thread requester = Thread.ofPlatform().start(() -> waitingRef.set(mutex.lockAsync()));
        requester.join();

        var waiting = waitingRef.get();
        assertInstanceOf(OresMutex.GuardFuture.class, waiting);
        assertFalse(waiting.isDone());

        assertThrows(UnsupportedOperationException.class, () -> waiting.complete(null));
        assertThrows(UnsupportedOperationException.class,
                () -> waiting.completeExceptionally(new RuntimeException("forged")));
        assertThrows(UnsupportedOperationException.class,
                () -> waiting.completeAsync(() -> null));
        assertThrows(UnsupportedOperationException.class,
                () -> waiting.orTimeout(1, TimeUnit.MILLISECONDS));
        assertThrows(UnsupportedOperationException.class,
                () -> waiting.completeOnTimeout(null, 1, TimeUnit.MILLISECONDS));
        assertThrows(UnsupportedOperationException.class, () -> waiting.obtrudeValue(null));
        assertThrows(UnsupportedOperationException.class,
                () -> waiting.obtrudeException(new RuntimeException("forged")));

        assertTrue(waiting.cancel(true));
        guard.release();

        var next = mutex.lock();
        next.release();
    }

@Test
    void cancelledQueuedWaiterIsSkippedByDirectHandoff() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lock();

        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> firstRef = new AtomicReference<>();
        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> secondRef = new AtomicReference<>();

        Thread firstRequester = Thread.ofPlatform().start(() -> firstRef.set(mutex.lockAsync()));
        Thread secondRequester = Thread.ofPlatform().start(() -> secondRef.set(mutex.lockAsync()));
        firstRequester.join();
        secondRequester.join();

        var first = firstRef.get();
        var second = secondRef.get();
        assertFalse(first.isDone());
        assertFalse(second.isDone());
        assertTrue(first.cancel(true));

        guard.release();

        var secondGuard = second.get(2, TimeUnit.SECONDS);
        secondGuard.value()[0] = 9;
        secondGuard.release();

        assertTrue(first.isCancelled());
        assertEquals(9, mutex.withLock(value -> value[0]).intValue());
    }

@Test
    void poisoningFailsQueuedAsyncWaitersAndRecoveryRestoresHandoff() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lock();

        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> firstRef = new AtomicReference<>();
        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> secondRef = new AtomicReference<>();
        Thread firstRequester = Thread.ofPlatform().start(() -> firstRef.set(mutex.lockAsync()));
        Thread secondRequester = Thread.ofPlatform().start(() -> secondRef.set(mutex.lockAsync()));
        firstRequester.join();
        secondRequester.join();

        guard.value()[0] = 17;
        guard.fail();

        for (var waiting : List.of(firstRef.get(), secondRef.get())) {
            var failure = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> waiting.get(2, TimeUnit.SECONDS));
            assertInstanceOf(OresMutex.PoisonedMutexException.class, failure.getCause());
        }

        assertTrue(mutex.isPoisoned());
        mutex.recover(value -> {
            value[0] = 0;
            return null;
        });

        var next = mutex.lockAsync().get(2, TimeUnit.SECONDS);
        next.value()[0] = 3;
        next.release();
        assertEquals(3, mutex.withLock(value -> value[0]).intValue());
    }

    @Test
    void asyncFastPathDoesNotBargeAfterReleaseToQueuedHostWaiter() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var initial = mutex.lock();

        CountDownLatch blockingAttempted = new CountDownLatch(1);
        CountDownLatch blockingAcquired = new CountDownLatch(1);
        CountDownLatch releaseBlocking = new CountDownLatch(1);
        AtomicReference<Throwable> blockingFailure = new AtomicReference<>();

        Thread blocking = Thread.ofPlatform().start(() -> {
            blockingAttempted.countDown();
            try {
                var guard = mutex.lock();
                blockingAcquired.countDown();
                releaseBlocking.await();
                guard.release();
            } catch (Throwable failure) {
                blockingFailure.set(failure);
            }
        });

        assertTrue(blockingAttempted.await(1, TimeUnit.SECONDS));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (blocking.getState() != Thread.State.WAITING
                && blocking.getState() != Thread.State.TIMED_WAITING
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(blocking.getState() == Thread.State.WAITING
                        || blocking.getState() == Thread.State.TIMED_WAITING,
                "blocking host waiter must be queued before release");

        // No async waiter exists yet, so this release deliberately exposes the
        // fair Semaphore permit to the already-queued blocking host waiter.
        initial.release();

        // Arrive only after the release. An untimed tryAcquire() may barge here
        // before the awakened host thread runs; timed-zero fair acquisition may not.
        var async = mutex.lockAsync();

        assertTrue(blockingAcquired.await(2, TimeUnit.SECONDS),
                "queued blocking waiter must retain its fair position after release");
        assertFalse(async.isDone(),
                "later async fast-path request must not steal the released permit");

        releaseBlocking.countDown();
        var asyncGuard = async.get(2, TimeUnit.SECONDS);
        asyncGuard.release();

        blocking.join();
        assertNull(blockingFailure.get());
    }

    @Test
    void blockingAndAsyncWaitersBothMakeProgress() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var initial = mutex.lock();
        CountDownLatch blockingStarted = new CountDownLatch(1);
        CountDownLatch blockingDone = new CountDownLatch(1);
        AtomicReference<Throwable> blockingFailure = new AtomicReference<>();

        Thread blocking = Thread.ofPlatform().start(() -> {
            blockingStarted.countDown();
            try {
                var guard = mutex.lock();
                guard.value()[0] += 1;
                guard.release();
            } catch (Throwable failure) {
                blockingFailure.set(failure);
            } finally {
                blockingDone.countDown();
            }
        });
        assertTrue(blockingStarted.await(1, TimeUnit.SECONDS));

        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> asyncRef = new AtomicReference<>();
        Thread asyncRequester = Thread.ofPlatform().start(() -> asyncRef.set(mutex.lockAsync()));
        asyncRequester.join();
        var async = asyncRef.get();
        assertFalse(async.isDone());

        initial.release();

        var asyncGuard = async.get(2, TimeUnit.SECONDS);
        asyncGuard.value()[0] += 10;
        asyncGuard.release();

        assertTrue(blockingDone.await(2, TimeUnit.SECONDS));
        blocking.join();
        assertNull(blockingFailure.get());
        assertEquals(11, mutex.withLock(value -> value[0]).intValue());
    }

@Test
    void sharedTimedLockSaturatesHugePositiveDurations() {
        var mutex = OresMutex.shared(new int[]{1});

        var guard = mutex.lockFor(Duration.ofSeconds(Long.MAX_VALUE)).orElseThrow();
        assertEquals(1, guard.value()[0]);
        guard.release();
    }

@Test
    void failedRecoveryKeepsMutexPoisoned() {
        var mutex = OresMutex.shared(new int[]{0});

        assertThrows(IllegalStateException.class, () -> mutex.withLock(value -> {
            value[0] = 9;
            throw new IllegalStateException("poison");
        }));

        assertThrows(IllegalStateException.class, () -> mutex.recover(value -> {
            value[0] = 3;
            throw new IllegalStateException("repair failed");
        }));

        assertTrue(mutex.isPoisoned());
        assertThrows(OresMutex.PoisonedMutexException.class, mutex::tryLock);

        mutex.recover(value -> {
            value[0] = 0;
            return null;
        });
        assertFalse(mutex.isPoisoned());
    }

@Test
    void normalReleaseFollowedByFailDoesNotPoisonMutex() {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lock();

        guard.release();
        guard.fail();

        assertFalse(mutex.isPoisoned());
        var next = mutex.lock();
        next.release();
    }


    @Test
    void failedActorAdmissionDoesNotPermanentlyBindSharedMutex() throws Exception {
        var shared = OresMutex.shared(new int[]{0});

        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            CountDownLatch enteredBehavior = new CountDownLatch(1);
            CountDownLatch allowFailure = new CountDownLatch(1);
            CountDownLatch validationEntered = new CountDownLatch(1);
            CountDownLatch releaseValidation = new CountDownLatch(1);
            AtomicReference<Throwable> senderFailure = new AtomicReference<>();

            var doomed = runtimeA.<Object>spawnShared(() -> (message, context) -> {
                if ("die".equals(message)) {
                    enteredBehavior.countDown();
                    allowFailure.await();
                    throw new IllegalStateException("intentional actor failure");
                }
            });

            doomed.send("die");
            assertTrue(enteredBehavior.await(2, TimeUnit.SECONDS));

            Object blockedMessage =
                    new BlockingSingleElementList(shared, validationEntered, releaseValidation);
            Thread sender = Thread.ofPlatform().start(() -> {
                try {
                    doomed.send(blockedMessage);
                } catch (Throwable failure) {
                    senderFailure.set(failure);
                }
            });

            assertTrue(validationEntered.await(2, TimeUnit.SECONDS));
            allowFailure.countDown();
            for (int i = 0; i < 500 && doomed.failure().isEmpty(); i++) Thread.yield();
            assertTrue(doomed.failure().isPresent());

            releaseValidation.countDown();
            sender.join();

            assertNotNull(senderFailure.get());
            assertTrue(senderFailure.get().getMessage().contains("terminated")
                    || senderFailure.get().getMessage().contains("closed"));

            CountDownLatch delivered = new CountDownLatch(1);
            var receiver = runtimeB.<OresMutex.Shared<int[]>>spawnShared(() -> (mutex, context) -> {
                var guard = mutex.tryLock().orElseThrow();
                guard.value()[0] = 42;
                guard.release();
                delivered.countDown();
            });

            assertDoesNotThrow(() -> receiver.send(shared));
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(42, shared.withLock(value -> value[0]).intValue());
        }
    }

    @Test
    void actorCannotUseBlockingSharedMutexApis() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            var ref = runtime.<OresMutex.Shared<int[]>>spawnShared(() -> (mutex, context) -> {
                try {
                    assertThrows(OresMutex.WrongMutexDomainException.class, mutex::lock);
                    assertThrows(
                            OresMutex.WrongMutexDomainException.class,
                            () -> mutex.lockFor(Duration.ZERO));
                    assertThrows(
                            OresMutex.WrongMutexDomainException.class,
                            () -> mutex.withLock(value -> null));

                    var guard = mutex.tryLock().orElseThrow();
                    guard.release();
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    checked.countDown();
                }
            });

            ref.send(OresMutex.shared(new int[]{0}));
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
        }
    }

    @Test
    void sharedGuardCannotBeReleasedFromAnotherActorDomain() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var shared = OresMutex.shared(new int[]{0});
            AtomicReference<OresMutex.Guard<int[]>> guardRef = new AtomicReference<>();
            AtomicReference<Throwable> observed = new AtomicReference<>();
            CountDownLatch acquired = new CountDownLatch(1);
            CountDownLatch attempted = new CountDownLatch(1);
            CountDownLatch ownerCanRelease = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);

            var owner = runtime.<OresMutex.Shared<int[]>>spawnShared(() -> (mutex, context) -> {
                var guard = mutex.lockAsync().join();
                guardRef.set(guard);
                acquired.countDown();
                ownerCanRelease.await();
                guard.release();
                done.countDown();
            });

            var intruder = runtime.<String>spawnShared(() -> (message, context) -> {
                assertTrue(acquired.await(2, TimeUnit.SECONDS));
                try {
                    guardRef.get().release();
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    attempted.countDown();
                    ownerCanRelease.countDown();
                }
            });

            owner.send(shared);
            intruder.send("try");

            assertTrue(attempted.await(2, TimeUnit.SECONDS));
            assertInstanceOf(OresMutex.WrongMutexDomainException.class, observed.get());
            assertTrue(done.await(2, TimeUnit.SECONDS));

            var next = shared.lock();
            next.release();
        }
    }

    @Test
    void shutdownRacingSendDoesNotBindOrAdmitSharedMutex() throws Exception {
        ActorRuntime runtimeA = new ActorRuntime();
        var shared = OresMutex.shared(new int[]{0});
        CountDownLatch behaviorStarted = new CountDownLatch(1);
        CountDownLatch releaseBehavior = new CountDownLatch(1);
        CountDownLatch validationEntered = new CountDownLatch(1);
        CountDownLatch releaseValidation = new CountDownLatch(1);
        AtomicReference<Throwable> senderFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();

        var target = runtimeA.<Object>spawnShared(() -> (message, context) -> {
            if ("block".equals(message)) {
                behaviorStarted.countDown();
                while (true) {
                    try {
                        releaseBehavior.await();
                        return;
                    } catch (InterruptedException ignored) {
                        // Deliberately keep the turn alive through the first shutdown interrupt.
                    }
                }
            }
        });

        target.send("block");
        assertTrue(behaviorStarted.await(2, TimeUnit.SECONDS));

        Object blockedMessage =
                new BlockingSingleElementList(shared, validationEntered, releaseValidation);
        Thread sender = Thread.ofPlatform().start(() -> {
            try {
                target.send(blockedMessage);
            } catch (Throwable failure) {
                senderFailure.set(failure);
            }
        });
        assertTrue(validationEntered.await(2, TimeUnit.SECONDS));

        Thread closer = Thread.ofPlatform().start(() -> {
            try {
                runtimeA.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        });

        IllegalStateException closedObserved = null;
        for (int i = 0; i < 500 && closedObserved == null; i++) {
            try {
                target.send("probe");
                Thread.yield();
            } catch (IllegalStateException failure) {
                if (failure.getMessage().contains("actor runtime is closed")) {
                    closedObserved = failure;
                }
            }
        }
        assertNotNull(closedObserved);

        releaseValidation.countDown();
        sender.join();
        assertInstanceOf(IllegalStateException.class, senderFailure.get());
        assertTrue(senderFailure.get().getMessage().contains("actor runtime is closed"));

        releaseBehavior.countDown();
        closer.join();
        assertNull(closeFailure.get());

        try (ActorRuntime runtimeB = new ActorRuntime()) {
            CountDownLatch delivered = new CountDownLatch(1);
            var receiver = runtimeB.<OresMutex.Shared<int[]>>spawnShared(() -> (mutex, context) -> {
                var guard = mutex.tryLock().orElseThrow();
                guard.value()[0] = 7;
                guard.release();
                delivered.countDown();
            });

            assertDoesNotThrow(() -> receiver.send(shared));
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(7, shared.withLock(value -> value[0]).intValue());
        }
    }

    @Test
    void mixedRuntimePublicationFailsAtomicallyAcrossAllHandles() throws Exception {
        var unbound = OresMutex.shared(new int[]{1});
        var foreign = OresMutex.shared(new int[]{2});

        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime();
             ActorRuntime runtimeC = new ActorRuntime()) {
            CountDownLatch boundForeign = new CountDownLatch(1);
            var owner = runtimeA.<OresMutex.Shared<int[]>>spawnShared(() -> (mutex, context) -> {
                assertFalse(mutex.isPoisoned());
                boundForeign.countDown();
            });
            owner.send(foreign);
            assertTrue(boundForeign.await(2, TimeUnit.SECONDS));

            var rejected = runtimeB.<List<OresMutex.Shared<int[]>>>spawnShared(
                    () -> (message, context) -> { });

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> rejected.send(List.of(unbound, foreign)));
            assertTrue(error.getMessage().contains("ActorRuntime"));

            CountDownLatch delivered = new CountDownLatch(1);
            var receiver = runtimeC.<OresMutex.Shared<int[]>>spawnShared(() -> (mutex, context) -> {
                var guard = mutex.tryLock().orElseThrow();
                guard.value()[0] = 11;
                guard.release();
                delivered.countDown();
            });

            assertDoesNotThrow(() -> receiver.send(unbound));
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(11, unbound.withLock(value -> value[0]).intValue());
        }
    }



    @Test
    void actorTransportRejectsHostCreatedSharedMutexWithUnsafePayload() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var receiver = runtime.<OresMutex.Shared<Object>>spawnShared(
                    () -> (message, context) -> { });

            var nestedLocal = OresMutex.shared((Object) OresMutex.local(new int[]{1}));
            IllegalArgumentException localError = assertThrows(
                    IllegalArgumentException.class,
                    () -> receiver.send(nestedLocal));
            assertTrue(localError.getMessage().contains("actor-local mutex state"));

            var opaque = OresMutex.shared(new Object());
            IllegalArgumentException opaqueError = assertThrows(
                    IllegalArgumentException.class,
                    () -> receiver.send(opaque));
            assertTrue(opaqueError.getMessage().contains("opaque host value"));
        }
    }

    @Test
    void actorTransportAcceptsRuntimeInspectableSharedState() throws Exception {
        record SafeBox(int value) implements OresMutex.SharedState {
            @Override public Iterable<?> sharedStateChildren() { return List.of(value); }
        }

        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch delivered = new CountDownLatch(1);
            var receiver = runtime.<OresMutex.Shared<SafeBox>>spawnShared(
                    () -> (message, context) -> {
                        var guard = message.tryLock().orElseThrow();
                        assertEquals(7, guard.value().value());
                        guard.release();
                        delivered.countDown();
                    });

            var shared = OresMutex.shared(new SafeBox(7));
            assertDoesNotThrow(() -> receiver.send(shared));
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
        }
    }


    @Test
    void nestedSharedMutexPayloadsAreRejectedUntilRecursiveLockOrderingExists() {
        record Box(OresMutex.Shared<int[]> inner) implements OresMutex.SharedState {
            @Override public Iterable<?> sharedStateChildren() { return List.of(inner); }
        }

        var outer = OresMutex.shared(new Box(OresMutex.shared(new int[]{3})));

        try (ActorRuntime runtime = new ActorRuntime()) {
            var receiver = runtime.<OresMutex.Shared<Box>>spawnShared(
                    () -> (message, context) -> { });
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> receiver.send(outer));
            assertTrue(error.getMessage().contains("cannot contain another SharedMutex"));
        }
    }

    @Test
    void transportInspectionNeverBlocksOnALockedSharedMutex() {
        var shared = OresMutex.shared(new int[]{1});
        var guard = shared.lock();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var receiver = runtime.<OresMutex.Shared<int[]>>spawnShared(
                    () -> (message, context) -> { });

            IllegalStateException busy = assertThrows(
                    IllegalStateException.class,
                    () -> receiver.send(shared));
            assertTrue(busy.getMessage().contains("cannot be published while locked or contended"));

            guard.release();
            assertDoesNotThrow(() -> receiver.send(shared));
        }
    }

    @Test
    void cyclicRuntimeSharedStateIsRejectedAtTransportBoundary() {
        final class CyclicBox implements OresMutex.SharedState {
            private Object child;
            @Override public Iterable<?> sharedStateChildren() { return List.of(child); }
        }

        CyclicBox box = new CyclicBox();
        box.child = box;
        var shared = OresMutex.shared(box);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var receiver = runtime.<OresMutex.Shared<CyclicBox>>spawnShared(
                    () -> (message, context) -> { });
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> receiver.send(shared));
            assertTrue(error.getMessage().contains("cyclic SharedMutex payload"));
        }
    }


    @Test
    void asyncTimedLockTimesOutAndReleasesItsDomainReservation() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lock();

        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> waitingRef = new AtomicReference<>();
        Thread requester = Thread.ofPlatform().start(
                () -> waitingRef.set(mutex.lockAsyncFor(Duration.ofMillis(25))));
        requester.join();

        var waiting = waitingRef.get();
        var failure = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> waiting.get(2, TimeUnit.SECONDS));
        assertInstanceOf(OresMutex.LockTimeoutException.class, failure.getCause());

        guard.release();
        var next = mutex.lock();
        next.release();
    }

    @Test
    void asyncTimedLockCanWinBeforeDeadline() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lock();

        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> waitingRef = new AtomicReference<>();
        Thread requester = Thread.ofPlatform().start(
                () -> waitingRef.set(mutex.lockAsyncFor(Duration.ofSeconds(2))));
        requester.join();
        var waiting = waitingRef.get();
        assertFalse(waiting.isDone());

        guard.release();

        var acquired = waiting.get(2, TimeUnit.SECONDS);
        acquired.value()[0] = 5;
        acquired.release();
        assertEquals(5, mutex.withLock(value -> value[0]).intValue());
    }

    @Test
    void asyncTimedLockZeroAndNegativeTimeoutsAreWellDefined() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var guard = mutex.lock();

        AtomicReference<java.util.concurrent.CompletableFuture<OresMutex.Guard<int[]>>> zeroRef = new AtomicReference<>();
        Thread requester = Thread.ofPlatform().start(
                () -> zeroRef.set(mutex.lockAsyncFor(Duration.ZERO)));
        requester.join();

        var zero = zeroRef.get();
        var failure = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> zero.get(2, TimeUnit.SECONDS));
        assertInstanceOf(OresMutex.LockTimeoutException.class, failure.getCause());

        assertThrows(IllegalArgumentException.class,
                () -> mutex.lockAsyncFor(Duration.ofMillis(-1)));

        guard.release();
        var next = mutex.lock();
        next.release();
    }


    @Test
    void crossMutexTwoDomainCycleIsRejectedInsteadOfHanging() throws Exception {
        var left = OresMutex.shared(new int[]{0});
        var right = OresMutex.shared(new int[]{0});
        CountDownLatch bothHeld = new CountDownLatch(2);
        CountDownLatch startCrossAcquire = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        Thread first = Thread.ofPlatform().start(() -> {
            OresMutex.Guard<int[]> leftGuard = left.lock();
            try {
                bothHeld.countDown();
                assertTrue(bothHeld.await(2, TimeUnit.SECONDS));
                assertTrue(startCrossAcquire.await(2, TimeUnit.SECONDS));
                try {
                    var rightGuard = right.lock();
                    rightGuard.release();
                } catch (Throwable failure) {
                    firstFailure.set(failure);
                }
            } catch (Throwable failure) {
                firstFailure.compareAndSet(null, failure);
            } finally {
                leftGuard.release();
            }
        });

        Thread second = Thread.ofPlatform().start(() -> {
            OresMutex.Guard<int[]> rightGuard = right.lock();
            try {
                bothHeld.countDown();
                assertTrue(bothHeld.await(2, TimeUnit.SECONDS));
                assertTrue(startCrossAcquire.await(2, TimeUnit.SECONDS));
                try {
                    var leftGuard = left.lock();
                    leftGuard.release();
                } catch (Throwable failure) {
                    secondFailure.set(failure);
                }
            } catch (Throwable failure) {
                secondFailure.compareAndSet(null, failure);
            } finally {
                rightGuard.release();
            }
        });

        assertTrue(bothHeld.await(2, TimeUnit.SECONDS));
        startCrossAcquire.countDown();
        first.join(3_000);
        second.join(3_000);

        assertFalse(first.isAlive(), "first domain must not remain deadlocked");
        assertFalse(second.isAlive(), "second domain must not remain deadlocked");
        assertTrue(
                firstFailure.get() instanceof OresMutex.DeadlockDetectedException
                        || secondFailure.get() instanceof OresMutex.DeadlockDetectedException,
                "at least one edge that closes the cycle must be rejected");
    }

    @Test
    void timedOutAsyncWaitRemovesDeadlockGraphEdge() throws Exception {
        var held = OresMutex.shared(new int[]{0});
        var free = OresMutex.shared(new int[]{0});
        var owner = held.lock();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                var timed = held.lockAsyncFor(Duration.ofMillis(25));
                var timedFailure = assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> timed.get(2, TimeUnit.SECONDS));
                assertInstanceOf(OresMutex.LockTimeoutException.class, timedFailure.getCause());

                var next = free.lock();
                next.release();
            } catch (Throwable problem) {
                failure.set(problem);
            }
        });

        waiter.join(3_000);
        assertFalse(waiter.isAlive());
        assertNull(failure.get());
        owner.release();
    }

    @Test
    void zeroDurationLockForRemainsTryOnly() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var owner = mutex.lock();
        AtomicReference<Optional<OresMutex.Guard<int[]>>> result = new AtomicReference<>();
        Thread contender = Thread.ofPlatform().start(
                () -> result.set(mutex.lockFor(Duration.ZERO)));
        contender.join();
        assertTrue(result.get().isEmpty());
        owner.release();
    }


}
