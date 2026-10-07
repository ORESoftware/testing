package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ConcurrencyContractProofTest {
    @Test
    void actorMailboxIsPhysicallyBackedByChannelRuntimeTransport() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> { });
            assertTrue(runtime.mailboxUsesChannelTransport(actor));
            actor.stop();
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void nbReadRegistrationAndImmediateTryProbeRemainDistinct() {
        ChannelRuntime.Channel<Integer> channel =
                new ChannelRuntime.Channel<>(1);

        OresFuture<Integer> pending = channel.readAsync();
        assertFalse(pending.isDone());

        assertTrue(channel.tryRead().isEmpty());

        assertTrue(pending.cancel(false));
        assertTrue(channel.tryWrite(7));
        assertEquals(7, channel.tryRead().orElseThrow());
    }

    @Test
    void nbWriteFutureRegistersWithoutBlockingAndCancellationWithdrawsIt() {
        ChannelRuntime.Channel<String> channel =
                new ChannelRuntime.Channel<>(1);

        assertTrue(channel.tryWrite("occupied"));
        OresFuture<Void> pending = channel.writeAsync("later");
        assertFalse(pending.isDone());

        assertFalse(channel.tryWrite("probe"));
        assertTrue(pending.cancel(false));

        assertEquals("occupied", channel.tryRead().orElseThrow());
        assertTrue(channel.tryWrite("after-cancel"));
        assertEquals("after-cancel", channel.tryRead().orElseThrow());
    }


    @Test
    void runtimeCoordinationHandlesCannotCrossActorMailbox() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.ActorRef<Object> receiver =
                    runtime.spawnShared(() -> (message, context) -> { });
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            runtime.setForceCancellationTreeHook(request -> true);
            ActorRuntime.ActorControlHandle control =
                    runtime.controlHandle(receiver);

            assertThrows(SecurityException.class, () -> receiver.send(group));
            assertThrows(SecurityException.class, () -> receiver.send(control));

            ChannelRuntime.SelectSet set =
                    ChannelRuntime.SelectSet.of(
                            ChannelRuntime.read(new ChannelRuntime.Channel<>(1)));
            assertThrows(SecurityException.class, () -> receiver.send(set));

            receiver.stop();
            group.close();
        }
    }

    @Test
    void futureWriteCompletionCallbackReturnsThroughActorMailboxNotProducerThread()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ChannelRuntime.Channel<String> channel =
                    new ChannelRuntime.Channel<>(0);
            CountDownLatch registered = new CountDownLatch(1);
            CountDownLatch callbackRan = new CountDownLatch(1);
            AtomicReference<Thread> producerThread = new AtomicReference<>();
            AtomicReference<Thread> callbackThread = new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> {
                        if (!"register".equals(message)) return;

                        OresFuture<Void> completion =
                                channel.writeAsync("payload");
                        ActorRuntime.ContinuationTarget target =
                                context.runtime()
                                        .captureCurrentContinuationTarget();

                        context.runtime().enqueueOnCompletion(
                                completion,
                                target,
                                (value, failure) -> {
                                    assertTrue(failure == null);
                                    callbackThread.set(Thread.currentThread());
                                    callbackRan.countDown();
                                    context.self().stop();
                                });
                        registered.countDown();
                    });

            actor.send("register");
            assertTrue(registered.await(2, TimeUnit.SECONDS));

            Thread producer =
                    Thread.ofPlatform()
                            .start(() -> {
                                producerThread.set(Thread.currentThread());
                                channel.readAsync().join();
                            });
            producer.join();

            assertTrue(callbackRan.await(2, TimeUnit.SECONDS));
            assertNotSame(
                    producerThread.get(),
                    callbackThread.get(),
                    "future completion must only enqueue actor continuation");
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }
    @Test
    void pendingActorBlockingWaitUsesVirtualImplementationThread()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ChannelRuntime.Channel<Integer> gate =
                    new ChannelRuntime.Channel<>(0);
            CountDownLatch blocked = new CountDownLatch(1);
            CountDownLatch siblingRan = new CountDownLatch(1);
            AtomicReference<Boolean> virtual = new AtomicReference<>();
            AtomicReference<Boolean> blockingImpl =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<String> waiter =
                    runtime.spawnShared(() -> (message, context) -> {
                        if (!"block".equals(message)) return;
                        virtual.set(Thread.currentThread().isVirtual());
                        blockingImpl.set(
                                ActorRuntime.isActorBlockingImplementationThread());
                        blocked.countDown();
                        assertEquals(
                                9,
                                gate.readAsync().join(),
                                "the actor may park only on its virtual implementation turn");
                        context.self().stop();
                    });

            ActorRuntime.ActorRef<String> sibling =
                    runtime.spawnShared(() -> (message, context) -> {
                        if (!"ping".equals(message)) return;
                        siblingRan.countDown();
                        context.self().stop();
                    });

            waiter.send("block");
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            sibling.send("ping");

            assertTrue(
                    siblingRan.await(2, TimeUnit.SECONDS),
                    "a blocked actor must not consume the bounded shared dispatcher carrier");
            assertEquals(Boolean.TRUE, virtual.get());
            assertEquals(Boolean.TRUE, blockingImpl.get());

            assertTrue(
                    gate.tryWrite(9),
                    "rendezvous write should release the parked actor");
            assertTrue(waiter.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(sibling.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void pendingActorAwaitUsesSameVirtualImplementationBoundary()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            java.util.concurrent.CompletableFuture<Integer> gate =
                    new java.util.concurrent.CompletableFuture<>();
            CountDownLatch blocked = new CountDownLatch(1);
            CountDownLatch siblingRan = new CountDownLatch(1);
            AtomicReference<Boolean> blockingImpl =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<String> waiter =
                    runtime.spawnShared(() -> (message, context) -> {
                        if (!"block".equals(message)) return;
                        blockingImpl.set(
                                ActorRuntime.isActorBlockingImplementationThread());
                        blocked.countDown();
                        assertEquals(13, AsyncRuntime.await(gate));
                        context.self().stop();
                    });

            ActorRuntime.ActorRef<String> sibling =
                    runtime.spawnShared(() -> (message, context) -> {
                        if (!"ping".equals(message)) return;
                        siblingRan.countDown();
                        context.self().stop();
                    });

            waiter.send("block");
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            sibling.send("ping");
            assertTrue(siblingRan.await(2, TimeUnit.SECONDS));
            assertEquals(Boolean.TRUE, blockingImpl.get());

            gate.complete(13);
            assertTrue(waiter.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(sibling.awaitTermination(2, TimeUnit.SECONDS));
        }
    }


}
