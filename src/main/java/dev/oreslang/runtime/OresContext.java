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
    private final RuntimeGarbageCollector garbageCollector;
    private final UUID contextId = UUID.randomUUID();
    private final AtomicLong schedulerSafepoints = new AtomicLong();
    private final IsolatePolicy isolatePolicy;
    private final ExecutionProfile executionProfile;
    private final ReentrantLock adversarialActorTurnLock = new ReentrantLock(true);
    private final Map<String, Object> linkedCodeUnits = new HashMap<>();

    public OresContext(OresLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
        this.input = new BufferedReader(new InputStreamReader(env.in()));
        this.output = new PrintWriter(env.out(), true);
        this.isolatePolicy = IsolatePolicy.fromApplicationArguments(env.getApplicationArguments());
        this.executionProfile = IsolatePolicy.executionProfileFromApplicationArguments(env.getApplicationArguments());
        this.vm = OresVM.contextOwner(env.getApplicationArguments());
        ActorRuntime.ActorGenerationLeaseFactory generationLeaseFactory =
                vm.generationLeaseFactory(env.getApplicationArguments());
        ActorRuntime.RuntimePlacement runtimePlacement =
                isolatePolicy.adversarial()
                        ? ActorRuntime.RuntimePlacement.SPAWNED_GRAAL_ISOLATE
                        : ActorRuntime.RuntimePlacement.MAIN_GRAAL_ISOLATE;
        this.actors = vm.newActorRuntime(
                isolatePolicy,
                this::executeActorTurn,
                generationLeaseFactory,
                runtimePlacement);
        this.garbageCollector = new RuntimeGarbageCollector();
        this.actors.installActorExitHookFromKernel(
                garbageCollector::retireActorDomain);
    }

    public static OresContext get(Node node) {
        return REFERENCE.get(node);
    }

    public OresLanguage language() { return language; }
    public TruffleLanguage.Env env() { return env; }
    public BufferedReader input() { return input; }
    public PrintWriter output() { return output; }
    public ActorRuntime actors() { return actors; }
    public RuntimeGarbageCollector garbageCollector() { return garbageCollector; }
    public UUID contextId() { return contextId; }
    public IsolatePolicy isolatePolicy() { return isolatePolicy; }
    public ExecutionProfile executionProfile() { return executionProfile; }

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
        ActorRuntime carrierRuntime = ActorRuntime.currentActorRuntime();
        if (carrierRuntime != null && carrierRuntime != actors) {
            carrierRuntime.schedulerSafepoint();
            return;
        }
        ActorRuntime rootRuntime = ActorRuntime.currentRootRuntime();
        if (rootRuntime != null && rootRuntime != actors) {
            rootRuntime.schedulerSafepoint();
            return;
        }
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

    private void executeActorTurn(Runnable turn) {
        boolean serialize = isolatePolicy.adversarial();
        boolean lockHeld = false;
        if (serialize) {
            try {
                // A watchdog must be able to wake a carrier that is queued
                // behind another adversarial turn. ReentrantLock.lock() is not
                // interruptible and would let one hostile turn pin every
                // carrier waiting to enter this context.
                adversarialActorTurnLock.lockInterruptibly();
                lockHeld = true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException(
                        "adversarial actor interrupted while waiting to enter its Truffle context");
            }
        }
        TruffleContext truffleContext = env.getContext();
        Object previous = null;
        boolean entered = false;
        try {
            previous = truffleContext.enter(null);
            entered = true;
            turn.run();
        } finally {
            if (entered) truffleContext.leave(null, previous);
            if (lockHeld) adversarialActorTurnLock.unlock();
        }
    }


    public Map<String, Object> processDescriptor() {
        return Map.of(
                "context_id", contextId.toString(),
                "runtime", "graalvm-truffle",
                "language", "oreslang",
                "vm_id", vm.id().toString(),
                "scheduler_domains", vm.schedulerTopology().domains().stream()
                        .map(Enum::name)
                        .toList(),
                "execution_mode", executionProfile.mode().name(),
                "platform", executionProfile.platform().name(),
                "scheduler_safepoints", schedulerSafepoints.get());
    }

    @Override
    public void close() {
        try {
            actors.closeFromSupervisor();
        } finally {
            synchronized (this) {
                linkedCodeUnits.clear();
            }
            garbageCollector.close();
            output.flush();
        }
    }
}
