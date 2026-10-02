package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorRuntimeTest {
    @Test
    void actorCannotUseClosureCapturedForeignRuntimeSendOrSpawn() throws Exception {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> sendFailure = new AtomicReference<>();
            AtomicReference<Throwable> spawnFailure = new AtomicReference<>();

            var target = runtimeA.<String>spawn(() -> (message, context) -> { });
            var caller = runtimeB.<String>spawn(() -> (message, context) -> {
                try {
                    runtimeA.send(target, "cross-runtime");
                } catch (Throwable problem) {
                    sendFailure.set(problem);
                }
                try {
                    runtimeA.<String>spawn(() -> (childMessage, childContext) -> { });
                } catch (Throwable problem) {
                    spawnFailure.set(problem);
                } finally {
                    checked.countDown();
                }
            });

            caller.send("go");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, sendFailure.get());
            assertInstanceOf(SecurityException.class, spawnFailure.get());
        }
    }

    @Test
    void actorCannotInvokeClosureCapturedForeignActorRef() throws Exception {
        try (ActorRuntime runtimeA = new ActorRuntime();
             ActorRuntime runtimeB = new ActorRuntime()) {
            CountDownLatch targetReceived = new CountDownLatch(1);
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            var target = runtimeA.<String>spawn(() -> (message, context) -> targetReceived.countDown());
            var foreignCaller = runtimeB.<String>spawn(() -> (message, context) -> {
                try {
                    target.send("cross-runtime");
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    checked.countDown();
                }
            });

            foreignCaller.send("go");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, failure.get());
            assertEquals(1L, targetReceived.getCount());
        }
    }

    @Test
    void freezesMessagesBeforeDelivery() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<List<?>> observed = new AtomicReference<>();
            var ref = runtime.<List<Integer>>spawn(() -> (message, context) -> {
                observed.set(message);
                received.countDown();
            });

            ArrayList<Integer> mutable = new ArrayList<>(List.of(1, 2));
            ref.send(mutable);
            mutable.add(3);

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), observed.get());
            assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) observed.get()).add(9));
        }
    }

    @Test
    void rejectsUnknownMutableHostObjects() {
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(new StringBuilder("mutable")));
    }

    @Test
    void actorCarriesItsOwnStricterPolicy() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<IsolatePolicy> observed = new AtomicReference<>();
            IsolatePolicy strict = IsolatePolicy.strictFaas();

            var ref = runtime.<String>spawn(strict, () -> (message, context) -> {
                observed.set(context.policy());
                received.countDown();
            });
            ref.send("ping");

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(strict.capabilities(), observed.get().capabilities());
            assertEquals(strict.maxMailboxMessages(), observed.get().maxMailboxMessages());
        }
    }

    @Test
    void childActorCannotEscalatePastRuntimePolicyCeiling() {
        IsolatePolicy ceiling = IsolatePolicy.strictFaas();
        try (ActorRuntime runtime = new ActorRuntime(ceiling)) {
            IsolatePolicy escalated = ceiling.withCapabilities(IsolatePolicy.Capability.PROCESS_INFO);
            assertThrows(SecurityException.class, () ->
                    runtime.<String>spawn(escalated, () -> (message, context) -> { }));
        }
    }

    @Test
    void readonlySharingDeepFreezesContainers() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var shared = runtime.shareReadonly(Map.of("items", List.of(1, 2, 3)));
            assertNotNull(shared.value());
        }
    }

    @Test
    void actorRuntimeEnforcesConfiguredActorCeiling() {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), 1)) {
            runtime.<String>spawn(() -> (message, context) -> { });
            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.<String>spawn(() -> (message, context) -> { }));
            assertTrue(error.getMessage().contains("actor runtime limit exceeded"));
            assertEquals(1, runtime.maxActors());
        }
    }

    @Test
    void actorCodeCannotCloseItsOwnRuntime() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            var ref = runtime.<String>spawn(() -> (message, context) -> {
                try {
                    assertThrows(SecurityException.class, context.runtime()::close);
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    checked.countDown();
                }
            });

            ref.send("check");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
        }
    }

    @Test
    void fullMailboxRejectsBeforeFreezingAnotherMessage() throws Exception {
        IsolatePolicy oneQueuedMessage = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                IsolatePolicy.developer().maxHeapBytes(),
                1,
                IsolatePolicy.developer().maxWallTime(),
                false);

        try (ActorRuntime runtime = new ActorRuntime(oneQueuedMessage)) {
            CountDownLatch processing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            var ref = runtime.<Object>spawn(oneQueuedMessage, () -> (message, context) -> {
                if ("first".equals(message)) {
                    processing.countDown();
                    release.await();
                }
            });

            ref.send("first");
            assertTrue(processing.await(2, TimeUnit.SECONDS));
            ref.send("queued");

            ActorRuntime.Sendable shouldNotFreeze = () -> {
                throw new AssertionError("freezeForSend must not run when mailbox is already full");
            };

            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> ref.send(shouldNotFreeze));
            assertTrue(error.getMessage().contains("mailbox limit exceeded"));

            release.countDown();
        }
    }


    @Test
    void strictRuntimeCannotCreateReadonlyShareFromHost() {
        IsolatePolicy strict = IsolatePolicy.strictFaas();
        try (ActorRuntime runtime = new ActorRuntime(strict)) {
            SecurityException error = assertThrows(
                    SecurityException.class,
                    () -> runtime.shareReadonly(List.of(1, 2, 3)));
            assertTrue(error.getMessage().contains("ACTOR_SHARE_READONLY"));
        }
    }

    @Test
    void strictActorCannotBypassReadonlyCapabilityThroughRuntimeHandle() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IsolatePolicy strict = IsolatePolicy.strictFaas();
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();

            var ref = runtime.<String>spawn(strict, () -> (message, context) -> {
                try {
                    context.runtime().shareReadonly(List.of("secret"));
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    checked.countDown();
                }
            });

            ref.send("check");
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, observed.get());
        }
    }

    @Test
    void closeStopsActorEvenWhenBehaviorClearsInterruptBeforeReturning() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cleared = new CountDownLatch(1);

        var ref = runtime.<String>spawn(() -> (message, context) -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException interrupted) {
                // Deliberately consume/clear the interrupt. Runtime shutdown
                // must still be observed by the mailbox-turn lifecycle.
                Thread.interrupted();
                cleared.countDown();
            }
        });

        ref.send("block");
        assertTrue(started.await(2, TimeUnit.SECONDS));

        assertDoesNotThrow(runtime::close);
        assertTrue(cleared.await(1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> ref.send("after-close"));
    }


    @Test
    void concurrentSendersReserveMailboxBeforeFreezing() throws Exception {
        IsolatePolicy oneQueuedMessage = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                IsolatePolicy.developer().maxHeapBytes(),
                1,
                IsolatePolicy.developer().maxWallTime(),
                false);

        try (ActorRuntime runtime = new ActorRuntime(oneQueuedMessage)) {
            CountDownLatch processing = new CountDownLatch(1);
            CountDownLatch releaseActor = new CountDownLatch(1);
            CountDownLatch firstFreezeEntered = new CountDownLatch(1);
            CountDownLatch releaseFirstFreeze = new CountDownLatch(1);
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();
            AtomicReference<Boolean> secondFreezeRan = new AtomicReference<>(false);

            var ref = runtime.<Object>spawn(oneQueuedMessage, () -> (message, context) -> {
                if ("processing".equals(message)) {
                    processing.countDown();
                    releaseActor.await();
                }
            });

            ref.send("processing");
            assertTrue(processing.await(2, TimeUnit.SECONDS));

            ActorRuntime.Sendable first = () -> {
                firstFreezeEntered.countDown();
                try {
                    if (!releaseFirstFreeze.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to finish first freeze");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException();
                }
                return "first-queued";
            };

            Thread sender = Thread.ofPlatform().start(() -> {
                try {
                    ref.send(first);
                } catch (Throwable failure) {
                    firstFailure.set(failure);
                }
            });

            assertTrue(firstFreezeEntered.await(2, TimeUnit.SECONDS));

            ActorRuntime.Sendable second = () -> {
                secondFreezeRan.set(true);
                return "second-queued";
            };

            IllegalStateException rejected = assertThrows(
                    IllegalStateException.class,
                    () -> ref.send(second));
            assertTrue(rejected.getMessage().contains("mailbox limit exceeded"));
            assertFalse(secondFreezeRan.get());

            releaseFirstFreeze.countDown();
            sender.join();
            assertNull(firstFailure.get());

            releaseActor.countDown();
        }
    }


    @Test
    void sendFailsIfActorTerminatesWhileMessageIsBeingFrozen() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch enteredBehavior = new CountDownLatch(1);
            CountDownLatch allowFailure = new CountDownLatch(1);
            CountDownLatch freezeEntered = new CountDownLatch(1);
            CountDownLatch releaseFreeze = new CountDownLatch(1);
            AtomicReference<Throwable> senderFailure = new AtomicReference<>();

            var ref = runtime.<Object>spawn(() -> (message, context) -> {
                if ("die".equals(message)) {
                    enteredBehavior.countDown();
                    allowFailure.await();
                    throw new IllegalStateException("intentional actor failure");
                }
            });

            ref.send("die");
            assertTrue(enteredBehavior.await(2, TimeUnit.SECONDS));

            ActorRuntime.Sendable blocking = () -> {
                freezeEntered.countDown();
                try {
                    if (!releaseFreeze.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release freeze");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException();
                }
                return "late-message";
            };

            Thread sender = Thread.ofPlatform().start(() -> {
                try {
                    ref.send(blocking);
                } catch (Throwable failure) {
                    senderFailure.set(failure);
                }
            });

            assertTrue(freezeEntered.await(2, TimeUnit.SECONDS));
            allowFailure.countDown();

            IllegalStateException unknown = null;
            for (int i = 0; i < 500 && unknown == null; i++) {
                try {
                    ref.send("probe");
                    Thread.yield();
                } catch (IllegalStateException failure) {
                    if (failure.getMessage().contains("unknown actor")) unknown = failure;
                }
            }
            assertNotNull(unknown, "actor should have terminated and left the registry");

            releaseFreeze.countDown();
            sender.join();

            assertInstanceOf(IllegalStateException.class, senderFailure.get());
            assertTrue(senderFailure.get().getMessage().contains("terminated before message admission"));
        }
    }

    @Test
    void closeCanBeRetriedAfterInitialTerminationTimeout() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        var ref = runtime.<String>spawn(() -> (message, context) -> {
            started.countDown();
            while (true) {
                try {
                    release.await();
                    return;
                } catch (InterruptedException ignored) {
                    // Deliberately ignore the first shutdown interrupt so the
                    // supervisor's bounded close wait expires.
                }
            }
        });

        ref.send("block");
        assertTrue(started.await(2, TimeUnit.SECONDS));

        IllegalStateException timedOut = assertThrows(IllegalStateException.class, runtime::close);
        assertTrue(timedOut.getMessage().contains("did not observe full actor termination"));

        release.countDown();
        assertDoesNotThrow(runtime::close);
        assertThrows(IllegalStateException.class, () -> ref.send("after-close"));
    }

}
