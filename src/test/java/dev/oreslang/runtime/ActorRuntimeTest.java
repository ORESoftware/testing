package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorRuntimeTest {

    @Test
    void processGcDoesNotInvokeJvmWideGcUnlessHostOptsIn() {
        String previous = System.getProperty("oreslang.gc.host-hint");
        System.clearProperty("oreslang.gc.host-hint");
        try (ActorRuntime runtime = new ActorRuntime()) {
            GcController gc = new GcController(runtime);
            var result = gc.collectProcess();
            assertEquals(false, result.get("host_gc_hint_enabled"));
            assertEquals(false, result.get("host_gc_hint_executed"));
        } finally {
            if (previous == null) System.clearProperty("oreslang.gc.host-hint");
            else System.setProperty("oreslang.gc.host-hint", previous);
        }
    }

    @Test
    void actorCountLimitIsAtomicAndSlotsReturnAfterTermination() {
        IsolatePolicy policy = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                16L * 1024L * 1024L,
                32,
                2,
                Duration.ofSeconds(5),
                false);

        try (ActorRuntime runtime = new ActorRuntime(policy)) {
            ActorRuntime.ActorRef<Object> first = runtime.spawn(() -> (message, context) -> { });
            ActorRuntime.ActorRef<Object> second = runtime.spawn(() -> (message, context) -> { });

            assertEquals(2, runtime.activeActorCount());
            IllegalStateException full = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.spawn(() -> (message, context) -> { }));
            assertTrue(full.getMessage().contains("maxActors"));

            assertTrue(runtime.stop(first));
            assertTrue(runtime.join(first, Duration.ofSeconds(2)));
            assertEquals(1, runtime.activeActorCount());

            ActorRuntime.ActorRef<Object> replacement = runtime.spawn(() -> (message, context) -> { });
            assertNotEquals(first.id(), replacement.id());
            assertEquals(2, runtime.activeActorCount());

            runtime.stop(second);
            runtime.stop(replacement);
        }
    }

    @Test
    void actorsOwnBehaviorAndCommunicateThroughFrozenMessages() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<Object> seen = new AtomicReference<>();

            ActorRuntime.ActorRef<Object> actor = runtime.spawn(() -> (message, context) -> {
                seen.set(message);
                received.countDown();
            });

            ArrayList<String> mutable = new ArrayList<>(List.of("hello"));
            runtime.send(actor, mutable);
            mutable.add("mutated-after-send");

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("hello"), seen.get());
            assertNotSame(mutable, seen.get());

            assertTrue(runtime.stop(actor));
            assertTrue(runtime.join(actor, Duration.ofSeconds(2)));
        }
    }

    @Test
    void singletonIsOneNamedActorNotSharedMutableHeapState() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Object> first = runtime.spawnSingleton("cache.primary", () -> (message, context) -> { });
            ActorRuntime.ActorRef<Object> second = runtime.spawnSingleton("cache.primary", () -> (message, context) -> {
                fail("the second factory must not replace a live singleton");
            });

            assertEquals(first.id(), second.id());
            assertTrue(runtime.lookupSingleton("cache.primary").isPresent());
        }
    }

    @Test
    void failedSingletonConstructionDoesNotLeaveAStaleRegistryEntry() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Object> failed = runtime.spawnSingleton(
                    "service.primary",
                    () -> {
                        throw new IllegalStateException("construction failed");
                    });

            assertTrue(runtime.join(failed, Duration.ofSeconds(2)));
            assertTrue(runtime.lookupSingleton("service.primary").isEmpty());

            ActorRuntime.ActorRef<Object> replacement = runtime.spawnSingleton(
                    "service.primary",
                    () -> (message, context) -> { });

            assertNotEquals(failed.id(), replacement.id());
            assertEquals(
                    replacement.id(),
                    runtime.<Object>lookupSingleton("service.primary").orElseThrow().id());
        }
    }

    @Test
    void explicitReadonlySharingAvoidsMutableAliases() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<Object> seen = new AtomicReference<>();
            ActorRuntime.ActorRef<Object> actor = runtime.spawn(() -> (message, context) -> {
                seen.set(message);
                received.countDown();
            });

            ArrayList<String> source = new ArrayList<>(List.of("large", "payload"));
            ActorRuntime.Shared<List<String>> shared = runtime.shareReadonly(source);
            source.set(0, "changed");

            runtime.send(actor, shared);
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertSame(shared.value(), seen.get());
            assertEquals(List.of("large", "payload"), seen.get());
            assertThrows(UnsupportedOperationException.class, () -> shared.value().add("nope"));
        }
    }

    @Test
    void largeReadonlyRegionCanBeSharedWithActorWhosePrivateHeapIsSmaller() throws Exception {
        IsolatePolicy ceiling = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                64L * 1024L * 1024L,
                64,
                Duration.ofSeconds(5));
        IsolatePolicy smallActor = new IsolatePolicy(
                ceiling.capabilities(),
                16L * 1024L * 1024L,
                32,
                Duration.ofSeconds(5));

        try (ActorRuntime runtime = new ActorRuntime(ceiling)) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<Object> seen = new AtomicReference<>();
            ActorRuntime.ActorRef<Object> actor = runtime.spawn(
                    smallActor,
                    () -> (message, context) -> {
                        seen.set(message);
                        received.countDown();
                    });

            String payload = "x".repeat(9_000_000);
            ActorRuntime.Shared<String> shared = runtime.shareReadonly(payload);
            assertTrue(shared.estimatedBytes() > smallActor.maxHeapBytes());

            runtime.send(actor, shared);

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertSame(payload, seen.get());
        }
    }

    @Test
    void cyclicMutableMessagesAreRejectedInsteadOfRecursingForever() {
        ArrayList<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(cyclic));

        assertTrue(error.getMessage().contains("cyclic"));
    }

    @Test
    void inFlightMessageRemainsChargedToActorHeapUntilHandlerReturns() throws Exception {
        IsolatePolicy policy = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                16L * 1024L * 1024L,
                32,
                Duration.ofSeconds(5));

        try (ActorRuntime runtime = new ActorRuntime(policy)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ActorRuntime.ActorRef<Object> actor = runtime.spawnOwned(
                    policy,
                    1024L,
                    () -> (message, context) -> {
                        entered.countDown();
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                    });

            runtime.send(actor, "x".repeat(100_000));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            ActorRuntime.ActorSnapshot snapshot = runtime.snapshot(actor);
            assertEquals(0L, snapshot.mailboxBytes());
            assertTrue(snapshot.activeMessageBytes() > 100_000L);
            assertEquals(1024L, snapshot.ownedStateBytes());

            release.countDown();
            assertTrue(runtime.stop(actor));
            assertTrue(runtime.join(actor, Duration.ofSeconds(2)));
        }
    }

    @Test
    void logicalActorHeapTracksPersistentStateSeparatelyFromMailbox() throws Exception {
        IsolatePolicy policy = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                16L * 1024L * 1024L,
                32,
                Duration.ofSeconds(5));

        try (ActorRuntime runtime = new ActorRuntime(policy)) {
            CountDownLatch updated = new CountDownLatch(1);
            ActorRuntime.ActorRef<Object> actor = runtime.spawnOwned(
                    policy,
                    1024L,
                    () -> (message, context) -> {
                        context.replaceOwnedStateBytes(4096L);
                        updated.countDown();
                    });

            assertEquals(1024L, runtime.snapshot(actor).ownedStateBytes());
            runtime.send(actor, "update");
            assertTrue(updated.await(2, TimeUnit.SECONDS));
            assertEquals(4096L, runtime.snapshot(actor).ownedStateBytes());

            assertTrue(runtime.stop(actor));
            assertTrue(runtime.join(actor, Duration.ofSeconds(2)));
            assertEquals(0L, runtime.snapshot(actor).ownedStateBytes());
        }
    }

    @Test
    void mailboxAndPersistentStateShareTheActorHeapBudget() {
        IsolatePolicy policy = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                16L * 1024L * 1024L,
                32,
                Duration.ofSeconds(5));

        try (ActorRuntime runtime = new ActorRuntime(policy)) {
            ActorRuntime.ActorRef<Object> actor = runtime.spawnOwned(
                    policy,
                    15L * 1024L * 1024L,
                    () -> (message, context) -> { });

            String message = "x".repeat(600_000);
            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.send(actor, message));

            assertTrue(error.getMessage().contains("mailbox memory"));
            assertEquals(15L * 1024L * 1024L, runtime.snapshot(actor).ownedStateBytes());
            assertEquals(0L, runtime.snapshot(actor).mailboxBytes());
        }
    }

    @Test
    void oversizedMessagesAreRejectedBeforeMailboxAdmission() {
        IsolatePolicy policy = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                16L * 1024L * 1024L,
                32,
                Duration.ofSeconds(5));

        try (ActorRuntime runtime = new ActorRuntime(policy)) {
            ActorRuntime.ActorRef<Object> actor = runtime.spawn(() -> (message, context) -> { });
            String oversized = "x".repeat(5_000_000);

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.send(actor, oversized));

            assertTrue(error.getMessage().contains("too large"));
            assertEquals(0, runtime.snapshot(actor).mailboxMessages());
        }
    }

    @Test
    void cyclicMessagesAreRejectedDuringSizePreflight() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Object> actor = runtime.spawn(() -> (message, context) -> { });
            ArrayList<Object> cyclic = new ArrayList<>();
            cyclic.add(cyclic);

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.send(actor, cyclic));

            assertTrue(error.getMessage().contains("cyclic"));
            assertEquals(0, runtime.snapshot(actor).mailboxMessages());
        }
    }

    @Test
    void oversizedObjectArraysAreSizedBeforeFreezeAllocation() {
        IsolatePolicy policy = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                16L * 1024L * 1024L,
                32,
                Duration.ofSeconds(5));

        try (ActorRuntime runtime = new ActorRuntime(policy)) {
            ActorRuntime.ActorRef<Object> actor = runtime.spawn(() -> (message, context) -> { });
            String[] oversized = new String[300_000];
            Arrays.fill(oversized, "0123456789");

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.send(actor, oversized));

            assertTrue(error.getMessage().contains("too large"));
            assertEquals(0, runtime.snapshot(actor).mailboxMessages());
        }
    }

    @Test
    void unknownMutableHostObjectsCannotCrossActorBoundary() {
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(new Object()));
    }
}
