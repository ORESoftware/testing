package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresMutex;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class PrivateActorIsolationTest {

    @Test
    void compilerPrivateFactoryRejectsCapturedHostState() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            StringBuilder hostMutable = new StringBuilder("host");

            SecurityException failure = assertThrows(
                    SecurityException.class,
                    () -> runtime.<String>spawnPrivate(factoryContext -> {
                        hostMutable.append("captured");
                        return (message, context) -> { };
                    }));

            assertTrue(failure.getMessage().contains("stateless"));
        }
    }


    @Test
    void captureFreeFactoryStillCannotHideMutableJvmStateInBehavior() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnPrivate(factoryContext -> {
                int[] ordinaryJvmState = new int[]{41};
                return (message, context) -> ordinaryJvmState[0]++;
            });

            ref.send("run");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isPresent());
            assertInstanceOf(SecurityException.class, ref.failure().orElseThrow());
            assertTrue(ref.failure().orElseThrow().getMessage().contains("context.privateMemory"));
            assertEquals(0L, runtime.privateMemoryBytes());
        }
    }

    @Test
    void trustedHostEscapeHatchIsExplicitAndUnavailableToAdversarialPolicy() {
        StringBuilder hostMutable = new StringBuilder("host");

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnPrivateTrusted(factoryContext -> {
                hostMutable.append("-trusted");
                return (message, context) -> context.self().stop();
            });
            ref.send("run");
            assertDoesNotThrow(() -> assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS)));
            assertEquals("host-trusted", hostMutable.toString());
        }

        IsolatePolicy strict = IsolatePolicy.strictFaas();
        try (ActorRuntime runtime = new ActorRuntime(strict)) {
            assertThrows(SecurityException.class, () ->
                    runtime.<String>spawnPrivateTrusted(
                            strict,
                            factoryContext -> (message, context) -> { }));
        }
    }

    @Test
    void privateActorPolicyStripsAllSharedMemoryCapabilities() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnPrivate(factoryContext -> {
                if (factoryContext.policy().allows(IsolatePolicy.Capability.SHARED_MEMORY)) {
                    throw new AssertionError("private actor retained SHARED_MEMORY");
                }
                if (factoryContext.policy().allows(IsolatePolicy.Capability.ACTOR_SHARE_READONLY)) {
                    throw new AssertionError("private actor retained ACTOR_SHARE_READONLY");
                }
                return (message, context) -> context.self().stop();
            });

            ref.send("check");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void privateMailboxIsolationCopiesMutableContainersBeforeEnqueue() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<Object> observed = new AtomicReference<>();
            ArrayList<String> mutable = new ArrayList<>(List.of("before"));

            var ref = runtime.<Object>spawnPrivate(() -> (message, context) -> {
                observed.set(message);
                received.countDown();
                context.self().stop();
            });

            ref.send(mutable);
            mutable.add("after");

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("before"), observed.get());
            @SuppressWarnings("unchecked")
            List<String> isolated = (List<String>) observed.get();
            assertThrows(UnsupportedOperationException.class, () -> isolated.add("mutate"));
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void readonlySharedInputIsCopiedIntoPrivateDomainNotRetainedAsHandle() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.Shared<List<String>> shared = runtime.shareReadonly(List.of("a", "b"));
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<Object> observed = new AtomicReference<>();

            var ref = runtime.<Object>spawnPrivate(() -> (message, context) -> {
                observed.set(message);
                received.countDown();
                context.self().stop();
            });

            ref.send(shared);

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("a", "b"), observed.get());
            assertFalse(observed.get() instanceof ActorRuntime.Shared<?>);
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void privateActorRejectsWritableSharedMemoryHandlesAtMailboxBoundary() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var privateRef = runtime.<Object>spawnPrivate(() -> (message, context) -> { });
            var syncCell = runtime.syncCell(1);
            var sharedMutex = OresMutex.shared(new int[]{1});

            assertThrows(IllegalArgumentException.class, () -> privateRef.send(syncCell));
            assertThrows(RuntimeException.class, () -> privateRef.send(sharedMutex));
            privateRef.stop();
        }
    }

    @Test
    void privateActorCannotCreateOrDereferenceSharedMemoryFromInsideTurn() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.Shared<List<String>> shared = runtime.shareReadonly(List.of("outside"));
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> first = new AtomicReference<>();
            AtomicReference<Throwable> second = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                try {
                    context.runtime().shareReadonly(List.of("inside"));
                } catch (Throwable failure) {
                    first.set(failure);
                }
                try {
                    shared.value();
                } catch (Throwable failure) {
                    second.set(failure);
                }
                checked.countDown();
                context.self().stop();
            });

            ref.send("check");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertNotNull(first.get());
            assertNotNull(second.get());
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void directPrivateMemoryIsActorOwnedAndReleasedOnlyAfterFinalization() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Byte>spawnPrivate(factoryContext -> {
                ActorRuntime.PrivateMemoryBlock block =
                        factoryContext.privateMemory().orElseThrow().allocatePrivateBytes(256);
                block.writeByte(0, (byte) 11);

                return (message, context) -> {
                    if (block.readByte(0) != 11) throw new AssertionError("private block state changed");
                    block.writeByte(1, message);
                    if (block.readByte(1) != message) throw new AssertionError("private block write failed");
                    context.self().stop();
                };
            });

            ref.send((byte) 77);
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0L, runtime.privateMemoryBytes());
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void leakedDirectMemoryHandleCannotBeAccessedOutsideOwningActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorRuntime.PrivateMemoryBlock> leaked = new AtomicReference<>();
            CountDownLatch created = new CountDownLatch(1);

            var ref = runtime.<String>spawnPrivateTrusted(factoryContext -> {
                ActorRuntime.PrivateMemoryBlock block =
                        factoryContext.privateMemory().orElseThrow().allocatePrivateBytes(32);
                block.writeByte(0, (byte) 9);
                leaked.set(block);
                created.countDown();
                return (message, context) -> { };
            });

            ref.send("initialize");
            assertTrue(created.await(2, TimeUnit.SECONDS));

            assertThrows(IllegalStateException.class, () -> leaked.get().readByte(0));

            ref.stop();
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(leaked.get().closed());
            assertEquals(0L, runtime.privateMemoryBytes());
        }
    }

    @Test
    void privateMemoryHandleCannotCrossToAnotherActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorRuntime.PrivateMemoryBlock> leaked = new AtomicReference<>();
            CountDownLatch created = new CountDownLatch(1);

            var owner = runtime.<String>spawnPrivateTrusted(factoryContext -> {
                leaked.set(factoryContext.privateMemory().orElseThrow().allocatePrivateBytes(16));
                created.countDown();
                return (message, context) -> { };
            });
            owner.send("initialize");
            assertTrue(created.await(2, TimeUnit.SECONDS));

            var other = runtime.<Object>spawnPrivate(() -> (message, context) -> { });
            assertThrows(IllegalArgumentException.class, () -> other.send(leaked.get()));

            owner.stop();
            other.stop();
            assertTrue(owner.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(other.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void privateAndSharedActorsStayOnDifferentCarrierPools() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CountDownLatch done = new CountDownLatch(2);
            AtomicReference<String> privateThread = new AtomicReference<>();
            AtomicReference<String> sharedThread = new AtomicReference<>();

            var privateRef = runtime.<String>spawnPrivate(() -> (message, context) -> {
                privateThread.set(Thread.currentThread().getName());
                done.countDown();
                context.self().stop();
            });
            var sharedRef = runtime.<String>spawnShared(() -> (message, context) -> {
                sharedThread.set(Thread.currentThread().getName());
                done.countDown();
                context.self().stop();
            });

            privateRef.send("private");
            sharedRef.send("shared");

            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertTrue(privateThread.get().startsWith("ores-private-actor-dispatcher-"));
            assertTrue(sharedThread.get().startsWith("ores-shared-actor-dispatcher-"));
            assertTrue(privateRef.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(sharedRef.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
    @Test
    void privateActorCannotEscalateBySpawningSharedChild() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var parent = runtime.<String>spawnPrivate(factoryContext -> (message, context) -> {
                try {
                    context.runtime().spawnShared(
                            childContext -> (childMessage, childTurn) -> { });
                    throw new AssertionError("private actor spawned shared child");
                } catch (SecurityException expected) {
                    if (!expected.getMessage().contains("SHARED_MEMORY")) throw expected;
                }
                context.self().stop();
            });

            parent.send("check");
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(parent.failure().isEmpty());
        }
    }

    @Test
    void privateChildInheritsParentStrippedCapabilities() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var parent = runtime.<String>spawnPrivate(factoryContext -> (message, context) -> {
                ActorRuntime.ActorRef<String> child = context.runtime().spawnPrivate(childContext -> {
                    if (childContext.policy().allows(IsolatePolicy.Capability.SHARED_MEMORY)) {
                        throw new AssertionError("private child regained SHARED_MEMORY");
                    }
                    if (childContext.policy().allows(IsolatePolicy.Capability.ACTOR_SHARE_READONLY)) {
                        throw new AssertionError("private child regained ACTOR_SHARE_READONLY");
                    }
                    return (childMessage, childTurn) -> childTurn.self().stop();
                });
                child.send("run");
                context.self().stop();
            });

            parent.send("run");
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(parent.failure().isEmpty());
        }
    }

    @Test
    void actorCodeCannotUseTrustedHostConstructionEscapeHatches() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var parent = runtime.<String>spawnPrivate(factoryContext -> (message, context) -> {
                assertThrows(SecurityException.class, () ->
                        context.runtime().spawnPrivateTrusted(
                                childContext -> (childMessage, childTurn) -> { }));
                assertThrows(SecurityException.class, () ->
                        context.runtime().spawnPrivate(
                                () -> (childMessage, childTurn) -> { }));
                context.self().stop();
            });

            parent.send("check");
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(parent.failure().isEmpty());
        }
    }



    @Test
    void privateBehaviorCannotPersistPrimitiveStateOnJvmHeap() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnPrivate(factoryContext -> mutablePrimitiveBehavior());

            ref.send("increment");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isPresent());
            assertInstanceOf(SecurityException.class, ref.failure().orElseThrow());
            assertTrue(ref.failure().orElseThrow().getMessage().contains("context.privateMemory"));
            assertEquals(0L, runtime.privateMemoryBytes());
        }
    }

    private static ActorRuntime.Behavior<String> mutablePrimitiveBehavior() {
        return new ActorRuntime.Behavior<>() {
            private int counter;

            @Override
            public void onMessage(String message, ActorRuntime.ActorContext<String> context) {
                counter++;
            }
        };
    }

    @Test
    void privateBehaviorCannotRetainNewJvmMutableStateAcrossTurns() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnPrivate(factoryContext -> mutableJvmStateBehavior());

            ref.send("store");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isPresent());
            assertInstanceOf(SecurityException.class, ref.failure().orElseThrow());
            assertTrue(ref.failure().orElseThrow().getMessage().contains("context.privateMemory"));
            assertEquals(0L, runtime.privateMemoryBytes());
        }
    }

    private static ActorRuntime.Behavior<String> mutableJvmStateBehavior() {
        return new ActorRuntime.Behavior<>() {
            private Object retained;

            @Override
            public void onMessage(String message, ActorRuntime.ActorContext<String> context) {
                retained = new byte[]{1, 2, 3};
            }
        };
    }


    @Test
    void privateBehaviorCannotUseMutableStaticJvmState() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnPrivate(factoryContext -> mutableStaticBehavior());

            ref.send("run");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isPresent());
            assertInstanceOf(SecurityException.class, ref.failure().orElseThrow());
            assertTrue(ref.failure().orElseThrow().getMessage().contains("static JVM state"));
        }
    }

    private static ActorRuntime.Behavior<String> mutableStaticBehavior() {
        return new ActorRuntime.Behavior<>() {
            private static int sharedCounter;

            @Override
            public void onMessage(String message, ActorRuntime.ActorContext<String> context) {
                sharedCounter++;
            }
        };
    }

    @Test
    void privateBehaviorCannotHideSharedMutableObjectBehindStaticFinal() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnPrivate(factoryContext -> staticFinalMutableBehavior());

            ref.send("run");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isPresent());
            assertInstanceOf(SecurityException.class, ref.failure().orElseThrow());
            assertTrue(ref.failure().orElseThrow().getMessage().contains("shared static object"));
        }
    }

    private static ActorRuntime.Behavior<String> staticFinalMutableBehavior() {
        return new ActorRuntime.Behavior<>() {
            private static final AtomicInteger SHARED_COUNTER = new AtomicInteger();

            @Override
            public void onMessage(String message, ActorRuntime.ActorContext<String> context) {
                SHARED_COUNTER.incrementAndGet();
            }
        };
    }


    @Test
    void privateFactoryCannotUseMutableStaticJvmState() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            assertThrows(SecurityException.class, () ->
                    runtime.<String>spawnPrivate(new ActorRuntime.BehaviorFactory<>() {
                        private static int SHARED_COUNTER;

                        @Override
                        public ActorRuntime.Behavior<String> create(ActorRuntime.ActorContext<String> context) {
                            SHARED_COUNTER++;
                            return (message, turn) -> { };
                        }
                    }));
        }
    }

    @Test
    void privateFactoryCannotHideSharedMutableObjectBehindStaticFinal() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            assertThrows(SecurityException.class, () ->
                    runtime.<String>spawnPrivate(new ActorRuntime.BehaviorFactory<>() {
                        private static final AtomicInteger SHARED_COUNTER = new AtomicInteger();

                        @Override
                        public ActorRuntime.Behavior<String> create(ActorRuntime.ActorContext<String> context) {
                            SHARED_COUNTER.incrementAndGet();
                            return (message, turn) -> { };
                        }
                    }));
        }
    }


}
