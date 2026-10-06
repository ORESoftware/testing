package dev.oreslang.runtime;

import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;
import dev.oreslang.OresLanguage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public final class OresContext implements AutoCloseable {
    private static final ContextReference<OresContext> REFERENCE = ContextReference.create(OresLanguage.class);

    private final OresLanguage language;
    private final TruffleLanguage.Env env;
    private final BufferedReader input;
    private final PrintWriter output;
    private final OresVM vm;
    private final ActorRuntime actors;
    private final AsyncRuntime asyncRuntime;
    private final RuntimeGarbageCollector garbageCollector;
    private final UUID contextId = UUID.randomUUID();
    private final AtomicLong schedulerSafepoints = new AtomicLong();
    private final IsolatePolicy isolatePolicy;
    private final ExecutionProfile executionProfile;
    private final ReentrantLock adversarialActorTurnLock = new ReentrantLock(true);
    private final Map<String, Object> linkedCodeUnits = new HashMap<>();
    private final Map<String, Map<String, String>> linkedImportResolutions = new HashMap<>();

    public OresContext(OresLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
        this.input = new BufferedReader(new InputStreamReader(env.in()));
        this.output = new PrintWriter(env.out(), true);
        this.isolatePolicy = IsolatePolicy.fromApplicationArguments(env.getApplicationArguments());
        this.executionProfile = IsolatePolicy.executionProfileFromApplicationArguments(env.getApplicationArguments());
        OresVM vm = OresVM.create(this::executeRootTurn);
        if (!vm.started()) {
            vm.close();
            throw new IllegalStateException(
                    "Oreslang VM startup barrier was not established before context admission");
        }

        ActorRuntime actors = null;
        AsyncRuntime async = null;
        RuntimeGarbageCollector gc = null;
        try {
            actors = new ActorRuntime(
                    isolatePolicy,
                    ActorRuntime.DispatcherConfig.defaults(),
                    this::executeActorTurn);
            async = new AsyncRuntime(this::executeAsyncTurn);
            gc = new RuntimeGarbageCollector();
            actors.setActorExitHook(gc::retireActorDomain);

            this.vm = vm;
            this.actors = actors;
            this.asyncRuntime = async;
            this.garbageCollector = gc;
        } catch (RuntimeException | Error failure) {
            if (async != null) {
                try { async.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            if (actors != null) {
                try { actors.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            try { vm.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public static OresContext get(Node node) {
        return REFERENCE.get(node);
    }

    public OresLanguage language() { return language; }
    public TruffleLanguage.Env env() { return env; }
    public BufferedReader input() { return input; }
    public PrintWriter output() { return output; }
    public OresVM vm() { return vm; }
    public ActorRuntime actors() { return actors; }
    public AsyncRuntime asyncRuntime() { return asyncRuntime; }
    public RuntimeGarbageCollector garbageCollector() { return garbageCollector; }
    public UUID contextId() { return contextId; }
    public IsolatePolicy isolatePolicy() { return isolatePolicy; }
    public ExecutionProfile executionProfile() { return executionProfile; }

    public Object lookupHostSymbol(String className) {
        requireCapability(IsolatePolicy.Capability.JAVA_INTEROP, "Java host import " + className);
        if (!env.isHostLookupAllowed()) {
            throw new SecurityException("Java host class lookup is disabled by the embedding Context");
        }
        try {
            return env.lookupHostSymbol(className);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "Java host class is not allowlisted or unavailable: " + className,
                    failure);
        }
    }

    public void requireCapability(IsolatePolicy.Capability capability, String api) {
        IsolatePolicy actorPolicy = ActorRuntime.currentActorPolicy();
        if (actorPolicy != null && ActorRuntime.currentActorRuntime() != actors) {
            throw new SecurityException(
                    "actor capability check crossed ActorRuntime boundary for " + api);
        }
        requireEffectiveCapability(isolatePolicy, capability, api);
    }

    static void requireEffectiveCapability(
            IsolatePolicy contextPolicy,
            IsolatePolicy.Capability capability,
            String api) {
        // Actor turns execute inside the parent Truffle context, but they may
        // have a strictly narrower capability set than that context. Always
        // enforce the actor-local policy first so helper functions, imported
        // code, and ordinary class methods cannot launder authority from the
        // parent context into a private actor.
        IsolatePolicy actorPolicy = ActorRuntime.currentActorPolicy();
        if (actorPolicy != null) actorPolicy.require(capability, api);
        contextPolicy.require(capability, api);
    }

    /**
     * Compiler-injected cooperative scheduling checkpoint. Loops call this on
     * every iteration so a future supervisor/control mailbox can interrupt
     * long-running actor code without requiring recursion-only looping.
     */
    public void schedulerSafepoint() {
        schedulerSafepoints.incrementAndGet();
        actors.schedulerSafepoint();
    }

    public long schedulerSafepoints() { return schedulerSafepoints.get(); }

    /**
     * Host-managed cross-file link registry. Guest imports may only observe
     * units that the host has explicitly loaded into this context; import
     * syntax never grants filesystem access.
     */
    public synchronized void registerLinkedCodeUnit(String codeUnitId, Object unit) {
        if (codeUnitId == null || codeUnitId.isBlank()) {
            throw new IllegalArgumentException("linked code unit id cannot be blank");
        }
        Object previous = linkedCodeUnits.putIfAbsent(codeUnitId, unit);
        if (previous != null && previous != unit) {
            throw new IllegalStateException("code unit already linked in this context: " + codeUnitId);
        }
    }

    public synchronized Object linkedCodeUnit(String codeUnitId) {
        return linkedCodeUnits.get(codeUnitId);
    }

    public synchronized boolean hasLinkedCodeUnit(String codeUnitId) {
        return linkedCodeUnits.containsKey(codeUnitId);
    }

    /**
     * Registers the host compiler's exact filesystem resolution for one import.
     * Guest code can only consume these aliases; it does not gain filesystem
     * access by knowing the resolved target.
     */
    public synchronized void registerLinkedImportResolution(
            String importerCodeUnitId,
            String importPath,
            String targetCodeUnitId) {
        if (importerCodeUnitId == null || importerCodeUnitId.isBlank()) {
            throw new IllegalArgumentException("importer code unit id cannot be blank");
        }
        if (importPath == null || importPath.isBlank()) {
            throw new IllegalArgumentException("linked import path cannot be blank");
        }
        if (targetCodeUnitId == null || targetCodeUnitId.isBlank()) {
            throw new IllegalArgumentException("target code unit id cannot be blank");
        }

        Map<String, String> imports = linkedImportResolutions.computeIfAbsent(
                normalizeCodeUnitId(importerCodeUnitId),
                ignored -> new HashMap<>());
        String target = normalizeCodeUnitId(targetCodeUnitId);
        String previous = imports.putIfAbsent(importPath, target);
        if (previous != null && !previous.equals(target)) {
            throw new IllegalStateException(
                    "conflicting import resolution for '" + importPath + "' in '" + importerCodeUnitId + "'");
        }
    }

    public synchronized String resolvedLinkedImport(String importerCodeUnitId, String importPath) {
        Map<String, String> imports = linkedImportResolutions.get(normalizeCodeUnitId(importerCodeUnitId));
        return imports == null ? null : imports.get(importPath);
    }

    private static String normalizeCodeUnitId(String id) {
        return java.nio.file.Path.of(id).normalize().toString().replace('\\', '/');
    }

    /**
     * Run the VM root task only through the prestarted CONTROL scheduler.
     * Completion of an async main Future is awaited through the same resumable
     * scheduler ABI, so the CONTROL carrier itself is never parked waiting for
     * the terminal Future.
     */
    public Object runRootMain(Callable<?> main) {
        if (vm.isRootSchedulerCurrent()) {
            try {
                return main.call();
            } catch (Exception failure) {
                throw new IllegalStateException("root main failed", failure);
            }
        }

        OresFuture<Object> task = vm.rootScheduler().start(new OresScheduler.Task<>() {
            private int pc;

            @Override
            public OresScheduler.Step<Object> resume(OresScheduler.Resume resume)
                    throws Exception {
                if (pc == 0) {
                    if (!resume.initial()) {
                        throw new IllegalStateException(
                                "root main task must begin with an initial resume");
                    }
                    Object result = main.call();
                    if (result instanceof OresFuture<?> future) {
                        pc = 1;
                        return OresScheduler.await(future);
                    }
                    if (result instanceof CompletionStage<?> stage) {
                        pc = 1;
                        return OresScheduler.await(OresFuture.from(stage));
                    }
                    return OresScheduler.done(result);
                }

                if (pc == 1) {
                    if (resume.failure() != null) {
                        Throwable failure = OresFuture.unwrap(resume.failure());
                        if (failure instanceof Exception checked) throw checked;
                        if (failure instanceof Error error) throw error;
                        throw new RuntimeException(failure);
                    }
                    @SuppressWarnings("unchecked")
                    Object value = resume.value();
                    pc = 2;
                    return OresScheduler.done(value);
                }

                throw new IllegalStateException(
                        "root main scheduler task resumed after terminal state");
            }
        });
        return task.join();
    }

    private void executeRootTurn(Runnable turn) {
        executeGuestTurn(turn, isolatePolicy.adversarial());
    }

    private void executeActorTurn(Runnable turn) {
        executeGuestTurn(turn, isolatePolicy.adversarial());
    }

    private void executeAsyncTurn(Runnable turn) {
        // The current interpreter executes an async callable as one virtual-
        // thread task. Holding the adversarial actor serialization lock across
        // an await could deadlock a nested async task, so strict/adversarial
        // profiles fail closed until compiler continuation lowering can release
        // the guest turn at each await suspension point.
        if (isolatePolicy.adversarial()) {
            throw new SecurityException(
                    "async callable execution in adversarial contexts requires continuation lowering");
        }
        executeGuestTurn(turn, false);
    }

    private void executeGuestTurn(Runnable turn, boolean serialize) {
        if (serialize) adversarialActorTurnLock.lock();
        TruffleContext truffleContext = env.getContext();
        Object previous = null;
        boolean entered = false;
        try {
            previous = truffleContext.enter(null);
            entered = true;
            turn.run();
        } finally {
            if (entered) truffleContext.leave(null, previous);
            if (serialize) adversarialActorTurnLock.unlock();
        }
    }


    public Map<String, Object> processDescriptor() {
        return Map.of(
                "context_id", contextId.toString(),
                "runtime", "graalvm-truffle",
                "language", "oreslang",
                "execution_mode", executionProfile.mode().name(),
                "platform", executionProfile.platform().name(),
                "actor_carrier_backend", actors.carrierBackend().name().toLowerCase(java.util.Locale.ROOT),
                "root_scheduler", vm.rootScheduler().name(),
                "scheduler_started", vm.started(),
                "control_carriers_started", vm.controlCarrierCount(),
                "scheduler_safepoints", schedulerSafepoints.get());
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            asyncRuntime.close();
        } catch (RuntimeException asyncFailure) {
            failure = asyncFailure;
        }
        try {
            actors.close();
        } catch (RuntimeException actorFailure) {
            if (failure == null) failure = actorFailure;
            else failure.addSuppressed(actorFailure);
        }
        try {
            vm.close();
        } catch (RuntimeException vmFailure) {
            if (failure == null) failure = vmFailure;
            else failure.addSuppressed(vmFailure);
        } finally {
            synchronized (this) {
                linkedCodeUnits.clear();
                linkedImportResolutions.clear();
            }
            garbageCollector.close();
            output.flush();
        }
        if (failure != null) throw failure;
    }
}
