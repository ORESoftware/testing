package dev.oreslang.runtime;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final int MAX_FREEZE_DEPTH = 256;
    private static final int MAX_FREEZE_NODES = 100_000;
    private static final long MAX_FREEZE_BYTES = 16L * 1024 * 1024;
    private static final int DEFAULT_MAX_ACTORS = 16_384;
    private static final Duration MAX_CLOSE_WAIT = Duration.ofSeconds(2);

    private static final ThreadLocal<IsolatePolicy> CURRENT_ACTOR_POLICY = new ThreadLocal<>();
    private static final ThreadLocal<ActorRuntime> CURRENT_ACTOR_RUNTIME = new ThreadLocal<>();
    private static final ThreadLocal<Object> CURRENT_ACTOR_DOMAIN = new ThreadLocal<>();

    private final Map<ActorId, ActorCell<?>> actors = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger actorCount = new AtomicInteger();
    private final Object lifecycleLock = new Object();
    private final IsolatePolicy policyCeiling;
    private final int maxActors;

    public ActorRuntime() {
        this(IsolatePolicy.developer(), DEFAULT_MAX_ACTORS);
    }

    public ActorRuntime(IsolatePolicy policyCeiling) {
        this(policyCeiling, DEFAULT_MAX_ACTORS);
    }

    public ActorRuntime(IsolatePolicy policyCeiling, int maxActors) {
        this.policyCeiling = java.util.Objects.requireNonNull(policyCeiling);
        if (maxActors <= 0 || maxActors > DEFAULT_MAX_ACTORS) {
            throw new IllegalArgumentException(
                    "maxActors must be between 1 and " + DEFAULT_MAX_ACTORS);
        }
        this.maxActors = maxActors;
    }

    public IsolatePolicy policyCeiling() { return policyCeiling; }
    public int maxActors() { return maxActors; }

    private void requireCallerRuntimeAffinity(String operation) {
        ActorRuntime caller = CURRENT_ACTOR_RUNTIME.get();
        if (caller != null && caller != this) {
            throw new SecurityException(
                    "actor cannot " + operation + " through another ActorRuntime");
        }
    }

    private void requireSupervisorContext(String operation) {
        if (CURRENT_ACTOR_RUNTIME.get() != null) {
            throw new SecurityException(
                    "actor code cannot " + operation + "; this operation belongs to the host/supervisor");
        }
    }

    private boolean reserveActorSlot() {
        while (true) {
            int current = actorCount.get();
            if (current >= maxActors) return false;
            if (actorCount.compareAndSet(current, current + 1)) return true;
        }
    }

    private void releaseActorSlot() {
        int remaining = actorCount.decrementAndGet();
        if (remaining < 0) {
            actorCount.incrementAndGet();
            throw new IllegalStateException("actor-count accounting underflow");
        }
    }

    /** True only while the current JVM thread is executing an actor behavior. */
    public static boolean inActorExecution() {
        return CURRENT_ACTOR_POLICY.get() != null;
    }

    /** Current actor policy for runtime primitives that need capability checks. */
    public static IsolatePolicy currentActorPolicy() {
        return CURRENT_ACTOR_POLICY.get();
    }

    /** Owning ActorRuntime for the actor currently executing on this thread. */
    static ActorRuntime currentActorRuntime() {
        return CURRENT_ACTOR_RUNTIME.get();
    }

    /**
     * Stable semantic execution domain for actor-local state.
     *
     * Shared actors may migrate between JVM worker threads, so actor-local
     * confinement must key off actor identity rather than Thread identity.
     * Outside actor execution, the current Thread is the local domain.
     */
    public static Object currentExecutionDomain() {
        Object actorDomain = CURRENT_ACTOR_DOMAIN.get();
        if (actorDomain != null) return actorDomain;
        if (CURRENT_ACTOR_POLICY.get() != null) {
            throw new IllegalStateException(
                    "actor execution is missing its semantic execution domain; refusing to fall back to JVM Thread identity");
        }
        return Thread.currentThread();
    }

    public record ActorId(UUID value) {
        public static ActorId create() { return new ActorId(UUID.randomUUID()); }
    }

    /**
     * Runtime-qualified semantic actor identity. Value equality lets a future
     * pooled scheduler recreate the token on each mailbox turn without tying
     * actor-local state to a particular JVM worker thread.
     */
    private record ActorDomain(ActorRuntime runtime, ActorId actorId) { }

    public static final class Shared<T> {
        private final T value;

        private Shared(T value) {
            this.value = value;
        }

        public T value() {
            return value;
        }

        @Override
        public String toString() {
            return "Shared[readonly]";
        }
    }

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
            ActorRuntime callerRuntime = CURRENT_ACTOR_RUNTIME.get();
            if (callerRuntime != null && callerRuntime != ActorRuntime.this) {
                throw new SecurityException(
                        "ActorRef cannot be invoked from an actor owned by another ActorRuntime");
            }
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
        requireCallerRuntimeAffinity("spawn actors");
        java.util.Objects.requireNonNull(policy, "policy");
        java.util.Objects.requireNonNull(behaviorFactory, "behaviorFactory");
        requireWithinCeiling(policy);

        synchronized (lifecycleLock) {
            if (closed.get()) throw new IllegalStateException("actor runtime is closed");
            if (!reserveActorSlot()) {
                throw new IllegalStateException(
                        "actor runtime limit exceeded: " + maxActors);
            }

            ActorId id = ActorId.create();
            ActorRef<M> ref = new ActorRef<>(id);
            ActorCell<M> cell = new ActorCell<>(ref, policy, behaviorFactory);
            actors.put(id, cell);
            try {
                cell.start();
                return ref;
            } catch (Throwable startupFailure) {
                actors.remove(id, cell);
                releaseActorSlot();
                throw startupFailure;
            }
        }
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
        requireCallerRuntimeAffinity("send messages");
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        if (ref == null || !ref.belongsTo(this)) {
            throw new IllegalArgumentException("ActorRef belongs to another ActorRuntime");
        }
        ActorCell<M> cell = (ActorCell<M>) actors.get(ref.id());
        if (cell == null) throw new IllegalStateException("unknown actor " + ref.id());
        if (!cell.reserveMailboxSlot()) {
            throw new IllegalStateException("actor mailbox limit exceeded for " + ref.id());
        }

        boolean enqueued = false;
        List<OresMutex.Shared<?>> sharedMutexReservations = List.of();
        try {
            Object frozen = freezeForThisRuntime(message);
            boolean containsShared = containsSharedMutex(frozen);
            if (containsShared) {
                IsolatePolicy sender = CURRENT_ACTOR_POLICY.get();
                if (sender != null) sender.require(IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex actor send");
                else policyCeiling.require(IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex host send");
                cell.policy.require(IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex actor receive");
            }

            synchronized (lifecycleLock) {
                if (closed.get()) {
                    throw new IllegalStateException("actor runtime is closed");
                }
                if (containsShared) {
                    sharedMutexReservations = reserveSharedMutexBindings(frozen);
                }
                if (!cell.enqueueReserved(frozen)) {
                    throw new IllegalStateException(
                            "actor terminated before message admission for " + ref.id());
                }
                enqueued = true;
                commitSharedMutexBindings(sharedMutexReservations);
            }
        } finally {
            if (!enqueued) {
                abortSharedMutexBindings(sharedMutexReservations);
                cell.releaseMailboxSlot();
            }
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
        if (closed.get()) throw new CancellationException("actor runtime is closing");
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("actor execution interrupted");
        Thread.yield();
    }

    public Shared<Object> shareReadonly(Object value) {
        requireCallerRuntimeAffinity("share readonly values");
        IsolatePolicy callerPolicy = CURRENT_ACTOR_POLICY.get();
        if (callerPolicy != null) {
            callerPolicy.require(
                    IsolatePolicy.Capability.ACTOR_SHARE_READONLY,
                    "ActorRuntime.shareReadonly");
        } else {
            policyCeiling.require(
                    IsolatePolicy.Capability.ACTOR_SHARE_READONLY,
                    "ActorRuntime.shareReadonly");
        }
        return new Shared<>(freezeForThisRuntime(value));
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
            throw new IllegalArgumentException(
                    "actor message exceeds maximum nesting depth " + MAX_FREEZE_DEPTH);
        }
        budget.addNode();

        if (value == null) {
            budget.addBytes(1);
            return null;
        }
        if (value instanceof String text) {
            budget.addBytes(16L + 2L * text.length());
            return text;
        }
        if (value instanceof BigInteger integer) {
            budget.addBytes(32L + Math.max(1L, (integer.bitLength() + 7L) / 8L));
            return integer;
        }
        if (value instanceof BigDecimal decimal) {
            budget.addBytes(40L + Math.max(1L,
                    (decimal.unscaledValue().bitLength() + 7L) / 8L));
            return decimal;
        }
        if (value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double
                || value instanceof Enum<?> || value instanceof UUID
                || value instanceof ActorId) {
            budget.addBytes(32);
            return value;
        }
        budget.addBytes(24);

        /*
         * SharedMutex is an explicitly capability-gated writable shared-memory
         * handle. Its protected payload is intentionally not copied here.
         */
        if (value instanceof OresMutex.Shared<?> sharedMutex) {
            if (allowedActorRuntime == null) {
                throw new IllegalArgumentException(
                        "SharedMutex is a runtime-scoped writable capability and cannot cross a generic freeze boundary");
            }
            return sharedMutex;
        }

        if (value instanceof ActorRef<?> ref) {
            if (allowedActorRuntime == null || !ref.belongsTo(allowedActorRuntime)) {
                throw new IllegalArgumentException(
                        "ActorRef capabilities may cross only within their owning ActorRuntime");
            }
            return ref;
        }

        /*
         * Revalidate readonly wrappers whenever they cross a boundary. This
         * prevents a wrapper created in one runtime from smuggling a foreign
         * ActorRef or any other runtime-scoped capability into another.
         */
        if (value instanceof Shared<?> shared) {
            enterComposite(value, path);
            try {
                return new Shared<>(freeze(
                        shared.value(), path, budget, depth + 1, allowedActorRuntime));
            } finally {
                path.remove(value);
            }
        }

        if (value instanceof List<?> list) {
            if (list.size() > MAX_FREEZE_NODES) {
                throw new IllegalArgumentException(
                        "actor message exceeds maximum graph size " + MAX_FREEZE_NODES);
            }
            enterComposite(value, path);
            try {
                List<Object> frozen = new ArrayList<>();
                for (Object item : list) {
                    frozen.add(freeze(item, path, budget, depth + 1, allowedActorRuntime));
                }
                return Collections.unmodifiableList(frozen);
            } finally {
                path.remove(value);
            }
        }
        if (value instanceof Set<?> set) {
            if (set.size() > MAX_FREEZE_NODES) {
                throw new IllegalArgumentException(
                        "actor message exceeds maximum graph size " + MAX_FREEZE_NODES);
            }
            enterComposite(value, path);
            try {
                Set<Object> frozen = new LinkedHashSet<>();
                for (Object item : set) {
                    Object copy = freeze(item, path, budget, depth + 1, allowedActorRuntime);
                    if (!frozen.add(copy)) {
                        throw new IllegalArgumentException(
                                "actor message set elements collide after freezing");
                    }
                }
                return Collections.unmodifiableSet(frozen);
            } finally {
                path.remove(value);
            }
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > MAX_FREEZE_NODES) {
                throw new IllegalArgumentException(
                        "actor message exceeds maximum graph size " + MAX_FREEZE_NODES);
            }
            enterComposite(value, path);
            try {
                Map<Object, Object> frozen = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = freeze(
                            entry.getKey(), path, budget, depth + 1, allowedActorRuntime);
                    Object item = freeze(
                            entry.getValue(), path, budget, depth + 1, allowedActorRuntime);
                    if (frozen.containsKey(key)) {
                        throw new IllegalArgumentException(
                                "actor message map keys collide after freezing");
                    }
                    frozen.put(key, item);
                }
                return Collections.unmodifiableMap(frozen);
            } finally {
                path.remove(value);
            }
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            if (length > MAX_FREEZE_NODES) {
                throw new IllegalArgumentException(
                        "actor message exceeds maximum graph size " + MAX_FREEZE_NODES);
            }
            enterComposite(value, path);
            try {
                List<Object> frozen = new ArrayList<>();
                for (int i = 0; i < length; i++) {
                    frozen.add(freeze(
                            Array.get(value, i), path, budget, depth + 1, allowedActorRuntime));
                }
                return Collections.unmodifiableList(frozen);
            } finally {
                path.remove(value);
            }
        }
        if (value instanceof Sendable sendable) {
            enterComposite(value, path);
            try {
                Object candidate = sendable.freezeForSend();
                if (candidate == value) {
                    throw new IllegalArgumentException(
                            "Sendable.freezeForSend() must return a distinct frozen representation; self-returning mutable aliases are forbidden");
                }
                return freeze(candidate, path, budget, depth + 1, allowedActorRuntime);
            } finally {
                path.remove(value);
            }
        }
        throw new IllegalArgumentException("value of type " + value.getClass().getName()
                + " is not Sendable; mutable host objects cannot cross actor boundaries");
    }

    private static void enterComposite(
            Object value,
            IdentityHashMap<Object, Boolean> path) {
        if (path.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException(
                    "cyclic actor message graphs are not Sendable");
        }
    }

    private static final class FreezeBudget {
        private int nodes;
        private long bytes;

        private void addNode() {
            if (++nodes > MAX_FREEZE_NODES) {
                throw new IllegalArgumentException(
                        "actor message exceeds maximum graph size " + MAX_FREEZE_NODES);
            }
        }

        private void addBytes(long amount) {
            if (amount < 0 || bytes > MAX_FREEZE_BYTES - amount) {
                throw new IllegalArgumentException(
                        "actor message exceeds maximum frozen size "
                                + MAX_FREEZE_BYTES + " bytes");
            }
            bytes += amount;
        }
    }

    private List<OresMutex.Shared<?>> reserveSharedMutexBindings(Object value) {
        Set<OresMutex.Shared<?>> unique =
                Collections.newSetFromMap(new IdentityHashMap<>());
        collectSharedMutexes(value, unique);

        List<OresMutex.Shared<?>> reserved = new ArrayList<>(unique.size());
        try {
            for (OresMutex.Shared<?> mutex : unique) {
                if (!mutex.reserveRuntimePublication(this)) {
                    throw new IllegalArgumentException(
                            "SharedMutex may cross actor mailboxes only within its owning ActorRuntime");
                }
                reserved.add(mutex);
            }
            return reserved;
        } catch (RuntimeException | Error failure) {
            abortSharedMutexBindings(reserved);
            throw failure;
        }
    }

    private static void collectSharedMutexes(
            Object value,
            Set<OresMutex.Shared<?>> out) {
        if (value instanceof OresMutex.Shared<?> sharedMutex) {
            out.add(sharedMutex);
            return;
        }
        if (value instanceof Shared<?> shared) {
            collectSharedMutexes(shared.value(), out);
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) collectSharedMutexes(item, out);
            return;
        }
        if (value instanceof Set<?> set) {
            for (Object item : set) collectSharedMutexes(item, out);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                collectSharedMutexes(entry.getKey(), out);
                collectSharedMutexes(entry.getValue(), out);
            }
        }
    }

    private void commitSharedMutexBindings(List<OresMutex.Shared<?>> reservations) {
        for (OresMutex.Shared<?> mutex : reservations) {
            mutex.commitRuntimePublication(this);
        }
    }

    private void abortSharedMutexBindings(List<OresMutex.Shared<?>> reservations) {
        for (int i = reservations.size() - 1; i >= 0; i--) {
            reservations.get(i).abortRuntimePublication(this);
        }
    }

    private static boolean containsSharedMutex(Object value) {
        if (value instanceof OresMutex.Shared<?>) return true;
        if (value instanceof Shared<?> shared) return containsSharedMutex(shared.value());
        if (value instanceof List<?> list) {
            for (Object item : list) if (containsSharedMutex(item)) return true;
            return false;
        }
        if (value instanceof Set<?> set) {
            for (Object item : set) if (containsSharedMutex(item)) return true;
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (containsSharedMutex(entry.getKey()) || containsSharedMutex(entry.getValue())) return true;
            }
            return false;
        }
        return false;
    }

    /** Implemented by generated immutable Oreslang aggregate values. */
    public interface Sendable {
        Object freezeForSend();
    }

    @Override
    public void close() {
        requireSupervisorContext("close an ActorRuntime");

        final List<ActorCell<?>> snapshot;
        synchronized (lifecycleLock) {
            closed.set(true);
            snapshot = List.copyOf(actors.values());
        }

        for (ActorCell<?> cell : snapshot) cell.stop();

        long deadline = System.nanoTime() + MAX_CLOSE_WAIT.toNanos();
        boolean interrupted = false;
        List<ActorId> stillRunning = new ArrayList<>();
        for (ActorCell<?> cell : snapshot) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try {
                    cell.awaitStopped(remaining);
                } catch (InterruptedException stopWaitInterrupted) {
                    interrupted = true;
                    break;
                }
            }
            if (cell.isAlive()) stillRunning.add(cell.ref.id());
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
            for (ActorCell<?> cell : snapshot) {
                if (cell.isAlive() && !stillRunning.contains(cell.ref.id())) {
                    stillRunning.add(cell.ref.id());
                }
            }
        }
        if (!stillRunning.isEmpty()) {
            throw new IllegalStateException(
                    "ActorRuntime close did not observe full actor termination: "
                            + stillRunning.size() + " actor(s) still running");
        }
    }

    private final class ActorCell<M> {
        private static final Object STOP = new Object();
        private final ActorRef<M> ref;
        private final IsolatePolicy policy;
        private final Supplier<? extends Behavior<M>> behaviorFactory;
        private final BlockingQueue<Object> mailbox;
        private final AtomicInteger queuedMessages = new AtomicInteger();
        private boolean terminated;
        private volatile Thread thread;

        private ActorCell(ActorRef<M> ref, IsolatePolicy policy, Supplier<? extends Behavior<M>> behaviorFactory) {
            this.ref = ref;
            this.policy = policy;
            this.behaviorFactory = behaviorFactory;
            this.mailbox = new LinkedBlockingQueue<>(policy.maxMailboxMessages());
        }

        private boolean reserveMailboxSlot() {
            while (true) {
                int current = queuedMessages.get();
                if (current >= policy.maxMailboxMessages()) return false;
                if (queuedMessages.compareAndSet(current, current + 1)) return true;
            }
        }

        private void releaseMailboxSlot() {
            int remaining = queuedMessages.decrementAndGet();
            if (remaining < 0) {
                queuedMessages.incrementAndGet();
                throw new IllegalStateException(
                        "actor mailbox accounting underflow for " + ref.id());
            }
        }

        private synchronized boolean enqueueReserved(Object message) {
            if (terminated) return false;
            if (!mailbox.offer(message)) {
                throw new IllegalStateException(
                        "actor mailbox physical capacity unexpectedly exhausted for " + ref.id());
            }
            return true;
        }

        private synchronized void markTerminated() {
            terminated = true;
        }

        private void start() {
            thread = Thread.ofVirtual().name("ores-actor-" + ref.id().value()).start(this::run);
        }

        @SuppressWarnings("unchecked")
        private void run() {
            CURRENT_ACTOR_POLICY.set(policy);
            CURRENT_ACTOR_RUNTIME.set(ActorRuntime.this);
            CURRENT_ACTOR_DOMAIN.set(new ActorDomain(ActorRuntime.this, ref.id()));
            try {
                final Behavior<M> behavior = java.util.Objects.requireNonNull(
                        behaviorFactory.get(), "actor behavior factory returned null");
                final ActorContext<M> context = new ActorContext<>() {
                    @Override public ActorRef<M> self() { return ref; }
                    @Override public ActorRuntime runtime() { return ActorRuntime.this; }
                    @Override public IsolatePolicy policy() { return policy; }
                };
                while (!closed.get()) {
                    Object message = mailbox.take();
                    if (message == STOP) return;
                    releaseMailboxSlot();
                    if (closed.get()) return;
                    behavior.onMessage((M) message, context);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (ThreadDeath death) {
                throw death;
            } catch (Throwable failure) {
                // v0 fail-stop supervision policy. The cell is removed below
                // so subsequent sends fail immediately instead of targeting a
                // dead actor left behind in the runtime registry.
            } finally {
                markTerminated();
                CURRENT_ACTOR_DOMAIN.remove();
                CURRENT_ACTOR_RUNTIME.remove();
                CURRENT_ACTOR_POLICY.remove();
                if (actors.remove(ref.id(), this)) {
                    releaseActorSlot();
                }
            }
        }

        private boolean isAlive() {
            Thread current = thread;
            return current != null && current.isAlive();
        }

        private void awaitStopped(long remainingNanos) throws InterruptedException {
            Thread current = thread;
            if (current == null || current == Thread.currentThread()) return;
            long millis = Math.max(1L, Math.min(
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNanos),
                    MAX_CLOSE_WAIT.toMillis()));
            current.join(millis);
        }

        private void stop() {
            mailbox.offer(STOP);
            Thread t = thread;
            if (t != null) t.interrupt();
        }
    }
}
