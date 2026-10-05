package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresMutex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ActorInvocationRuntimeTest {

    @Test
    void invokeUsesRequestedSharedOrPrivateActorKind() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorKind shared = runtime.invoke(
                    ActorRuntime.ActorKind.SHARED,
                    "ping",
                    (message, context) -> {
                        assertTrue(context.privateMemory().isEmpty());
                        return context.kind();
                    });

            ActorRuntime.ActorKind isolated = runtime.invoke(
                    ActorRuntime.ActorKind.PRIVATE,
                    "ping",
                    (message, context) -> {
                        assertTrue(context.privateMemory().isPresent());
                        assertFalse(context.policy().allows(IsolatePolicy.Capability.SHARED_MEMORY));
                        assertFalse(context.policy().allows(IsolatePolicy.Capability.ACTOR_SHARE_READONLY));
                        return context.kind();
                    });

            assertEquals(ActorRuntime.ActorKind.SHARED, shared);
            assertEquals(ActorRuntime.ActorKind.PRIVATE, isolated);
        }
    }

    @Test
    void privateInvocationRejectsSharedMutexTransport() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            OresMutex.Shared<List<Integer>> shared = OresMutex.shared(List.of(1, 2, 3));

            assertThrows(
                    SecurityException.class,
                    () -> runtime.invoke(
                            ActorRuntime.ActorKind.PRIVATE,
                            shared,
                            (message, context) -> 1));
        }
    }

    @Test
    void invocationRejectsMutableHostReturnValues() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.invoke(
                            ActorRuntime.ActorKind.SHARED,
                            "ok",
                            (message, context) -> new StringBuilder(message)));

            assertTrue(failure.getMessage().contains("not Sendable"));
        }
    }

    @Test
    void invocationPropagatesActorFailure() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.invoke(
                            ActorRuntime.ActorKind.SHARED,
                            "boom",
                            (message, context) -> {
                                throw new IllegalStateException(message);
                            }));

            assertEquals("boom", failure.getMessage());
        }
    }
}
