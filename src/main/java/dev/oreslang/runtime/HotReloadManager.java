package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Versioned source/IR hot loader.
 *
 * <p>The loader is a trusted control-plane object. Loader authority is never
 * inherited by guest code: the supervisor must hold {@code HOT_CODE_LOAD}, but
 * staged generations run under a separately supplied guest policy with
 * {@code HOT_CODE_LOAD} stripped.
 *
 * <p>A generation has a two-phase lifecycle:
 *
 * <ol>
 *   <li>load/stage: parse, type-check, capability-check, create a fresh context;</li>
 *   <li>start then activate: explicitly evaluate the selected code-unit entry,
 *       then atomically publish the generation for new work. Loading/linking
 *       alone never invokes a magic init hook.</li>
 * </ol>
 *
 * <p>Existing requests/actors may pin an active generation with
 * {@link #pinActive(String)}. Replacing or retiring a pinned generation marks it
 * draining; its context is closed only after the final lease is released.
 *
 * <p>No JNI/JNA/FFI or OS dynamic-library loading is required. JIT domains use
 * Truffle/Graal compilation of guest code. The stable supervisor may remain AOT.
 */
public final class HotReloadManager implements AutoCloseable {
    public static final int MAX_CODE_UNIT_ID_CHARS = 512;
    public static final int MAX_UNTRUSTED_SOURCE_CHARS = 1_048_576;
    public static final int MAX_UNTRUSTED_LIVE_GENERATIONS_PER_UNIT = 8;
    public static final int MAX_TRUSTED_LIVE_GENERATIONS_PER_UNIT = 64;

    public enum ExecutionDomain {
        /** Trusted reloadable code sharing one JVM/Graal engine. */
        TRUSTED_JIT(false, false),

        /**
         * Trusted isoactor code. It has confined/private actor-memory semantics,
         * but remains co-resident in the primary Graal isolate.
         */
        TRUSTED_ISOACTOR_JIT(false, false),

        /**
         * Adversarial code. This is the only JIT domain permitted to cross into
         * a spawned/subsequent Graal isolate.
         */
        UNTRUSTED_JIT(true, true),

        /** Compatibility mode for AOT/interpreted targets that still reload source/IR. */
        AOT_INTERPRETED(false, false);

        private final boolean spawnedIsolate;
        private final boolean untrusted;

        ExecutionDomain(boolean spawnedIsolate, boolean untrusted) {
            this.spawnedIsolate = spawnedIsolate;
            this.untrusted = untrusted;
        }

        public boolean spawnedIsolate() { return spawnedIsolate; }
        public boolean untrusted() { return untrusted; }
        public boolean jit() { return this != AOT_INTERPRETED; }
    }

    public enum GenerationState {
        STAGED,
        STARTED,
        ACTIVE,
        DRAINING,
        FAILED,
        CLOSED
    }

    private final OresVM vm;
    private final IsolatePolicy supervisorPolicy;
    private final IsolatePolicy guestPolicy;
    private final ExecutionProfile executionProfile;
    private final ExecutionDomain executionDomain;
    private final Engine sharedTrustedEngine;
    // Process-monotonic IDs prevent a fresh loader instance from reusing a
    // generation number that process-owned state has already observed.
    private static final AtomicLong PROCESS_GENERATION_SEQUENCE = new AtomicLong();
    private final AtomicReference<Generation> active = new AtomicReference<>();
    private final Map<String, Generation> activeByCodeUnit = new LinkedHashMap<>();
    private final Map<Long, Generation> generations = new LinkedHashMap<>();
    private final AtomicBoolean managerClosed = new AtomicBoolean();

    /**
     * Compatibility constructor. The caller policy is treated as supervisor
     * authority; guest authority is derived from it with HOT_CODE_LOAD removed.
     */
    public HotReloadManager(IsolatePolicy policy, ExecutionProfile executionProfile) {
        this(
                OresVM.process(),
                policy,
                policy,
                executionProfile,
                executionProfile.guestJitAllowed()
                        ? (policy.adversarial() ? ExecutionDomain.UNTRUSTED_JIT : ExecutionDomain.TRUSTED_JIT)
                        : ExecutionDomain.AOT_INTERPRETED);
    }

    /**
     * Creates a loader with explicitly separated control-plane and guest policy.
     */
    public HotReloadManager(
            IsolatePolicy supervisorPolicy,
            IsolatePolicy guestPolicy,
            ExecutionProfile executionProfile,
            ExecutionDomain executionDomain) {
        this(
                OresVM.process(),
                supervisorPolicy,
                guestPolicy,
                executionProfile,
                executionDomain);
    }

    HotReloadManager(
            OresVM vm,
            IsolatePolicy supervisorPolicy,
            IsolatePolicy guestPolicy,
            ExecutionProfile executionProfile,
            ExecutionDomain executionDomain) {
        this.vm = Objects.requireNonNull(vm, "vm");
        this.supervisorPolicy = Objects.requireNonNull(supervisorPolicy, "supervisorPolicy");
        this.executionProfile = Objects.requireNonNull(executionProfile, "executionProfile");
        this.executionDomain = Objects.requireNonNull(executionDomain, "executionDomain");
        supervisorPolicy.require(IsolatePolicy.Capability.HOT_CODE_LOAD, "HotReloadManager");

        if (executionDomain.jit() && !executionProfile.guestJitAllowed()) {
            throw new IllegalArgumentException(
                    executionDomain + " requires an execution profile that permits guest JIT");
        }

        this.guestPolicy = normalizeGuestPolicy(
                Objects.requireNonNull(guestPolicy, "guestPolicy"),
                executionDomain);
        this.sharedTrustedEngine = (executionDomain == ExecutionDomain.TRUSTED_JIT
                || executionDomain == ExecutionDomain.TRUSTED_ISOACTOR_JIT)
                ? Engine.newBuilder().build()
                : null;
        vm.registerHotReloadManager(this);
    }

    public IsolatePolicy supervisorPolicy() { return supervisorPolicy; }
    public IsolatePolicy guestPolicy() { return guestPolicy; }
    public ExecutionProfile executionProfile() { return executionProfile; }
    public ExecutionDomain executionDomain() { return executionDomain; }

    /**
     * Validates and stages a new generation without executing its entrypoint and
     * without publishing it as active.
     */
    public synchronized Generation load(String name, String sourceText) {
        ensureOpen();
        validateStageInput(name, sourceText);
        OresCompiler.validateForIsolate(sourceText, guestPolicy);
        return stage(name, digest(sourceText), sourceText);
    }

    /**
     * Reuses an incrementally compiled unit. Static compilation work is reused,
     * while capability admission is deliberately repeated for the destination
     * guest policy because authority belongs to the runtime, never the cache.
     */
    public synchronized Generation load(IncrementalCompiler.CompiledUnit unit) {
        ensureOpen();
        Objects.requireNonNull(unit, "unit");
        validateStageInput(unit.unitId(), unit.sourceText());
        CapabilityChecker.check(unit.program(), guestPolicy);
        return stage(unit.unitId(), unit.sourceDigest(), unit.sourceText());
    }

    private Generation stage(String codeUnitId, String sourceDigest, String sourceText) {
        enforceGenerationQuota(codeUnitId);

        long id = PROCESS_GENERATION_SEQUENCE.incrementAndGet();
        AtomicReference<Generation> boundGeneration = new AtomicReference<>();
        String generationBindingToken = vm.registerGenerationBinding(() -> {
            Generation generation = boundGeneration.get();
            if (generation == null) {
                throw new IllegalStateException(
                        "hot-load generation is not ready to admit actors");
            }
            return acquireActorLease(generation);
        });

        Context context = null;
        try {
            Context.Builder builder = guestPolicy.restrictedContextBuilder(executionProfile)
                    .arguments(
                            OresLanguage.ID,
                            vm.bindApplicationArguments(
                                    guestPolicy.applicationArguments(executionProfile),
                                    generationBindingToken));
            if (sharedTrustedEngine != null) builder.engine(sharedTrustedEngine);
            context = builder.build();

            Source source = Source.newBuilder(OresLanguage.ID, sourceText, codeUnitId)
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral();
            Generation generation = new Generation(
                    this,
                    id,
                    codeUnitId,
                    sourceDigest,
                    context,
                    source,
                    executionProfile,
                    executionDomain,
                    guestPolicy,
                    generationBindingToken);
            generations.put(id, generation);
            boundGeneration.set(generation);
            return generation;
        } catch (RuntimeException | Error failure) {
            vm.unregisterGenerationBinding(generationBindingToken);
            if (context != null) context.close(true);
            throw failure;
        }
    }

    /**
     * Convenience path for simple callers: stage, start, then atomically activate.
     * If start fails, the previously active generation remains untouched.
     */
    public Generation loadAndStart(String name, String sourceText) {
        Generation generation = load(name, sourceText);
        // Never hold the manager monitor across guest evaluation. Actor startup
        // may acquire/release generation leases on different carrier threads.
        generation.start();
        activate(generation);
        return generation;
    }

    /**
     * Atomically publishes a successfully started generation for new work.
     * The previous generation for the same code unit starts draining.
     */
    public void activate(Generation generation) {
        Generation reclaim = null;
        synchronized (this) {
            ensureOpen();
            requireOwned(generation);
            GenerationState state = generation.state();
            if (state == GenerationState.ACTIVE
                    && activeByCodeUnit.get(generation.codeUnitId()) == generation) {
                return;
            }
            if (state != GenerationState.STARTED) {
                throw new IllegalStateException(
                        "generation must be STARTED before activation; current state=" + state);
            }

            Generation previous = activeByCodeUnit.put(generation.codeUnitId(), generation);
            generation.transition(GenerationState.STARTED, GenerationState.ACTIVE);
            active.set(generation);

            if (previous != null && previous != generation) {
                reclaim = beginDrainLocked(previous);
            }
        }
        closeDetached(reclaim);
    }

    /** Last successfully activated generation, retained for single-unit compatibility. */
    public Generation active() { return active.get(); }

    /** Active generation for one independently compiled code unit. */
    public synchronized Generation active(String codeUnitId) {
        return activeByCodeUnit.get(codeUnitId);
    }

    public synchronized Map<String, Generation> activeGenerations() {
        return Map.copyOf(activeByCodeUnit);
    }

    /**
     * Pins the currently active generation. Actor/request runtimes should keep
     * this lease for the entire turn/lifetime that must continue on old code.
     */
    public synchronized GenerationLease pinActive(String codeUnitId) {
        ensureOpen();
        Generation generation = activeByCodeUnit.get(codeUnitId);
        if (generation == null || generation.state() != GenerationState.ACTIVE) {
            throw new IllegalStateException("no active generation for code unit " + codeUnitId);
        }
        return acquireActorLease(generation);
    }

    /**
     * Internal actor birth pin. Generation startup code may create actors before
     * activation, and already-running old actors may finish their lifecycle
     * while the generation is draining. No new actor may be born from a staged,
     * failed, or closed generation.
     */
    private synchronized GenerationLease acquireActorLease(Generation generation) {
        ensureOpen();
        requireOwned(generation);
        GenerationState state = generation.state();
        if (state != GenerationState.STARTED
                && state != GenerationState.ACTIVE
                && state != GenerationState.DRAINING) {
            throw new IllegalStateException(
                    "generation " + generation.id()
                            + " cannot admit an actor while " + state);
        }
        generation.pins.incrementAndGet();
        return new GenerationLease(this, generation);
    }

    /**
     * Explicit retirement stops new admissions and closes immediately only when
     * no actor/request still pins the generation.
     */
    public void retire(long generationId) {
        Generation reclaim;
        synchronized (this) {
            if (managerClosed.get()) return;
            Generation generation = generations.get(generationId);
            if (generation == null) return;

            active.compareAndSet(generation, null);
            activeByCodeUnit.remove(generation.codeUnitId(), generation);
            reclaim = beginDrainLocked(generation);
        }
        closeDetached(reclaim);
    }

    public synchronized int liveGenerations() { return generations.size(); }

    public synchronized int liveGenerations(String codeUnitId) {
        int count = 0;
        for (Generation generation : generations.values()) {
            if (generation.codeUnitId().equals(codeUnitId)
                    && generation.state() != GenerationState.CLOSED
                    && generation.state() != GenerationState.FAILED) {
                count++;
            }
        }
        return count;
    }

    private void release(Generation generation) {
        Generation reclaim = null;
        synchronized (this) {
            if (!generations.containsKey(generation.id())) return;
            int remaining = generation.pins.decrementAndGet();
            if (remaining < 0) {
                generation.pins.incrementAndGet();
                throw new IllegalStateException("generation lease released more than once");
            }
            if (remaining == 0
                    && generation.state() == GenerationState.DRAINING
                    && !managerClosed.get()) {
                reclaim = detachForCloseLocked(generation);
            }
        }

        if (reclaim != null) {
            Generation detached = reclaim;
            vm.executeControlMaintenance(() -> closeDetached(detached));
        }
    }

    private void generationFailed(Generation generation) {
        boolean owned;
        synchronized (this) {
            owned = generations.get(generation.id()) == generation;
            if (owned) {
                active.compareAndSet(generation, null);
                activeByCodeUnit.remove(generation.codeUnitId(), generation);
                generations.remove(generation.id());
            }
        }
        if (owned) generation.closeContextAfterFailure();
    }

    /**
     * Mark one generation draining while holding the manager monitor. If no
     * leases remain, detach it from routing/ownership maps but close its Graal
     * Context only after the monitor has been released.
     */
    private Generation beginDrainLocked(Generation generation) {
        GenerationState state = generation.state();
        if (state == GenerationState.CLOSED || state == GenerationState.FAILED) {
            return null;
        }

        if (state == GenerationState.STAGED
                || state == GenerationState.STARTED
                || state == GenerationState.ACTIVE) {
            generation.state.set(GenerationState.DRAINING);
        }
        if (generation.pins.get() == 0) {
            return detachForCloseLocked(generation);
        }
        return null;
    }

    private Generation detachForCloseLocked(Generation generation) {
        // Keep the generation in the ownership map until its Context is
        // actually closed. If VM shutdown races a queued control-plane cleanup,
        // manager.close() can still discover and reclaim it.
        active.compareAndSet(generation, null);
        activeByCodeUnit.remove(generation.codeUnitId(), generation);
        return generation;
    }

    private void closeDetached(Generation generation) {
        if (generation == null) return;
        try {
            generation.closeContext();
        } finally {
            synchronized (this) {
                generations.remove(generation.id(), generation);
            }
        }
    }

    private void validateStageInput(String codeUnitId, String sourceText) {
        Objects.requireNonNull(codeUnitId, "codeUnitId");
        Objects.requireNonNull(sourceText, "sourceText");
        if (codeUnitId.isBlank()) throw new IllegalArgumentException("code unit id cannot be blank");
        if (codeUnitId.length() > MAX_CODE_UNIT_ID_CHARS) {
            throw new IllegalArgumentException(
                    "code unit id exceeds " + MAX_CODE_UNIT_ID_CHARS + " characters");
        }
        if (executionDomain.untrusted() && sourceText.length() > MAX_UNTRUSTED_SOURCE_CHARS) {
            throw new IllegalArgumentException(
                    "untrusted source exceeds " + MAX_UNTRUSTED_SOURCE_CHARS + " characters");
        }
    }

    private void enforceGenerationQuota(String codeUnitId) {
        int max = executionDomain.untrusted()
                ? MAX_UNTRUSTED_LIVE_GENERATIONS_PER_UNIT
                : MAX_TRUSTED_LIVE_GENERATIONS_PER_UNIT;
        if (liveGenerations(codeUnitId) >= max) {
            throw new IllegalStateException(
                    "too many live hot-load generations for " + codeUnitId + " (max " + max + ")");
        }
    }

    private void requireOwned(Generation generation) {
        Objects.requireNonNull(generation, "generation");
        if (generation.owner != this || generations.get(generation.id()) != generation) {
            throw new IllegalArgumentException("generation does not belong to this HotReloadManager");
        }
    }

    private static IsolatePolicy normalizeGuestPolicy(
            IsolatePolicy requested,
            ExecutionDomain executionDomain) {
        IsolatePolicy noLoaderAuthority =
                requested.withoutCapabilities(IsolatePolicy.Capability.HOT_CODE_LOAD);

        if (executionDomain == ExecutionDomain.TRUSTED_JIT
                || executionDomain == ExecutionDomain.AOT_INTERPRETED) {
            return noLoaderAuthority;
        }

        // Trusted isoactors are memory-confined actors, not adversarial guests.
        // Keep them in the primary Graal isolate while still stripping ambient
        // host escape hatches that would defeat actor-memory confinement.
        IsolatePolicy confined = noLoaderAuthority.withoutCapabilities(
                IsolatePolicy.Capability.FFI,
                IsolatePolicy.Capability.NATIVE,
                IsolatePolicy.Capability.REFLECTION,
                IsolatePolicy.Capability.THREAD_CREATE,
                IsolatePolicy.Capability.POLYGLOT);

        if (executionDomain == ExecutionDomain.TRUSTED_ISOACTOR_JIT) {
            if (confined.adversarial()) {
                throw new IllegalArgumentException(
                        "trusted isoactor JIT must use a non-adversarial policy in the primary Graal isolate");
            }
            return confined;
        }

        if (executionDomain != ExecutionDomain.UNTRUSTED_JIT) {
            throw new IllegalStateException("unexpected hot-load execution domain " + executionDomain);
        }

        IsolatePolicy isolated = confined.asAdversarial();

        // UntrustedActor ambient authority is deny-by-default. Narrow request/
        // response and Recipient capabilities are object capabilities and are not
        // represented by this ambient capability set.
        IsolatePolicy ceiling = IsolatePolicy.untrustedActor();
        return new IsolatePolicy(
                Set.of(),
                Math.min(isolated.maxHeapBytes(), ceiling.maxHeapBytes()),
                Math.min(isolated.maxMailboxMessages(), ceiling.maxMailboxMessages()),
                min(isolated.maxWallTime(), ceiling.maxWallTime()),
                true);
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private void ensureOpen() {
        if (managerClosed.get()) {
            throw new IllegalStateException("hot reload manager is closed");
        }
        if (vm.shutdown()) {
            throw new IllegalStateException("owning Oreslang VM is shut down");
        }
    }

    @Override
    public void close() {
        if (!managerClosed.compareAndSet(false, true)) return;

        List<Generation> toClose;
        synchronized (this) {
            toClose = List.copyOf(generations.values());
            generations.clear();
            activeByCodeUnit.clear();
            active.set(null);
        }

        RuntimeException failure = null;
        try {
            for (Generation generation : toClose) {
                try {
                    generation.closeContext();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (sharedTrustedEngine != null) {
                try {
                    sharedTrustedEngine.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
        } finally {
            vm.unregisterHotReloadManager(this);
        }
        if (failure != null) throw failure;
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static final class Generation implements AutoCloseable {
        private final HotReloadManager owner;
        private final long id;
        private final String codeUnitId;
        private final String sha256;
        private final Context context;
        private final Source source;
        private final ExecutionProfile executionProfile;
        private final ExecutionDomain executionDomain;
        private final IsolatePolicy guestPolicy;
        private final String generationBindingToken;
        private final AtomicReference<GenerationState> state =
                new AtomicReference<>(GenerationState.STAGED);
        private final AtomicInteger pins = new AtomicInteger();
        private final AtomicBoolean contextClosed = new AtomicBoolean();

        private Generation(
                HotReloadManager owner,
                long id,
                String codeUnitId,
                String sha256,
                Context context,
                Source source,
                ExecutionProfile executionProfile,
                ExecutionDomain executionDomain,
                IsolatePolicy guestPolicy,
                String generationBindingToken) {
            this.owner = owner;
            this.id = id;
            this.codeUnitId = codeUnitId;
            this.sha256 = sha256;
            this.context = context;
            this.source = source;
            this.executionProfile = executionProfile;
            this.executionDomain = executionDomain;
            this.guestPolicy = guestPolicy;
            this.generationBindingToken =
                    Objects.requireNonNull(generationBindingToken, "generationBindingToken");
        }

        public long id() { return id; }
        public String codeUnitId() { return codeUnitId; }
        public String sha256() { return sha256; }
        Context context() { return context; }
        Source source() { return source; }
        public ExecutionProfile executionProfile() { return executionProfile; }
        public ExecutionDomain executionDomain() { return executionDomain; }
        public IsolatePolicy guestPolicy() { return guestPolicy; }
        public GenerationState state() { return state.get(); }
        public int pinCount() { return pins.get(); }
        public boolean started() {
            GenerationState current = state.get();
            return current == GenerationState.STARTED
                    || current == GenerationState.ACTIVE
                    || current == GenerationState.DRAINING;
        }
        public boolean active() { return state.get() == GenerationState.ACTIVE; }
        public boolean closed() {
            GenerationState current = state.get();
            return current == GenerationState.CLOSED || current == GenerationState.FAILED;
        }

        /** Starts the staged generation exactly once without publishing it. */
        public Value start() {
            if (!state.compareAndSet(GenerationState.STAGED, GenerationState.STARTED)) {
                throw new IllegalStateException(
                        "generation can only start from STAGED; current state=" + state.get());
            }
            try {
                return context.eval(source);
            } catch (RuntimeException failure) {
                state.set(GenerationState.FAILED);
                owner.generationFailed(this);
                throw failure;
            }
        }

        /** Publish this successfully started generation for new work. */
        public void activate() {
            owner.activate(this);
        }

        private void transition(GenerationState expected, GenerationState next) {
            if (!state.compareAndSet(expected, next)) {
                throw new IllegalStateException(
                        "generation state changed concurrently: expected "
                                + expected + ", actual " + state.get());
            }
        }

        private void closeContextAfterFailure() {
            if (contextClosed.compareAndSet(false, true)) {
                owner.vm.unregisterGenerationBinding(generationBindingToken);
                context.close(true);
            }
        }

        private void closeContext() {
            if (contextClosed.compareAndSet(false, true)) {
                state.set(GenerationState.CLOSED);
                owner.vm.unregisterGenerationBinding(generationBindingToken);
                context.close(true);
            }
        }

        @Override
        public void close() {
            owner.retire(id);
        }
    }

    public static final class GenerationLease
            implements AutoCloseable, ActorRuntime.ActorGenerationLease {
        private final HotReloadManager owner;
        private final Generation generation;
        private final AtomicBoolean released = new AtomicBoolean();

        private GenerationLease(HotReloadManager owner, Generation generation) {
            this.owner = owner;
            this.generation = generation;
        }

        public Generation generation() { return generation; }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) owner.release(generation);
        }
    }
}
