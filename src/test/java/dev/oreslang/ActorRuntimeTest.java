package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

final class ActorRuntimeTest {
    @Test
    void asynchronousTurnExecutorCannotEscapeActorExecutionLease() throws Exception {
        ExecutorService async = Executors.newSingleThreadExecutor();
        try {
            ActorRuntime.TurnExecutor invalidExecutor = turn -> async.execute(turn);
            CountDownLatch guestRan = new CountDownLatch(1);

            try (ActorRuntime runtime = new ActorRuntime(
                    IsolatePolicy.developer(),
                    new ActorRuntime.DispatcherConfig(2, 2, 1, 8, 1024),
                    invalidExecutor)) {
                var actor = runtime.<String>spawnShared(() ->
                        (message, context) -> guestRan.countDown());

                actor.send("go");

                assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
                assertFalse(
                        guestRan.await(100, TimeUnit.MILLISECONDS),
                        "an asynchronous/thread-hopping TurnExecutor must never run actor code outside the lease");

                Throwable failure = actor.failure().orElseThrow();
                assertTrue(
                        failure.getMessage().contains("TurnExecutor")
                                || failure.getMessage().contains("synchronously"),
                        failure.toString());
            }
        } finally {
            async.shutdownNow();
            assertTrue(async.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void typedSourceProtocolDispatchUsesOneMailboxAndRuntimeOwnedReplyFutures() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.spawnSourceSharedProtocolActor(context -> {
                AtomicInteger value = new AtomicInteger();
                return (method, arguments, turnContext) -> switch (method) {
                    case "add" -> {
                        value.addAndGet(((Number) arguments.getFirst()).intValue());
                        yield null;
                    }
                    case "read" -> value.get();
                    default -> throw new IllegalArgumentException("unknown protocol method " + method);
                };
            });

            assertNull(runtime.invokeSourceProtocol(ref, "add", List.of(40))
                    .get(2, TimeUnit.SECONDS));
            assertNull(runtime.invokeSourceProtocol(ref, "add", List.of(2))
                    .get(2, TimeUnit.SECONDS));
            assertEquals(42, runtime.invokeSourceProtocol(ref, "read", List.of())
                    .get(2, TimeUnit.SECONDS));
            assertTrue(ref.isAlive());
        }
    }

    @Test
    void suspendedTypedProtocolCompletesReplyOnlyAfterContinuationReturn() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CompletableFuture<Integer> gate = new CompletableFuture<>();
            CountDownLatch suspended = new CountDownLatch(1);

            var ref = runtime.spawnSourceSharedProtocolActor(factoryContext ->
                    (method, arguments, turnContext) -> {
                        if (!method.equals("wait")) {
                            throw new IllegalArgumentException("unexpected method " + method);
                        }
                        suspended.countDown();
                        turnContext.suspendOn(gate, (value, failure, resumeContext) -> {
                            assertNull(failure);
                            resumeContext.completeProtocolReply(value);
                        });
                        throw new AssertionError("suspendOn must unwind the current actor turn");
                    });

            var reply = runtime.invokeSourceProtocol(ref, "wait", List.of());
            assertTrue(suspended.await(2, TimeUnit.SECONDS));
            assertFalse(reply.isDone());

            gate.complete(42);

            assertEquals(42, reply.get(2, TimeUnit.SECONDS));
            assertTrue(ref.isAlive());
        }
    }

    @Test
    void suspendedTypedProtocolPropagatesContinuationFailureToReply() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CompletableFuture<Integer> gate = new CompletableFuture<>();
            CountDownLatch suspended = new CountDownLatch(1);

            var ref = runtime.spawnSourceSharedProtocolActor(factoryContext ->
                    (method, arguments, turnContext) -> {
                        suspended.countDown();
                        turnContext.suspendOn(gate, (value, failure, resumeContext) -> {
                            throw new IllegalStateException("protocol-resume-boom");
                        });
                        throw new AssertionError("suspendOn must unwind the current actor turn");
                    });

            var reply = runtime.invokeSourceProtocol(ref, "wait", List.of());
            assertTrue(suspended.await(2, TimeUnit.SECONDS));
            gate.complete(1);

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> reply.get(2, TimeUnit.SECONDS));
            assertEquals("protocol-resume-boom", failure.getCause().getMessage());

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (ref.isAlive() && System.nanoTime() < deadline) Thread.sleep(2);
            assertFalse(ref.isAlive(), "unhandled protocol continuation failure is fail-stop");
        }
    }

    @Test
    void cancellingProtocolReplyDoesNotCancelOrRewindActorTurn() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CompletableFuture<Integer> gate = new CompletableFuture<>();
            CountDownLatch suspended = new CountDownLatch(1);
            CountDownLatch resumed = new CountDownLatch(1);

            var ref = runtime.spawnSourceSharedProtocolActor(factoryContext ->
                    (method, arguments, turnContext) -> {
                        suspended.countDown();
                        turnContext.suspendOn(gate, (value, failure, resumeContext) -> {
                            resumed.countDown();
                            resumeContext.completeProtocolReply(value);
                        });
                        throw new AssertionError("suspendOn must unwind the current actor turn");
                    });

            var reply = runtime.invokeSourceProtocol(ref, "wait", List.of());
            assertTrue(suspended.await(2, TimeUnit.SECONDS));
            assertTrue(reply.cancel(true));

            gate.complete(7);

            assertTrue(resumed.await(2, TimeUnit.SECONDS));
            assertTrue(reply.isCancelled());
            assertTrue(ref.isAlive(),
                    "cancelling an observation Future must not kill the actor or rewind its turn");
        }
    }

    @Test
    void cancellingQueuedProtocolRequestSkipsGuestDispatch() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CompletableFuture<Integer> gate = new CompletableFuture<>();
            CountDownLatch firstSuspended = new CountDownLatch(1);
            AtomicInteger skippedDispatches = new AtomicInteger();

            var ref = runtime.spawnSourceSharedProtocolActor(factoryContext ->
                    (method, arguments, turnContext) -> {
                        if (method.equals("block")) {
                            firstSuspended.countDown();
                            turnContext.suspendOn(gate, (value, failure, resumeContext) ->
                                    resumeContext.completeProtocolReply(value));
                            throw new AssertionError("suspendOn must unwind the current actor turn");
                        }
                        if (method.equals("should_not_run")) {
                            skippedDispatches.incrementAndGet();
                            return 99;
                        }
                        throw new IllegalArgumentException("unexpected protocol method " + method);
                    });

            var blocker = runtime.invokeSourceProtocol(ref, "block", List.of());
            assertTrue(firstSuspended.await(2, TimeUnit.SECONDS));

            var cancelled = runtime.invokeSourceProtocol(ref, "should_not_run", List.of());
            assertTrue(cancelled.cancel(true));

            gate.complete(1);
            assertEquals(1, blocker.get(2, TimeUnit.SECONDS));

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
            while (skippedDispatches.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertEquals(0, skippedDispatches.get(),
                    "a protocol request cancelled before dispatch must not execute guest code");
            assertTrue(ref.isAlive());
        }
    }

    @Test
    void stoppingSuspendedProtocolActorCancelsPendingReplyAndDropsLateResume() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CompletableFuture<Integer> gate = new CompletableFuture<>();
            CountDownLatch suspended = new CountDownLatch(1);
            AtomicInteger resumed = new AtomicInteger();

            var ref = runtime.spawnSourceSharedProtocolActor(factoryContext ->
                    (method, arguments, turnContext) -> {
                        suspended.countDown();
                        turnContext.suspendOn(gate, (value, failure, resumeContext) -> {
                            resumed.incrementAndGet();
                            resumeContext.completeProtocolReply(value);
                        });
                        throw new AssertionError("suspendOn must unwind the current actor turn");
                    });

            var reply = runtime.invokeSourceProtocol(ref, "wait", List.of());
            assertTrue(suspended.await(2, TimeUnit.SECONDS));

            ref.stop();

            assertTrue(reply.isDone());
            assertTrue(reply.isCancelled(),
                    "stopping an actor with an in-flight protocol request must settle the reply");
            assertFalse(ref.isAlive());

            gate.complete(7);
            Thread.sleep(50);
            assertEquals(0, resumed.get(),
                    "a late producer completion must not resurrect a stopped actor turn");
        }
    }

    @Test
    void protocolInvocationRejectsForeignRuntimeActorRefs() {
        try (ActorRuntime owner = new ActorRuntime();
             ActorRuntime foreign = new ActorRuntime()) {
            var ref = owner.spawnSourceSharedProtocolActor(factoryContext ->
                    (method, arguments, turnContext) -> 1);

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> foreign.invokeSourceProtocol(ref, "read", List.of()));

            assertTrue(failure.getMessage().contains("different ActorRuntime"));
            assertTrue(ref.isAlive());
        }
    }

    @Test
    void rawMessagesFailClosedAgainstTypedSourceProtocolDispatcher() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.spawnSourceSharedProtocolActor(factoryContext ->
                    (method, arguments, turnContext) -> 1);

            ref.send("raw-message");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (ref.isAlive() && System.nanoTime() < deadline) Thread.sleep(2);

            assertFalse(ref.isAlive());
            assertTrue(ref.failure().orElseThrow().getMessage()
                    .contains("raw mailbox messages"));
        }
    }

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
    void boundedMailboxBatchLetsPeerActorRunBeforeHotMailboxDrains() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(
                1,
                1,
                1,
                2,
                Long.MAX_VALUE,
                1024);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CountDownLatch firstHotEntered = new CountDownLatch(1);
            CountDownLatch releaseFirstHot = new CountDownLatch(1);
            CountDownLatch peerRan = new CountDownLatch(1);
            AtomicInteger hotProcessed = new AtomicInteger();
            AtomicInteger hotCountWhenPeerRan = new AtomicInteger(Integer.MAX_VALUE);

            var hot = runtime.<Integer>spawnPrivate(() -> (message, context) -> {
                if (message == 0) {
                    firstHotEntered.countDown();
                    assertTrue(releaseFirstHot.await(2, TimeUnit.SECONDS));
                }
                hotProcessed.incrementAndGet();
            });
            var peer = runtime.<String>spawnPrivate(() -> (message, context) -> {
                hotCountWhenPeerRan.set(hotProcessed.get());
                peerRan.countDown();
            });

            for (int i = 0; i < 10; i++) hot.send(i);
            assertTrue(firstHotEntered.await(2, TimeUnit.SECONDS));
            peer.send("peer");
            releaseFirstHot.countDown();

            assertTrue(peerRan.await(2, TimeUnit.SECONDS));
            assertTrue(
                    hotCountWhenPeerRan.get() <= config.throughput(),
                    "hot actor must return to the FIFO dispatcher queue after one bounded message batch");
        }
    }

    @Test
    void wallClockBatchQuantumLetsPeerRunEvenWithLargeMessageThroughput() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(
                1,
                1,
                1,
                64,
                1L,
                1024);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CountDownLatch firstHotEntered = new CountDownLatch(1);
            CountDownLatch releaseFirstHot = new CountDownLatch(1);
            CountDownLatch peerRan = new CountDownLatch(1);
            AtomicInteger hotProcessed = new AtomicInteger();
            AtomicInteger hotCountWhenPeerRan = new AtomicInteger(Integer.MAX_VALUE);

            var hot = runtime.<Integer>spawnPrivate(() -> (message, context) -> {
                if (message == 0) {
                    firstHotEntered.countDown();
                    assertTrue(releaseFirstHot.await(2, TimeUnit.SECONDS));
                }
                hotProcessed.incrementAndGet();
            });
            var peer = runtime.<String>spawnPrivate(() -> (message, context) -> {
                hotCountWhenPeerRan.set(hotProcessed.get());
                peerRan.countDown();
            });

            for (int i = 0; i < 10; i++) hot.send(i);
            assertTrue(firstHotEntered.await(2, TimeUnit.SECONDS));
            peer.send("peer");
            releaseFirstHot.countDown();

            assertTrue(peerRan.await(2, TimeUnit.SECONDS));
            assertEquals(
                    1,
                    hotCountWhenPeerRan.get(),
                    "elapsed batch quantum must requeue a hot actor before its next mailbox message");
        }
    }

    @Test
    void untrustedDispatcherAlwaysUsesSingleMessageTurns() {
        var config = new ActorRuntime.DispatcherConfig(4, 4, 2, 64, 1024);
        assertEquals(64, config.throughputFor(ActorRuntime.ActorKind.PRIVATE));
        assertEquals(64, config.throughputFor(ActorRuntime.ActorKind.SHARED));
        assertEquals(1, config.throughputFor(ActorRuntime.ActorKind.UNTRUSTED));
    }

    @Test
    void sharedActorsCannotMutateExternalSyncCell() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 4, 32);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            ActorRuntime.SyncCell<Integer> cell = runtime.syncCell(0);
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();

            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    cell.update(value -> value + 1);
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    checked.countDown();
                    context.self().stop();
                }
            });

            actor.send("attempt");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, observed.get());
            assertEquals(0, cell.snapshot());
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
    @Test
    void privateActorsRejectSharedMutableCells() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.SyncCell<Integer> cell = runtime.syncCell(0);
            var ref = runtime.<Object>spawnPrivate(() -> (message, context) -> { });
            assertThrows(SecurityException.class, () -> ref.send(cell));
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
            assertTrue(failure.getMessage().contains("private actor inbox limit exceeded"));
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
    void syncCellMutationIsHostRootOnly() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var cell = runtime.syncCell(1);
            assertEquals(2, cell.update(value -> value + 1));
            assertEquals(2, cell.snapshot());
        }
    }
    @Test
    void privateActorRejectsNestedSharedMutableCells() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var cell = runtime.syncCell(0);
            var ref = runtime.<Object>spawnPrivate(() -> (message, context) -> { });
            assertThrows(SecurityException.class,
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
            assertInstanceOf(SecurityException.class, observed.get());
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
    void nestedDifferentHostSyncCellsAreRejectedInsteadOfRiskingLockOrderDeadlock() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var first = runtime.syncCell(1);
            var second = runtime.syncCell(2);
            AtomicReference<Throwable> observed = new AtomicReference<>();

            first.update(value -> {
                try {
                    second.snapshot();
                } catch (Throwable failure) {
                    observed.set(failure);
                }
                return value;
            });

            assertInstanceOf(IllegalStateException.class, observed.get());
            assertTrue(observed.get().getMessage().contains("nested synchronization"));
        }
    }
    @Test
    void actorCannotHoldSyncCellLockDuringRuntimeClose() throws Exception {
        ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8));
        var cell = runtime.syncCell(1);
        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<Throwable> observed = new AtomicReference<>();

        var ref = runtime.<String>spawnShared(() -> (message, context) -> {
            try {
                cell.update(value -> value + 1);
            } catch (Throwable failure) {
                observed.set(failure);
            } finally {
                checked.countDown();
                context.self().stop();
            }
        });

        ref.send("attempt");
        assertTrue(checked.await(2, TimeUnit.SECONDS));
        assertInstanceOf(SecurityException.class, observed.get());
        assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));

        assertDoesNotThrow(runtime::close);
        assertTrue(cell.closed());
        assertEquals(0L, runtime.sharedMemoryBytes());
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

            SecurityException cellFailure = assertThrows(
                    SecurityException.class,
                    () -> ref.send(foreignCell));
            assertTrue(cellFailure.getMessage().contains("SyncCell"));
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
    void actorContextDoesNotExposeRawRuntimeAuthority() {
        for (var method : ActorRuntime.ActorContext.class.getMethods()) {
            assertNotEquals(
                    ActorRuntime.class,
                    method.getReturnType(),
                    "actor context must not expose the owning runtime: " + method);
            assertNotEquals("runtime", method.getName());
            assertNotEquals("close", method.getName());
            assertFalse(
                    method.getName().contains("Trusted"),
                    "actor context must not expose trusted host construction: " + method);
        }
    }

    @Test
    void strictActorCannotBypassReadonlyCapabilityThroughRuntimeHandle() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IsolatePolicy strict = IsolatePolicy.strictFaas();
            var ref = runtime.<String>spawnPrivate(
                    strict,
                    factoryContext -> (message, context) ->
                            context.shareReadonly(List.of("secret")));

            ref.send("check");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));

            assertTrue(ref.failure().isPresent());
            assertInstanceOf(SecurityException.class, ref.failure().orElseThrow());
        }
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
                    context.shareReadonly(List.of("private"));
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
                    throw new AssertionError("message graph must not be traversed after inbox admission fails");
                }

                @Override
                public int size() {
                    return 1;
                }
            };

            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> ref.send(shouldNotTraverse));
            assertTrue(error.getMessage().contains("inbox limit exceeded"));
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
            assertTrue(rejected.getMessage().contains("inbox limit exceeded"));
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

    @Test
    void productionDefaultsUseThreeElasticFiveToTwentyCarrierPools() {
        var config = ActorRuntime.DispatcherConfig.defaultsForProcessors(16);

        assertEquals(5, config.privateParallelism());
        assertEquals(5, config.sharedParallelism());
        assertEquals(5, config.untrustedParallelism());
        assertEquals(20, config.maxParallelismFor(ActorRuntime.ActorKind.PRIVATE));
        assertEquals(20, config.maxParallelismFor(ActorRuntime.ActorKind.SHARED));
        assertEquals(20, config.maxParallelismFor(ActorRuntime.ActorKind.UNTRUSTED));
        assertTrue(config.maxCompensatingThreads() > 0);
        assertTrue(config.maxMessageNanos() >= config.maxBatchNanos());
    }

    @Test
    void everyActorKindHasAtMostOneActiveCarrier() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            int messages = 32;

            AtomicInteger privateActive = new AtomicInteger();
            AtomicInteger privateMax = new AtomicInteger();
            CountDownLatch privateDone = new CountDownLatch(messages);
            var privateRef = runtime.<Integer>spawnPrivateTrusted(ignored -> (message, turn) -> {
                int now = privateActive.incrementAndGet();
                privateMax.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(1);
                } finally {
                    privateActive.decrementAndGet();
                    privateDone.countDown();
                }
            });

            AtomicInteger sharedActive = new AtomicInteger();
            AtomicInteger sharedMax = new AtomicInteger();
            CountDownLatch sharedDone = new CountDownLatch(messages);
            var sharedRef = runtime.<Integer>spawnSharedTrusted(ignored -> (message, turn) -> {
                int now = sharedActive.incrementAndGet();
                sharedMax.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(1);
                } finally {
                    sharedActive.decrementAndGet();
                    sharedDone.countDown();
                }
            });

            AtomicInteger untrustedActive = new AtomicInteger();
            AtomicInteger untrustedMax = new AtomicInteger();
            CountDownLatch untrustedDone = new CountDownLatch(messages);
            ActorRuntime.HttpResponseTransport responseTransport = source -> {
                int now = untrustedActive.incrementAndGet();
                untrustedMax.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(1);
                    int remaining = source.remaining();
                    source.position(source.limit());
                    return remaining;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("interrupted", interrupted);
                } finally {
                    untrustedActive.decrementAndGet();
                    untrustedDone.countDown();
                }
            };
            var untrustedRef = runtime.<Integer>spawnUntrusted(
                    IsolatePolicy.untrustedActor(),
                    ActorRuntime.UntrustedActorLimits.defaults(),
                    responseTransport,
                    ignored -> (message, turn) ->
                            turn.httpResponse().orElseThrow().write(
                                    java.nio.ByteBuffer.wrap(new byte[] { 1 })));

            for (int i = 0; i < messages; i++) {
                privateRef.send(i);
                sharedRef.send(i);
                untrustedRef.send(i);
            }

            assertTrue(privateDone.await(5, TimeUnit.SECONDS));
            assertTrue(sharedDone.await(5, TimeUnit.SECONDS));
            assertTrue(untrustedDone.await(5, TimeUnit.SECONDS));
            assertEquals(1, privateMax.get());
            assertEquals(1, sharedMax.get());
            assertEquals(1, untrustedMax.get());

            privateRef.stop();
            sharedRef.stop();
            untrustedRef.stop();
        }
    }

    @Test
    void rootProcessExecutesOnControlPlaneCarrierPool() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            String threadName = runtime.executeRootTask(
                    () -> Thread.currentThread().getName());
            assertTrue(
                    threadName.startsWith("ores-control-plane-dispatcher-"),
                    threadName);
        }
    }

    @Test
    void stuckTrustedActorGetsBoundedCompensationSoPeerStillRuns() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(
                1,
                1,
                1,
                64,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.MILLISECONDS.toNanos(40),
                1,
                64);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CountDownLatch hogEntered = new CountDownLatch(1);
            CountDownLatch releaseHog = new CountDownLatch(1);
            CountDownLatch peerRan = new CountDownLatch(1);

            var hog = runtime.<String>spawnPrivateTrusted(
                    IsolatePolicy.developer(),
                    ignored -> (message, turn) -> {
                        hogEntered.countDown();
                        while (releaseHog.getCount() != 0) {
                            // Deliberately no Oreslang/runtime checkpoint and no
                            // interrupt handling: model an uncooperative host stack.
                            Thread.onSpinWait();
                        }
                    });
            var peer = runtime.<String>spawnPrivateTrusted(
                    IsolatePolicy.developer(),
                    ignored -> (message, turn) -> peerRan.countDown());

            hog.send("hog");
            assertTrue(hogEntered.await(2, TimeUnit.SECONDS));
            peer.send("peer");

            assertTrue(
                    peerRan.await(2, TimeUnit.SECONDS),
                    "a stuck actor must not permanently consume the domain's only base carrier");
            assertTrue(runtime.dispatcherStats(ActorRuntime.ActorKind.PRIVATE).overrunTurns() >= 1);
            assertTrue(runtime.dispatcherStats(ActorRuntime.ActorKind.PRIVATE).largestPoolSize() >= 2);
            assertInstanceOf(
                    ActorRuntime.ActorTurnExceededException.class,
                    hog.failure().orElseThrow());

            releaseHog.countDown();
            assertTrue(hog.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void entryWaitIsWatchdogBoundedBeforeTurnExecutorRunsGuestCode() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(
                1,
                1,
                1,
                64,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.MILLISECONDS.toNanos(40),
                1,
                64);

        ReentrantLock contextGate = new ReentrantLock(true);
        contextGate.lock();
        ActorRuntime.TurnExecutor interruptibleEntry = turn -> {
            boolean locked = false;
            try {
                contextGate.lockInterruptibly();
                locked = true;
                turn.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException(
                        "interrupted before actor context entry");
            } finally {
                if (locked) contextGate.unlock();
            }
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                config,
                interruptibleEntry)) {
            var blocked = runtime.<String>spawnPrivateTrusted(
                    IsolatePolicy.developer(),
                    ignored -> (message, turn) -> fail(
                            "guest callback must not run while context entry is blocked"));

            blocked.send("blocked");

            assertTrue(
                    blocked.awaitTermination(2, TimeUnit.SECONDS),
                    "context-entry wait must be covered by the actor turn watchdog");
            assertInstanceOf(
                    ActorRuntime.ActorTurnExceededException.class,
                    blocked.failure().orElseThrow());
            assertTrue(
                    runtime.dispatcherStats(ActorRuntime.ActorKind.PRIVATE).overrunTurns() >= 1);
        } finally {
            contextGate.unlock();
        }
    }

    @Test
    void hostileUntrustedActorsCannotConsumeEveryActorIdentity() {
        var config = new ActorRuntime.DispatcherConfig(
                2,
                2,
                1,
                64,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.MILLISECONDS.toNanos(250),
                1,
                16);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            int limit = config.maxUntrustedActors();
            List<ActorRuntime.ActorRef<String>> sandboxes = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                sandboxes.add(runtime.<String>spawnUntrusted(
                        ignored -> (message, turn) -> { }));
            }

            IllegalStateException rejected = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.<String>spawnUntrusted(
                            ignored -> (message, turn) -> { }));
            assertTrue(rejected.getMessage().contains("untrusted actor limit"));

            // Trusted actors still retain admission capacity under sandbox load.
            assertDoesNotThrow(() ->
                    runtime.<String>spawnPrivate(
                            ignored -> (message, turn) -> { }));
        }
    }

    @Test
    void processSharedRuntimesReuseCarriersAndCloseIndependently() {
        ActorRuntime first = ActorRuntime.processShared(
                IsolatePolicy.developer(),
                ActorRuntime.TurnExecutor.direct());
        ActorRuntime second = ActorRuntime.processShared(
                IsolatePolicy.developer(),
                ActorRuntime.TurnExecutor.direct());
        try {
            assertTrue(first.usesProcessSharedDispatchers());
            assertTrue(second.usesProcessSharedDispatchers());

            String firstThread = first.executeRootTask(
                    () -> Thread.currentThread().getName());
            assertTrue(firstThread.startsWith("ores-process-control-plane-dispatcher-"),
                    firstThread);

            first.close();

            String secondThread = second.executeRootTask(
                    () -> Thread.currentThread().getName());
            assertTrue(secondThread.startsWith("ores-process-control-plane-dispatcher-"),
                    secondThread);
        } finally {
            try {
                first.close();
            } catch (IllegalStateException ignored) {
                // A prior close already completed or reported its own teardown issue.
            }
            second.close();
        }
    }

    @Test
    void cooperativeRunawayRootIsWallTimeBoundedAndCompensated() {
        var base = IsolatePolicy.developer();
        var shortRootPolicy = new IsolatePolicy(
                base.capabilities(),
                base.maxHeapBytes(),
                base.maxMailboxMessages(),
                Duration.ofMillis(40),
                false);
        var config = new ActorRuntime.DispatcherConfig(
                1,
                2,
                1,
                64,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.MILLISECONDS.toNanos(250),
                1,
                64);

        try (ActorRuntime runtime = new ActorRuntime(shortRootPolicy, config)) {
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> runtime.executeRootTask(() -> {
                        while (true) runtime.schedulerSafepoint();
                    }));

            assertTrue(
                    runtime.controlDispatcherStats().overrunTurns() >= 1,
                    "root wall-time expiration must be observable on the control-plane dispatcher");
            assertEquals(
                    0,
                    runtime.dispatcherStats(ActorRuntime.ActorKind.SHARED).overrunTurns(),
                    "root wall-time expiration must not contaminate shared-actor metrics");
        }
    }

    @Test
    void rootTaskCannotStarveSharedActorsOfEveryBaseCarrier() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(
                1,
                2,
                1,
                64,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.SECONDS.toNanos(5),
                1,
                64);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CountDownLatch rootEntered = new CountDownLatch(1);
            CountDownLatch releaseRoot = new CountDownLatch(1);
            CountDownLatch actorRan = new CountDownLatch(1);
            AtomicReference<Throwable> rootFailure = new AtomicReference<>();

            Thread rootCaller = new Thread(() -> {
                try {
                    runtime.executeRootTask(() -> {
                        rootEntered.countDown();
                        try {
                            releaseRoot.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(interrupted);
                        }
                        return null;
                    });
                } catch (Throwable failure) {
                    rootFailure.set(failure);
                }
            }, "root-caller-test");
            rootCaller.start();

            assertTrue(rootEntered.await(2, TimeUnit.SECONDS));

            var shared = runtime.<String>spawnSharedTrusted(
                    IsolatePolicy.developer(),
                    ignored -> (message, turn) -> actorRan.countDown());
            shared.send("ping");

            assertTrue(
                    actorRan.await(2, TimeUnit.SECONDS),
                    "a control-plane root/main task must not consume shared-actor carriers");

            releaseRoot.countDown();
            rootCaller.join(2_000);
            assertFalse(rootCaller.isAlive());
            assertNull(rootFailure.get());
        }
    }

    @Test
    void adversarialRootCannotSynchronouslyWaitOnSerializedActorTurn() {
        try (ActorRuntime runtime = ActorRuntime.processShared(
                IsolatePolicy.strictFaas(),
                ActorRuntime.TurnExecutor.direct())) {
            SecurityException denied = assertThrows(
                    SecurityException.class,
                    () -> runtime.executeRootTask(() -> runtime.invoke(
                            ActorRuntime.ActorKind.PRIVATE,
                            "ping",
                            (message, turn) -> message)));
            assertTrue(denied.getMessage().contains("cannot synchronously invoke"));
        }
    }

    @Test
    void processSharedMemoryAccountingSpansContextsAndReleasesOnClose() {
        ActorRuntime first = ActorRuntime.processShared(
                IsolatePolicy.developer(),
                ActorRuntime.TurnExecutor.direct());
        ActorRuntime second = ActorRuntime.processShared(
                IsolatePolicy.developer(),
                ActorRuntime.TurnExecutor.direct());
        ActorRuntime.SyncCell<List<Integer>> cell = null;
        try {
            long baseline = second.processCarrierMemoryBytes();
            cell = first.syncCell(List.of(1, 2, 3, 4));
            long reserved = second.processCarrierMemoryBytes();

            assertTrue(reserved > baseline,
                    "process carrier memory accounting must include other contexts");

            first.close();
            assertTrue(cell.closed());
            assertThrows(IllegalStateException.class, cell::snapshot);
            assertEquals(
                    baseline,
                    second.processCarrierMemoryBytes(),
                    "closing one context must release its process-level reservations");

            assertEquals(
                    "still-alive",
                    second.executeRootTask(() -> "still-alive"),
                    "releasing one context's memory must not shut the shared carriers");
        } finally {
            try {
                first.close();
            } catch (IllegalStateException ignored) {
            }
            second.close();
        }
    }

    @Test
    void uncooperativeActorCannotAcquireSyncCellBeforeCloseTimeout() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(
                1,
                1,
                1,
                64,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.SECONDS.toNanos(5),
                1,
                64);
        ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config);
        AtomicBoolean release = new AtomicBoolean();
        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<Throwable> observed = new AtomicReference<>();

        ActorRuntime.SyncCell<Integer> cell = runtime.syncCell(0);
        var actor = runtime.<String>spawnSharedTrusted(
                IsolatePolicy.developer(),
                ignored -> (message, turn) -> {
                    try {
                        cell.update(value -> value + 1);
                    } catch (Throwable failure) {
                        observed.set(failure);
                    }
                    checked.countDown();
                    while (!release.get()) Thread.onSpinWait();
                });
        actor.send("hold");
        assertTrue(checked.await(2, TimeUnit.SECONDS));
        assertInstanceOf(SecurityException.class, observed.get());

        long started = System.nanoTime();
        IllegalStateException timeout = assertThrows(
                IllegalStateException.class,
                runtime::close);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);

        assertTrue(timeout.getMessage().contains("still running"));
        assertTrue(elapsedMillis < 2_000);

        release.set(true);
        assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
        assertDoesNotThrow(runtime::close);
        assertTrue(cell.closed());
    }
}
