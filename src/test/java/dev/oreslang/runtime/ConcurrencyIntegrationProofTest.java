package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ConcurrencyIntegrationProofTest {

    @Test
    void channelFutureAwaitResumesOnlyThroughFreshOwningSchedulerTurn() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(0);
            AtomicInteger pc = new AtomicInteger();
            AtomicLong firstDispatch = new AtomicLong();
            AtomicReference<Thread> producer = new AtomicReference<>();
            AtomicReference<Thread> firstCarrier = new AtomicReference<>();

            OresFuture<Integer> result = scheduler.start(resume -> {
                assertSame(scheduler, OresScheduler.current());
                int step = pc.getAndIncrement();
                if (step == 0) {
                    firstDispatch.set(OresScheduler.currentDispatchId());
                    firstCarrier.set(Thread.currentThread());
                    return OresScheduler.await(channel.readAsync());
                }

                assertNotEquals(firstDispatch.get(), OresScheduler.currentDispatchId(),
                        "channel completion must re-enter through a fresh scheduler dispatch");
                assertNotSame(producer.get(), Thread.currentThread(),
                        "producer/completion thread must never execute the continuation");
                assertSame(firstCarrier.get(), Thread.currentThread(),
                        "one-carrier scheduler may reuse the physical carrier after unwinding");
                assertEquals(41, resume.value());
                return OresScheduler.done(((Integer) resume.value()) + 1);
            });

            Thread writer = Thread.ofPlatform().name("proof-channel-producer").start(() -> {
                producer.set(Thread.currentThread());
                assertTrue(channel.tryWrite(41));
            });
            writer.join();

            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            assertEquals(2, pc.get());
        }
    }

    @Test
    void actorMailboxTransportIsTheChannelRuntime() throws Exception {
        Class<?> actorCell = Arrays.stream(ActorRuntime.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("ActorCell"))
                .findFirst()
                .orElseThrow();
        Field mailbox = actorCell.getDeclaredField("mailbox");
        assertSame(ChannelRuntime.Channel.class, mailbox.getType(),
                "actor mailbox transport must be ChannelRuntime.Channel<Envelope>");
    }

    @Test
    void nbChannelCompletionReentersActorMailboxLeaseInsteadOfProducerThread() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(0);
            CountDownLatch armed = new CountDownLatch(1);
            CountDownLatch callbackRan = new CountDownLatch(1);
            AtomicReference<Thread> producer = new AtomicReference<>();
            AtomicReference<Thread> actorTurn = new AtomicReference<>();
            AtomicReference<Thread> callbackTurn = new AtomicReference<>();
            AtomicReference<String> observed = new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                if (!message.equals("arm")) return;
                actorTurn.set(Thread.currentThread());
                ActorRuntime.ContinuationTarget target =
                        context.runtime().captureCurrentContinuationTarget();
                OresFuture<String> pending = channel.readAsync();
                context.runtime().enqueueOnCompletion(pending, target, (value, failure) -> {
                    assertNull(failure);
                    assertTrue(ActorRuntime.inActorExecution());
                    assertEquals(context.self().id(), ActorRuntime.currentActorId().orElseThrow());
                    callbackTurn.set(Thread.currentThread());
                    observed.set(value);
                    callbackRan.countDown();
                });
                armed.countDown();
            });

            actor.send("arm");
            assertTrue(armed.await(2, TimeUnit.SECONDS));

            Thread writer = Thread.ofPlatform().name("proof-nb-producer").start(() -> {
                producer.set(Thread.currentThread());
                assertTrue(channel.tryWrite("ready"));
            });
            writer.join();

            assertTrue(callbackRan.await(2, TimeUnit.SECONDS));
            assertEquals("ready", observed.get());
            assertNotSame(producer.get(), callbackTurn.get());
            assertNotNull(actorTurn.get());
            actor.stop();
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void nbWriteFutureAndCallbackUseSameChannelRegistrationWithDifferentGuestSurface() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            ChannelRuntime.Channel<String> futureChannel = new ChannelRuntime.Channel<>(0);
            ChannelRuntime.Channel<String> callbackChannel = new ChannelRuntime.Channel<>(0);
            AtomicReference<OresFuture<Void>> futureSurface = new AtomicReference<>();
            AtomicReference<OresFuture<Void>> callbackRegistration = new AtomicReference<>();
            AtomicReference<Thread> callbackThread = new AtomicReference<>();
            CountDownLatch armed = new CountDownLatch(1);
            CountDownLatch callbackRan = new CountDownLatch(1);

            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                if (!message.equals("arm")) return;

                futureSurface.set(futureChannel.writeAsync("future"));

                OresFuture<Void> registration = callbackChannel.writeAsync("callback");
                callbackRegistration.set(registration);
                ActorRuntime.ContinuationTarget target =
                        context.runtime().captureCurrentContinuationTarget();
                context.runtime().enqueueOnCompletion(registration, target, (ignored, failure) -> {
                    assertNull(failure);
                    assertTrue(ActorRuntime.inActorExecution());
                    callbackThread.set(Thread.currentThread());
                    callbackRan.countDown();
                });
                armed.countDown();
            });

            actor.send("arm");
            assertTrue(armed.await(2, TimeUnit.SECONDS));
            assertFalse(futureSurface.get().isDone());
            assertFalse(callbackRegistration.get().isDone());

            assertEquals("future", futureChannel.readAsync().get(2, TimeUnit.SECONDS));
            futureSurface.get().get(2, TimeUnit.SECONDS);

            Thread completingReader = Thread.currentThread();
            assertEquals("callback", callbackChannel.readAsync().get(2, TimeUnit.SECONDS));
            callbackRegistration.get().get(2, TimeUnit.SECONDS);
            assertTrue(callbackRan.await(2, TimeUnit.SECONDS));
            assertNotSame(completingReader, callbackThread.get(),
                    "cb lowering must enqueue an actor continuation, never inline guest code");

            actor.stop();
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void eventBusChannelFutureReentersSubscriberActorThroughMailboxContinuation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            group.events().defineTopic("proof", ActorEventBus.DeliveryPolicy.RELIABLE, 1);

            CountDownLatch armed = new CountDownLatch(1);
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<Thread> publisher = new AtomicReference<>();
            AtomicReference<Thread> callback = new AtomicReference<>();
            AtomicReference<String> value = new AtomicReference<>();

            ActorRuntime.ActorRef<String> subscriber = runtime.spawnShared(() -> (message, context) -> {
                if (!message.equals("subscribe")) return;
                join.join();
                ActorEventBus.Subscription<String> subscription =
                        group.events().subscribe("proof");
                OresFuture<ActorEventBus.Event<String>> pending = subscription.readAsync();
                ActorRuntime.ContinuationTarget target =
                        context.runtime().captureCurrentContinuationTarget();
                context.runtime().enqueueOnCompletion(pending, target, (event, failure) -> {
                    assertNull(failure);
                    assertTrue(ActorRuntime.inActorExecution());
                    assertEquals(context.self().id(), ActorRuntime.currentActorId().orElseThrow());
                    callback.set(Thread.currentThread());
                    value.set(event.value());
                    delivered.countDown();
                });
                armed.countDown();
            });

            subscriber.send("subscribe");
            assertTrue(armed.await(2, TimeUnit.SECONDS));

            Thread producer = Thread.ofPlatform().name("proof-event-publisher").start(() -> {
                publisher.set(Thread.currentThread());
                ActorEventBus.PublishReceipt receipt =
                        group.events().publishSystem("proof", "event");
                receipt.completion().join();
            });
            producer.join();

            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals("event", value.get());
            assertNotSame(publisher.get(), callback.get(),
                    "event publication must not execute subscriber guest code inline");

            subscriber.stop();
            assertTrue(subscriber.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void sharedAndPrivateActorsProgressOnSeparateBulkheadedDispatchers() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            CountDownLatch done = new CountDownLatch(2);
            AtomicReference<Thread> sharedThread = new AtomicReference<>();
            AtomicReference<Thread> privateThread = new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorKind> sharedKind = new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorKind> privateKind = new AtomicReference<>();

            ActorRuntime.ActorRef<String> shared = runtime.spawnShared(() -> (message, context) -> {
                assertTrue(ActorRuntime.isActorCarrierThread());
                sharedThread.set(Thread.currentThread());
                sharedKind.set(context.kind());
                done.countDown();
                context.self().stop();
            });
            ActorRuntime.ActorRef<String> isolated = runtime.spawnPrivate(() -> (message, context) -> {
                assertTrue(ActorRuntime.isActorCarrierThread());
                privateThread.set(Thread.currentThread());
                privateKind.set(context.kind());
                done.countDown();
                context.self().stop();
            });

            shared.send("go");
            isolated.send("go");

            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(ActorRuntime.ActorKind.SHARED, sharedKind.get());
            assertEquals(ActorRuntime.ActorKind.PRIVATE, privateKind.get());
            assertNotSame(sharedThread.get(), privateThread.get(),
                    "shared/private actors must be bulkheaded onto separate dispatcher carriers");
        }
    }

    @Test
    void cancellingActorDetachesOutstandingChannelContinuation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(1);
            CountDownLatch armed = new CountDownLatch(1);
            AtomicReference<OresFuture<String>> pending = new AtomicReference<>();
            AtomicBoolean callbackRan = new AtomicBoolean();

            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                OresFuture<String> read = channel.readAsync();
                pending.set(read);
                ActorRuntime.ContinuationTarget target =
                        context.runtime().captureCurrentContinuationTarget();
                context.runtime().enqueueOnCompletion(read, target, (value, failure) ->
                        callbackRan.set(true));
                armed.countDown();
            });

            actor.send("arm");
            assertTrue(armed.await(2, TimeUnit.SECONDS));
            assertTrue(actor.cancel());
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(pending.get().isDone(), "actor teardown must detach/cancel pending waits");

            channel.tryWrite("late");
            Thread.sleep(50);
            assertFalse(callbackRan.get(), "late channel completion must not resurrect a cancelled actor");
        }
    }

    @Test
    void hungryActorDedicatedCarrierDoesNotStarveOrdinaryActorPool() throws Exception {
        CountDownLatch hungryStarted = new CountDownLatch(1);
        AtomicBoolean releaseCpu = new AtomicBoolean();

        try (HungryActor<String> hungry = new HungryActor<>(
                    "proof-hot-loop",
                    2,
                    (message, context) -> {
                        hungryStarted.countDown();
                        while (!releaseCpu.get()) {
                            context.schedulerSafepoint();
                        }
                        context.release();
                    });
             ActorRuntime runtime = new ActorRuntime(
                    IsolatePolicy.developer(),
                    new ActorRuntime.DispatcherConfig(1, 1, 8))) {

            hungry.send("spin");
            assertTrue(hungryStarted.await(2, TimeUnit.SECONDS));

            CountDownLatch ordinaryRan = new CountDownLatch(1);
            ActorRuntime.ActorRef<String> ordinary = runtime.spawnPrivate(() -> (message, context) -> {
                ordinaryRan.countDown();
                context.self().stop();
            });
            ordinary.send("ping");

            assertTrue(ordinaryRan.await(2, TimeUnit.SECONDS),
                    "dedicated hungry actor must not consume the ordinary actor dispatcher");
            assertTrue(hungry.isNativeCarrier());
            releaseCpu.set(true);
            assertTrue(hungry.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
