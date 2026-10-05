package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

final class ActorSpawnRuntimeTest {

    @Test
    void spawnInvocationSeparatesReadyFromCompletion() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            ActorRuntime.ActorSpawn<String, String> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    "work",
                    (message, context) -> {
                        entered.countDown();
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                        return message + "-done";
                    });

            assertNotNull(spawn.id());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(spawn.ready().isDone(), "READY must precede actor callable execution");
            assertFalse(spawn.result().isDone(), "spawn must not wait for callable completion");

            release.countDown();
            assertEquals("work-done", spawn.result().get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void spawnInvocationReturnsBeforeActorCallableCompletes() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch release = new CountDownLatch(1);

            ActorRuntime.ActorSpawn<Integer, Integer> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    7,
                    (message, context) -> {
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                        return message * 6;
                    });

            assertNotNull(spawn.id());
            assertFalse(spawn.result().isDone());
            release.countDown();
            assertEquals(42, spawn.result().get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void doneTracksCompletionIndependentlyOfReady() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch release = new CountDownLatch(1);
            ActorRuntime.ActorSpawn<String, String> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    "ok",
                    (message, context) -> {
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                        return message;
                    });

            assertEquals(spawn.id(), spawn.ready().get(2, TimeUnit.SECONDS).id());
            assertFalse(spawn.done().isDone());

            release.countDown();
            assertTrue(spawn.done().get(2, TimeUnit.SECONDS));
            assertEquals("ok", spawn.result().get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void readinessFutureYieldsTheSameActorIdentity() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorSpawn<String, String> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    "ok",
                    (message, context) -> message);

            ActorRuntime.ActorRef<String> ref = spawn.ready().get(2, TimeUnit.SECONDS);
            assertEquals(spawn.id(), ref.id());
            assertEquals("ok", spawn.result().get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void callableFailureFailsResultAndRecordsActorTerminationCause() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorSpawn<String, String> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    "boom",
                    (message, context) -> {
                        throw new IllegalStateException(message);
                    });

            ActorRuntime.ActorRef<String> ref = spawn.ready().get(2, TimeUnit.SECONDS);

            ExecutionException resultFailure = assertThrows(
                    ExecutionException.class,
                    () -> spawn.result().get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, resultFailure.getCause());
            assertEquals("boom", resultFailure.getCause().getMessage());

            ExecutionException doneFailure = assertThrows(
                    ExecutionException.class,
                    () -> spawn.done().get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, doneFailure.getCause());

            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, ref.failure().orElseThrow());
        }
    }

    @Test
    void actorSpawnTicketCannotCrossActorBoundary() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch release = new CountDownLatch(1);
            ActorRuntime.ActorSpawn<String, String> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    "work",
                    (message, context) -> {
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                        return message;
                    });

            ActorRuntime.ActorRef<Object> target = runtime.spawnPrivateTrusted(
                    ignored -> (message, context) -> { });

            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> target.send(spawn));
            assertTrue(denied.getMessage().contains("ActorSpawn"));

            release.countDown();
            assertEquals("work", spawn.result().get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void contextEntryWatchdogSettlesReadyAndResultWithoutWaitingForCarrierUnwind() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1,
                1,
                1,
                64,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.MILLISECONDS.toNanos(40),
                1,
                64);

        ReentrantLock contextGate = new ReentrantLock(true);
        contextGate.lock();
        ActorRuntime.TurnExecutor interruptibleEntry = turn -> {
            boolean locked = false;
            try {
                contextGate.lockInterruptibly();
                locked = true;
                turn.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException(
                        "interrupted before actor context entry");
            } finally {
                if (locked) contextGate.unlock();
            }
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                config,
                interruptibleEntry)) {
            ActorRuntime.ActorSpawn<String, String> spawn = runtime.spawnInvocation(
                    ActorRuntime.ActorKind.PRIVATE,
                    "work",
                    (message, context) -> fail("guest callback must not run"));

            ExecutionException readyFailure = assertThrows(
                    ExecutionException.class,
                    () -> spawn.ready().get(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorTurnExceededException.class,
                    readyFailure.getCause());

            ExecutionException resultFailure = assertThrows(
                    ExecutionException.class,
                    () -> spawn.result().get(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorTurnExceededException.class,
                    resultFailure.getCause());
        } finally {
            contextGate.unlock();
        }
    }

}
