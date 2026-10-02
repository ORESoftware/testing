package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.IncrementalCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Versioned source hot loader.
 *
 * Loading is side-effect free with respect to guest execution: source is
 * parsed/type/capability checked, then assigned a fresh context. The trusted
 * supervisor explicitly starts the generation after activation.
 *
 * No JNI/FFI or OS dynamic-library loading is required.
 */
public final class HotReloadManager implements AutoCloseable {
    private final IsolatePolicy policy;
    private final ExecutionProfile executionProfile;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicReference<Generation> active = new AtomicReference<>();
    private final int maxRetainedGenerations;
    private final Map<String, Generation> activeByCodeUnit = new LinkedHashMap<>();
    private final Map<Long, Generation> generations = new LinkedHashMap<>();

    public HotReloadManager(IsolatePolicy policy, ExecutionProfile executionProfile) {
        this(policy, executionProfile, Math.max(64, Math.min(policy.maxActors(), 4096)));
    }

    public HotReloadManager(
            IsolatePolicy policy,
            ExecutionProfile executionProfile,
            int maxRetainedGenerations) {
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        this.executionProfile = java.util.Objects.requireNonNull(executionProfile, "executionProfile");
        if (maxRetainedGenerations <= 0) {
            throw new IllegalArgumentException("maxRetainedGenerations must be positive");
        }
        this.maxRetainedGenerations = maxRetainedGenerations;
        if (!policy.allows(IsolatePolicy.Capability.HOT_CODE_LOAD)) {
            throw new SecurityException("HOT_CODE_LOAD capability is required");
        }
    }

    /**
     * Validates and stages a new generation without executing its entrypoint.
     */
    public synchronized Generation load(String name, String sourceText) {
        OresCompiler.validateForIsolate(sourceText, policy);
        return stage(name, digest(sourceText), sourceText);
    }

    /**
     * Reuses an incrementally compiled unit. Static compilation work is reused,
     * while capability admission is deliberately repeated for the destination
     * isolate because authority belongs to the runtime policy, not the cache.
     */
    public synchronized Generation load(IncrementalCompiler.CompiledUnit unit) {
        CapabilityChecker.check(unit.program(), policy);
        return stage(unit.unitId(), unit.sourceDigest(), unit.sourceText());
    }

    private Generation stage(String codeUnitId, String sourceDigest, String sourceText) {
        if (generations.size() >= maxRetainedGenerations) {
            throw new IllegalStateException(
                    "hot-reload retained generation limit exceeded: maxRetainedGenerations="
                            + maxRetainedGenerations);
        }
        long id = sequence.incrementAndGet();
        Context context = policy.restrictedContextBuilder(executionProfile).build();
        try {
            Source source = Source.newBuilder(OresLanguage.ID, sourceText, codeUnitId)
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral();
            Generation generation = new Generation(
                    id,
                    codeUnitId,
                    sourceDigest,
                    context,
                    source,
                    executionProfile,
                    this::onGenerationClosed);
            generations.put(id, generation);
            return generation;
        } catch (RuntimeException failure) {
            context.close(true);
            throw failure;
        }
    }

    public synchronized Generation loadAndStart(String name, String sourceText) {
        Generation generation = load(name, sourceText);
        generation.start();
        activate(generation);
        return generation;
    }

    /**
     * Routes a successfully started staged generation to new work. Replacing
     * the same code unit retires the previous generation; existing leases keep
     * that old context alive until they drain.
     */
    public synchronized void activate(Generation generation) {
        java.util.Objects.requireNonNull(generation, "generation");
        if (generations.get(generation.id()) != generation) {
            throw new IllegalArgumentException("generation is not staged by this HotReloadManager");
        }
        if (!generation.started()) throw new IllegalStateException("generation must be started before activation");
        if (generation.closed() || generation.retired()) {
            throw new IllegalStateException("generation is closed or retired");
        }

        generation.markActivated();
        Generation previous = activeByCodeUnit.put(generation.codeUnitId(), generation);
        active.set(generation);
        if (previous != null && previous != generation) previous.retire();
    }

    /** Last generation activated, retained for compatibility with the single-unit API. */
    public Generation active() { return active.get(); }

    /** Active generation for one independently compiled code unit. */
    public synchronized Generation active(String codeUnitId) {
        return activeByCodeUnit.get(codeUnitId);
    }

    public synchronized Map<String, Generation> activeGenerations() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(activeByCodeUnit));
    }

    /**
     * Retirement prevents new leases immediately but lets old actors/requests
     * drain. The generation closes itself after the final outstanding lease.
     */
    public synchronized void retire(long generationId) {
        Generation generation = generations.get(generationId);
        if (generation != null) {
            active.compareAndSet(generation, null);
            activeByCodeUnit.remove(generation.codeUnitId(), generation);
            generation.retire();
        }
    }

    /** Non-retired generations available for new work. */
    public synchronized int liveGenerations() {
        return (int) generations.values().stream().filter(generation -> !generation.retired()).count();
    }

    /** Includes retired generations still held alive by existing leases. */
    public synchronized int retainedGenerations() {
        return generations.size();
    }

    public int maxRetainedGenerations() {
        return maxRetainedGenerations;
    }

    private synchronized void onGenerationClosed(long id, Generation generation) {
        generations.remove(id, generation);
        active.compareAndSet(generation, null);
        activeByCodeUnit.remove(generation.codeUnitId(), generation);
    }

    @Override
    public synchronized void close() {
        for (Generation generation : java.util.List.copyOf(generations.values())) generation.close();
        generations.clear();
        activeByCodeUnit.clear();
        active.set(null);
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static final class Generation implements AutoCloseable {
        private final long id;
        private final String codeUnitId;
        private final String sha256;
        private final Context context;
        private final Source source;
        private final ExecutionProfile executionProfile;
        private final BiConsumer<Long, Generation> onClosed;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean activated = new AtomicBoolean();
        private final AtomicBoolean retired = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicLong leases = new AtomicLong();

        private Generation(
                long id,
                String codeUnitId,
                String sha256,
                Context context,
                Source source,
                ExecutionProfile executionProfile,
                BiConsumer<Long, Generation> onClosed) {
            this.id = id;
            this.codeUnitId = codeUnitId;
            this.sha256 = sha256;
            this.context = context;
            this.source = source;
            this.executionProfile = executionProfile;
            this.onClosed = onClosed;
        }

        public long id() { return id; }
        public String codeUnitId() { return codeUnitId; }
        public String sha256() { return sha256; }
        public Context context() { return context; }
        public Source source() { return source; }
        public ExecutionProfile executionProfile() { return executionProfile; }
        public boolean started() { return started.get(); }
        public boolean activated() { return activated.get(); }
        public boolean retired() { return retired.get(); }
        public boolean closed() { return closed.get(); }
        public long leaseCount() { return leases.get(); }

        /**
         * Pins this generation for an actor/request. Retirement denies new
         * leases but does not close the context until all existing leases drain.
         */
        public Lease acquire() {
            if (!activated.get() || retired.get() || closed.get()) {
                throw new IllegalStateException("generation is not active, or is retired/closed");
            }
            leases.incrementAndGet();
            if (!activated.get() || retired.get() || closed.get()) {
                releaseLease();
                throw new IllegalStateException("generation stopped being active while lease was being acquired");
            }
            return new Lease(this);
        }

        private void markActivated() {
            if (!started.get() || retired.get() || closed.get()) {
                throw new IllegalStateException("only a started live generation can be activated");
            }
            activated.set(true);
        }

        private void releaseLease() {
            long remaining = leases.decrementAndGet();
            if (remaining < 0L) {
                leases.incrementAndGet();
                throw new IllegalStateException("generation lease underflow");
            }
            if (remaining == 0L && retired.get()) close();
        }

        private void retire() {
            activated.set(false);
            if (retired.compareAndSet(false, true) && leases.get() == 0L) close();
        }

        /** Starts the staged generation exactly once. */
        public Value start() {
            if (retired.get() || closed.get()) throw new IllegalStateException("generation is retired or closed");
            if (!started.compareAndSet(false, true)) throw new IllegalStateException("generation already started");
            try {
                return context.eval(source);
            } catch (RuntimeException failure) {
                close();
                throw failure;
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                activated.set(false);
                retired.set(true);
                context.close(true);
                onClosed.accept(id, this);
            }
        }
    }

    public static final class Lease implements AutoCloseable {
        private final Generation generation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(Generation generation) {
            this.generation = generation;
        }

        public Generation generation() { return generation; }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) generation.releaseLease();
        }
    }
}
