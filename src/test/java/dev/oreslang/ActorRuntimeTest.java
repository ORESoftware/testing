package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorRuntimeTest {
    @Test
    void freezesMessagesBeforeDelivery() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<List<?>> observed = new AtomicReference<>();
            var ref = runtime.<List<Integer>>spawn(() -> (message, context) -> {
                observed.set(message);
                received.countDown();
            });

            ArrayList<Integer> mutable = new ArrayList<>(List.of(1, 2));
            ref.send(mutable);
            mutable.add(3);

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), observed.get());
            assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) observed.get()).add(9));
        }
    }

    @Test
    void rejectsUnknownMutableHostObjects() {
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(new StringBuilder("mutable")));
    }

    @Test
    void actorCarriesItsOwnStricterPolicy() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IsolatePolicy strict = IsolatePolicy.strictFaas();

            var ref = runtime.<String>spawnPrivate(strict, factoryContext -> {
                if (factoryContext.policy().allows(IsolatePolicy.Capability.SHARED_MEMORY)) {
                    throw new AssertionError("private actor policy retained SHARED_MEMORY");
                }
                if (factoryContext.policy().maxMailboxMessages() != IsolatePolicy.strictFaas().maxMailboxMessages()) {
                    throw new AssertionError("private actor policy did not preserve mailbox limit");
                }
                return (message, context) -> context.self().stop();
            });
            ref.send("ping");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (ref.isAlive() && System.nanoTime() < deadline) Thread.sleep(5);
            assertFalse(ref.isAlive());
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void childActorCannotEscalatePastRuntimePolicyCeiling() {
        IsolatePolicy ceiling = IsolatePolicy.strictFaas();
        try (ActorRuntime runtime = new ActorRuntime(ceiling)) {
            IsolatePolicy escalated = ceiling.withCapabilities(IsolatePolicy.Capability.PROCESS_INFO);
            assertThrows(SecurityException.class, () ->
                    runtime.<String>spawnPrivate(escalated, factoryContext -> (message, context) -> { }));
        }
    }

    @Test
    void readonlySharingDeepFreezesContainers() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var shared = runtime.shareReadonly(Map.of("items", List.of(1, 2, 3)));
            assertNotNull(shared.value());
        }
    }
    @Test
    void sharedActorProcessesOnlyOneMessageAtATime() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(2, 4, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger maxInFlight = new AtomicInteger();
            CountDownLatch received = new CountDownLatch(64);

            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                int current = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(current, Math::max);
                try {
                    Thread.sleep(2);
                } finally {
                    inFlight.decrementAndGet();
                    received.countDown();
                }
            });

            for (int i = 0; i < 64; i++) ref.send(i);

            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertEquals(1, maxInFlight.get(), "one actor mailbox must never run concurrently");
        }
    }

    @Test
    void carrierHandoffAndTerminationWaitForTurnExecutorExit() throws Exception {
        CountDownLatch firstTurnReturnedToExecutor = new CountDownLatch(1);
        CountDownLatch releaseFirstExecutor = new CountDownLatch(1);
        CountDownLatch secondDelivered = new CountDownLatch(1);
        AtomicInteger executorRuns = new AtomicInteger();
        AtomicInteger activeExecutors = new AtomicInteger();
        AtomicInteger maxActiveExecutors = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();

        ActorRuntime.TurnExecutor executor = turn -> {
            int active = activeExecutors.incrementAndGet();
            maxActiveExecutors.accumulateAndGet(active, Math::max);
            int runNumber = executorRuns.incrementAndGet();
            try {
                turn.run();
                if (runNumber == 1) {
                    firstTurnReturnedToExecutor.countDown();
                    try {
                        if (!releaseFirstExecutor.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test executor release timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(interrupted);
                    }
                }
            } finally {
                activeExecutors.decrementAndGet();
            }
        };

        var config = new ActorRuntime.DispatcherConfig(1, 2, 1, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config, executor)) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                int seen = delivered.incrementAndGet();
                if (seen == 2) {
                    secondDelivered.countDown();
                    context.self().stop();
                }
            });

            ref.send(1);
            ref.send(2);

            assertTrue(firstTurnReturnedToExecutor.await(2, TimeUnit.SECONDS));
            try {
                Thread.sleep(50);
                assertEquals(
                        1,
                        delivered.get(),
                        "next mailbox batch must not start until the prior TurnExecutor boundary exits");
                assertEquals(
                        1,
                        maxActiveExecutors.get(),
                        "one actor must not occupy overlapping TurnExecutor/carrier boundaries");
                assertFalse(
                        ref.awaitTermination(25, TimeUnit.MILLISECONDS),
                        "termination cannot become visible while a scheduled carrier still owns the executor boundary");
            } finally {
                releaseFirstExecutor.countDown();
            }

            assertTrue(secondDelivered.await(2, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(1, maxActiveExecutors.get());
            assertEquals(2, delivered.get());
        } finally {
            releaseFirstExecutor.countDown();
        }
    }

    @Test
    void closeFinalizesQueuedActorBatchCancelledBeforeCarrierEntry() throws Exception {
        CountDownLatch executorEntered = new CountDownLatch(1);
        CountDownLatch releaseExecutor = new CountDownLatch(1);

        ActorRuntime.TurnExecutor executor = turn -> {
            executorEntered.countDown();
            try {
                releaseExecutor.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            turn.run();
        };

        var config = new ActorRuntime.DispatcherConfig(1, 1, 1, 16);
        ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config, executor);
        try {
            var first = runtime.<Integer>spawnShared(() -> (message, context) -> { });
            var queued = runtime.<Integer>spawnShared(() -> (message, context) -> { });

            first.send(1);
            assertTrue(executorEntered.await(2, TimeUnit.SECONDS));
            queued.send(2);

            assertDoesNotThrow(runtime::close);
            assertFalse(first.isAlive());
            assertFalse(queued.isAlive());
        } finally {
            releaseExecutor.countDown();
            try {
                runtime.close();
            } catch (IllegalStateException ignored) {
                // Preserve the primary assertion if a broken implementation
                // already reported a close failure above.
            }
        }
    }

    @Test
    void privateAndSharedActorsUseDifferentDispatchers() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CountDownLatch received = new CountDownLatch(2);
            AtomicReference<String> privateThread = new AtomicReference<>();
            AtomicReference<String> sharedThread = new AtomicReference<>();

            var privateRef = runtime.<String>spawnPrivate(() -> (message, context) -> {
                privateThread.set(Thread.currentThread().getName());
                assertEquals(ActorRuntime.ActorKind.PRIVATE, context.kind());
                received.countDown();
            });
            var sharedRef = runtime.<String>spawnShared(() -> (message, context) -> {
                sharedThread.set(Thread.currentThread().getName());
                assertEquals(ActorRuntime.ActorKind.SHARED, context.kind());
                received.countDown();
            });

            privateRef.send("private");
            sharedRef.send("shared");

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertTrue(privateThread.get().startsWith("ores-private-actor-dispatcher-"));
            assertTrue(sharedThread.get().startsWith("ores-shared-actor-dispatcher-"));
        }
    }

    @Test
    void sharedActorsCanCoordinateThroughExplicitSyncCell() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 4, 32);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            ActorRuntime.SyncCell<Integer> cell = runtime.syncCell(0);
            CountDownLatch received = new CountDownLatch(200);

            var a = runtime.<Integer>spawnShared(() -> (message, context) -> {
                cell.update(value -> value + 1);
                received.countDown();
            });
            var b = runtime.<Integer>spawnShared(() -> (message, context) -> {
                cell.update(value -> value + 1);
                received.countDown();
            });

            for (int i = 0; i < 100; i++) {
                a.send(i);
                b.send(i);
            }

            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertEquals(200, cell.snapshot());
        }
    }

    @Test
    void privateActorsRejectSharedMutableCells() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.SyncCell<Integer> cell = runtime.syncCell(0);
            var ref = runtime.<Object>spawnPrivate(() -> (message, context) -> { });
            assertThrows(IllegalArgumentException.class, () -> ref.send(cell));
        }
    }

    @Test
    void privateActorsIsolationCopyReadonlySharedValues() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<Object> observed = new AtomicReference<>();
            var ref = runtime.<Object>spawnPrivate(() -> (message, context) -> {
                observed.set(message);
                received.countDown();
            });

            var shared = runtime.shareReadonly(List.of("a", "b"));
            ref.send(shared);

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("a", "b"), observed.get());
            assertFalse(observed.get() instanceof ActorRuntime.Shared<?>);
        }
    }

    @Test
    void privateActorGetsConfinedMemorySliceOwnedByItsActorId() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorId> owner = new AtomicReference<>();
            AtomicReference<Long> usedDuringTurn = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                var memory = context.privateMemory().orElseThrow();
                owner.set(memory.owner());
                usedDuringTurn.set(memory.usedBytes());
                received.countDown();
            });

            ref.send("private-payload");

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(ref.id(), owner.get());
            assertTrue(usedDuringTurn.get() > 0L, "mailbox payload must be charged to the private slice during delivery");
        }
    }

    @Test
    void sharedActorHasNoPrivateMemorySlice() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<Boolean> hasPrivateMemory = new AtomicReference<>();

            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                hasPrivateMemory.set(context.privateMemory().isPresent());
                received.countDown();
            });

            ref.send("shared-payload");

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertFalse(hasPrivateMemory.get());
        }
    }

    @Test
    void privateActorRejectsMessageThatExceedsItsMemorySlice() {
        IsolatePolicy ceiling = IsolatePolicy.developer();
        IsolatePolicy small = new IsolatePolicy(
                ceiling.capabilities(),
                16L * 1024 * 1024,
                ceiling.maxMailboxMessages(),
                ceiling.maxWallTime(),
                false);

        try (ActorRuntime runtime = new ActorRuntime(ceiling)) {
            var ref = runtime.<String>spawnPrivate(small, () -> (message, context) -> { });
            String tooLarge = "x".repeat(9 * 1024 * 1024);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> ref.send(tooLarge));
            assertTrue(failure.getMessage().contains("private actor mailbox limit exceeded"));
            assertEquals(0L, runtime.privateMemoryBytes(), "failed admission must roll back aggregate accounting");
        }
    }

    @Test
    void persistentPrivateHeapReservationsShareTheSameSliceBudget() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch reserved = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<ActorRuntime.MemoryReservation> stateReservation = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                var memory = context.privateMemory().orElseThrow();
                stateReservation.set(memory.reserveHeap(1024));
                assertTrue(memory.usedBytes() >= 1024);
                reserved.countDown();
                assertTrue(release.await(2, TimeUnit.SECONDS));
                stateReservation.get().close();
            });

            ref.send("reserve");
            assertTrue(reserved.await(2, TimeUnit.SECONDS));
            assertTrue(runtime.privateMemoryBytes() >= 1024);
            release.countDown();
        }
    }

    @Test
    void privateActorFactoryInitializesInsideOwnedMemorySlice() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch constructed = new CountDownLatch(1);
            CountDownLatch handled = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorId> constructionOwner = new AtomicReference<>();
            AtomicReference<ActorRuntime.MemoryReservation> state = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivateTrusted(context -> {
                var memory = context.privateMemory().orElseThrow();
                constructionOwner.set(memory.owner());
                state.set(memory.reserveHeap(4096));
                constructed.countDown();

                return (message, turn) -> {
                    assertEquals(memory.owner(), turn.self().id());
                    assertTrue(memory.usedBytes() >= 4096);
                    state.get().close();
                    handled.countDown();
                };
            });

            ref.send("initialize");

            assertTrue(constructed.await(2, TimeUnit.SECONDS));
            assertEquals(ref.id(), constructionOwner.get());
            assertTrue(handled.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void leakedPrivateMemorySliceCannotBeReservedOutsideOwningActorTurn() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch captured = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorMemorySlice> leaked = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                leaked.set(context.privateMemory().orElseThrow());
                captured.countDown();
            });

            ref.send("capture");
            assertTrue(captured.await(2, TimeUnit.SECONDS));

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> leaked.get().reserveHeap(1));
            assertTrue(failure.getMessage().contains("owning actor"));
        }
    }

    @Test
    void privateActorsShareParentAggregateMemoryCeiling() throws Exception {
        IsolatePolicy parent = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                16L * 1024 * 1024,
                32,
                IsolatePolicy.developer().maxWallTime(),
                false);

        try (ActorRuntime runtime = new ActorRuntime(parent, new ActorRuntime.DispatcherConfig(2, 1, 8))) {
            CountDownLatch firstReserved = new CountDownLatch(1);
            CountDownLatch holdFirst = new CountDownLatch(1);

            var first = runtime.<String>spawnPrivateTrusted(parent, context -> {
                var reservation = context.privateMemory().orElseThrow().reserveHeap(10L * 1024 * 1024);
                firstReserved.countDown();
                return (message, turn) -> {
                    try {
                        assertTrue(holdFirst.await(2, TimeUnit.SECONDS));
                    } finally {
                        reservation.close();
                    }
                };
            });

            var second = runtime.<String>spawnPrivate(parent, () -> (message, context) -> { });

            first.send("start");
            assertTrue(firstReserved.await(2, TimeUnit.SECONDS));
            assertTrue(runtime.privateMemoryBytes() >= 10L * 1024 * 1024);

            // ~8 MiB logical footprint: below the second actor's 16 MiB slice,
            // but above the remaining aggregate parent budget.
            String payload = "x".repeat(4 * 1024 * 1024);
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> second.send(payload));
            assertTrue(failure.getMessage().contains("aggregate runtime limit exceeded"));

            holdFirst.countDown();
        }
    }

    @Test
    void readonlySharingRejectsNestedSyncCells() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var cell = runtime.syncCell(1);
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.shareReadonly(Map.of("cell", cell)));
        }
    }

    @Test
    void syncCellMutationRequiresSharedActorTurn() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var cell = runtime.syncCell(1);
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> cell.update(value -> value + 1));
            assertTrue(failure.getMessage().contains("shared actor mailbox turn"));
        }
    }

    @Test
    void privateActorRejectsNestedSharedMutableCells() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var cell = runtime.syncCell(0);
            var ref = runtime.<Object>spawnPrivate(() -> (message, context) -> { });
            assertThrows(IllegalArgumentException.class,
                    () -> ref.send(Map.of("nested", List.of(cell))));
        }
    }

    @Test
    void capturedPrivateMemorySliceCannotBeUsedOutsideOwnerTurn() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorMemorySlice> captured = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                captured.set(context.privateMemory().orElseThrow());
                received.countDown();
            });

            ref.send("capture");
            assertTrue(received.await(2, TimeUnit.SECONDS));

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> captured.get().reserveHeap(64));
            assertTrue(failure.getMessage().contains("owning actor"));
        }
    }


    @Test
    void adversarialActorsRejectSupplierFactoriesThatCanCaptureHostState() {
        IsolatePolicy strict = IsolatePolicy.strictFaas();
        try (ActorRuntime runtime = new ActorRuntime(strict)) {
            assertThrows(SecurityException.class, () ->
                    runtime.<String>spawnPrivate(strict, () -> (message, context) -> { }));

            assertDoesNotThrow(() ->
                    runtime.<String>spawnPrivate(strict, factoryContext -> (message, context) -> { }));
        }
    }

    @Test
    void actorFailureIsRetainedOnRefAndSubsequentSendReportsTermination() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                entered.countDown();
                throw new IllegalStateException("boom");
            });

            ref.send("fail");
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (ref.isAlive() && System.nanoTime() < deadline) Thread.sleep(5);

            assertFalse(ref.isAlive());
            assertTrue(ref.failure().isPresent());
            assertEquals("boom", ref.failure().orElseThrow().getMessage());

            ActorRuntime.ActorTerminatedException terminated = assertThrows(
                    ActorRuntime.ActorTerminatedException.class,
                    () -> ref.send("after-failure"));
            assertSame(ref.failure().orElseThrow(), terminated.getCause());
        }
    }

    @Test
    void explicitStopClosesPrivateSliceAndRejectsFurtherMessages() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch reserved = new CountDownLatch(1);
            AtomicReference<ActorRuntime.MemoryReservation> reservation = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivateTrusted(factoryContext -> {
                reservation.set(factoryContext.privateMemory().orElseThrow().reserveHeap(4096));
                reserved.countDown();
                return (message, context) -> { };
            });

            ref.send("initialize");
            assertTrue(reserved.await(2, TimeUnit.SECONDS));
            assertTrue(runtime.privateMemoryBytes() >= 4096);

            ref.stop();

            assertFalse(ref.isAlive());
            assertEquals(0L, runtime.privateMemoryBytes());
            assertThrows(ActorRuntime.ActorTerminatedException.class, () -> ref.send("after-stop"));
            reservation.get().close(); // idempotent after slice teardown
            assertEquals(0L, runtime.privateMemoryBytes());
        }
    }


    @Test
    void actorMessageGraphDepthIsBounded() {
        Object nested = "leaf";
        for (int i = 0; i < 300; i++) nested = List.of(nested);
        Object tooDeep = nested;

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(tooDeep));
        assertTrue(failure.getMessage().contains("maximum nesting depth"));
    }

    @Test
    void sharedCellsConsumeAndReleaseRuntimeActorMemoryBudget() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            assertEquals(0L, runtime.sharedMemoryBytes());
            var cell = runtime.syncCell("shared-state");
            assertTrue(runtime.sharedMemoryBytes() > 0L);
            assertEquals(runtime.sharedMemoryBytes(), runtime.actorMemoryBytes());

            cell.close();

            assertEquals(0L, runtime.sharedMemoryBytes());
            assertEquals(0L, runtime.actorMemoryBytes());
            assertTrue(cell.closed());
            assertThrows(IllegalStateException.class, cell::snapshot);
        }
    }

    @Test
    void privateActorCannotAccessLeakedSyncCellHandle() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var cell = runtime.syncCell(1);
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                try {
                    cell.snapshot();
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    checked.countDown();
                }
            });

            ref.send("read");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, observed.get());
            assertTrue(observed.get().getMessage().contains("private actors cannot access synchronized shared memory"));
        }
    }

    @Test
    void privateAndSharedMemoryCompeteForOneParentCeiling() throws Exception {
        IsolatePolicy parent = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                16L * 1024 * 1024,
                128,
                IsolatePolicy.developer().maxWallTime(),
                false);

        try (ActorRuntime runtime = new ActorRuntime(parent)) {
            CountDownLatch reserved = new CountDownLatch(1);
            var ref = runtime.<String>spawnPrivateTrusted(parent, factoryContext -> {
                factoryContext.privateMemory().orElseThrow().reserveHeap(10L * 1024 * 1024);
                reserved.countDown();
                return (message, context) -> { };
            });

            ref.send("initialize");
            assertTrue(reserved.await(2, TimeUnit.SECONDS));
            assertTrue(runtime.privateMemoryBytes() >= 10L * 1024 * 1024);

            String sharedTooLarge = "x".repeat(4 * 1024 * 1024);
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.syncCell(sharedTooLarge));
            assertTrue(failure.getMessage().contains("aggregate runtime limit exceeded"));
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }


    @Test
    void strictPolicyRejectsSharedActorMemoryAtRuntime() {
        IsolatePolicy strict = IsolatePolicy.strictFaas();
        try (ActorRuntime runtime = new ActorRuntime(strict)) {
            assertThrows(SecurityException.class, () ->
                    runtime.<String>spawnShared(strict, factoryContext -> (message, context) -> { }));
            assertThrows(SecurityException.class, () -> runtime.syncCell(1));
        }
    }


    @Test
    void readonlySharedValuesAreQuotaAccountedAndInvalidAfterRuntimeClose() {
        ActorRuntime runtime = new ActorRuntime();
        ActorRuntime.Shared<Map<String, List<Integer>>> shared =
                runtime.shareReadonly(Map.of("items", List.of(1, 2, 3)));

        assertTrue(runtime.sharedMemoryBytes() > 0L);
        assertEquals(List.of(1, 2, 3), shared.value().get("items"));

        runtime.close();

        assertEquals(0L, runtime.sharedMemoryBytes());
        assertThrows(IllegalStateException.class, shared::value);
    }

    @Test
    void strictPolicyRejectsReadonlySharingWithoutCapability() {
        IsolatePolicy strict = IsolatePolicy.strictFaas();
        try (ActorRuntime runtime = new ActorRuntime(strict)) {
            assertThrows(SecurityException.class, () -> runtime.shareReadonly(List.of(1, 2, 3)));
        }
    }


    @Test
    void nestedDifferentSyncCellsAreRejectedInsteadOfRiskingLockOrderDeadlock() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var first = runtime.syncCell(1);
            var second = runtime.syncCell(2);
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();

            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                first.update(value -> {
                    try {
                        second.snapshot();
                    } catch (Throwable failure) {
                        observed.set(failure);
                    }
                    return value;
                });
                checked.countDown();
            });

            ref.send("check");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, observed.get());
            assertTrue(observed.get().getMessage().contains("nested synchronization"));
        }
    }


    @Test
    void runtimeCloseDoesNotBlockOnActorHeldSyncCellLock() throws Exception {
        ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8));
        var cell = runtime.syncCell(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        var ref = runtime.<String>spawnShared(() -> (message, context) ->
                cell.update(value -> {
                    entered.countDown();
                    boolean done = false;
                    while (!done) {
                        try {
                            done = release.await(25, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                            // Deliberately ignore shutdown interruption to prove
                            // runtime.close() does not wait on this user lock.
                        }
                    }
                    return value;
                }));

        ref.send("hold");
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try {
                runtime.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        });
        closer.start();
        closer.join(500);

        try {
            assertFalse(closer.isAlive(), "runtime close must not wait for user code holding a SyncCell lock");
            assertInstanceOf(IllegalStateException.class, closeFailure.get());
            assertTrue(closeFailure.get().getMessage().contains("full actor termination"));
            assertTrue(cell.closed());
            assertEquals(0L, runtime.sharedMemoryBytes());
        } finally {
            release.countDown();
            closer.join(2000);
        }

        assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
        assertDoesNotThrow(runtime::close);
    }


    @Test
    void sharedActorsRejectForeignRuntimeSharedHandles() {
        try (ActorRuntime left = new ActorRuntime();
             ActorRuntime right = new ActorRuntime()) {
            var foreignShared = left.shareReadonly(List.of(1, 2, 3));
            var foreignCell = left.syncCell(1);
            var ref = right.<Object>spawnShared(() -> (message, context) -> { });

            IllegalArgumentException sharedFailure = assertThrows(
                    IllegalArgumentException.class,
                    () -> ref.send(foreignShared));
            assertTrue(sharedFailure.getMessage().contains("different ActorRuntime"));

            IllegalArgumentException cellFailure = assertThrows(
                    IllegalArgumentException.class,
                    () -> ref.send(foreignCell));
            assertTrue(cellFailure.getMessage().contains("different ActorRuntime"));
        }
    }

    @Test
    void privateActorsCopyForeignReadonlyValuesInsteadOfRetainingRuntimeHandle() throws Exception {
        try (ActorRuntime left = new ActorRuntime();
             ActorRuntime right = new ActorRuntime()) {
            var foreignShared = left.shareReadonly(List.of("a", "b"));
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<Object> observed = new AtomicReference<>();

            var ref = right.<Object>spawnPrivate(() -> (message, context) -> {
                observed.set(message);
                received.countDown();
            });

            ref.send(foreignShared);

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("a", "b"), observed.get());
            assertFalse(observed.get() instanceof ActorRuntime.Shared<?>);
        }
    }


    @Test
    void actorRefsCannotBecomeImplicitCrossRuntimeChannels() {
        try (ActorRuntime left = new ActorRuntime();
             ActorRuntime right = new ActorRuntime()) {
            var foreign = left.<String>spawnPrivate(() -> (message, context) -> { });
            var localPrivate = right.<Object>spawnPrivate(() -> (message, context) -> { });
            var localShared = right.<Object>spawnShared(() -> (message, context) -> { });

            IllegalArgumentException privateFailure = assertThrows(
                    IllegalArgumentException.class,
                    () -> localPrivate.send(foreign));
            assertTrue(privateFailure.getMessage().contains("different ActorRuntime"));

            IllegalArgumentException sharedFailure = assertThrows(
                    IllegalArgumentException.class,
                    () -> localShared.send(Map.of("ref", foreign)));
            assertTrue(sharedFailure.getMessage().contains("different ActorRuntime"));

            assertThrows(IllegalArgumentException.class,
                    () -> right.shareReadonly(List.of(foreign)));
        }
    }

    @Test
    void actorRefsRemainSendableInsideOneRuntime() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            var target = runtime.<String>spawnPrivate(() -> (message, context) -> { });
            var receiver = runtime.<Object>spawnPrivate(() -> (message, context) -> {
                assertSame(target, message);
                received.countDown();
            });

            receiver.send(target);
            assertTrue(received.await(2, TimeUnit.SECONDS));
        }
    }


    @Test
    void actorPopulationIsBoundedAndSlotsReturnOnStop() {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8, 2);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var first = runtime.<String>spawnPrivate(() -> (message, context) -> { });
            var second = runtime.<String>spawnShared(() -> (message, context) -> { });

            assertEquals(2, runtime.actorCount());
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.<String>spawnPrivate(() -> (message, context) -> { }));
            assertTrue(failure.getMessage().contains("actor runtime limit exceeded"));

            first.stop();
            assertEquals(1, runtime.actorCount());

            assertDoesNotThrow(() ->
                    runtime.<String>spawnPrivate(() -> (message, context) -> { }));
            assertEquals(2, runtime.actorCount());

            second.stop();
        }
    }


    @Test
    void safePrivateFactoryRejectsCapturedHostStateEvenInDeveloperMode() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Object mutableHostState = new StringBuilder("host");
            SecurityException failure = assertThrows(
                    SecurityException.class,
                    () -> runtime.<String>spawnPrivate(factoryContext -> {
                        mutableHostState.toString();
                        return (message, context) -> { };
                    }));
            assertTrue(failure.getMessage().contains("stateless"));
        }
    }

    @Test
    void isolatedPrivateActorCanUseOwnerCheckedDirectMemory() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Byte>spawnPrivate(factoryContext -> {
                ActorRuntime.PrivateMemoryBlock block =
                        factoryContext.privateMemory().orElseThrow().allocatePrivateBytes(64);
                block.writeByte(0, (byte) 7);

                return (message, context) -> {
                    if (block.readByte(0) != 7) throw new AssertionError("private block lost actor state");
                    block.writeByte(1, message);
                    if (block.readByte(1) != message) throw new AssertionError("private block write mismatch");
                    context.self().stop();
                };
            });

            ref.send((byte) 42);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (ref.isAlive() && System.nanoTime() < deadline) Thread.sleep(5);
            assertFalse(ref.isAlive());
            assertTrue(ref.failure().isEmpty());
            assertEquals(0L, runtime.privateMemoryBytes(), "actor teardown must release its private direct-memory quota");
        }
    }

    @Test
    void leakedPrivateDirectMemoryCannotBeReadOutsideOwnerActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorRuntime.PrivateMemoryBlock> leaked = new AtomicReference<>();
            CountDownLatch created = new CountDownLatch(1);

            var ref = runtime.<String>spawnPrivateTrusted(factoryContext -> {
                ActorRuntime.PrivateMemoryBlock block =
                        factoryContext.privateMemory().orElseThrow().allocatePrivateBytes(8);
                block.writeByte(0, (byte) 99);
                leaked.set(block);
                created.countDown();
                return (message, context) -> { };
            });

            ref.send("initialize");
            assertTrue(created.await(2, TimeUnit.SECONDS));
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> leaked.get().readByte(0));
            assertTrue(failure.getMessage().contains("owning actor"));
            ref.stop();
            assertTrue(leaked.get().closed());
        }
    }

    @Test
    void privateDirectMemoryHandleCannotCrossMailboxBoundary() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorRuntime.PrivateMemoryBlock> block = new AtomicReference<>();
            CountDownLatch created = new CountDownLatch(1);

            var owner = runtime.<String>spawnPrivateTrusted(factoryContext -> {
                block.set(factoryContext.privateMemory().orElseThrow().allocatePrivateBytes(8));
                created.countDown();
                return (message, context) -> { };
            });
            owner.send("initialize");
            assertTrue(created.await(2, TimeUnit.SECONDS));

            var other = runtime.<Object>spawnPrivate(() -> (message, context) -> { });
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> other.send(block.get()));
            assertTrue(failure.getMessage().contains("not Sendable")
                    || failure.getMessage().contains("actor boundaries"));

            owner.stop();
            other.stop();
        }
    }



    @Test
    void actorCannotUseClosureCapturedForeignRuntimeSendOrSpawn() throws Exception {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> sendFailure = new AtomicReference<>();
            AtomicReference<Throwable> spawnFailure = new AtomicReference<>();

            var target = runtimeA.<String>spawn(() -> (message, context) -> { });
            var caller = runtimeB.<String>spawn(() -> (message, context) -> {
                try {
                    runtimeA.send(target, "cross-runtime");
                } catch (Throwable problem) {
                    sendFailure.set(problem);
                }
                try {
                    runtimeA.<String>spawn(() -> (childMessage, childContext) -> { });
                } catch (Throwable problem) {
                    spawnFailure.set(problem);
                } finally {
                    checked.countDown();
                }
            });

            caller.send("go");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, sendFailure.get());
            assertInstanceOf(SecurityException.class, spawnFailure.get());
        }
    }

    @Test
    void actorCannotInvokeClosureCapturedForeignActorRef() throws Exception {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            CountDownLatch targetReceived = new CountDownLatch(1);
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            var target = runtimeA.<String>spawn(() -> (message, context) -> targetReceived.countDown());
            var foreignCaller = runtimeB.<String>spawn(() -> (message, context) -> {
                try {
                    target.send("cross-runtime");
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    checked.countDown();
                }
            });

            foreignCaller.send("go");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, failure.get());
            assertEquals(1L, targetReceived.getCount());
        }
    }

    @Test
    void actorRuntimeEnforcesConfiguredActorCeiling() {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), 1)) {
            runtime.<String>spawn(() -> (message, context) -> { });
            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.<String>spawn(() -> (message, context) -> { }));
            assertTrue(error.getMessage().contains("actor runtime limit exceeded"));
            assertEquals(1, runtime.maxActors());
        }
    }

    @Test
    void actorCodeCannotCloseItsOwnRuntime() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            var ref = runtime.<String>spawn(() -> (message, context) -> {
                try {
                    assertThrows(SecurityException.class, context.runtime()::close);
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    checked.countDown();
                }
            });

            ref.send("check");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
        }
    }

    @Test
    void strictActorCannotBypassReadonlyCapabilityThroughRuntimeHandle() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IsolatePolicy strict = IsolatePolicy.strictFaas();
            var ref = runtime.<String>spawnPrivate(
                    strict,
                    factoryContext -> (message, context) ->
                            context.runtime().shareReadonly(List.of("secret")));

            ref.send("check");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));

            assertTrue(ref.failure().isPresent());
            assertInstanceOf(SecurityException.class, ref.failure().orElseThrow());
        }
    }

    @Test
    void synchronousInvokeWaitsForCarrierToLeaveTurnExecutor() throws Exception {
        CountDownLatch guestTurnReturned = new CountDownLatch(1);
        CountDownLatch releaseCarrier = new CountDownLatch(1);

        ActorRuntime.TurnExecutor turnExecutor = turn -> {
            turn.run();
            guestTurnReturned.countDown();
            try {
                if (!releaseCarrier.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test carrier release timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test carrier interrupted", interrupted);
            }
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8),
                turnExecutor)) {
            AtomicReference<Integer> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread caller = new Thread(() -> {
                try {
                    result.set(runtime.invoke(
                            ActorRuntime.ActorKind.PRIVATE,
                            41,
                            (value, context) -> value + 1));
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            caller.start();

            assertTrue(guestTurnReturned.await(2, TimeUnit.SECONDS));
            Thread.sleep(50);
            assertTrue(
                    caller.isAlive(),
                    "synchronous invoke must not return while its actor carrier is still inside the host turn executor");

            releaseCarrier.countDown();
            caller.join(1000);

            assertFalse(caller.isAlive());
            assertNull(failure.get());
            assertEquals(42, result.get());
        }
    }

    @Test
    void closeWaitsForCarrierToLeaveTurnExecutorAfterActorFinalizes() throws Exception {
        CountDownLatch turnExecutorAfterGuestTurn = new CountDownLatch(1);
        CountDownLatch releaseCarrier = new CountDownLatch(1);

        ActorRuntime.TurnExecutor turnExecutor = turn -> {
            turn.run();
            turnExecutorAfterGuestTurn.countDown();

            boolean released = false;
            while (!released) {
                try {
                    released = releaseCarrier.await(25, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // shutdownNow() interrupts the carrier. Keep the wrapper
                    // entered until the test explicitly releases it.
                }
            }
        };

        ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8),
                turnExecutor);
        CountDownLatch handled = new CountDownLatch(1);
        var ref = runtime.<String>spawnShared(() -> (message, context) -> handled.countDown());

        ref.send("ping");
        assertTrue(handled.await(2, TimeUnit.SECONDS));
        assertTrue(turnExecutorAfterGuestTurn.await(2, TimeUnit.SECONDS));

        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try {
                runtime.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        });
        closer.start();

        Thread.sleep(50);
        assertTrue(
                closer.isAlive(),
                "close must wait until the carrier exits the host turn executor/Truffle boundary");

        releaseCarrier.countDown();
        closer.join(1000);

        assertFalse(closer.isAlive());
        assertNull(closeFailure.get());
        assertThrows(IllegalStateException.class, () -> ref.send("after-close"));
    }

    @Test
    void closeStopsActorEvenWhenBehaviorClearsInterruptBeforeReturning() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cleared = new CountDownLatch(1);

        var ref = runtime.<String>spawn(() -> (message, context) -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException interrupted) {
                Thread.interrupted();
                cleared.countDown();
            }
        });

        ref.send("block");
        assertTrue(started.await(2, TimeUnit.SECONDS));

        assertDoesNotThrow(runtime::close);
        assertTrue(cleared.await(1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> ref.send("after-close"));
    }

    @Test
    void closeCanBeRetriedAfterInitialTerminationTimeout() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        var ref = runtime.<String>spawn(() -> (message, context) -> {
            started.countDown();
            while (true) {
                try {
                    release.await();
                    return;
                } catch (InterruptedException ignored) {
                    // Deliberately ignore the first shutdown interrupt.
                }
            }
        });

        ref.send("block");
        assertTrue(started.await(2, TimeUnit.SECONDS));

        IllegalStateException timedOut = assertThrows(IllegalStateException.class, runtime::close);
        assertTrue(timedOut.getMessage().contains("did not observe full actor termination"));

        release.countDown();
        assertDoesNotThrow(runtime::close);
        assertThrows(IllegalStateException.class, () -> ref.send("after-close"));
    }

    @Test
    void privateActorCannotCreateReadonlySharedMemory() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                try {
                    context.runtime().shareReadonly(List.of("private"));
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    checked.countDown();
                }
            });

            ref.send("check");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertNotNull(observed.get());
            assertTrue(observed.get().getMessage().contains("private actors cannot access synchronized shared memory")
                    || observed.get().getMessage().contains("shareReadonly")
                    || observed.get() instanceof SecurityException);
        }
    }

    @Test
    void privateActorCannotDereferenceCapturedReadonlySharedHandle() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.Shared<List<String>> shared = runtime.shareReadonly(List.of("shared"));
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                try {
                    shared.value();
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    checked.countDown();
                }
            });

            ref.send("check");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, observed.get());
            assertTrue(observed.get().getMessage().contains("private actors cannot access"));
        }
    }



    @Test
    void fullMailboxRejectsBeforeTraversingAnotherMessage() throws Exception {
        IsolatePolicy oneQueuedMessage = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                IsolatePolicy.developer().maxHeapBytes(),
                1,
                IsolatePolicy.developer().maxWallTime(),
                false);

        try (ActorRuntime runtime = new ActorRuntime(oneQueuedMessage)) {
            CountDownLatch processing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            var ref = runtime.<Object>spawn(oneQueuedMessage, () -> (message, context) -> {
                if ("first".equals(message)) {
                    processing.countDown();
                    release.await();
                }
            });

            ref.send("first");
            assertTrue(processing.await(2, TimeUnit.SECONDS));
            ref.send("queued");

            AtomicBoolean traversed = new AtomicBoolean();
            List<Object> shouldNotTraverse = new AbstractList<>() {
                @Override
                public Object get(int index) {
                    traversed.set(true);
                    throw new AssertionError("message graph must not be traversed after mailbox admission fails");
                }

                @Override
                public int size() {
                    return 1;
                }
            };

            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> ref.send(shouldNotTraverse));
            assertTrue(error.getMessage().contains("mailbox limit exceeded"));
            assertFalse(traversed.get());

            release.countDown();
        }
    }

    @Test
    void concurrentSendersReserveMailboxBeforeTransportTraversal() throws Exception {
        IsolatePolicy oneQueuedMessage = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                IsolatePolicy.developer().maxHeapBytes(),
                1,
                IsolatePolicy.developer().maxWallTime(),
                false);

        try (ActorRuntime runtime = new ActorRuntime(oneQueuedMessage)) {
            CountDownLatch processing = new CountDownLatch(1);
            CountDownLatch releaseActor = new CountDownLatch(1);
            CountDownLatch firstTraversalEntered = new CountDownLatch(1);
            CountDownLatch releaseFirstTraversal = new CountDownLatch(1);
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();
            AtomicBoolean secondTraversalRan = new AtomicBoolean();

            var ref = runtime.<Object>spawn(oneQueuedMessage, () -> (message, context) -> {
                if ("processing".equals(message)) {
                    processing.countDown();
                    releaseActor.await();
                }
            });

            ref.send("processing");
            assertTrue(processing.await(2, TimeUnit.SECONDS));

            List<Object> first = new AbstractList<>() {
                private final AtomicBoolean blocked = new AtomicBoolean();

                @Override
                public Object get(int index) {
                    if (index != 0) throw new IndexOutOfBoundsException(index);
                    if (blocked.compareAndSet(false, true)) {
                        firstTraversalEntered.countDown();
                        try {
                            if (!releaseFirstTraversal.await(2, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("timed out waiting to finish first traversal");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new java.util.concurrent.CancellationException();
                        }
                    }
                    return "first-queued";
                }

                @Override
                public int size() {
                    return 1;
                }
            };

            Thread sender = Thread.ofPlatform().start(() -> {
                try {
                    ref.send(first);
                } catch (Throwable failure) {
                    firstFailure.set(failure);
                }
            });

            assertTrue(firstTraversalEntered.await(2, TimeUnit.SECONDS));

            List<Object> second = new AbstractList<>() {
                @Override
                public Object get(int index) {
                    secondTraversalRan.set(true);
                    return "second-queued";
                }

                @Override
                public int size() {
                    return 1;
                }
            };

            IllegalStateException rejected = assertThrows(
                    IllegalStateException.class,
                    () -> ref.send(second));
            assertTrue(rejected.getMessage().contains("mailbox limit exceeded"));
            assertFalse(secondTraversalRan.get());

            releaseFirstTraversal.countDown();
            sender.join();
            assertNull(firstFailure.get());

            releaseActor.countDown();
        }
    }



    @Test
    void sharedActorBehaviorFactoryMustBeCaptureFree() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            List<String> captured = new ArrayList<>();

            SecurityException failure = assertThrows(
                    SecurityException.class,
                    () -> runtime.<String>spawnShared(factoryContext -> {
                        captured.add("captured");
                        return (message, context) -> { };
                    }));

            assertTrue(failure.getMessage().contains("stateless")
                    || failure.getMessage().contains("captured host state"));
            assertTrue(captured.isEmpty(), "rejected factory must never execute");
        }
    }

    @Test
    void trustedSharedFactoryIsSupervisorOnlyAndNonAdversarial() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            List<String> captured = new ArrayList<>();
            CountDownLatch delivered = new CountDownLatch(1);

            var ref = runtime.<String>spawnSharedTrusted(factoryContext -> {
                captured.add("factory");
                return (message, context) -> {
                    captured.add(message);
                    delivered.countDown();
                };
            });

            ref.send("message");
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("factory", "message"), captured);
        }

        IsolatePolicy adversarialShared = IsolatePolicy.strictFaas()
                .withCapabilities(IsolatePolicy.Capability.SHARED_MEMORY);
        try (ActorRuntime runtime = new ActorRuntime(adversarialShared)) {
            SecurityException failure = assertThrows(
                    SecurityException.class,
                    () -> runtime.<String>spawnSharedTrusted(
                            adversarialShared,
                            factoryContext -> (message, context) -> { }));
            assertTrue(failure.getMessage().contains("adversarial"));
        }
    }


}
