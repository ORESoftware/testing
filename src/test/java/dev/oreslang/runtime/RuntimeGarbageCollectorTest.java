package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class RuntimeGarbageCollectorTest {
    @Test
    void processCollectionRequestsJvmGcAtMostOncePerThrottleWindow() {
        AtomicInteger gcRequests = new AtomicInteger();
        Object owner = new Object();
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(
                gcRequests::incrementAndGet, Duration.ofHours(1), Duration.ofHours(1), 16)) {
            gc.track(owner, () -> fail("live owner must not be cleaned"));
            var first = gc.collectProcess();
            var second = gc.collectProcess();
            assertTrue(first.jvmGcRequested());
            assertFalse(second.jvmGcRequested());
            assertEquals(1, gcRequests.get());
            assertEquals(1, first.trackedAfter());
            assertNotNull(owner);
        }
    }

    @Test
    void cleanupHandleIsDeterministicAndIdempotent() {
        AtomicInteger cleanups = new AtomicInteger();
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1))) {
            var handle = gc.track(new Object(), cleanups::incrementAndGet);
            handle.close();
            handle.close();
            assertEquals(1, cleanups.get());
        }
    }

    @Test
    void failedCleanupRemainsRetryable() {
        AtomicInteger attempts = new AtomicInteger();
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1))) {
            var handle = gc.track(new Object(), () -> {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("transient");
            });
            assertThrows(IllegalStateException.class, handle::close);
            assertDoesNotThrow(handle::close);
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void registryGrowthIsBounded() {
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(
                () -> {}, Duration.ofHours(1), Duration.ofSeconds(1), 1)) {
            Object owner = new Object();
            gc.track(owner, () -> {});
            assertThrows(IllegalStateException.class, () -> gc.track(new Object(), () -> {}));
            assertNotNull(owner);
        }
    }

    @Test
    void actorCollectionIsDomainLocalAndNeverRequestsJvmGc() throws Exception {
        AtomicInteger gcRequests = new AtomicInteger();
        AtomicReference<RuntimeGarbageCollector.CollectionReport> report = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(gcRequests::incrementAndGet, Duration.ofHours(1));
             ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawn(() -> (message, context) -> {
                report.set(gc.collectCurrentActor());
                done.countDown();
            });
            ref.send("gc");
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals("actor", report.get().scope());
            assertFalse(report.get().jvmGcRequested());
            assertEquals(0, gcRequests.get());
        }
    }


    @Test
    void actorCollectionReportsOnlyItsOwnIndexedDomain() throws Exception {
        AtomicReference<RuntimeGarbageCollector.CollectionReport> firstReport = new AtomicReference<>();
        AtomicReference<RuntimeGarbageCollector.CollectionReport> secondReport = new AtomicReference<>();
        CountDownLatch registered = new CountDownLatch(2);
        CountDownLatch done = new CountDownLatch(2);

        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1));
             ActorRuntime runtime = new ActorRuntime()) {
            var first = runtime.<String>spawn(() -> (message, context) -> {
                Object owner = new Object();
                gc.track(owner, () -> {});
                registered.countDown();
                assertTrue(registered.await(2, TimeUnit.SECONDS));
                firstReport.set(gc.collectCurrentActor());
                assertNotNull(owner);
                done.countDown();
            });
            var second = runtime.<String>spawn(() -> (message, context) -> {
                Object owner = new Object();
                gc.track(owner, () -> {});
                registered.countDown();
                assertTrue(registered.await(2, TimeUnit.SECONDS));
                secondReport.set(gc.collectCurrentActor());
                assertNotNull(owner);
                done.countDown();
            });

            first.send("gc");
            second.send("gc");

            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(1, firstReport.get().trackedBefore());
            assertEquals(1, secondReport.get().trackedBefore());
            assertEquals(1, firstReport.get().inspected());
            assertEquals(1, secondReport.get().inspected());
        }
    }

    @Test
    void actorCollectionHasABoundedInspectionQuantum() throws Exception {
        AtomicReference<RuntimeGarbageCollector.CollectionReport> report = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1));
             ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawn(() -> (message, context) -> {
                ArrayList<Object> owners = new ArrayList<>();
                for (int i = 0; i < 300; i++) {
                    Object owner = new Object();
                    owners.add(owner);
                    gc.track(owner, () -> {});
                }
                report.set(gc.collectCurrentActor());
                assertEquals(300, owners.size());
                done.countDown();
            });

            ref.send("gc");
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(300, report.get().trackedBefore());
            assertEquals(256, report.get().inspected());
            assertEquals(300, report.get().trackedAfter());
            assertFalse(report.get().jvmGcRequested());
        }
    }


    @Test
    void actorExitDeterministicallyRetiresItsCleanupDomain() throws Exception {
        AtomicInteger cleanups = new AtomicInteger();
        AtomicReference<Object> leakedOwner = new AtomicReference<>();
        CountDownLatch registered = new CountDownLatch(1);

        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1));
             ActorRuntime runtime = new ActorRuntime()) {
            runtime.setActorExitHook(gc::retireActorDomain);
            var ref = runtime.<String>spawn(() -> (message, context) -> {
                Object owner = new Object();
                leakedOwner.set(owner); // deliberately keep a stale host reference alive
                gc.track(owner, cleanups::incrementAndGet);
                registered.countDown();
                context.self().stop();
            });

            ref.send("stop");
            assertTrue(registered.await(2, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertNotNull(leakedOwner.get());
            assertEquals(1, cleanups.get(),
                    "actor termination must retire actor-local runtime resources even with a stale owner reference");
        }
    }

    @Test
    void actorCollectionOutsideActorIsRejected() {
        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(() -> {}, Duration.ofHours(1))) {
            var error = assertThrows(IllegalStateException.class, gc::collectCurrentActor);
            assertTrue(error.getMessage().contains("actor.gc() requires execution inside an actor"));
        }
    }
}
