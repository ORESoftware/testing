package dev.oreslang.runtime;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/**
 * Host-side actor substrate used by the first interpreter.
 *
 * Oreslang actor state is owned by one actor. Messages pass through freeze(),
 * which only accepts values that can be made deeply immutable without leaving
 * a writable alias in the receiver. Cross-isolate transports should serialize
 * Frozen values rather than sharing Java object references.
 */
public final class ActorRuntime implements AutoCloseable {
    private static final Duration MAX_CLOSE_WAIT = Duration.ofSeconds(5);
    private static final int MAX_FREEZE_DEPTH = 256;
    private static final int MAX_FREEZE_NODES = 100_000;
    private static final long MAX_FREEZE_BYTES = 16L * 1024 * 1024;
    private static final ThreadLocal<ActorExecution> CURRENT_ACTOR = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_ACTOR_DEADLINE_NANOS = new ThreadLocal<>();

    private final Map<ActorId, ActorCell<?>> actors = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final IsolatePolicy policyCeiling;

    public ActorRuntime() {
        this(IsolatePolicy.developer());
    }

    public ActorRuntime(IsolatePolicy policyCeiling) {
        this.policyCeiling = java.util.Objects.requireNonNull(policyCeiling);
    }

    public IsolatePolicy policyCeiling() { return policyCeiling; }

    public record ActorId(UUID value) {
        public static ActorId create() { return new ActorId(UUID.randomUUID()); }
    }

    private record ActorExecution(
            ActorRuntime runtime,
            ActorId id,
            Map<Object, Object> locals,
            Set<Object> initializingLocals) { }

    /** Returns this runtime's currently executing actor, or null off-actor. */
    public ActorId currentActorId() {
        ActorExecution execution = CURRENT_ACTOR.get();
        return execution != null && execution.runtime == this ? execution.id : null;
    }

    /**
     * Actor-cell-local host storage. Values live exactly as long as the actor
     * cell and are never shared with another actor. Intended for compiler/runtime
     * lowering such as per-actor module/init state, not direct guest access.
     *
     * Returns null when called outside an actor owned by this runtime.
     */
    @SuppressWarnings("unchecked")
    public <T> T currentActorLocal(Object key, Supplier<? extends T> initializer) {
        if (closed.get()) throw new ExecutionTerminated("actor runtime is closing");
        java.util.Objects.requireNonNull(key, "key");
        java.util.Objects.requireNonNull(initializer, "initializer");
        ActorExecution execution = CURRENT_ACTOR.get();
        if (execution == null || execution.runtime != this) return null;

        Object existing = execution.locals.get(key);
        if (existing != null) return (T) existing;
        if (!execution.initializingLocals.add(key)) {
            throw new IllegalStateException(
                    "actor-local initialization cycle for " + diagnosticKey(key));
        }
        try {
            T value = java.util.Objects.requireNonNull(initializer.get(),
                    "actor-local initializer returned null for " + diagnosticKey(key));
            execution.locals.put(key, value);
            return value;
        } finally {
            execution.initializingLocals.remove(key);
        }
    }

    public record Shared<T>(T value) { }

    @FunctionalInterface
    public interface Behavior<M> {
        void onMessage(M message, ActorContext<M> context) throws Exception;
    }

    public interface ActorContext<M> {
        ActorRef<M> self();
        ActorRuntime runtime();
        IsolatePolicy policy();
    }

    public final class ActorRef<M> {
        private final ActorId id;

        private ActorRef(ActorId id) {
            this.id = id;
        }

        public ActorId id() { return id; }

        public void send(M message) {
            ActorRuntime.this.send(this, message);
        }

        private boolean belongsTo(ActorRuntime runtime) {
            return ActorRuntime.this == runtime;
        }

        @Override
        public String toString() {
            return "ActorRef[" + id.value() + "]";
        }
    }

    /**
     * Creates actor-local behavior inside the actor thread. The Supplier should
     * be generated by Oreslang lowering, not supplied from untrusted guest code.
     */
    public <M> ActorRef<M> spawn(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawn(policyCeiling, behaviorFactory);
    }

    public <M> ActorRef<M> spawn(IsolatePolicy policy, Supplier<? extends Behavior<M>> behaviorFactory) {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        requireWithinCeiling(policy);
        ActorId id = ActorId.create();
        ActorRef<M> ref = new ActorRef<>(id);
        ActorCell<M> cell = new ActorCell<>(ref, policy, behaviorFactory);
        actors.put(id, cell);
        cell.start();
        return ref;
    }

    private void requireWithinCeiling(IsolatePolicy child) {
        if (!policyCeiling.capabilities().containsAll(child.capabilities())) {
            java.util.Set<IsolatePolicy.Capability> excess = java.util.EnumSet.copyOf(child.capabilities());
            excess.removeAll(policyCeiling.capabilities());
            throw new SecurityException("child actor policy exceeds parent capabilities: " + excess);
        }
        if (child.maxHeapBytes() > policyCeiling.maxHeapBytes()) {
            throw new SecurityException("child actor maxHeapBytes exceeds parent policy");
        }
        if (child.maxMailboxMessages() > policyCeiling.maxMailboxMessages()) {
            throw new SecurityException("child actor mailbox limit exceeds parent policy");
        }
        if (child.maxWallTime().compareTo(policyCeiling.maxWallTime()) > 0) {
            throw new SecurityException("child actor wall-time limit exceeds parent policy");
        }
        if (policyCeiling.adversarial() && !child.adversarial()) {
            throw new SecurityException("child actor cannot weaken an adversarial parent policy");
        }
    }

    @SuppressWarnings("unchecked")
    public <M> void send(ActorRef<M> ref, M message) {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        if (!ref.belongsTo(this)) {
            throw new IllegalArgumentException("ActorRef belongs to a different ActorRuntime");
        }
        ActorCell<M> cell = (ActorCell<M>) actors.get(ref.id());
        if (cell == null) throw new IllegalStateException("unknown actor " + ref.id());
        Object frozen = freezeForThisRuntime(message);
        if (!cell.mailbox.offer(frozen)) {
            throw new IllegalStateException("actor mailbox limit exceeded for " + ref.id());
        }
    }

    /**
     * Creates an explicitly read-only shared value. The returned graph is a
     * frozen representation; no mutable source object itself is exposed.
     */
    /**
     * Cooperative scheduler hook used by compiler-injected loop safepoints.
     * It observes runtime shutdown/interruption and yields the carrier so
     * supervisor/control-plane work can run. This is intentionally a runtime
     * hook rather than guest-accessible thread control.
     */
    public void schedulerSafepoint() {
        if (closed.get()) throw new ExecutionTerminated("actor runtime is closing");
        if (Thread.currentThread().isInterrupted()) throw new ExecutionTerminated("actor execution interrupted");

        ActorExecution execution = CURRENT_ACTOR.get();
        Long deadline = CURRENT_ACTOR_DEADLINE_NANOS.get();
        if (execution != null && execution.runtime == this && deadline != null
                && System.nanoTime() - deadline >= 0) {
            throw new ExecutionTerminated("actor message wall-time budget exceeded");
        }
        Thread.yield();
    }

    @SuppressWarnings("unchecked")
    public <T> Shared<T> shareReadonly(T value) {
        if (closed.get()) throw new ExecutionTerminated("actor runtime is closing");
        return new Shared<>((T) freezeForThisRuntime(value));
    }

    /**
     * Converts supported values into a deeply immutable/sendable graph.
     * Unknown host objects are rejected instead of being passed by reference.
     */
    public static Object freeze(Object value) {
        return freeze(value, new IdentityHashMap<>(), new FreezeBudget(), 0, null);
    }

    private Object freezeForThisRuntime(Object value) {
        return freeze(value, new IdentityHashMap<>(), new FreezeBudget(), 0, this);
    }

    private static Object freeze(
            Object value,
            IdentityHashMap<Object, Boolean> path,
            FreezeBudget budget,
            int depth,
            ActorRuntime allowedActorRuntime) {
        if (depth > MAX_FREEZE_DEPTH) {
            throw new IllegalArgumentException("actor message exceeds maximum nesting depth " + MAX_FREEZE_DEPTH);
        }
        budget.addNode();

        if (value == null) {
            budget.addBytes(1);
            return null;
        }
        if (value instanceof String text) {
            budget.addBytes(16L + 2L * text.length());
            return value;
        }
        if (value instanceof BigInteger integer) {
            budget.addBytes(32L + Math.max(1L, (integer.bitLength() + 7L) / 8L));
            return value;
        }
        if (value instanceof BigDecimal decimal) {
            budget.addBytes(40L + Math.max(1L, (decimal.unscaledValue().bitLength() + 7L) / 8L));
            return value;
        }
        if (value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double
                || value instanceof Enum<?> || value instanceof UUID || value instanceof ActorId) {
            budget.addBytes(32);
            return value;
        }
        budget.addBytes(24);

        if (value instanceof OresValues.Complex) {
            return value;
        }
        if (value instanceof OresValues.OptionValue option) {
            if (!option.present()) return option;
            return new OresValues.OptionValue(
                    true,
                    freeze(option.value(), path, budget, depth + 1, allowedActorRuntime));
        }

        /*
         * Shared is public host API, so do not trust an externally constructed
         * Shared wrapper to already contain frozen data. Re-freeze its payload.
         */
        if (value instanceof Shared<?> shared) {
            return new Shared<>(freeze(shared.value(), path, budget, depth + 1, allowedActorRuntime));
        }
        if (value instanceof ActorRef<?> ref) {
            if (allowedActorRuntime == null || !ref.belongsTo(allowedActorRuntime)) {
                throw new IllegalArgumentException("ActorRef capabilities may cross only within their owning ActorRuntime");
            }
            return ref;
        }

        if (value instanceof List<?> list) {
            enterComposite(value, path);
            try {
                List<Object> frozen = new ArrayList<>(list.size());
                for (Object item : list) frozen.add(freeze(item, path, budget, depth + 1, allowedActorRuntime));
                return Collections.unmodifiableList(frozen);
            } finally {
                path.remove(value);
            }
        }
        if (value instanceof Set<?> set) {
            enterComposite(value, path);
            try {
                Set<Object> frozen = new LinkedHashSet<>();
                for (Object item : set) {
                    Object copy = freeze(item, path, budget, depth + 1, allowedActorRuntime);
                    if (!frozen.add(copy)) {
                        throw new IllegalArgumentException("actor message set elements collide after freezing");
                    }
                }
                return Collections.unmodifiableSet(frozen);
            } finally {
                path.remove(value);
            }
        }
        if (value instanceof Map<?, ?> map) {
            enterComposite(value, path);
            try {
                Map<Object, Object> frozen = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = freeze(entry.getKey(), path, budget, depth + 1, allowedActorRuntime);
                    Object item = freeze(entry.getValue(), path, budget, depth + 1, allowedActorRuntime);
                    if (frozen.containsKey(key)) {
                        throw new IllegalArgumentException("actor message map keys collide after freezing");
                    }
                    frozen.put(key, item);
                }
                return Collections.unmodifiableMap(frozen);
            } finally {
                path.remove(value);
            }
        }
        if (value.getClass().isArray()) {
            enterComposite(value, path);
            try {
                int length = Array.getLength(value);
                List<Object> frozen = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    frozen.add(freeze(Array.get(value, i), path, budget, depth + 1, allowedActorRuntime));
                }
                return Collections.unmodifiableList(frozen);
            } finally {
                path.remove(value);
            }
        }

        throw new IllegalArgumentException("value of type " + value.getClass().getName()
                + " is not Sendable; mutable host objects cannot cross actor boundaries");
    }

    /**
     * Materializes a receiver-owned mutable copy from a value that has already
     * passed freeze(). This is used by actor RPC boundaries where Oreslang
     * by-value aggregates must become owned by the receiving actor rather than
     * retaining the transport's unmodifiable container representation.
     *
     * Shared<T> deliberately remains shared/read-only.
     */
    public static Object materializeOwned(Object value) {
        return materializeFrozen(freeze(value));
    }

    static Object materializeFrozen(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigInteger
                || value instanceof BigDecimal
                || value instanceof Enum<?>
                || value instanceof UUID
                || value instanceof ActorId
                || value instanceof OresValues.Complex) {
            return value;
        }
        if (value instanceof OresValues.OptionValue option) {
            return option.present()
                    ? new OresValues.OptionValue(true, materializeFrozen(option.value()))
                    : option;
        }
        if (value instanceof Shared<?>) return value;
        if (value instanceof ActorRef<?>) {
            throw new IllegalArgumentException(
                    "context-free actor RPC cannot materialize a live ActorRef capability");
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(materializeFrozen(item));
            return copy;
        }
        if (value instanceof Set<?> set) {
            LinkedHashSet<Object> copy = new LinkedHashSet<>();
            for (Object item : set) {
                Object owned = materializeFrozen(item);
                if (!copy.add(owned)) {
                    throw new IllegalArgumentException(
                            "actor-owned set elements collide during transport materialization");
                }
            }
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = materializeFrozen(entry.getKey());
                Object item = materializeFrozen(entry.getValue());
                if (copy.containsKey(key)) {
                    throw new IllegalArgumentException(
                            "actor-owned map keys collide during transport materialization");
                }
                copy.put(key, item);
            }
            return copy;
        }
        throw new IllegalArgumentException(
                "frozen transport contains unsupported value " + value.getClass().getName());
    }

    private static void enterComposite(Object value, IdentityHashMap<Object, Boolean> path) {
        if (path.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic actor message graphs are not Sendable");
        }
    }

    private static final class FreezeBudget {
        private int nodes;
        private long bytes;

        private void addNode() {
            if (++nodes > MAX_FREEZE_NODES) {
                throw new IllegalArgumentException("actor message exceeds maximum graph size " + MAX_FREEZE_NODES);
            }
        }

        private void addBytes(long amount) {
            if (amount < 0 || bytes > MAX_FREEZE_BYTES - amount) {
                throw new IllegalArgumentException("actor message exceeds maximum frozen size " + MAX_FREEZE_BYTES + " bytes");
            }
            bytes += amount;
        }
    }

    private static String diagnosticKey(Object key) {
        return key.getClass().getSimpleName() + "#"
                + Integer.toUnsignedString(key.hashCode(), 16);
    }

    private static long deadlineAfter(Duration duration) {
        long delta;
        try {
            delta = duration.toNanos();
        } catch (ArithmeticException overflow) {
            delta = Long.MAX_VALUE / 4;
        }
        delta = Math.max(1L, Math.min(delta, Long.MAX_VALUE / 4));
        return System.nanoTime() + delta;
    }

    private static <T> void restoreThreadLocal(ThreadLocal<T> local, T previous) {
        if (previous == null) local.remove();
        else local.set(previous);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        List<ActorCell<?>> snapshot = new ArrayList<>(actors.values());
        for (ActorCell<?> cell : snapshot) cell.stop();

        Duration requested = policyCeiling.maxWallTime();
        Duration budget = requested.compareTo(MAX_CLOSE_WAIT) > 0 ? MAX_CLOSE_WAIT : requested;
        long deadline = deadlineAfter(budget);
        for (ActorCell<?> cell : snapshot) {
            if (!cell.awaitStopped(deadline)) break;
        }
        actors.clear();
    }

    private final class ActorCell<M> {
        private static final Object STOP = new Object();
        private final ActorRef<M> ref;
        private final IsolatePolicy policy;
        private final Supplier<? extends Behavior<M>> behaviorFactory;
        private final BlockingQueue<Object> mailbox;
        private final Map<Object, Object> locals = new LinkedHashMap<>();
        private final Set<Object> localInitializing = new LinkedHashSet<>();
        private volatile Thread thread;

        private ActorCell(ActorRef<M> ref, IsolatePolicy policy, Supplier<? extends Behavior<M>> behaviorFactory) {
            this.ref = ref;
            this.policy = policy;
            this.behaviorFactory = behaviorFactory;
            this.mailbox = new LinkedBlockingQueue<>(policy.maxMailboxMessages());
        }

        private void start() {
            thread = Thread.ofVirtual().name("ores-actor-" + ref.id().value()).start(this::run);
        }

        @SuppressWarnings("unchecked")
        private void run() {
            ActorExecution previous = CURRENT_ACTOR.get();
            CURRENT_ACTOR.set(new ActorExecution(
                    ActorRuntime.this,
                    ref.id(),
                    locals,
                    localInitializing));
            try {
                final Behavior<M> behavior = java.util.Objects.requireNonNull(
                        behaviorFactory.get(), "actor behavior factory returned null");
                final ActorContext<M> context = new ActorContext<>() {
                    @Override public ActorRef<M> self() { return ref; }
                    @Override public ActorRuntime runtime() { return ActorRuntime.this; }
                    @Override public IsolatePolicy policy() { return policy; }
                };
                while (true) {
                    Object message = mailbox.take();
                    if (message == STOP) return;

                    Long previousDeadline = CURRENT_ACTOR_DEADLINE_NANOS.get();
                    CURRENT_ACTOR_DEADLINE_NANOS.set(deadlineAfter(policy.maxWallTime()));
                    try {
                        behavior.onMessage((M) message, context);
                        schedulerSafepoint();
                    } finally {
                        restoreThreadLocal(CURRENT_ACTOR_DEADLINE_NANOS, previousDeadline);
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                // v0 fail-stop supervision policy. The cell is removed below
                // so subsequent sends fail immediately instead of targeting a
                // dead actor left behind in the runtime registry.
            } finally {
                CURRENT_ACTOR_DEADLINE_NANOS.remove();
                if (previous == null) CURRENT_ACTOR.remove();
                else CURRENT_ACTOR.set(previous);
                locals.clear();
                localInitializing.clear();
                actors.remove(ref.id(), this);
            }
        }

        private void stop() {
            mailbox.offer(STOP);
            Thread t = thread;
            if (t != null) t.interrupt();
        }

        private boolean awaitStopped(long deadlineNanos) {
            Thread t = thread;
            if (t == null || t == Thread.currentThread() || !t.isAlive()) return true;

            while (t.isAlive()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    t.interrupt();
                    return false;
                }
                long millis = Math.max(1L, Math.min(
                        TimeUnit.NANOSECONDS.toMillis(remaining),
                        250L));
                try {
                    t.join(millis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }
}
