package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorGroupRuntimeTest {

    private static ActorGroupConfig.GroupPolicy policy(
            ActorRuntime.ActorKind kind,
            int maxActors,
            int outboxCapacity) {
        return new ActorGroupConfig.GroupPolicy(
                kind,
                0,
                maxActors,
                256,
                outboxCapacity,
                ActorGroupConfig.RestartStrategy.ONE_FOR_ONE,
                3,
                Duration.ofSeconds(5),
                ActorGroupConfig.RestartPolicy.PERMANENT,
                null);
    }

    private static final ActorRuntime.BehaviorFactory<String> ECHO =
            ignoredFactoryContext -> (message, turn) -> turn.emit(message);

    @Test
    void mailmanIsSingleLogicalConsumerWithOrderedBoundedOutbox() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            int count = 80;
            CountDownLatch delivered = new CountDownLatch(count);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            List<Long> sequences = Collections.synchronizedList(new ArrayList<>());

            ActorMailman<String> mailman = new ActorMailman<>() {
                @Override
                public void receiveMail(
                        ActorMail<String> mail,
                        ActorGroupContext<String> group) throws Exception {
                    int now = active.incrementAndGet();
                    maxActive.accumulateAndGet(now, Math::max);
                    try {
                        sequences.add(mail.sequence());
                        Thread.sleep(1);
                    } finally {
                        active.decrementAndGet();
                        delivered.countDown();
                    }
                }
            };

            ActorGroupRef<String> group = runtime.defineActorGroup(
                    ActorRuntime.ActorKind.SHARED,
                    policy(ActorRuntime.ActorKind.SHARED, 4, 128),
                    mailman);

            ActorRuntime.ActorRef<String> a = runtime.spawnInGroup(
                    group,
                    ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(),
                    ECHO);
            ActorRuntime.ActorRef<String> b = runtime.spawnInGroup(
                    group,
                    ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(),
                    ECHO);

            for (int i = 0; i < count; i++) {
                (i % 2 == 0 ? a : b).send("m-" + i);
            }

            assertTrue(delivered.await(10, TimeUnit.SECONDS));
            assertEquals(1, maxActive.get(), "one group must have one active mailman lease");
            assertEquals(count, sequences.size());
            for (int i = 1; i < sequences.size(); i++) {
                assertTrue(
                        sequences.get(i) > sequences.get(i - 1),
                        "outbox sequence must preserve one serialized group order");
            }
        }
    }

    @Test
    void groupOutboxMemoryStaysChargedUntilMailmanFinishes() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch mailmanEntered = new CountDownLatch(1);
            CountDownLatch releaseMailman = new CountDownLatch(1);
            CountDownLatch delivered = new CountDownLatch(1);

            ActorGroupRef<List<String>> group = runtime.defineActorGroup(
                    ActorRuntime.ActorKind.SHARED,
                    policy(ActorRuntime.ActorKind.SHARED, 1, 8),
                    new ActorMailman<>() {
                        @Override
                        public void receiveMail(
                                ActorMail<List<String>> mail,
                                ActorGroupContext<List<String>> ignored) throws Exception {
                            mailmanEntered.countDown();
                            assertTrue(releaseMailman.await(2, TimeUnit.SECONDS));
                            delivered.countDown();
                        }
                    });

            ActorRuntime.ActorRef<String> actor = runtime.spawnInGroup(
                    group,
                    ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(),
                    ignored -> (message, turn) ->
                            turn.emit(List.of("x".repeat(16_384))));

            actor.send("emit");
            assertTrue(mailmanEntered.await(2, TimeUnit.SECONDS));

            long charged = runtime.sharedMemoryBytes();
            assertTrue(
                    charged > 0L,
                    "accepted actor-group mail must remain process-memory charged while the mailman owns it");

            releaseMailman.countDown();
            assertTrue(delivered.await(2, TimeUnit.SECONDS));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (runtime.sharedMemoryBytes() != 0L && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertEquals(
                    0L,
                    runtime.sharedMemoryBytes(),
                    "group outbox reservation must be released after serialized mailman delivery");
        }
    }

    @Test
    void groupCapacityIsReservedAndReleasedWithActorLifetime() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorGroupRef<String> group = runtime.defineActorGroup(
                    ActorRuntime.ActorKind.SHARED,
                    policy(ActorRuntime.ActorKind.SHARED, 2, 16));

            ActorRuntime.ActorRef<String> a = runtime.spawnInGroup(
                    group,
                    ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(),
                    ECHO);
            ActorRuntime.ActorRef<String> b = runtime.spawnInGroup(
                    group,
                    ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(),
                    ECHO);

            assertEquals(2, runtime.actorGroupActorCount(group));

            IllegalStateException full = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.spawnInGroup(
                            group,
                            ActorRuntime.ActorKind.SHARED,
                            IsolatePolicy.developer(),
                            ECHO));
            assertTrue(full.getMessage().contains("actor group capacity exceeded"));
            assertEquals(2, runtime.actorGroupActorCount(group));

            a.stop();
            assertTrue(a.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(1, runtime.actorGroupActorCount(group));

            ActorRuntime.ActorRef<String> replacement = runtime.spawnInGroup(
                    group,
                    ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(),
                    ECHO);
            assertNotNull(replacement);
            assertEquals(2, runtime.actorGroupActorCount(group));

            b.stop();
            replacement.stop();
        }
    }

    @Test
    void privateActorSeesOnlyOpaquePrivateGroupHandle() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<String> observed = new AtomicReference<>();

            ActorGroupRef<String> group = runtime.defineActorGroup(
                    ActorRuntime.ActorKind.PRIVATE,
                    policy(ActorRuntime.ActorKind.PRIVATE, 2, 16),
                    new ActorMailman<>() {
                        @Override
                        public void receiveMail(
                                ActorMail<String> mail,
                                ActorGroupContext<String> ignored) {
                            observed.set(mail.message());
                            delivered.countDown();
                        }
                    });

            ActorRuntime.ActorRef<String> actor = runtime.spawnInGroup(
                    group,
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    ignored -> (message, turn) -> {
                        ActorGroupHandle<?> handle = turn.group().orElseThrow();
                        turn.emit(handle.kind() + ":" + (handle.id() != null));
                    });

            actor.send("probe");
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals("PRIVATE:true", observed.get());

            for (Field field : ActorGroupHandle.class.getDeclaredFields()) {
                assertNotEquals(ActorRuntime.class, field.getType());
                assertNotEquals(ActorGroupRuntime.class, field.getType());
                assertFalse(
                        ActorMailman.class.isAssignableFrom(field.getType()),
                        "opaque handle must not retain mailman state");
            }
        }
    }

    @Test
    void sharedActorCanSpawnGrandchildThroughItsGroupHandle() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch childOutput = new CountDownLatch(1);
            AtomicReference<String> observed = new AtomicReference<>();

            ActorGroupRef<String> group = runtime.defineActorGroup(
                    ActorRuntime.ActorKind.SHARED,
                    policy(ActorRuntime.ActorKind.SHARED, 2, 16),
                    new ActorMailman<>() {
                        @Override
                        public void receiveMail(
                                ActorMail<String> mail,
                                ActorGroupContext<String> ignored) {
                            observed.set(mail.message());
                            childOutput.countDown();
                        }
                    });

            ActorRuntime.BehaviorFactory<String> parentFactory =
                    ignored -> (message, turn) -> {
                        if (!message.equals("spawn-child")) return;
                        @SuppressWarnings("unchecked")
                        ActorGroupHandle<String> sameGroup =
                                (ActorGroupHandle<String>) turn.group().orElseThrow();
                        ActorRuntime.ActorRef<String> child =
                                turn.spawnInGroup(sameGroup, ECHO);
                        child.send("from-child");
                    };

            ActorRuntime.ActorRef<String> parent = runtime.spawnInGroup(
                    group,
                    ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(),
                    parentFactory);

            parent.send("spawn-child");
            assertTrue(childOutput.await(5, TimeUnit.SECONDS));
            assertEquals("from-child", observed.get());
            assertEquals(2, runtime.actorGroupActorCount(group));
        }
    }

    @Test
    void untrustedActorHasMembershipButNoSpawnCapableGroupHandle() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<String> observed = new AtomicReference<>();

            ActorGroupRef<String> group = runtime.defineActorGroup(
                    ActorRuntime.ActorKind.UNTRUSTED,
                    policy(ActorRuntime.ActorKind.UNTRUSTED, 2, 16),
                    new ActorMailman<>() {
                        @Override
                        public void receiveMail(
                                ActorMail<String> mail,
                                ActorGroupContext<String> ignored) {
                            observed.set(mail.message());
                            delivered.countDown();
                        }
                    });

            ActorRuntime.ActorRef<Object> actor = runtime.spawnUntrustedInGroup(
                    group,
                    IsolatePolicy.untrustedActor(),
                    ActorRuntime.UntrustedActorLimits.defaults(),
                    null,
                    null,
                    null,
                    ignored -> (message, turn) -> turn.emit(
                            turn.group().isPresent() ? "handle-present" : "no-handle"));

            actor.send("probe");
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals("no-handle", observed.get());

            SecurityException capabilityLeak = assertThrows(
                    SecurityException.class,
                    () -> actor.send(group.handle()));
            assertTrue(capabilityLeak.getMessage().contains(
                    "untrusted actors cannot receive spawn-capable ActorGroupHandle"));
        }
    }

    @Test
    void groupDomainMismatchAndNullGroupFailBeforeActorCreation() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorGroupRef<String> privateGroup = runtime.defineActorGroup(
                    ActorRuntime.ActorKind.PRIVATE,
                    policy(ActorRuntime.ActorKind.PRIVATE, 2, 16));

            assertThrows(
                    SecurityException.class,
                    () -> runtime.spawnInGroup(
                            privateGroup,
                            ActorRuntime.ActorKind.SHARED,
                            IsolatePolicy.developer(),
                            ECHO));

            assertThrows(
                    NullPointerException.class,
                    () -> runtime.spawnInGroup(
                            (ActorGroupHandle<String>) null,
                            ActorRuntime.ActorKind.SHARED,
                            IsolatePolicy.developer(),
                            ECHO));

            assertEquals(0, runtime.actorCount());
        }
    }
}
