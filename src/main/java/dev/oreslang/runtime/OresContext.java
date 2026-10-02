package dev.oreslang.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;
import dev.oreslang.OresLanguage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class OresContext implements AutoCloseable {
    private static final ContextReference<OresContext> REFERENCE = ContextReference.create(OresLanguage.class);

    private final OresLanguage language;
    private final TruffleLanguage.Env env;
    private final BufferedReader input;
    private final PrintWriter output;
    private final ActorRuntime actors;
    private final UUID contextId = UUID.randomUUID();
    private final AtomicLong schedulerSafepoints = new AtomicLong();
    private final Map<Object, Object> contextLocals = new ConcurrentHashMap<>();
    private final IsolatePolicy isolatePolicy;
    private final ExecutionProfile executionProfile;
    private final boolean graalIsolated;
    private final long codeGeneration;

    public OresContext(OresLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
        this.input = new BufferedReader(new InputStreamReader(env.in()));
        this.output = new PrintWriter(env.out(), true);
        this.isolatePolicy = IsolatePolicy.fromApplicationArguments(env.getApplicationArguments());
        this.executionProfile = IsolatePolicy.executionProfileFromApplicationArguments(env.getApplicationArguments());
        this.graalIsolated = IsolatePolicy.graalIsolatedFromApplicationArguments(env.getApplicationArguments());
        this.codeGeneration = codeGenerationFromApplicationArguments(env.getApplicationArguments());
        this.actors = new ActorRuntime(isolatePolicy);
    }

    public static OresContext get(Node node) {
        return REFERENCE.get(node);
    }

    public OresLanguage language() { return language; }
    public TruffleLanguage.Env env() { return env; }
    public BufferedReader input() { return input; }
    public PrintWriter output() { return output; }
    public ActorRuntime actors() { return actors; }
    public UUID contextId() { return contextId; }
    public IsolatePolicy isolatePolicy() { return isolatePolicy; }
    public ExecutionProfile executionProfile() { return executionProfile; }
    public boolean graalIsolated() { return graalIsolated; }
    public long codeGeneration() { return codeGeneration; }

    public void requireCapability(IsolatePolicy.Capability capability, String api) {
        isolatePolicy.require(capability, api);
    }

    /**
     * Compiler-injected cooperative scheduling checkpoint. Loops call this on
     * every iteration so a future supervisor/control mailbox can interrupt
     * long-running actor code without requiring recursion-only looping.
     */
    public void schedulerSafepoint() {
        schedulerSafepoints.incrementAndGet();
        ProcessSingletonRegistry.checkExecutionBudget();
        actors.schedulerSafepoint();
    }

    public long schedulerSafepoints() { return schedulerSafepoints.get(); }

    /**
     * Context-lifetime storage used when code executes outside an actor. This
     * is deliberately separate from actor-local state so ordinary module state
     * cannot leak between actor identities.
     */
    @SuppressWarnings("unchecked")
    public <K, V> V contextLocal(K key, Supplier<? extends V> factory) {
        java.util.Objects.requireNonNull(key, "context-local key");
        java.util.Objects.requireNonNull(factory, "context-local factory");
        return (V) contextLocals.computeIfAbsent(
                key,
                ignored -> java.util.Objects.requireNonNull(
                        factory.get(), "context-local factory returned null"));
    }

    private static long codeGenerationFromApplicationArguments(String[] args) {
        for (String arg : args) {
            if (!arg.startsWith("--ores-code-generation=")) continue;
            long generation = Long.parseLong(arg.substring("--ores-code-generation=".length()));
            if (generation < 0) throw new IllegalArgumentException("ores code generation cannot be negative");
            return generation;
        }
        return 0L;
    }

    public Map<String, Object> processDescriptor() {
        return Map.of(
                "context_id", contextId.toString(),
                "runtime", "graalvm-truffle",
                "language", "oreslang",
                "execution_mode", executionProfile.mode().name(),
                "platform", executionProfile.platform().name(),
                "graal_isolated", graalIsolated,
                "scheduler_safepoints", schedulerSafepoints.get());
    }

    @Override
    public void close() {
        actors.close();
        contextLocals.clear();
        output.flush();
    }
}
