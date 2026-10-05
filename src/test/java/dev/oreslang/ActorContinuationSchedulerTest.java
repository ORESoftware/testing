package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorContinuationSchedulerTest {
    private static ActorRuntime.DispatcherConfig singleCarrierConfig() {
        return new ActorRuntime.DispatcherConfig(
                1,
                1,
                1,
                8,
                Long.MAX_VALUE,
                1024);
    }

    @Test
    void incompleteAwaitReleasesCarrierAndResumesBeforeLaterMailboxWork() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                singleCarrierConfig())) {
            CompletableFuture<String> awaited = new CompletableFuture<>();
            CountDownLatch suspended = new CountDownLatch(1);
            CountDownLatch peerRan = new CountDownLatch(1);
            CountDownLatch resumed = new CountDownLatch(1);
            CountDownLatch secondMessageRan = new CountDownLatch(1);
            AtomicInteger sequence = new AtomicInteger();
            AtomicInteger resumeOrder = new AtomicInteger();
            AtomicInteger secondOrder = new AtomicInteger();
            AtomicReference<String> initialCarrier = new AtomicReference<>();
            AtomicReference<String> resumeCarrier = new AtomicReference<>();

            var actor = runtime.<String>spawnPrivate(() -> (message, context) -> {
                if (message.equals("suspend")) {
                    initialCarrier.set(Thread.currentThread().getName());
                    sequence.incrementAndGet();
                    suspended.countDown();
                    context.suspendOn(awaited, (value, failure, resumeContext) -> {
                        assertNull(failure);
                        assertEquals("ready", value);
                        assertEquals(context.self().id(), resumeContext.self().id());
                        resumeCarrier.set(Thread.currentThread().getName());
                        resumeOrder.set(sequence.incrementAndGet());
                        resumed.countDown();
                    });
                    fail("suspendOn must end the current carrier turn");
                }

                if (message.equals("second")) {
                    secondOrder.set(sequence.incrementAndGet());
                    secondMessageRan.countDown();
                }
            });

            var peer = runtime.<String>spawnPrivate(() -> (message, context) -> {
                sequence.incrementAndGet();
                peerRan.countDown();
            });

            actor.send("suspend");
            assertTrue(suspended.await(2, TimeUnit.SECONDS));

            actor.send("second");
            peer.send("peer");

            assertTrue(
                    peerRan.await(2, TimeUnit.SECONDS),
                    "a peer actor must run while the first actor is suspended");
            assertFalse(
                    secondMessageRan.await(75, TimeUnit.MILLISECONDS),
                    "later mailbox messages must not overtake a suspended logical turn");

            awaited.complete("ready");

            assertTrue(resumed.await(2, TimeUnit.SECONDS));
            assertTrue(secondMessageRan.await(2, TimeUnit.SECONDS));
            assertTrue(
                    resumeOrder.get() < secondOrder.get(),
                    "the await continuation must finish before later mailbox work");
            assertTrue(initialCarrier.get().startsWith("ores-private-actor-dispatcher-"));
            assertTrue(resumeCarrier.get().startsWith("ores-private-actor-dispatcher-"));
        }
    }

    @Test
    void suspendedMailboxMessageStaysMemoryChargedUntilContinuationFinishes() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                singleCarrierConfig())) {
            CompletableFuture<String> awaited = new CompletableFuture<>();
            CountDownLatch suspended = new CountDownLatch(1);
            CountDownLatch resumed = new CountDownLatch(1);

            var actor = runtime.<String>spawnPrivate(() -> (message, context) -> {
                suspended.countDown();
                context.suspendOn(awaited, (value, failure, resumeContext) -> {
                    assertNull(failure);
                    assertEquals(message, "payload-kept-alive");
                    resumed.countDown();
                });
            });

            actor.send("payload-kept-alive");
            assertTrue(suspended.await(2, TimeUnit.SECONDS));

            long chargedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (runtime.privateMemoryBytes() == 0L && System.nanoTime() < chargedDeadline) {
                Thread.sleep(1);
            }
            assertTrue(
                    runtime.privateMemoryBytes() > 0L,
                    "suspended message graph must remain charged while its continuation can still reference it");

            awaited.complete("done");
            assertTrue(resumed.await(2, TimeUnit.SECONDS));

            long releasedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (runtime.privateMemoryBytes() != 0L && System.nanoTime() < releasedDeadline) {
                Thread.sleep(1);
            }
            assertEquals(
                    0L,
                    runtime.privateMemoryBytes(),
                    "message reservation must be released when the logical mailbox turn completes");
        }
    }

    @Test
    void alreadyCompletedAwaitStillYieldsToALaterSchedulerTurn() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                singleCarrierConfig())) {
            CountDownLatch handlerReturned = new CountDownLatch(1);
            CountDownLatch resumed = new CountDownLatch(1);
            AtomicInteger sequence = new AtomicInteger();
            AtomicInteger handlerOrder = new AtomicInteger();
            AtomicInteger resumeOrder = new AtomicInteger();

            var actor = runtime.<String>spawnPrivate(() -> (message, context) -> {
                context.nextTick((ignored, failure, tickContext) -> {
                    assertNull(failure);
                    handlerOrder.set(sequence.incrementAndGet());
                    handlerReturned.countDown();
                });

                context.suspendOn(
                        CompletableFuture.completedFuture("done"),
                        (value, failure, resumeContext) -> {
                            assertNull(failure);
                            assertEquals("done", value);
                            resumeOrder.set(sequence.incrementAndGet());
                            resumed.countDown();
                        });
                fail("completed await must still yield instead of resuming inline");
            });

            actor.send("go");

            assertTrue(resumed.await(2, TimeUnit.SECONDS));
            assertTrue(handlerReturned.await(2, TimeUnit.SECONDS));
            assertTrue(resumeOrder.get() > 0);
            assertTrue(handlerOrder.get() > 0);
            assertNotEquals(
                    resumeOrder.get(),
                    handlerOrder.get(),
                    "resume and next_tick must execute as distinct actor turns");
        }
    }

    @Test
    void timerCannotMasqueradeAsAwaitResumeWhileTurnIsSuspended() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                singleCarrierConfig())) {
            CompletableFuture<String> awaited = new CompletableFuture<>();
            CountDownLatch suspended = new CountDownLatch(1);
            CountDownLatch resumed = new CountDownLatch(1);
            CountDownLatch timerRan = new CountDownLatch(1);
            AtomicInteger sequence = new AtomicInteger();
            AtomicInteger resumeOrder = new AtomicInteger();
            AtomicInteger timerOrder = new AtomicInteger();

            var actor = runtime.<String>spawnPrivate(() -> (message, context) -> {
                context.setTimer(
                        Duration.ofMillis(20),
                        (value, failure, timerContext) -> {
                            assertNull(failure);
                            timerOrder.set(sequence.incrementAndGet());
                            timerRan.countDown();
                        });

                suspended.countDown();
                context.suspendOn(
                        awaited,
                        (value, failure, resumeContext) -> {
                            assertNull(failure);
                            assertEquals("ready", value);
                            resumeOrder.set(sequence.incrementAndGet());
                            resumed.countDown();
                        });
                fail("suspendOn must end the current carrier turn");
            });

            actor.send("go");
            assertTrue(suspended.await(2, TimeUnit.SECONDS));

            assertFalse(
                    timerRan.await(100, TimeUnit.MILLISECONDS),
                    "ordinary timer events must remain queued while an await owns the suspended logical turn");
            assertFalse(resumed.await(25, TimeUnit.MILLISECONDS));

            awaited.complete("ready");

            assertTrue(resumed.await(2, TimeUnit.SECONDS));
            assertTrue(timerRan.await(2, TimeUnit.SECONDS));
            assertTrue(
                    resumeOrder.get() < timerOrder.get(),
                    "only the awaited completion may resume a suspended mailbox turn; timer work follows later");
        }
    }

    @Test
    void nextTickRunsAfterCurrentMessageAndBeforeNextMailboxMessage() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                singleCarrierConfig())) {
            CountDownLatch tickRan = new CountDownLatch(1);
            CountDownLatch secondRan = new CountDownLatch(1);
            AtomicInteger sequence = new AtomicInteger();
            AtomicInteger messageEndOrder = new AtomicInteger();
            AtomicInteger tickOrder = new AtomicInteger();
            AtomicInteger secondOrder = new AtomicInteger();

            var actor = runtime.<String>spawnPrivate(() -> (message, context) -> {
                if (message.equals("first")) {
                    sequence.incrementAndGet();
                    context.nextTick((value, failure, tickContext) -> {
                        assertNull(failure);
                        tickOrder.set(sequence.incrementAndGet());
                        tickRan.countDown();
                    });
                    messageEndOrder.set(sequence.incrementAndGet());
                    return;
                }
                secondOrder.set(sequence.incrementAndGet());
                secondRan.countDown();
            });

            actor.send("first");
            actor.send("second");

            assertTrue(tickRan.await(2, TimeUnit.SECONDS));
            assertTrue(secondRan.await(2, TimeUnit.SECONDS));
            assertTrue(messageEndOrder.get() < tickOrder.get());
            assertTrue(tickOrder.get() < secondOrder.get());
        }
    }

    @Test
    void actorTimerOnlyEnqueuesAndRunsContinuationOnActorCarrier() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                singleCarrierConfig())) {
            CountDownLatch timerRan = new CountDownLatch(1);
            AtomicReference<String> timerCarrier = new AtomicReference<>();
            AtomicReference<ActorRuntime.TimerHandle> handle = new AtomicReference<>();

            var actor = runtime.<String>spawnPrivate(() -> (message, context) -> {
                handle.set(context.setTimer(
                        Duration.ofMillis(15),
                        (value, failure, timerContext) -> {
                            assertNull(failure);
                            timerCarrier.set(Thread.currentThread().getName());
                            timerRan.countDown();
                        }));
            });

            actor.send("arm");

            assertTrue(timerRan.await(2, TimeUnit.SECONDS));
            assertNotNull(handle.get());
            assertTrue(handle.get().isDone());
            assertFalse(handle.get().isCancelled());
            assertTrue(timerCarrier.get().startsWith("ores-private-actor-dispatcher-"));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (runtime.pendingActorTimerCount() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertEquals(0L, runtime.pendingActorTimerCount());
        }
    }

    @Test
    void cancellingActorTimerPreventsContinuation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                singleCarrierConfig())) {
            CountDownLatch armed = new CountDownLatch(1);
            CountDownLatch fired = new CountDownLatch(1);
            AtomicReference<ActorRuntime.TimerHandle> handle = new AtomicReference<>();

            var actor = runtime.<String>spawnPrivate(() -> (message, context) -> {
                handle.set(context.setTimer(
                        Duration.ofMillis(150),
                        (value, failure, timerContext) -> fired.countDown()));
                armed.countDown();
            });

            actor.send("arm");
            assertTrue(armed.await(2, TimeUnit.SECONDS));
            assertTrue(handle.get().cancel());
            assertTrue(handle.get().isCancelled());
            assertFalse(fired.await(250, TimeUnit.MILLISECONDS));
        }
    }
}
