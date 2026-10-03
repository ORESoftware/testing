package dev.oreslang.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;
import dev.oreslang.OresLanguage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong schedulerSafepoints = new AtomicLong();
    private final Map<Object, Object> contextLocals = new LinkedHashMap<>();
    private final Set<Object> contextLocalInitializing = new LinkedHashSet<>();
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

    /**
     * Context-lifetime storage for ordinary module state evaluated outside an
     * actor. Initialization is serialized and recursive same-key initialization
     * is rejected deterministically.
     */
    @SuppressWarnings("unchecked")
    public synchronized <T> T contextLocal(Object key, Supplier<? extends T> initializer) {
        requireOpen();
        java.util.Objects.requireNonNull(key, "context-local key");
        java.util.Objects.requireNonNull(initializer, "context-local initializer");
        if (contextLocals.containsKey(key)) return (T) contextLocals.get(key);
        if (!contextLocalInitializing.add(key)) {
            throw new IllegalStateException("context-local initialization cycle for key " + key);
        }
        try {
            T value = java.util.Objects.requireNonNull(initializer.get(), "context-local initializer returned null");
            contextLocals.put(key, value);
            return value;
        } finally {
            contextLocalInitializing.remove(key);
        }
    }

    public void requireCapability(IsolatePolicy.Capability capability, String api) {
        requireOpen();
        isolatePolicy.require(capability, api);
    }

    private void requireOpen() {
        if (closed.get()) throw new ExecutionTerminated("Oreslang context is closing");
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
        if (!closed.compareAndSet(false, true)) return;
        actors.close();
        synchronized (this) {
            contextLocals.clear();
            contextLocalInitializing.clear();
        }
        output.flush();
    }
}
