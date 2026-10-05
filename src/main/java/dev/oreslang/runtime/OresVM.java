package dev.oreslang.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process/runtime boundary for Oreslang execution.
 *
 * <p>The VM owns physical scheduler infrastructure and hot-code generation
 * lifecycles. {@link OresContext} remains the Truffle/Graal integration object,
 * while logical {@link ActorRuntime} instances attach to this VM.
 *
 * <p>This class is deliberately package-private. Guest Oreslang code must never
 * receive an OresVM reference; runtime access crosses language primitives and
 * opaque capabilities instead.
 */
final class OresVM {
    enum SchedulerDomain {
        CONTROL,
        SHARED_ACTOR,
        ISOACTOR,
        UNTRUSTED_ACTOR
    }

    /**
     * Immutable scheduler declaration. Thread counts are carrier bounds, never
     * actor counts: many actors are multiplexed over each actor domain.
     */
    record SchedulerTopology(
            List<SchedulerDomain> domains,
            int controlMinThreads,
            int controlMaxThreads,
            int sharedActorMinThreads,
            int sharedActorMaxThreads,
            int isoactorMinThreads,
            int isoactorMaxThreads,
            int untrustedActorMinThreads,
            int untrustedActorMaxThreads) {
        SchedulerTopology {
            domains = List.copyOf(domains);
            if (domains.size() != 4
                    || !domains.containsAll(List.of(SchedulerDomain.values()))) {
                throw new IllegalArgumentException(
                        "Oreslang VM topology must declare exactly four scheduler domains");
            }
        }
    }

    private static final String VM_BINDING_ARG = "--ores-vm-binding=";
    private static final String GENERATION_BINDING_ARG = "--ores-generation-binding=";
    private static final java.util.concurrent.ConcurrentMap<String, OresVM> VM_BINDINGS =
            new ConcurrentHashMap<>();

    /**
     * Lazy process singleton. Loading OresVM must not allocate scheduler
     * threads: Native Image analysis and host tooling may load runtime classes
     * without actually starting an Oreslang VM.
     */
    private static final class ProcessHolder {
        private static final OresVM INSTANCE = new OresVM(
                ActorRuntime.DispatcherConfig.defaults(),
                "ores-process-",
                true);
    }

    private final UUID vmId = UUID.randomUUID();
    private final String contextBindingToken = UUID.randomUUID().toString();
    private final ActorRuntime.DispatcherGroup dispatchers;
    private final OresScheduler rootScheduler;
    private final BlockingIoExecutor blockingIo;
    private final boolean processVm;
    private final Set<HotReloadManager> hotReloadManagers = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentMap<
            String, ActorRuntime.ActorGenerationLeaseFactory> generationBindings =
            new ConcurrentHashMap<>();
    private final AtomicBoolean shutdown = new AtomicBoolean();

    private OresVM(
            ActorRuntime.DispatcherConfig config,
            String threadPrefix,
            boolean processVm) {
        String prefix = Objects.requireNonNull(threadPrefix, "threadPrefix");
        ActorRuntime.DispatcherConfig schedulerConfig =
                Objects.requireNonNull(config, "config");
        this.dispatchers = new ActorRuntime.DispatcherGroup(
                schedulerConfig,
                prefix);
        int rootParallelism = Math.max(1, schedulerConfig.controlParallelism() - 1);
        this.rootScheduler = OresScheduler.runtimeOwned(
                prefix + "root",
                rootParallelism,
                dispatchers::executeControlTask);
        this.blockingIo = new BlockingIoExecutor(prefix);
        this.processVm = processVm;
        VM_BINDINGS.put(contextBindingToken, this);
    }

    /** The one physical Oreslang VM for the current OS process. */
    static OresVM process() {
        return ProcessHolder.INSTANCE;
    }

    /** Dedicated VM used by host tests/tools that explicitly construct ActorRuntime. */
    static OresVM dedicated(ActorRuntime.DispatcherConfig config) {
        return new OresVM(config, "ores-", false);
    }

    /**
     * Resolve the VM selected by the privileged host for this language context.
     * The opaque binding token is injected by OresVM when it builds a hot-load
     * generation and is never exposed through the Oreslang guest API.
     */
    static OresVM contextOwner(String[] applicationArguments) {
        String token = null;
        for (String argument : applicationArguments) {
            if (argument.startsWith(VM_BINDING_ARG)) {
                token = argument.substring(VM_BINDING_ARG.length());
                break;
            }
        }
        if (token == null) return process();

        OresVM vm = VM_BINDINGS.get(token);
        if (vm == null || vm.shutdown()) {
            throw new SecurityException("invalid or retired Oreslang VM context binding");
        }
        return vm;
    }

    String[] bindApplicationArguments(
            String[] baseArguments,
            String generationBindingToken) {
        Objects.requireNonNull(baseArguments, "baseArguments");
        ensureRunning();
        int extra = generationBindingToken == null ? 1 : 2;
        String[] bound = java.util.Arrays.copyOf(baseArguments, baseArguments.length + extra);
        bound[baseArguments.length] = VM_BINDING_ARG + contextBindingToken;
        if (generationBindingToken != null) {
            if (!generationBindings.containsKey(generationBindingToken)) {
                throw new SecurityException("unknown Oreslang generation binding");
            }
            bound[baseArguments.length + 1] =
                    GENERATION_BINDING_ARG + generationBindingToken;
        }
        return bound;
    }

    String registerGenerationBinding(
            ActorRuntime.ActorGenerationLeaseFactory leaseFactory) {
        Objects.requireNonNull(leaseFactory, "leaseFactory");
        ensureRunning();
        String token;
        do {
            token = UUID.randomUUID().toString();
        } while (generationBindings.putIfAbsent(token, leaseFactory) != null);
        return token;
    }

    void unregisterGenerationBinding(String token) {
        if (token != null) generationBindings.remove(token);
    }

    ActorRuntime.ActorGenerationLeaseFactory generationLeaseFactory(
            String[] applicationArguments) {
        String token = null;
        for (String argument : applicationArguments) {
            if (argument.startsWith(GENERATION_BINDING_ARG)) {
                token = argument.substring(GENERATION_BINDING_ARG.length());
                break;
            }
        }
        if (token == null) return null;

        ActorRuntime.ActorGenerationLeaseFactory factory =
                generationBindings.get(token);
        if (factory == null || shutdown()) {
            throw new SecurityException(
                    "invalid or retired Oreslang generation binding");
        }
        return factory;
    }

    UUID id() {
        return vmId;
    }

    boolean processVm() {
        return processVm;
    }

    boolean shutdown() {
        return shutdown.get();
    }

    OresScheduler rootScheduler() {
        ensureRunning();
        return rootScheduler;
    }

    SchedulerTopology schedulerTopology() {
        ActorRuntime.DispatcherConfig config = dispatchers.config();
        return new SchedulerTopology(
                List.of(
                        SchedulerDomain.CONTROL,
                        SchedulerDomain.SHARED_ACTOR,
                        SchedulerDomain.ISOACTOR,
                        SchedulerDomain.UNTRUSTED_ACTOR),
                config.controlParallelism(),
                config.maxControlParallelism(),
                config.sharedParallelism(),
                config.maxParallelismFor(ActorRuntime.ActorKind.SHARED),
                config.privateParallelism(),
                config.maxParallelismFor(ActorRuntime.ActorKind.PRIVATE),
                config.untrustedParallelism(),
                config.maxParallelismFor(ActorRuntime.ActorKind.UNTRUSTED));
    }

    ActorRuntime newActorRuntime(
            IsolatePolicy policyCeiling,
            ActorRuntime.TurnExecutor turnExecutor) {
        ensureRunning();
        return ActorRuntime.attachToVm(this, policyCeiling, turnExecutor);
    }

    ActorRuntime newActorRuntime(
            IsolatePolicy policyCeiling,
            ActorRuntime.TurnExecutor turnExecutor,
            ActorRuntime.ActorGenerationLeaseFactory generationLeaseFactory) {
        return newActorRuntime(
                policyCeiling,
                turnExecutor,
                generationLeaseFactory,
                ActorRuntime.RuntimePlacement.MAIN_GRAAL_ISOLATE);
    }

    ActorRuntime newActorRuntime(
            IsolatePolicy policyCeiling,
            ActorRuntime.TurnExecutor turnExecutor,
            ActorRuntime.ActorGenerationLeaseFactory generationLeaseFactory,
            ActorRuntime.RuntimePlacement runtimePlacement) {
        ensureRunning();
        ActorRuntime.ActorGenerationLeaseFactory effectiveFactory =
                generationLeaseFactory == null
                        ? () -> () -> { }
                        : generationLeaseFactory;
        return ActorRuntime.attachToVm(
                this,
                policyCeiling,
                turnExecutor,
                effectiveFactory,
                Objects.requireNonNull(runtimePlacement, "runtimePlacement"));
    }

    /**
     * VM-owned hot loader. Trusted generations may share the main Graal engine;
     * isolated/untrusted generations are created through their sandboxed
     * Context policy. Loader authority remains control-plane only.
     */
    HotReloadManager newHotReloadManager(
            IsolatePolicy supervisorPolicy,
            IsolatePolicy guestPolicy,
            ExecutionProfile executionProfile,
            HotReloadManager.ExecutionDomain executionDomain) {
        ensureRunning();
        return new HotReloadManager(
                this,
                supervisorPolicy,
                guestPolicy,
                executionProfile,
                executionDomain);
    }

    void registerHotReloadManager(HotReloadManager manager) {
        Objects.requireNonNull(manager, "manager");
        ensureRunning();
        hotReloadManagers.add(manager);
    }

    void unregisterHotReloadManager(HotReloadManager manager) {
        if (manager != null) hotReloadManagers.remove(manager);
    }

    int hotReloadManagerCount() {
        return hotReloadManagers.size();
    }

    int generationBindingCount() {
        return generationBindings.size();
    }

    ActorRuntime.DispatcherGroup dispatcherGroup() {
        ensureRunning();
        return dispatchers;
    }

    void executeControlMaintenance(Runnable task) {
        // Teardown may race VM shutdown after shutdown=true but before the
        // physical CONTROL pool is stopped. Let already-owned cleanup drain.
        dispatchers.executeControlMaintenance(
                Objects.requireNonNull(task, "task"));
    }

    /**
     * Run a trusted blocking Java host call without occupying an Ores carrier.
     * The returned OresFuture is the only completion capability handed back to
     * scheduler/runtime code.
     */
    <T> OresFuture<T> submitJavaBlocking(
            java.util.concurrent.Callable<? extends T> operation) {
        ensureRunning();
        return blockingIo.submitJava(
                Objects.requireNonNull(operation, "operation"));
    }

    /**
     * Run JNI/FFM/unknown native blocking work on the bounded native executor.
     * Saturation fails the Future rather than running on the caller/carrier.
     */
    <T> OresFuture<T> submitNativeBlocking(
            java.util.concurrent.Callable<? extends T> operation) {
        ensureRunning();
        return blockingIo.submitNative(
                Objects.requireNonNull(operation, "operation"));
    }

    BlockingIoExecutor blockingIoExecutorForTesting() {
        ensureRunning();
        return blockingIo;
    }

    private void ensureRunning() {
        if (shutdown.get()) {
            throw new IllegalStateException("Oreslang VM is shut down");
        }
    }

    /**
     * Dedicated VM teardown. A process VM is host-owned and intentionally
     * outlives individual language contexts/hot-reload generations.
     */
    void shutdownNow() {
        if (processVm) {
            throw new IllegalStateException(
                    "the process Oreslang VM is host-owned and cannot be shut down by a context");
        }
        if (!shutdown.compareAndSet(false, true)) return;

        RuntimeException firstFailure = null;
        for (HotReloadManager manager : List.copyOf(hotReloadManagers)) {
            try {
                manager.close();
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        generationBindings.clear();
        rootScheduler.close();
        blockingIo.close();
        dispatchers.shutdownNow();
        VM_BINDINGS.remove(contextBindingToken, this);

        if (firstFailure != null) throw firstFailure;
    }
}
