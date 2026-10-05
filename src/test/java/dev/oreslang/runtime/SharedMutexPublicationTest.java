package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SharedMutexPublicationTest {
    @Test
    void abortedFirstPublicationDoesNotPermanentlyClaimRuntime() {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var shared = OresMutex.shared(new int[]{0});

            assertTrue(shared.reserveRuntimePublication(runtimeA));
            shared.abortRuntimePublication(runtimeA);

            assertTrue(
                    shared.bindToRuntime(runtimeB),
                    "a failed mailbox admission must leave an unpublished SharedMutex available to another runtime");
            assertFalse(shared.bindToRuntime(runtimeA));
        }
    }

    @Test
    void committedPublicationSurvivesAnotherAbortedReservation() {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var shared = OresMutex.shared(new int[]{0});

            assertTrue(shared.reserveRuntimePublication(runtimeA));
            assertTrue(shared.reserveRuntimePublication(runtimeA));

            shared.commitRuntimePublication(runtimeA);
            shared.abortRuntimePublication(runtimeA);

            assertTrue(shared.bindToRuntime(runtimeA));
            assertFalse(
                    shared.bindToRuntime(runtimeB),
                    "once any admitted message publishes a SharedMutex, later failed sends must not unbind it");
        }
    }

    @Test
    void foreignRuntimeCannotReserveWhilePublicationIsPending() {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            var shared = OresMutex.shared(new int[]{0});

            assertTrue(shared.reserveRuntimePublication(runtimeA));
            assertFalse(shared.reserveRuntimePublication(runtimeB));

            shared.abortRuntimePublication(runtimeA);
            assertTrue(shared.reserveRuntimePublication(runtimeB));
            shared.abortRuntimePublication(runtimeB);
        }
    }
}
