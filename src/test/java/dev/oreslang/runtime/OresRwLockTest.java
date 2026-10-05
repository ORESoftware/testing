package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class OresRwLockTest {

    @Test
    void ordinarySchedulerCodeMayReadAndWriteExternalState() {
        OresRwLock<Integer> lock = new OresRwLock<>(1);

        try (OresRwLock.WriteGuard<Integer> write = lock.writeLock()) {
            assertEquals(1, write.value());
            write.replace(2);
        }

        try (OresRwLock.ReadGuard<Integer> read = lock.readLock()) {
            assertEquals(2, read.value());
        }
    }

    @Test
    void guardCannotCrossCarrierThread() throws Exception {
        OresRwLock<Integer> lock = new OresRwLock<>(7);
        OresRwLock.ReadGuard<Integer> read = lock.readLock();
        try {
            final Throwable[] observed = new Throwable[1];
            Thread other = Thread.ofPlatform().start(() -> {
                try {
                    read.value();
                } catch (Throwable failure) {
                    observed[0] = failure;
                }
            });
            other.join();
            assertInstanceOf(IllegalStateException.class, observed[0]);
        } finally {
            read.close();
        }
    }

    @Test
    void sharedActorMayReadButCannotWriteExternalRwLock() throws Exception {
        OresRwLock<Integer> lock = new OresRwLock<>(11);

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<OresRwLock<Integer>> actor =
                    runtime.spawnShared(context -> (incoming, actorContext) -> {
                        try (OresRwLock.ReadGuard<Integer> read = incoming.readLock()) {
                            if (read.value() != 11) {
                                throw new AssertionError("shared actor observed wrong external value");
                            }
                        }

                        assertThrows(SecurityException.class, incoming::writeLock);
                        actorContext.self().stop();
                    });

            actor.send(lock);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(),
                    () -> "actor failed: " + actor.failure().orElse(null));
        }
    }

    @Test
    void privateActorCannotReceiveExternalRwLockCapability() {
        OresRwLock<Integer> lock = new OresRwLock<>(3);

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<OresRwLock<Integer>> actor =
                    runtime.spawnPrivate(context -> (incoming, actorContext) -> { });

            assertThrows(SecurityException.class, () -> actor.send(lock));
            actor.stop();
        }
    }

    @Test
    void sharedMutexCannotBeUsedByActorToMutateExternalState() throws Exception {
        OresMutex.Shared<Integer> mutex = OresMutex.shared(5);

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<OresMutex.Shared<Integer>> actor =
                    runtime.spawnShared(context -> (incoming, actorContext) -> {
                        assertThrows(SecurityException.class, incoming::tryLock);
                        actorContext.self().stop();
                    });

            actor.send(mutex);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(),
                    () -> "actor failed: " + actor.failure().orElse(null));
        }
    }
    @Test
    void sharedActorNeverBlocksCarrierOnContendedExternalReadLock() throws Exception {
        OresRwLock<Integer> lock = new OresRwLock<>(13);
        OresRwLock.WriteGuard<Integer> writer = lock.writeLock();

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<OresRwLock<Integer>> actor =
                    runtime.spawnShared(context -> (incoming, actorContext) -> {
                        // Failure is intentional: the important property is
                        // that this happens immediately on the actor carrier.
                        incoming.readLock();
                    });

            actor.send(lock);
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS),
                    "contended external read must fail fast rather than park an actor carrier");
            assertInstanceOf(
                    OresRwLock.WouldBlockException.class,
                    actor.failure().orElseThrow());
        } finally {
            writer.close();
        }
    }

}
