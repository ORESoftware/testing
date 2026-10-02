package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
            assertTrue(wrongDestinationRuntime.getMessage().contains("another ActorRuntime"));

            IllegalArgumentException direct = assertThrows(
                    IllegalArgumentException.class,
                    () -> targetB.send(refA));
            assertTrue(direct.getMessage().contains("owning ActorRuntime"));

            assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(refA));

            ActorRuntime.Shared<Object> wrapped = runtimeA.shareReadonly(refA);
            IllegalArgumentException wrappedFailure = assertThrows(
                    IllegalArgumentException.class,
                    () -> targetB.send(wrapped));
            assertTrue(wrappedFailure.getMessage().contains("owning ActorRuntime"));
        }
    }

    @Test
    void actorStartupFailureDoesNotLeaveAUsableDeadRef() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            java.util.concurrent.CountDownLatch attempted = new java.util.concurrent.CountDownLatch(1);
            var ref = runtime.<String>spawn(() -> {
                attempted.countDown();
                throw new IllegalStateException("startup failed");
            });

            assertTrue(attempted.await(2, java.util.concurrent.TimeUnit.SECONDS));

            IllegalStateException failure = null;
            long deadline = System.nanoTime()
                    + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (failure == null && System.nanoTime() - deadline < 0) {
                try {
                    ref.send("after-failure");
                    Thread.sleep(1);
                } catch (IllegalStateException expected) {
                    failure = expected;
                }
            }
            assertNotNull(failure, "failed actor must be removed from the runtime registry");
            assertTrue(
                    failure.getMessage().contains("unknown actor")
                            || failure.getMessage().contains("terminated before message admission"),
                    "unexpected failed-actor send diagnostic: " + failure.getMessage());
        }
    }

    @Test
    void cyclicMessageGraphsAreRejectedBeforeStackOverflow() {
        ArrayList<Object> cycle = new ArrayList<>();
        cycle.add(cycle);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(cycle));

        assertTrue(error.getMessage().contains("cyclic actor message"));
    }

    @Test
    void excessivelyDeepMessageGraphsAreRejected() {
        Object nested = "leaf";
        for (int i = 0; i < 300; i++) {
            nested = List.of(nested);
        }
        Object tooDeep = nested;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(tooDeep));

        assertTrue(error.getMessage().contains("maximum nesting depth"));
    }

    @Test
    void hostileContainerSizeCannotForceEagerAllocation() {
        List<Object> hostile = new java.util.AbstractList<>() {
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

        assertTrue(error.getMessage().contains("maximum graph size"));
    }

    @Test
    void oversizedMessageGraphsAreRejected() {
        List<Integer> tooManyNodes = java.util.Collections.nCopies(100_001, 1);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(tooManyNodes));

        assertTrue(error.getMessage().contains("maximum graph size"));
    }

    @Test
    void sharedMutexCannotUseGenericFreezeTransport() {
        var shared = dev.oreslang.runtime.OresMutex.shared(new int[]{0});

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(shared));

        assertTrue(error.getMessage().contains("runtime-scoped writable capability"));
    }

    @Test
    void sendableCannotReturnMutableHostObject() {
        ActorRuntime.Sendable malicious = () -> new StringBuilder("mutable");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(malicious));

        assertTrue(error.getMessage().contains("not Sendable"));
    }

    @Test
    void sendableCannotSelfAuthorizeByReturningItself() {
        final class SelfReturning implements ActorRuntime.Sendable {
            @Override
            public Object freezeForSend() {
                return this;
            }
        }

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(new SelfReturning()));

        assertTrue(error.getMessage().contains("distinct frozen representation"));
    }
}
