package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ActorRuntimeHardeningTest {

    @Test
    void runtimeScopedCapabilitiesCannotCrossTenantBoundaries() {
        try (ActorRuntime left = new ActorRuntime(); ActorRuntime right = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> leftRef = left.spawn(() -> (message, context) -> { });
            ActorRuntime.ActorRef<Object> rightWatcher = right.spawn(() -> (message, context) -> { });
            ActorRuntime.ActorRef<Map<String, Object>> rightSink =
                    right.spawn(() -> (message, context) -> { });

            assertEquals(ActorRuntime.SendResult.FOREIGN_RUNTIME, right.trySend(leftRef, "wrong-runtime"));
            assertThrows(SecurityException.class, () -> right.send(leftRef, "wrong-runtime"));
            assertThrows(SecurityException.class,
                    () -> rightSink.send(Map.<String, Object>of("ref", leftRef)));
            assertThrows(SecurityException.class, () -> ActorRuntime.freeze(leftRef));
            assertThrows(SecurityException.class, () -> right.monitor(rightWatcher, leftRef));

            ActorRuntime.Shared<List<Integer>> foreignShared = left.shareReadonly(List.of(1, 2, 3));
            assertThrows(SecurityException.class,
                    () -> rightSink.send(Map.<String, Object>of("shared", foreignShared)));
        }
    }

    @Test
    void foreignSharedHandleIsRejectedBeforeProtocolCanInspectItsValue() {
        try (ActorRuntime left = new ActorRuntime(); ActorRuntime right = new ActorRuntime()) {
            AtomicInteger validatorCalls = new AtomicInteger();
            ActorRuntime.Protocol<Object> protocol = new ActorRuntime.Protocol<>(
                    "opaque",
                    value -> {
                        validatorCalls.incrementAndGet();
                        return true;
                    });
            ActorRuntime.ActorRef<Object> sink = right.spawn(protocol, () -> (message, context) -> { });
            ActorRuntime.Shared<List<Integer>> foreign = left.shareReadonly(List.of(1, 2, 3));

            assertEquals(ActorRuntime.SendResult.FOREIGN_RUNTIME, sink.trySend(foreign));
            assertEquals(0, validatorCalls.get());
        }
    }

    @Test
    void singletonReplacementCannotOverlapAStoppingSingleton() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            ActorRuntime.ActorRef<String> first = runtime.spawnSingleton(
                    "cache.singleton",
                    ActorRuntime.Protocol.ofClass("cache-command", String.class),
                    () -> (message, context) -> {
                        entered.countDown();
                        release.await(2, TimeUnit.SECONDS);
                    });

            first.send("work");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(runtime.stop(first));

            IllegalStateException overlap = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.spawnSingleton(
                            "cache.singleton",
                            ActorRuntime.Protocol.ofClass("cache-command", String.class),
                            () -> (message, context) -> { }));
            assertTrue(overlap.getMessage().contains("stopping"));

            release.countDown();
            assertTrue(runtime.join(first, Duration.ofSeconds(2)));

            ActorRuntime.ActorRef<String> replacement = runtime.spawnSingleton(
                    "cache.singleton",
                    ActorRuntime.Protocol.ofClass("cache-command", String.class),
                    () -> (message, context) -> { });
            assertNotEquals(first.id(), replacement.id());
        }
    }

    @Test
    void rawNullIsRejectedUniformlyAtActorBoundaries() {
        IllegalArgumentException root = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(null));
        assertTrue(root.getMessage().contains("Option"));

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Object> ref = runtime.spawn(() -> (message, context) -> { });

            IllegalArgumentException direct = assertThrows(
                    IllegalArgumentException.class,
                    () -> ref.send(null));
            assertTrue(direct.getMessage().contains("Option"));

            java.util.HashMap<String, Object> nested = new java.util.HashMap<>();
            nested.put("nullable", null);
            IllegalArgumentException nestedFailure = assertThrows(
                    IllegalArgumentException.class,
                    () -> ref.send(nested));
            assertTrue(nestedFailure.getMessage().contains("Option"));
        }
    }

    @Test
    void monitorRegistrationsAreBoundedAndSlotsReturnOnDemonitor() {
        IsolatePolicy parent = IsolatePolicy.developer();
        IsolatePolicy policy = new IsolatePolicy(
                parent.capabilities(),
                16L * 1024L * 1024L,
                32,
                2,
                Duration.ofSeconds(5),
                false);

        try (ActorRuntime runtime = new ActorRuntime(policy)) {
            ActorRuntime.ActorRef<Object> watcher = runtime.spawn(() -> (message, context) -> { });
            ActorRuntime.ActorRef<Object> target = runtime.spawn(() -> (message, context) -> { });
            List<ActorRuntime.MonitorRef> monitors = new ArrayList<>();

            for (int i = 0; i < 16; i++) monitors.add(runtime.monitor(watcher, target));
            assertEquals(16, runtime.activeMonitorCount());

            IllegalStateException full = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.monitor(watcher, target));
            assertTrue(full.getMessage().contains("monitor limit"));

            assertTrue(runtime.demonitor(watcher, monitors.getFirst()));
            assertEquals(15, runtime.activeMonitorCount());

            ActorRuntime.MonitorRef replacement = runtime.monitor(watcher, target);
            assertNotNull(replacement);
            assertEquals(16, runtime.activeMonitorCount());
        }
    }

    @Test
    void actorFailureTombstonesBoundUntrustedErrorText() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> ref = runtime.spawn(
                    ActorRuntime.Protocol.ofClass("failure", String.class),
                    () -> (message, context) -> {
                        throw new IllegalStateException("x".repeat(100_000));
                    });

            ref.send("boom");
            assertTrue(runtime.join(ref, Duration.ofSeconds(2)));

            ActorRuntime.ActorSnapshot snapshot = runtime.snapshot(ref);
            assertEquals(ActorRuntime.ActorState.FAILED, snapshot.state());
            assertNotNull(snapshot.failure());
            assertTrue(snapshot.failure().message().length() <= 4096);
            assertTrue(snapshot.failure().message().endsWith("...[truncated]"));
        }
    }

    @Test
    void typedProtocolRejectsMalformedMessagesBeforeMailboxAdmission() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            ActorRuntime.Protocol<String> protocol = new ActorRuntime.Protocol<>(
                    "commands",
                    value -> value instanceof String text && text.startsWith("cmd:"));

            ActorRuntime.ActorRef<String> ref = runtime.spawn(protocol, () -> (message, context) -> received.countDown());

            assertEquals(ActorRuntime.SendResult.PROTOCOL_MISMATCH, ref.trySend("bad"));
            assertEquals(ActorRuntime.SendResult.SENT, ref.trySend("cmd:ping"));
            assertTrue(received.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void actorOwnedStateMayCarryOnlySameRuntimeCapabilities() {
        try (ActorRuntime left = new ActorRuntime(); ActorRuntime right = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> local = left.spawn(() -> (message, context) -> { });
            ActorRuntime.ActorRef<String> foreign = right.spawn(() -> (message, context) -> { });

            Object frozen = left.freezeForActorState(Map.of("child", local));
            assertTrue(frozen instanceof Map<?, ?>);
            assertSame(local, ((Map<?, ?>) frozen).get("child"));

            assertThrows(SecurityException.class,
                    () -> left.freezeForActorState(Map.of("child", foreign)));

            ActorRuntime.Shared<List<Integer>> foreignShared = right.shareReadonly(List.of(1, 2, 3));
            assertThrows(SecurityException.class,
                    () -> left.sharedValueForActorState(foreignShared));
        }
    }

    @Test
    void malformedTypedMessageIsRejectedBeforeSendableProjectionRuns() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicInteger projections = new AtomicInteger();
            ActorRuntime.Sendable expensive = (freezer, readOnlyShared) -> {
                projections.incrementAndGet();
                return "cmd:projected";
            };

            ActorRuntime.ActorRef<String> ref = runtime.spawn(
                    ActorRuntime.Protocol.ofClass("strings", String.class),
                    () -> (message, context) -> { });

            @SuppressWarnings({"rawtypes", "unchecked"})
            ActorRuntime.SendResult result = runtime.trySend((ActorRuntime.ActorRef) ref, expensive);

            assertEquals(ActorRuntime.SendResult.PROTOCOL_MISMATCH, result);
            assertEquals(0, projections.get());
        }
    }

    @Test
    void typedMonitorWatcherMustAdmitDownMessages() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> watcher = runtime.spawn(
                    ActorRuntime.Protocol.ofClass("strings", String.class),
                    () -> (message, context) -> { });
            ActorRuntime.ActorRef<String> target = runtime.spawn(
                    ActorRuntime.Protocol.ofClass("strings", String.class),
                    () -> (message, context) -> { });

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.monitor(watcher, target));
            assertTrue(error.getMessage().contains("DOWN"));
        }
    }

    @Test
    void monitorDownDeliveryCannotDisappearWhenWatcherMailboxIsFull() throws Exception {
        IsolatePolicy parent = IsolatePolicy.developer();
        IsolatePolicy tinyMailbox = new IsolatePolicy(
                parent.capabilities(),
                16L * 1024L * 1024L,
                1,
                Duration.ofSeconds(5));

        try (ActorRuntime runtime = new ActorRuntime(parent)) {
            CountDownLatch watcherEntered = new CountDownLatch(1);
            CountDownLatch releaseWatcher = new CountDownLatch(1);

            ActorRuntime.Protocol<Object> watcherProtocol = new ActorRuntime.Protocol<>(
                    "watcher",
                    value -> value instanceof String || value instanceof Map<?, ?>);
            ActorRuntime.ActorRef<Object> watcher = runtime.spawn(
                    tinyMailbox,
                    watcherProtocol,
                    () -> (message, context) -> {
                        if (message instanceof String) {
                            watcherEntered.countDown();
                            releaseWatcher.await(2, TimeUnit.SECONDS);
                        }
                    });

            ActorRuntime.ActorRef<String> target = runtime.spawn(
                    ActorRuntime.Protocol.ofClass("target", String.class),
                    () -> (message, context) -> {
                        throw new IllegalStateException("child boom");
                    });

            runtime.monitor(watcher, target);
            watcher.send("busy");
            assertTrue(watcherEntered.await(2, TimeUnit.SECONDS));
            watcher.send("queued");
            target.send("boom");

            assertTrue(runtime.join(target, Duration.ofSeconds(2)));
            assertTrue(runtime.join(watcher, Duration.ofSeconds(2)));

            ActorRuntime.ActorSnapshot watcherSnapshot = runtime.snapshot(watcher);
            assertEquals(ActorRuntime.ActorState.FAILED, watcherSnapshot.state());
            assertNotNull(watcherSnapshot.failure());
            assertTrue(watcherSnapshot.failure().message().contains("DOWN delivery failed"));
            releaseWatcher.countDown();
        }
    }

    @Test
    void askUsesMessagePassingAndAOneSlotReplyActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.Protocol<Map<String, Object>> requestProtocol =
                    new ActorRuntime.Protocol<>("query", value -> value instanceof Map<?, ?>);

            ActorRuntime.ActorRef<Map<String, Object>> target =
                    runtime.spawn(requestProtocol, () -> (message, context) -> {
                        @SuppressWarnings("unchecked")
                        ActorRuntime.ActorRef<String> reply =
                                (ActorRuntime.ActorRef<String>) message.get("reply");
                        reply.send("pong:" + message.get("payload"));
                    });

            String response = runtime.<Map<String, Object>, String>ask(
                            target,
                            reply -> Map.<String, Object>of("payload", "ping", "reply", reply),
                            ActorRuntime.Protocol.ofClass("reply", String.class),
                            Duration.ofSeconds(2))
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);

            assertEquals("pong:ping", response);
        }
    }

    @Test
    void gracefulStopCannotBeDefeatedByAFullMailbox() throws Exception {
        IsolatePolicy parent = IsolatePolicy.developer();
        IsolatePolicy tinyMailbox = new IsolatePolicy(
                parent.capabilities(),
                16L * 1024L * 1024L,
                1,
                Duration.ofSeconds(5));

        try (ActorRuntime runtime = new ActorRuntime(parent)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger seen = new AtomicInteger();

            ActorRuntime.ActorRef<String> ref = runtime.spawn(
                    tinyMailbox,
                    ActorRuntime.Protocol.ofClass("commands", String.class),
                    () -> (message, context) -> {
                        entered.countDown();
                        release.await(2, TimeUnit.SECONDS);
                        seen.incrementAndGet();
                    });

            ref.send("running");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            ref.send("queued");

            assertTrue(runtime.stop(ref));
            assertNotEquals(ActorRuntime.SendResult.SENT, ref.trySend("must-not-enter"));
            release.countDown();

            assertTrue(runtime.join(ref, Duration.ofSeconds(2)));
            assertEquals(2, seen.get());
            assertEquals(ActorRuntime.ActorState.STOPPED, runtime.snapshot(ref).state());
        }
    }

    @Test
    void gracefulStopLetsAlreadyAdmittedStatefulWorkFinish() throws Exception {
        IsolatePolicy parent = IsolatePolicy.developer();
        IsolatePolicy policy = new IsolatePolicy(
                parent.capabilities(),
                16L * 1024L * 1024L,
                4,
                parent.maxActors(),
                Duration.ofSeconds(5),
                false);

        try (ActorRuntime runtime = new ActorRuntime(parent)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            ActorRuntime.ActorRef<String> ref = runtime.spawnOwned(
                    policy,
                    1024L,
                    ActorRuntime.Protocol.ofClass("stateful", String.class),
                    () -> (message, context) -> {
                        entered.countDown();
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                        context.replaceOwnedStateBytes(2048L);
                    });

            ref.send("work");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(runtime.stop(ref));
            release.countDown();

            assertTrue(runtime.join(ref, Duration.ofSeconds(2)));
            assertEquals(ActorRuntime.ActorState.STOPPED, runtime.snapshot(ref).state());
            assertNull(runtime.snapshot(ref).failure());
        }
    }

    @Test
    void cyclicMessagesAreRejectedDuringSizePreflightNotByStackOverflow() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Object> ref = runtime.spawn(() -> (message, context) -> { });
            ArrayList<Object> cyclic = new ArrayList<>();
            cyclic.add(cyclic);

            IllegalArgumentException error =
                    assertThrows(IllegalArgumentException.class, () -> ref.send(cyclic));
            assertTrue(error.getMessage().contains("cyclic"));
        }
    }

    @Test
    void actorWallTimeIsEnforcedAtCompilerSchedulerSafepoints() {
        IsolatePolicy parent = IsolatePolicy.developer();
        IsolatePolicy shortBudget = new IsolatePolicy(
                parent.capabilities(),
                16L * 1024L * 1024L,
                8,
                Duration.ofMillis(50));

        try (ActorRuntime runtime = new ActorRuntime(parent)) {
            ActorRuntime.ActorRef<String> ref = runtime.spawn(
                    shortBudget,
                    ActorRuntime.Protocol.ofClass("loop", String.class),
                    () -> (message, context) -> {
                        while (true) context.runtime().schedulerSafepoint();
                    });

            ref.send("go");
            assertTrue(runtime.join(ref, Duration.ofSeconds(2)));

            ActorRuntime.ActorSnapshot snapshot = runtime.snapshot(ref);
            assertEquals(ActorRuntime.ActorState.FAILED, snapshot.state());
            assertNotNull(snapshot.failure());
            assertTrue(snapshot.failure().type().contains("CancellationException"));
        }
    }

    @Test
    void ordinaryContainerMessagesBecomeActorLocalMutableCopies() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ArrayList<String> senderValue = new ArrayList<>(List.of("sender"));
            CountDownLatch mutated = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<String> actorValue =
                    new java.util.concurrent.atomic.AtomicReference<>();

            ActorRuntime.ActorRef<List<String>> ref = runtime.spawn(
                    ActorRuntime.Protocol.ofClass("list-message", List.class),
                    () -> (message, context) -> {
                        message.set(0, "actor");
                        actorValue.set(message.getFirst());
                        mutated.countDown();
                    });

            ref.send(senderValue);
            assertTrue(runtime.stop(ref));
            assertTrue(runtime.join(ref, Duration.ofSeconds(2)));
            assertTrue(mutated.await(1, TimeUnit.SECONDS));

            assertEquals("actor", actorValue.get());
            assertEquals(List.of("sender"), senderValue);
        }
    }

    @Test
    void sharedContainerMessagesStayDeeplyReadonly() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ArrayList<String> senderValue = new ArrayList<>(List.of("sender"));
            ActorRuntime.Shared<List<String>> shared = runtime.shareReadonly(senderValue);
            CountDownLatch checked = new CountDownLatch(1);
            AtomicInteger rejectedMutation = new AtomicInteger();

            ActorRuntime.ActorRef<List<String>> ref = runtime.spawn(
                    ActorRuntime.Protocol.ofClass("shared-list-message", List.class),
                    () -> (message, context) -> {
                        try {
                            message.set(0, "actor");
                        } catch (UnsupportedOperationException expected) {
                            rejectedMutation.incrementAndGet();
                        } finally {
                            checked.countDown();
                        }
                    });

            ref.send(shared);
            assertTrue(runtime.stop(ref));
            assertTrue(runtime.join(ref, Duration.ofSeconds(2)));
            assertTrue(checked.await(1, TimeUnit.SECONDS));

            assertEquals(1, rejectedMutation.get());
            assertEquals(List.of("sender"), senderValue);
            assertEquals(List.of("sender"), shared.value());
        }
    }

    @Test
    void frozenMapsPreserveSourceIterationOrderDeterministically() {
        java.util.LinkedHashMap<String, Integer> source = new java.util.LinkedHashMap<>();
        source.put("zeta", 1);
        source.put("alpha", 2);
        source.put("middle", 3);

        Object frozen = ActorRuntime.freeze(source);
        assertInstanceOf(Map.class, frozen);
        assertEquals(
                List.of("zeta", "alpha", "middle"),
                new ArrayList<>(((Map<?, ?>) frozen).keySet()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ((Map<Object, Object>) frozen).put("later", 4));
    }

    @Test
    void frozenMapRejectsKeyCollisionsIntroducedBySendableProjection() {
        ActorRuntime.Sendable left = (freezer, readOnlyShared) -> "same-key";
        ActorRuntime.Sendable right = (freezer, readOnlyShared) -> "same-key";

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(Map.of(left, 1, right, 2)));

        assertTrue(error.getMessage().contains("same frozen key"));
    }
}
