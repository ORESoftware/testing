package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.OresRwLock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class SharedPrivateActorIsolationProofTest {

    @Test
    void sharedActorCanShareExplicitStateWhilePrivateActorRemainsConfined() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            // Positive control: SHARED actors may explicitly read external
            // state through an OresRwLock read capability, but cannot write it.
            OresRwLock<Integer> externalState = new OresRwLock<>(11);
            CountDownLatch sharedTurn = new CountDownLatch(1);

            var shared = runtime.<OresRwLock<Integer>>spawnShared(() -> (state, context) -> {
                assertEquals(ActorRuntime.ActorKind.SHARED, context.kind());
                assertTrue(context.privateMemory().isEmpty());
                try (OresRwLock.ReadGuard<Integer> read = state.readLock()) {
                    assertEquals(11, read.value());
                }
                assertThrows(SecurityException.class, state::writeLock);
                sharedTurn.countDown();
                context.self().stop();
            });

            shared.send(externalState);
            assertTrue(sharedTurn.await(2, TimeUnit.SECONDS));
            assertTrue(shared.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(shared.failure().isEmpty());

            // Negative control: a PRIVATE actor may not receive shared-memory
            // external-state capabilities at all.
            var isolated = runtime.<Object>spawnPrivate(factoryContext -> {
                assertEquals(ActorRuntime.ActorKind.PRIVATE, factoryContext.kind());
                assertTrue(factoryContext.privateMemory().isPresent());
                return (message, context) -> context.self().stop();
            });

            assertThrows(SecurityException.class, () -> isolated.send(externalState));
            isolated.send("ordinary-value");
            assertTrue(isolated.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(isolated.failure().isEmpty());

            // Compiler-facing private construction is capture-free: mutable host state
            // cannot silently become shared state reachable by the actor.
            ArrayList<String> mutableHostState = new ArrayList<>(List.of("host"));
            SecurityException capturedState = assertThrows(
                    SecurityException.class,
                    () -> runtime.<String>spawnPrivate(factoryContext -> {
                        mutableHostState.add("should-never-run");
                        return (message, context) -> context.self().stop();
                    }));
            assertTrue(capturedState.getMessage().contains("stateless"));
            assertEquals(List.of("host"), mutableHostState);

            // Even the explicit trusted-host escape hatch cannot make a private-memory
            // block readable outside the owning actor execution domain.
            AtomicReference<ActorRuntime.PrivateMemoryBlock> leaked = new AtomicReference<>();
            CountDownLatch allocated = new CountDownLatch(1);
            CountDownLatch ownerVerified = new CountDownLatch(1);

            var memoryOwner = runtime.<String>spawnPrivateTrusted(factoryContext -> {
                ActorRuntime.PrivateMemoryBlock block =
                        factoryContext.privateMemory().orElseThrow().allocatePrivateBytes(32);
                block.writeByte(0, (byte) 42);
                leaked.set(block);
                allocated.countDown();

                return (message, context) -> {
                    assertEquals(42, block.readByte(0));
                    block.writeByte(1, (byte) 7);
                    assertEquals(7, block.readByte(1));
                    ownerVerified.countDown();
                };
            });

            // Private actor construction is lazy: admitting work starts the actor and
            // creates its private slice.
            memoryOwner.send("verify-owner-access");
            assertTrue(allocated.await(2, TimeUnit.SECONDS));
            assertNotNull(leaked.get());

            // The same block is usable by its owner but unreadable from the host.
            assertThrows(IllegalStateException.class, () -> leaked.get().readByte(0));
            assertTrue(ownerVerified.await(2, TimeUnit.SECONDS));

            memoryOwner.stop();
            assertTrue(memoryOwner.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(memoryOwner.failure().isEmpty());
            assertTrue(leaked.get().closed());
            assertEquals(0L, runtime.privateMemoryBytes());
        }
    }
}
