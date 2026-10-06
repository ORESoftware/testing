package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
