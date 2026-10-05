package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresVMTest {

    @Test
    void processVmDeclaresExactlyFourSchedulerDomainsWithoutExposingExecutors() {
        OresVM vm = OresVM.process();
        OresVM.SchedulerTopology topology = vm.schedulerTopology();

        assertEquals(
                List.of(
                        OresVM.SchedulerDomain.CONTROL,
                        OresVM.SchedulerDomain.SHARED_ACTOR,
                        OresVM.SchedulerDomain.ISOACTOR,
                        OresVM.SchedulerDomain.UNTRUSTED_ACTOR),
                topology.domains());

        assertTrue(topology.controlMinThreads() > 0);
        assertTrue(topology.sharedActorMinThreads() > 0);
        assertTrue(topology.isoactorMinThreads() > 0);
        assertTrue(topology.untrustedActorMinThreads() > 0);
        assertTrue(topology.controlMaxThreads() >= topology.controlMinThreads());

        for (Method method : OresVM.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            assertFalse(
                    Executor.class.isAssignableFrom(method.getReturnType()),
                    "public OresVM API must not expose raw executor authority: " + method);
        }
    }

    @Test
    void vmKernelTypeIsNotPublicAndContextDoesNotExposeIt() {
        assertFalse(Modifier.isPublic(OresVM.class.getModifiers()),
                "OresVM is a runtime kernel type, not a user/interop API");

        for (Class<?> apiType : List.of(OresContext.class, ActorRuntime.class)) {
            for (Method method : apiType.getMethods()) {
                assertNotEquals(
                        OresVM.class,
                        method.getReturnType(),
                        apiType.getSimpleName()
                                + " must never hand guest/interop code the VM object: "
                                + method);
            }
        }
    }

    @Test
    void generationPublicApiDoesNotExposeRawGraalHandles() {
        for (Method method : HotReloadManager.Generation.class.getMethods()) {
            Class<?> returned = method.getReturnType();
            assertNotEquals(org.graalvm.polyglot.Context.class, returned, method.toString());
            assertNotEquals(org.graalvm.polyglot.Source.class, returned, method.toString());
            assertNotEquals(org.graalvm.polyglot.Engine.class, returned, method.toString());
            assertNotEquals(OresVM.class, returned, method.toString());
            assertNotEquals(ActorRuntime.class, returned, method.toString());
        }
    }

    @Test
    void vmOwnsHotReloadManagersAndDedicatedShutdownClosesGenerations() {
        ActorRuntime.DispatcherConfig config = ActorRuntime.DispatcherConfig.defaults();
        OresVM vm = OresVM.dedicated(config);

        HotReloadManager hot = vm.newHotReloadManager(
                IsolatePolicy.developer(),
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                HotReloadManager.ExecutionDomain.TRUSTED_JIT);

        assertEquals(1, vm.hotReloadManagerCount());

        HotReloadManager.Generation generation = hot.loadAndStart(
                "vm-owned.ores",
                """
                pub routine main() => void {
                  return;
                }
                """);
        assertEquals(HotReloadManager.GenerationState.ACTIVE, generation.state());
        assertEquals(1, vm.generationBindingCount());

        vm.shutdownNow();

        assertTrue(vm.shutdown());
        assertEquals(0, vm.hotReloadManagerCount());
        assertEquals(0, vm.generationBindingCount(),
                "VM shutdown must revoke every opaque generation binding");
        assertTrue(generation.closed(),
                "dedicated VM shutdown must retire contexts/generations it owns");
        assertThrows(
                IllegalStateException.class,
                () -> hot.load("after-shutdown.ores", "pub routine main() => void { return; }"));
    }

    @Test
    void replacingUnpinnedGenerationRevokesItsOpaqueBinding() {
        OresVM vm = OresVM.dedicated(ActorRuntime.DispatcherConfig.defaults());
        try (HotReloadManager hot = vm.newHotReloadManager(
                IsolatePolicy.developer(),
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                HotReloadManager.ExecutionDomain.TRUSTED_JIT)) {

            HotReloadManager.Generation first = hot.loadAndStart(
                    "service.ores",
                    "pub routine main() => void { return; }");
            assertEquals(1, vm.generationBindingCount());

            HotReloadManager.Generation second = hot.loadAndStart(
                    "service.ores",
                    """
                    pub routine main() => void {
                      val version = 2;
                      return;
                    }
                    """);

            assertTrue(first.closed());
            assertTrue(second.active());
            assertEquals(1, vm.generationBindingCount(),
                    "only the active generation binding should remain");
        } finally {
            assertEquals(0, vm.generationBindingCount());
            vm.shutdownNow();
        }
    }

    @Test
    void onlyUntrustedHotLoadDomainUsesSpawnedGraalIsolate() {
        assertFalse(HotReloadManager.ExecutionDomain.TRUSTED_JIT.spawnedIsolate());
        assertFalse(HotReloadManager.ExecutionDomain.TRUSTED_ISOACTOR_JIT.spawnedIsolate());
        assertFalse(HotReloadManager.ExecutionDomain.AOT_INTERPRETED.spawnedIsolate());
        assertTrue(HotReloadManager.ExecutionDomain.UNTRUSTED_JIT.spawnedIsolate());
        assertTrue(HotReloadManager.ExecutionDomain.UNTRUSTED_JIT.untrusted());
    }

    @Test
    void untrustedHotLoadDomainIsFailClosedAndMarkedForSpawnedIsolate() {
        OresVM vm = OresVM.dedicated(ActorRuntime.DispatcherConfig.defaults());
        try {
            HotReloadManager hot = vm.newHotReloadManager(
                    IsolatePolicy.developer(),
                    IsolatePolicy.developer().withCapabilities(
                            IsolatePolicy.Capability.FFI,
                            IsolatePolicy.Capability.NATIVE,
                            IsolatePolicy.Capability.REFLECTION,
                            IsolatePolicy.Capability.THREAD_CREATE,
                            IsolatePolicy.Capability.POLYGLOT),
                    ExecutionProfile.serverJit(),
                    HotReloadManager.ExecutionDomain.UNTRUSTED_JIT);
            try {
                assertTrue(hot.executionDomain().spawnedIsolate());
                assertTrue(hot.executionDomain().untrusted());
                assertTrue(hot.guestPolicy().adversarial());
                assertTrue(hot.guestPolicy().capabilities().isEmpty(),
                        "untrusted generations receive no ambient host authority");
            } finally {
                hot.close();
            }
            assertEquals(0, vm.hotReloadManagerCount());
        } finally {
            vm.shutdownNow();
        }
    }

    @Test
    void actorGenerationLeaseIsAcquiredAtBirthAndReleasedExactlyOnce() {
        OresVM vm = OresVM.dedicated(ActorRuntime.DispatcherConfig.defaults());
        AtomicInteger activeLeases = new AtomicInteger();
        AtomicInteger releasedLeases = new AtomicInteger();

        ActorRuntime runtime = ActorRuntime.attachToVm(
                vm,
                IsolatePolicy.developer(),
                ActorRuntime.TurnExecutor.direct(),
                () -> {
                    activeLeases.incrementAndGet();
                    AtomicBoolean released = new AtomicBoolean();
                    return () -> {
                        if (released.compareAndSet(false, true)) {
                            activeLeases.decrementAndGet();
                            releasedLeases.incrementAndGet();
                        }
                    };
                },
                ActorRuntime.RuntimePlacement.MAIN_GRAAL_ISOLATE);
        try {
            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivate(
                    IsolatePolicy.developer(),
                    ignored -> (message, turn) -> { });

            assertEquals(1, activeLeases.get());
            actor.stop();
            assertEquals(0, activeLeases.get());
            assertEquals(1, releasedLeases.get());

            runtime.close();
            assertEquals(1, releasedLeases.get(),
                    "runtime teardown must never release a finalized actor generation twice");
        } finally {
            runtime.close();
            vm.shutdownNow();
        }
    }

    @Test
    void mainGraalIsolateRefusesDirectUntrustedActorExecution() {
        OresVM vm = OresVM.dedicated(ActorRuntime.DispatcherConfig.defaults());
        ActorRuntime runtime = ActorRuntime.attachToVm(
                vm,
                IsolatePolicy.developer(),
                ActorRuntime.TurnExecutor.direct(),
                () -> () -> { },
                ActorRuntime.RuntimePlacement.MAIN_GRAAL_ISOLATE);
        try {
            SecurityException denied = assertThrows(
                    SecurityException.class,
                    () -> runtime.<String>spawnUntrusted(
                            ignored -> (message, turn) -> { }));
            assertTrue(denied.getMessage().contains("main Graal isolate"));
        } finally {
            runtime.close();
            vm.shutdownNow();
        }
    }

    @Test
    void mailmanControlCarrierDoesNotConferSupervisorAuthority() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1, 1, 1, 8,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.SECONDS.toNanos(1),
                1,
                64);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CountDownLatch attempted = new CountDownLatch(1);
            AtomicReference<Throwable> denied = new AtomicReference<>();

            ActorGroupConfig.GroupPolicy policy = new ActorGroupConfig.GroupPolicy(
                    ActorRuntime.ActorKind.SHARED,
                    0,
                    4,
                    32,
                    32,
                    ActorGroupConfig.RestartStrategy.ONE_FOR_ONE,
                    3,
                    Duration.ofSeconds(5),
                    ActorGroupConfig.RestartPolicy.PERMANENT,
                    null);

            ActorGroupRef<String> group = runtime.defineActorGroup(
                    ActorRuntime.ActorKind.SHARED,
                    policy,
                    new ActorMailman<>() {
                        @Override
                        public void receiveMail(
                                ActorMail<String> mail,
                                ActorGroupContext<String> ignored) {
                            try {
                                runtime.executeRootTask(() -> null);
                            } catch (Throwable failure) {
                                denied.set(failure);
                            } finally {
                                attempted.countDown();
                            }
                        }
                    });

            ActorRuntime.ActorRef<String> actor = runtime.spawnInGroup(
                    group,
                    ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(),
                    ignored -> (message, turn) -> turn.emit(message));

            actor.send("mail");

            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            SecurityException failure = assertInstanceOf(
                    SecurityException.class,
                    denied.get());
            assertTrue(failure.getMessage().contains("host/supervisor"));
        }
    }

    @Test
    void controlPlaneRunsRootAndMailmanOffSharedActorCarriers() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(
                1,
                1,
                1,
                8,
                TimeUnit.MILLISECONDS.toNanos(2),
                TimeUnit.SECONDS.toNanos(1),
                1,
                64);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<String> rootThread = new AtomicReference<>();
            runtime.executeRootTask(() -> {
                rootThread.set(Thread.currentThread().getName());
                return null;
            });
            assertNotNull(rootThread.get());
            assertTrue(rootThread.get().contains("control-plane-dispatcher-"));

            CountDownLatch mailDelivered = new CountDownLatch(1);
            AtomicReference<String> actorThread = new AtomicReference<>();
            AtomicReference<String> mailmanThread = new AtomicReference<>();

            ActorGroupConfig.GroupPolicy policy = new ActorGroupConfig.GroupPolicy(
                    ActorRuntime.ActorKind.SHARED,
                    0,
                    4,
                    32,
                    32,
                    ActorGroupConfig.RestartStrategy.ONE_FOR_ONE,
                    3,
                    Duration.ofSeconds(5),
                    ActorGroupConfig.RestartPolicy.PERMANENT,
                    null);

            ActorGroupRef<String> group = runtime.defineActorGroup(
                    ActorRuntime.ActorKind.SHARED,
                    policy,
                    new ActorMailman<>() {
                        @Override
                        public void receiveMail(
                                ActorMail<String> mail,
                                ActorGroupContext<String> ignored) {
                            actorThread.set(mail.message());
                            mailmanThread.set(Thread.currentThread().getName());
                            mailDelivered.countDown();
                        }
                    });

            ActorRuntime.ActorRef<String> actor = runtime.spawnInGroup(
                    group,
                    ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(),
                    ignored -> (message, turn) ->
                            turn.emit(Thread.currentThread().getName()));

            actor.send("hello");

            assertTrue(mailDelivered.await(5, TimeUnit.SECONDS));
            assertTrue(actorThread.get().contains("shared-actor-dispatcher-"));
            assertTrue(mailmanThread.get().contains("control-plane-dispatcher-"));
            assertNotEquals(actorThread.get(), mailmanThread.get());
        }
    }
}
