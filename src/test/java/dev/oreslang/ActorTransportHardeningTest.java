package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.OresMutex;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ActorTransportHardeningTest {
    @Test
    void readonlySharedWrapperFreezesBeforePublication() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ArrayList<String> source = new ArrayList<>(List.of("a"));
            ActorRuntime.Shared<Object> shared = runtime.shareReadonly(source);

            source.add("b");

            assertInstanceOf(List.class, shared.value());
            @SuppressWarnings("unchecked")
            List<String> frozen = (List<String>) shared.value();
            assertEquals(List.of("a"), frozen);
            assertThrows(UnsupportedOperationException.class, () -> frozen.add("c"));
        }
    }

    @Test
    void actorRefsCannotCrossRuntimeCapabilityBoundaries() {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var refA = runtimeA.<Object>spawn(() -> (message, context) -> { });
            var targetB = runtimeB.<Object>spawn(() -> (message, context) -> { });

            IllegalArgumentException wrongDestinationRuntime = assertThrows(
                    IllegalArgumentException.class,
                    () -> runtimeB.send(refA, "wrong-runtime"));
            assertTrue(wrongDestinationRuntime.getMessage().contains("different ActorRuntime"));

            IllegalArgumentException direct = assertThrows(
                    IllegalArgumentException.class,
                    () -> targetB.send(refA));
            assertTrue(direct.getMessage().contains("different ActorRuntime"));

            assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(refA));
            assertThrows(IllegalArgumentException.class, () -> runtimeA.shareReadonly(refA));
        }
    }

    @Test
    void cyclicMessageGraphsAreRejectedBeforeStackOverflow() {
        ArrayList<Object> cycle = new ArrayList<>();
        cycle.add(cycle);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(cycle));

        assertTrue(error.getMessage().contains("cyclic"));
    }

    @Test
    void excessivelyDeepMessageGraphsAreRejected() {
        Object nested = "leaf";
        for (int i = 0; i < 300; i++) nested = List.of(nested);
        Object tooDeep = nested;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(tooDeep));

        assertTrue(error.getMessage().contains("maximum nesting depth"));
    }

    @Test
    void hostileContainerSizeCannotForceEagerAllocation() {
        List<Object> hostile = new AbstractList<>() {
            @Override
            public Object get(int index) {
                throw new AssertionError("oversized container must be rejected before iteration");
            }

            @Override
            public int size() {
                return Integer.MAX_VALUE;
            }
        };

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(hostile));

        assertTrue(error.getMessage().contains("maximum node count"));
    }

    @Test
    void oversizedMessageGraphsAreRejected() {
        List<Integer> tooManyNodes = Collections.nCopies(100_001, 1);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(tooManyNodes));

        assertTrue(error.getMessage().contains("maximum node count"));
    }

    @Test
    void sharedMutexCannotUseGenericFreezeTransport() {
        var shared = OresMutex.shared(new int[]{0});

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(shared));

        assertTrue(error.getMessage().contains("live actor/shared capabilities"));
    }
    @Test
    void actorStartupFailureDoesNotLeaveUsableRefOrActorQuota() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            java.util.concurrent.CountDownLatch attempted = new java.util.concurrent.CountDownLatch(1);

            var ref = runtime.<String>spawnPrivateTrusted(factoryContext -> {
                attempted.countDown();
                throw new IllegalStateException("startup failed");
            });

            ref.send("trigger");
            assertTrue(attempted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(ref.isAlive());
            assertEquals(0, runtime.actorCount());
            assertTrue(ref.failure().isPresent());
            assertEquals("startup failed", ref.failure().orElseThrow().getMessage());
            assertThrows(ActorRuntime.ActorTerminatedException.class, () -> ref.send("after-failure"));
        }
    }


}
