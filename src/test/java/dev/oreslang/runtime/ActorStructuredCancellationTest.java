package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ActorStructuredCancellationTest {
    @Test
    void actorSpawnedFromActorTurnIsAChildAndParentStopCancelsIt() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch childCreated = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorRef<String>> childRef = new AtomicReference<>();

            ActorRuntime.ActorRef<String> parent = runtime.spawnShared(() -> (message, context) -> {
                ActorRuntime.ActorRef<String> child =
                        context.runtime().spawnPrivate(
                                factoryContext -> (childMessage, childContext) -> { });
                childRef.set(child);
                childCreated.countDown();

                assertEquals(context.self().id(), child.parentId().orElseThrow());
                assertTrue(context.self().childIds().contains(child.id()));
                context.self().stop();
            });

            parent.send("spawn");
            assertTrue(childCreated.await(2, TimeUnit.SECONDS));

            ActorRuntime.ActorRef<String> child = childRef.get();
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(child.awaitTermination(2, TimeUnit.SECONDS));
            assertFalse(parent.isAlive());
            assertFalse(child.isAlive());
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    child.failure().orElseThrow());
        }
    }

    @Test
    void childActorRefDoesNotGrantAuthorityToCancelParent() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch denied = new CountDownLatch(1);

            ActorRuntime.ActorRef<Object> parent =
                    runtime.spawnShared(() -> (message, context) -> {
                        if ("spawn-child".equals(message)) {
                            ActorRuntime.ActorRef<Object> child =
                                    context.runtime().spawnShared(
                                            factoryContext -> (childMessage, childContext) -> {
                                                if (childMessage instanceof ActorRuntime.ActorRef<?> parentRef) {
                                                    try {
                                                        parentRef.cancel();
                                                    } catch (SecurityException expected) {
                                                        @SuppressWarnings("unchecked")
                                                        ActorRuntime.ActorRef<Object> reply =
                                                                (ActorRuntime.ActorRef<Object>) parentRef;
                                                        reply.send("denied");
                                                        childContext.self().stop();
                                                    }
                                                }
                                            });
                            child.send(context.self());
                            return;
                        }

                        if ("denied".equals(message)) {
                            denied.countDown();
                            context.self().stop();
                        }
                    });

            parent.send("spawn-child");

            assertTrue(denied.await(2, TimeUnit.SECONDS));
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(parent.failure().isEmpty());
        }
    }

    @Test
    void parentMayCancelStructuredChildWithoutBlockingCarrier() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch requested = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorRef<String>> childRef =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<String> parent =
                    runtime.spawnShared(() -> (message, context) -> {
                        ActorRuntime.ActorRef<String> child =
                                context.runtime().spawnPrivate(
                                        factoryContext -> (childMessage, childContext) -> { });
                        childRef.set(child);
                        assertTrue(child.cancel());
                        requested.countDown();
                        context.self().stop();
                    });

            parent.send("cancel-child");

            assertTrue(requested.await(2, TimeUnit.SECONDS));
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(childRef.get().awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    childRef.get().failure().orElseThrow());
        }
    }

    @Test
    void explicitCancellationCascadesThroughChildTree() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch childCreated = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorRef<String>> childRef = new AtomicReference<>();

            ActorRuntime.ActorRef<String> parent = runtime.spawnShared(() -> (message, context) -> {
                ActorRuntime.ActorRef<String> child =
                        context.runtime().spawnPrivate(
                                factoryContext -> (childMessage, childContext) -> { });
                childRef.set(child);
                childCreated.countDown();
            });

            parent.send("spawn");
            assertTrue(childCreated.await(2, TimeUnit.SECONDS));

            assertTrue(parent.cancel());
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(childRef.get().awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    parent.failure().orElseThrow());
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    childRef.get().failure().orElseThrow());
        }
    }

    @Test
    void forceCancellationFailsClosedWithoutIsolationAuthority() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnPrivate(() -> (message, context) -> { });

            assertThrows(IllegalStateException.class, actor::forceCancel);
            assertTrue(actor.isAlive(), "denied force cancellation must have no logical side effect");
            actor.stop();
        }
    }

    @Test
    void structuredCancellationCannotBeSwallowedAsOrdinaryRuntimeFailure() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            AtomicBoolean swallowed = new AtomicBoolean();

            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(() -> (message, context) -> {
                entered.countDown();
                try {
                    while (true) {
                        context.runtime().schedulerSafepoint();
                    }
                } catch (RuntimeException ordinaryFailure) {
                    swallowed.set(true);
                }
            });

            actor.send("run");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(actor.cancel());
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertFalse(swallowed.get(), "actor cancellation is control flow, not a catchable guest failure");
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    actor.failure().orElseThrow());
        }
    }

    @Test
    void runtimeRejectsLocalChannelCapabilityAsMessagePayload() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Object> actor =
                    runtime.spawnShared(() -> (message, context) -> { });
            ChannelRuntime.Channel<String> localChannel =
                    new ChannelRuntime.Channel<>(1);

            IllegalArgumentException rejected = assertThrows(
                    IllegalArgumentException.class,
                    () -> actor.send(localChannel));
            assertTrue(rejected.getMessage().contains("execution-domain local"));
            actor.stop();
        }
    }

    @Test
    void actorTeardownCancelsOwnedNonBlockingChannelRegistration() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ChannelRuntime.Channel<String> channel =
                    new ChannelRuntime.Channel<>(0);
            AtomicReference<OresFuture<String>> pending = new AtomicReference<>();
            CountDownLatch registered = new CountDownLatch(1);

            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> {
                        OresFuture<String> read = context.runtime()
                                .ownCurrentActorFuture(channel.readAsync());
                        pending.set(read);
                        registered.countDown();
                        context.self().stop();
                    });

            actor.send("register");
            assertTrue(registered.await(2, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));

            OresFuture<String> read = pending.get();
            assertTrue(
                    read.isCancelled(),
                    "actor teardown must cancel an abandoned nb channel waiter");
            assertFalse(
                    channel.tryWrite("orphan"),
                    "dead actor's cancelled read must not remain registered");
        }
    }

    @Test
    void continuationHeadroomDoesNotReduceConfiguredUserMailboxCapacity() throws Exception {
        IsolatePolicy base = IsolatePolicy.developer();
        IsolatePolicy tinyMailbox = new IsolatePolicy(
                base.capabilities(),
                base.maxHeapBytes(),
                2,
                Duration.ofSeconds(5),
                false);

        try (ActorRuntime runtime = new ActorRuntime(base)) {
            CountDownLatch userMessagesAdmitted = new CountDownLatch(1);
            CountDownLatch userMessagesDelivered = new CountDownLatch(2);
            AtomicInteger delivered = new AtomicInteger();

            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(tinyMailbox, () -> (message, context) -> {
                        if (message.equals("seed")) {
                            ActorRuntime.ContinuationTarget target =
                                    context.runtime().captureCurrentContinuationTarget();

                            // These complete immediately but their continuations
                            // must queue behind the current actor turn.
                            for (int i = 0; i < 3; i++) {
                                context.runtime().enqueueOnCompletion(
                                        OresFuture.completed(i),
                                        target,
                                        (value, failure) -> { });
                            }

                            // User capacity remains exactly maxMailboxMessages=2
                            // despite the already-queued runtime continuations.
                            context.self().send("u1");
                            context.self().send("u2");
                            userMessagesAdmitted.countDown();
                            return;
                        }

                        if (message.equals("u1") || message.equals("u2")) {
                            delivered.incrementAndGet();
                            userMessagesDelivered.countDown();
                            if (delivered.get() == 2) context.self().stop();
                        }
                    });

            actor.send("seed");

            assertTrue(
                    userMessagesAdmitted.await(2, TimeUnit.SECONDS),
                    "runtime continuations must not steal user mailbox quota");
            assertTrue(userMessagesDelivered.await(2, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void carrierInterruptIsNotActorCancellationIdentity() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch continuedAfterSafepoint = new CountDownLatch(1);

            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> {
                        Thread.currentThread().interrupt();
                        try {
                            context.runtime().schedulerSafepoint();
                            continuedAfterSafepoint.countDown();
                        } finally {
                            // Do not leak this host-side test interrupt into a
                            // pooled carrier after the actor turn completes.
                            Thread.interrupted();
                            context.self().stop();
                        }
                    });

            actor.send("interrupt-carrier");

            assertTrue(
                    continuedAfterSafepoint.await(2, TimeUnit.SECONDS),
                    "carrier interrupt must not be interpreted as actor cancellation");
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(
                    actor.failure().isEmpty(),
                    "carrier interrupt must not record an actor failure/cancellation");
        }
    }

    @Test
    void structuredChildHandleIsParentBoundAndCanSendAndCancel()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch created = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorHandle<String>> handleRef =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<String> parent =
                    runtime.spawnShared(() -> (message, context) -> {
                        if ("spawn".equals(message)) {
                            handleRef.set(context.runtime().spawnChildShared(
                                    factoryContext ->
                                            (childMessage, childContext) -> {
                                                if ("stop".equals(childMessage)) {
                                                    childContext.self().stop();
                                                }
                                            }));
                            created.countDown();
                            return;
                        }
                        if ("send".equals(message)) {
                            handleRef.get().send("stop");
                        }
                    });

            parent.send("spawn");
            assertTrue(created.await(2, TimeUnit.SECONDS));

            ActorRuntime.ActorHandle<String> handle = handleRef.get();
            assertThrows(
                    SecurityException.class,
                    handle::cancel,
                    "child lifecycle handle must remain bound to its parent actor");

            parent.send("send");
            assertTrue(handle.awaitTermination(2, TimeUnit.SECONDS));
            parent.stop();
        }
    }

    @Test
    void parentHandleCanCancelDirectChild() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch childCreated = new CountDownLatch(1);
            CountDownLatch parentIssuedCancel = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorHandle<String>> childHandle =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<String> parent =
                    runtime.spawnShared(() -> (message, context) -> {
                        if ("spawn".equals(message)) {
                            childHandle.set(context.runtime().spawnChildShared(
                                    factoryContext ->
                                            (childMessage, childContext) -> { }));
                            childCreated.countDown();
                            return;
                        }
                        if ("cancel".equals(message)) {
                            assertTrue(childHandle.get().cancel());
                            parentIssuedCancel.countDown();
                            context.self().stop();
                        }
                    });

            parent.send("spawn");
            assertTrue(childCreated.await(2, TimeUnit.SECONDS));
            parent.send("cancel");

            assertTrue(parentIssuedCancel.await(2, TimeUnit.SECONDS));
            assertTrue(childHandle.get().awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    childHandle.get().failure().orElseThrow());
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(parent.failure().isEmpty());
        }
    }

    @Test
    void lifecycleHandleCannotCrossActorMailbox() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Object> receiver =
                    runtime.spawnShared(() -> (message, context) -> { });

            ActorRuntime.ActorRef<String> parent =
                    runtime.spawnShared(() -> (message, context) -> {
                        ActorRuntime.ActorHandle<String> child =
                                context.runtime().spawnChildShared(
                                        factoryContext ->
                                                (childMessage, childContext) -> { });
                        receiver.send(child);
                    });

            parent.send("leak");
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    SecurityException.class,
                    parent.failure().orElseThrow());
            receiver.stop();
        }
    }

    @Test
    void nestedLifecycleAuthorityCannotCrossMailbox() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Object> receiver =
                    runtime.spawnShared(() -> (message, context) -> { });
            ActorRuntime.ActorRef<String> controlled =
                    runtime.spawnShared(() -> (message, context) -> { });
            ActorRuntime.ActorControlHandle control =
                    runtime.controlHandle(controlled);

            assertThrows(
                    SecurityException.class,
                    () -> receiver.send(java.util.List.of(
                            java.util.Map.of("control", control))));

            receiver.stop();
            controlled.stop();
        }
    }


    @Test
    void hostControlHandleIsUnavailableToActorCode() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) ->
                            context.runtime().controlHandle(context.self().id()));

            actor.send("control");
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    SecurityException.class,
                    actor.failure().orElseThrow());
        }
    }

    @Test
    void hostControlHandleCanCancelActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> { });

            ActorRuntime.ActorControlHandle control =
                    runtime.controlHandle(actor);

            assertTrue(control.cancel());
            assertTrue(control.awaitTermination(2, TimeUnit.SECONDS));
            assertInstanceOf(
                    ActorRuntime.ActorCancelledException.class,
                    control.failure().orElseThrow());
        }
    }


    @Test
    void parentHandleCanForceKillDirectChildThroughHostRevoker()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorRuntime.ForceCancellationRequest> observed =
                    new AtomicReference<>();
            runtime.setForceCancellationTreeHook(request -> {
                observed.set(request);
                return true;
            });

            CountDownLatch killed = new CountDownLatch(1);
            ActorRuntime.ActorRef<String> parent =
                    runtime.spawnShared(() -> (message, context) -> {
                        ActorRuntime.ActorHandle<String> child =
                                context.runtime().spawnChildShared(
                                        factoryContext ->
                                                (childMessage, childContext) -> { });
                        assertTrue(child.kill());
                        killed.countDown();
                        context.self().stop();
                    });

            parent.send("kill-child");
            assertTrue(killed.await(2, TimeUnit.SECONDS));
            assertEquals(1, observed.get().targets().size());
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(parent.failure().isEmpty());
        }
    }

    @Test
    void forceKillRequiresAtomicRevocationOfCompleteStructuredSubtree()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch spawned = new CountDownLatch(1);

            ActorRuntime.ActorRef<String> root =
                    runtime.spawnShared(() -> (message, context) -> {
                        ActorRuntime.ActorHandle<String> child =
                                context.runtime().spawnChildShared(
                                        factoryContext ->
                                                (childMessage, childContext) -> {
                                                    childContext.runtime()
                                                            .spawnChildShared(
                                                                    grandFactoryContext ->
                                                                            (grandMessage,
                                                                             grandContext) -> { });
                                                });
                        child.send("spawn-grandchild");
                        spawned.countDown();
                    });

            root.send("spawn-tree");
            assertTrue(spawned.await(2, TimeUnit.SECONDS));

            ActorRuntime.ActorControlHandle rootControl =
                    runtime.controlHandle(root);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (rootControl.childIds().isEmpty()
                    && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(1, rootControl.childIds().size());

            ActorRuntime.ActorControlHandle childControl =
                    runtime.controlHandle(rootControl.childIds().get(0));
            while (childControl.childIds().isEmpty()
                    && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(1, childControl.childIds().size());

            runtime.setForceCancellationHook(
                    (actorId, executionDomain) -> true);
            assertThrows(
                    IllegalStateException.class,
                    rootControl::kill,
                    "leaf-only force hook must fail closed for a subtree");
            assertTrue(rootControl.isAlive());

            AtomicReference<ActorRuntime.ForceCancellationRequest> observed =
                    new AtomicReference<>();
            runtime.setForceCancellationTreeHook(request -> {
                observed.set(request);
                return true;
            });

            assertTrue(rootControl.kill());
            assertEquals(3, observed.get().targets().size());
            assertTrue(rootControl.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void parentActorCannotSynchronouslyBlockOnChildTermination() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch denied = new CountDownLatch(1);

            ActorRuntime.ActorRef<String> parent =
                    runtime.spawnShared(() -> (message, context) -> {
                        ActorRuntime.ActorHandle<String> child =
                                context.runtime().spawnChildShared(
                                        factoryContext ->
                                                (childMessage, childContext) -> { });
                        try {
                            child.awaitTermination(1, TimeUnit.MILLISECONDS);
                        } catch (SecurityException expected) {
                            denied.countDown();
                        }
                        child.cancel();
                        context.self().stop();
                    });

            parent.send("run");
            assertTrue(
                    denied.await(2, TimeUnit.SECONDS),
                    "actor carriers must never block synchronously waiting for a child");
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(parent.failure().isEmpty());
        }
    }

    @Test
    void rawChildActorRefCannotBypassParentHandleForHardKill() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicBoolean revokerInvoked = new AtomicBoolean();
            runtime.setForceCancellationTreeHook(request -> {
                revokerInvoked.set(true);
                return true;
            });
            CountDownLatch denied = new CountDownLatch(1);

            ActorRuntime.ActorRef<String> parent =
                    runtime.spawnShared(() -> (message, context) -> {
                        ActorRuntime.ActorHandle<String> child =
                                context.runtime().spawnChildShared(
                                        factoryContext ->
                                                (childMessage, childContext) -> { });

                        try {
                            child.ref().forceCancel();
                        } catch (SecurityException expected) {
                            denied.countDown();
                        }

                        assertFalse(
                                revokerInvoked.get(),
                                "recipient ActorRef must not carry hard-kill authority");
                        assertTrue(child.kill());
                        context.self().stop();
                    });

            parent.send("run");
            assertTrue(denied.await(2, TimeUnit.SECONDS));
            assertTrue(parent.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(parent.failure().isEmpty());
            assertTrue(revokerInvoked.get());
        }
    }

    @Test
    void failedForceRevocationClearsFenceAndActorCanResume() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch delivered = new CountDownLatch(2);
            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) ->
                            delivered.countDown());

            actor.send("before");
            long firstDeadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (delivered.getCount() == 2
                    && System.nanoTime() < firstDeadline) {
                Thread.sleep(5);
            }
            assertEquals(1, delivered.getCount());

            runtime.setForceCancellationTreeHook(request -> false);
            ActorRuntime.ActorControlHandle control =
                    runtime.controlHandle(actor);

            assertThrows(IllegalStateException.class, control::kill);
            assertTrue(actor.isAlive());

            actor.send("after");
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            actor.stop();
        }
    }

    @Test
    void throwingForceRevokerClearsFenceAndActorCanResume() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch delivered = new CountDownLatch(1);
            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) ->
                            delivered.countDown());

            runtime.setForceCancellationTreeHook(request -> {
                throw new IllegalStateException("synthetic revoker failure");
            });

            ActorRuntime.ActorControlHandle control =
                    runtime.controlHandle(actor);
            IllegalStateException failed = assertThrows(
                    IllegalStateException.class,
                    control::kill);
            assertTrue(failed.getMessage().contains("revoker failed"));
            assertTrue(actor.isAlive());

            actor.send("resume");
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            actor.stop();
        }
    }


    @Test
    void forceKillFenceRejectsNewMailboxWorkUntilRevocationCompletes()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch revokerEntered = new CountDownLatch(1);
            CountDownLatch releaseRevoker = new CountDownLatch(1);
            AtomicReference<Throwable> killFailure = new AtomicReference<>();

            runtime.setForceCancellationTreeHook(request -> {
                revokerEntered.countDown();
                try {
                    assertTrue(releaseRevoker.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return true;
            });

            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> { });
            ActorRuntime.ActorControlHandle control =
                    runtime.controlHandle(actor);

            Thread killer = Thread.ofPlatform().start(() -> {
                try {
                    assertTrue(control.kill());
                } catch (Throwable failure) {
                    killFailure.set(failure);
                }
            });

            assertTrue(revokerEntered.await(2, TimeUnit.SECONDS));
            assertThrows(
                    IllegalStateException.class,
                    () -> actor.send("must-not-enter-mailbox"));
            assertThrows(
                    IllegalStateException.class,
                    control::kill,
                    "overlapping force-kill attempts must fail closed");

            releaseRevoker.countDown();
            killer.join(2_000);
            assertFalse(killer.isAlive());
            if (killFailure.get() != null) {
                throw new AssertionError(killFailure.get());
            }
            assertTrue(control.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void forceRevokerCannotReenterActorRuntime() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicBoolean reentryDenied = new AtomicBoolean();

            runtime.setForceCancellationTreeHook(request -> {
                assertThrows(
                        IllegalStateException.class,
                        () -> runtime.spawnShared(
                                factoryContext ->
                                        (message, context) -> { }));
                reentryDenied.set(true);
                return true;
            });

            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> { });
            assertTrue(runtime.controlHandle(actor).kill());
            assertTrue(reentryDenied.get());
        }
    }


    @Test
    void actorExitHookRunsAfterLifecycleMonitorIsReleased() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch exitHookRan = new CountDownLatch(1);
            AtomicBoolean spawnedOnce = new AtomicBoolean();
            AtomicReference<ActorRuntime.ActorRef<String>> replacement =
                    new AtomicReference<>();

            runtime.setActorExitHook(executionDomain -> {
                if (!spawnedOnce.compareAndSet(false, true)) return;
                // A runtime mutation from the hook would deadlock if the dead
                // actor's lifecycle monitor were still held through parent/
                // runtime bookkeeping.
                replacement.set(runtime.spawnShared(
                        factoryContext -> (message, context) ->
                                context.self().stop()));
                exitHookRan.countDown();
            });

            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) ->
                            context.self().stop());

            actor.send("stop");
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(exitHookRan.await(2, TimeUnit.SECONDS));

            ActorRuntime.ActorRef<String> spawned = replacement.get();
            assertNotNull(spawned);
            spawned.send("stop");
            assertTrue(spawned.awaitTermination(2, TimeUnit.SECONDS));
        }
    }


    @Test
    void ordinaryActorCannotInvokeForceCancellationAuthority() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicBoolean revokerInvoked = new AtomicBoolean();
            CountDownLatch denied = new CountDownLatch(1);

            runtime.setForceCancellationHook((actorId, executionDomain) -> {
                revokerInvoked.set(true);
                return true;
            });

            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnShared(() -> (message, context) -> {
                        try {
                            context.self().forceCancel();
                        } catch (SecurityException expected) {
                            denied.countDown();
                            context.self().stop();
                        }
                    });

            actor.send("attempt-force-cancel");

            assertTrue(denied.await(2, TimeUnit.SECONDS));
            assertFalse(
                    revokerInvoked.get(),
                    "ordinary actor code must never reach host isolate-revocation authority");
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void forceCancellationDelegatesToOuterIsolationRevoker() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicBoolean invoked = new AtomicBoolean();
            runtime.setForceCancellationHook((actorId, executionDomain) -> {
                invoked.set(true);
                return true;
            });

            ActorRuntime.ActorRef<String> actor =
                    runtime.spawnPrivate(() -> (message, context) -> { });

            assertTrue(actor.forceCancel());
            assertTrue(invoked.get());
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
