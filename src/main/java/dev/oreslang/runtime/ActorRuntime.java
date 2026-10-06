package dev.oreslang.runtime;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Host-side actor substrate used by the first interpreter.
 *
 * Oreslang follows the core Akka-style execution invariant: actors are
 * multiplexed over dispatcher threads, but one actor processes its mailbox
 * serially. Carrier-thread identity is never actor identity.
 *
 * PRIVATE and SHARED actors are deliberately bulkheaded onto different
 * dispatchers. Private actors also receive a confined logical memory slice;
 * shared actors may coordinate through explicitly synchronized shared cells.
 */
public final class ActorRuntime implements AutoCloseable {
    private static final int MAX_MESSAGE_GRAPH_DEPTH = 256;
    private static final int MAX_MESSAGE_GRAPH_NODES = 100_000;
    private static final long CLOSE_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final ThreadLocal<Boolean> ACTOR_CARRIER = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<ActorExecutionContext> CURRENT_ACTOR_EXECUTION = new ThreadLocal<>();

    private record ActorExecutionContext(
            ActorRuntime runtime,
            ActorId actorId,
            ActorKind kind,
            IsolatePolicy policy,
            Object executionDomain) { }

    @FunctionalInterface
    public interface TurnExecutor {
        void execute(Runnable turn);

        static TurnExecutor direct() {
            return Runnable::run;
        }
    }

    public static boolean isActorCarrierThread() {
        return Boolean.TRUE.equals(ACTOR_CARRIER.get());
    }

    public static boolean inActorExecution() {
        return CURRENT_ACTOR_EXECUTION.get() != null;
    }

    public static IsolatePolicy currentActorPolicy() {
        ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
        return current == null ? null : current.policy();
    }

    public static ActorRuntime currentActorRuntime() {
        ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
        return current == null ? null : current.runtime();
    }

    public static ActorKind currentActorKind() {
        ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
        return current == null ? null : current.kind();
    }

    public static Object currentExecutionDomain() {
        ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
        return current == null ? Thread.currentThread() : current.executionDomain();
    }

    /**
     * Stable actor execution domain for actor-local runtime services, or null
     * when the caller is not currently inside an actor mailbox turn.
     */
    public static Object currentActorExecutionDomain() {
        ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
        return current == null ? null : current.executionDomain();
    }

    public enum ActorKind { PRIVATE, SHARED }

    public enum CarrierBackend { NATIVE_PTHREAD, JVM_THREAD_POOL }

    private static final String CARRIER_BACKEND_PROPERTY = "ores.runtime.carriers";

    public record DispatcherConfig(
            int privateParallelism,
            int sharedParallelism,
            int throughput,
            int maxActors) {
        public DispatcherConfig {
            if (privateParallelism <= 0) throw new IllegalArgumentException("privateParallelism must be > 0");
            if (sharedParallelism <= 0) throw new IllegalArgumentException("sharedParallelism must be > 0");
            if (throughput <= 0) throw new IllegalArgumentException("throughput must be > 0");
            if (maxActors <= 0) throw new IllegalArgumentException("maxActors must be > 0");
        }

        public DispatcherConfig(int privateParallelism, int sharedParallelism, int throughput) {
            this(privateParallelism, sharedParallelism, throughput, 16_384);
        }

        public static DispatcherConfig defaults() {
            int cpus = Math.max(2, Runtime.getRuntime().availableProcessors());
            return new DispatcherConfig(cpus, cpus, 64, 16_384);
        }
    }

    public record ActorId(UUID value) {
        public ActorId { Objects.requireNonNull(value); }
        public static ActorId create() { return new ActorId(UUID.randomUUID()); }
    }

    public static final class ActorTerminatedException extends IllegalStateException {
        private final ActorId actorId;
        private final ActorKind actorKind;

        private ActorTerminatedException(ActorId actorId, ActorKind actorKind, Throwable cause) {
            super("actor " + actorId + " (" + actorKind + ") is terminated", cause);
            this.actorId = actorId;
            this.actorKind = actorKind;
        }

        public ActorId actorId() { return actorId; }
        public ActorKind actorKind() { return actorKind; }
    }

    /**
     * Deeply immutable runtime-owned shared value. The backing graph is frozen
     * once, quota-accounted once, and retained until runtime teardown.
     */
    public final class Shared<T> {
        private final AtomicBoolean sharedClosed = new AtomicBoolean();
        private T value;
        private long reservedBytes;

        private Shared(T value, long reservedBytes) {
            this.value = value;
            this.reservedBytes = reservedBytes;
        }

        public T value() {
            rejectPrivateActorSharedMemoryAccess("Shared.value");
            if (sharedClosed.get()) throw new IllegalStateException("Shared value belongs to a closed actor runtime");
            return value;
        }

        private boolean ownedBy(ActorRuntime runtime) {
            return ActorRuntime.this == runtime;
        }

        private void closeFromRuntime() {
            if (!sharedClosed.compareAndSet(false, true)) return;
            long bytes = reservedBytes;
            reservedBytes = 0L;
            value = null;
            releaseSharedRuntimeBytes(bytes);
            sharedValues.remove(this);
        }
    }

    private final Map<ActorId, ActorCell<?>> actors = new ConcurrentHashMap<>();
    private final AtomicInteger actorCount = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong privateMemoryBytes = new AtomicLong();
    private final AtomicLong sharedMemoryBytes = new AtomicLong();
    private final Object memoryBudgetLock = new Object();
    private final Object runtimeLifecycleLock = new Object();
    private final Set<SyncCell<?>> syncCells = ConcurrentHashMap.newKeySet();
    private final Set<Shared<?>> sharedValues = ConcurrentHashMap.newKeySet();
    private final IsolatePolicy policyCeiling;
    private final DispatcherConfig dispatcherConfig;
    private final TurnExecutor turnExecutor;
    private volatile Consumer<Object> actorExitHook = ignored -> { };
    private final ExecutorService privateDispatcher;
    private final ExecutorService sharedDispatcher;
    private final ThreadLocal<ActorCell<?>> currentActor = new ThreadLocal<>();
    private final ThreadLocal<SyncCell<?>> currentSyncCell = new ThreadLocal<>();

    public ActorRuntime() {
        this(IsolatePolicy.developer(), DispatcherConfig.defaults(), TurnExecutor.direct());
    }

    public ActorRuntime(IsolatePolicy policyCeiling) {
        this(policyCeiling, DispatcherConfig.defaults(), TurnExecutor.direct());
    }

    public ActorRuntime(IsolatePolicy policyCeiling, int maxActors) {
        this(
                policyCeiling,
                new DispatcherConfig(
                        DispatcherConfig.defaults().privateParallelism(),
                        DispatcherConfig.defaults().sharedParallelism(),
                        DispatcherConfig.defaults().throughput(),
                        maxActors),
                TurnExecutor.direct());
    }

    public ActorRuntime(IsolatePolicy policyCeiling, DispatcherConfig dispatcherConfig) {
        this(policyCeiling, dispatcherConfig, TurnExecutor.direct());
    }

    public ActorRuntime(
            IsolatePolicy policyCeiling,
            DispatcherConfig dispatcherConfig,
            TurnExecutor turnExecutor) {
        this.policyCeiling = Objects.requireNonNull(policyCeiling);
        this.dispatcherConfig = Objects.requireNonNull(dispatcherConfig);
        this.turnExecutor = Objects.requireNonNull(turnExecutor);
        this.privateDispatcher = newDispatcher(
                dispatcherConfig.privateParallelism(),
                dispatcherConfig.maxActors(),
                "ores-private-actor-dispatcher-");
        this.sharedDispatcher = newDispatcher(
                dispatcherConfig.sharedParallelism(),
                dispatcherConfig.maxActors(),
                "ores-shared-actor-dispatcher-");
    }

    public IsolatePolicy policyCeiling() { return policyCeiling; }
    public DispatcherConfig dispatcherConfig() { return dispatcherConfig; }
    public int maxActors() { return dispatcherConfig.maxActors(); }

    /**
     * Physical carrier implementation currently backing actor turns.
     *
     * <p>This is diagnostic/control-plane information only. Actor identity is
     * never carrier identity and source semantics do not depend on this value.</p>
     */
    public CarrierBackend carrierBackend() {
        return privateDispatcher instanceof NativeCarrierExecutor
                && sharedDispatcher instanceof NativeCarrierExecutor
                ? CarrierBackend.NATIVE_PTHREAD
                : CarrierBackend.JVM_THREAD_POOL;
    }

    /**
     * Installs a host-owned hook invoked exactly once when an actor execution
     * domain is retired. Guest code cannot mutate this hook.
     */
    public void setActorExitHook(Consumer<Object> actorExitHook) {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        this.actorExitHook = Objects.requireNonNull(actorExitHook, "actorExitHook");
    }
    public int actorCount() { return actorCount.get(); }
    public long privateMemoryBytes() { return privateMemoryBytes.get(); }
    public long sharedMemoryBytes() { return sharedMemoryBytes.get(); }
    public long actorMemoryBytes() { return privateMemoryBytes.get() + sharedMemoryBytes.get(); }

    /**
     * Logical actor-confined memory slice for one private actor.
     *
     * This is independent of carrier threads. Mailbox payloads and persistent
     * actor-state allocations share one budget. The current JVM backend uses
     * accounting plus alias isolation; a native/polyglot-isolate backend can map
     * this same contract to a physically separate heap/arena.
     */
    public final class ActorMemorySlice implements AutoCloseable {
        private final ActorId owner;
        private final long limitBytes;
        private final AtomicLong usedBytes = new AtomicLong();
        private final AtomicBoolean sliceClosed = new AtomicBoolean();
        private final Set<PrivateMemoryBlock> blocks = ConcurrentHashMap.newKeySet();

        private ActorMemorySlice(ActorId owner, long limitBytes) {
            this.owner = Objects.requireNonNull(owner);
            this.limitBytes = limitBytes;
        }

        public ActorId owner() { return owner; }
        public long limitBytes() { return limitBytes; }
        public long usedBytes() { return usedBytes.get(); }
        public long remainingBytes() { return Math.max(0L, limitBytes - usedBytes.get()); }
        public boolean closed() { return sliceClosed.get(); }

        /**
         * Reserve persistent private-actor heap. Compiler/interpreter lowering
         * should retain the reservation for as long as the state allocation is
         * live and close it when that allocation dies.
         */
        public MemoryReservation reserveHeap(long bytes) {
            requireCurrentOwner();
            return reserve(bytes, "private actor heap");
        }

        /**
         * Allocate actor-confined direct memory. The raw ByteBuffer is never
         * exposed; all reads/writes verify the owning ActorId. This gives
         * compiler-lowered private actor state a genuinely unshared backing
         * region while actors remain multiplexed over carrier threads.
         */
        public PrivateMemoryBlock allocatePrivateBytes(int bytes) {
            requireCurrentOwner();
            if (bytes < 0) throw new IllegalArgumentException("private memory block size cannot be negative");
            MemoryReservation reservation = reserve(bytes, "private actor direct heap");
            try {
                PrivateMemoryBlock block = new PrivateMemoryBlock(this, reservation, bytes);
                blocks.add(block);
                return block;
            } catch (RuntimeException | Error failure) {
                reservation.close();
                throw failure;
            }
        }

        private MemoryReservation reserveMailbox(Object isolatedMessage) {
            return reserve(estimateFrozenBytes(isolatedMessage), "private actor mailbox");
        }

        private synchronized MemoryReservation reserve(long bytes, String purpose) {
            if (bytes < 0) throw new IllegalArgumentException("memory reservation cannot be negative");
            if (sliceClosed.get()) throw new IllegalStateException("private actor memory slice is closed");
            if (bytes == 0) return new MemoryReservation(this, 0);

            long current = usedBytes.get();
            long next;
            try {
                next = Math.addExact(current, bytes);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException(purpose + " accounting overflow");
            }
            if (next > limitBytes) {
                throw new IllegalStateException(purpose + " limit exceeded for " + owner
                        + ": requested=" + bytes + " used=" + current + " limit=" + limitBytes);
            }

            reservePrivateRuntimeBytes(bytes, owner, purpose);
            usedBytes.set(next);
            return new MemoryReservation(this, bytes);
        }

        private void requireCurrentOwner() {
            ActorCell<?> cell = currentActor.get();
            if (cell == null || cell.kind != ActorKind.PRIVATE || !cell.ref.id().equals(owner)) {
                throw new IllegalStateException(
                        "private actor memory slice may only be reserved by its owning actor");
            }
        }

        private synchronized void release(long bytes) {
            if (bytes == 0 || sliceClosed.get()) return;
            long remaining = usedBytes.addAndGet(-bytes);
            if (remaining < 0) {
                usedBytes.addAndGet(bytes);
                throw new IllegalStateException("private actor memory accounting underflow for " + owner);
            }
            try {
                releasePrivateRuntimeBytes(bytes, owner);
            } catch (RuntimeException failure) {
                usedBytes.addAndGet(bytes);
                throw failure;
            }
        }

        @Override
        public synchronized void close() {
            if (!sliceClosed.compareAndSet(false, true)) return;
            for (PrivateMemoryBlock block : List.copyOf(blocks)) block.invalidateFromSlice();
            blocks.clear();
            long bytes = usedBytes.getAndSet(0);
            if (bytes != 0) releasePrivateRuntimeBytes(bytes, owner);
        }

        private void unregister(PrivateMemoryBlock block) {
            blocks.remove(block);
        }
    }

    /**
     * Owner-checked direct memory owned by exactly one private actor.
     *
     * No mutable buffer reference escapes this wrapper. Closing the block or
     * terminating the actor overwrites the entire region before invalidation.
     */
    public final class PrivateMemoryBlock implements AutoCloseable {
        private final ActorMemorySlice slice;
        private final MemoryReservation reservation;
        private final int capacity;
        private volatile ByteBuffer memory;
        private final AtomicBoolean blockClosed = new AtomicBoolean();

        private PrivateMemoryBlock(
                ActorMemorySlice slice,
                MemoryReservation reservation,
                int bytes) {
            this.slice = Objects.requireNonNull(slice);
            this.reservation = Objects.requireNonNull(reservation);
            this.capacity = bytes;
            this.memory = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }

        public int capacity() { return capacity; }
        public ActorId owner() { return slice.owner(); }
        public boolean closed() { return blockClosed.get(); }

        public byte readByte(int index) {
            return openMemory().get(index);
        }

        public void writeByte(int index, byte value) {
            openMemory().put(index, value);
        }

        public int readInt(int index) {
            return openMemory().getInt(index);
        }

        public void writeInt(int index, int value) {
            openMemory().putInt(index, value);
        }

        public long readLong(int index) {
            return openMemory().getLong(index);
        }

        public void writeLong(int index, long value) {
            openMemory().putLong(index, value);
        }

        public double readDouble(int index) {
            return openMemory().getDouble(index);
        }

        public void writeDouble(int index, double value) {
            openMemory().putDouble(index, value);
        }

        public byte[] copyOut() {
            ByteBuffer live = openMemory();
            byte[] out = new byte[capacity];
            ByteBuffer duplicate = live.duplicate();
            duplicate.clear();
            duplicate.get(out);
            return out;
        }

        public void copyIn(byte[] bytes) {
            Objects.requireNonNull(bytes);
            ByteBuffer live = openMemory();
            if (bytes.length != capacity) {
                throw new IllegalArgumentException(
                        "private memory copy size mismatch: expected " + capacity
                                + " bytes but got " + bytes.length);
            }
            ByteBuffer duplicate = live.duplicate();
            duplicate.clear();
            duplicate.put(bytes);
        }

        private ByteBuffer openMemory() {
            if (blockClosed.get() || slice.closed()) {
                throw new IllegalStateException("private actor memory block is closed");
            }
            slice.requireCurrentOwner();
            ByteBuffer live = memory;
            if (live == null) throw new IllegalStateException("private actor memory block is closed");
            return live;
        }

        private void zeroAndDetachMemory() {
            ByteBuffer live = memory;
            if (live == null) return;
            ByteBuffer duplicate = live.duplicate();
            duplicate.clear();
            while (duplicate.hasRemaining()) duplicate.put((byte) 0);
            memory = null;
        }

        private void invalidateFromSlice() {
            if (!blockClosed.compareAndSet(false, true)) return;
            zeroAndDetachMemory();
        }

        @Override
        public void close() {
            openMemory(); // owner + liveness check before invalidation
            if (!blockClosed.compareAndSet(false, true)) return;
            zeroAndDetachMemory();
            slice.unregister(this);
            reservation.close();
        }
    }

    public final class MemoryReservation implements AutoCloseable {
        private final ActorMemorySlice slice;
        private final long bytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private MemoryReservation(ActorMemorySlice slice, long bytes) {
            this.slice = Objects.requireNonNull(slice);
            this.bytes = bytes;
        }

        public ActorId owner() { return slice.owner(); }
        public long bytes() { return bytes; }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) slice.release(bytes);
        }
    }

    private record MessageEnvelope(Object value, Runnable release) implements AutoCloseable {
        @Override
        public void close() {
            if (release != null) release.run();
        }
    }

    /**
     * Explicit synchronized shared-memory cell.
     *
     * Actor fields do not use this: a mailbox turn already provides exclusive
     * mutation of actor-owned state. SyncCell is for state intentionally shared
     * by multiple SHARED actors.
     */
    public final class SyncCell<T> implements AutoCloseable {
        private final ReentrantLock lock = new ReentrantLock(true);
        private final AtomicBoolean cellClosed = new AtomicBoolean();
        private T value;
        private long reservedBytes;

        @SuppressWarnings("unchecked")
        private SyncCell(T initialValue) {
            Object frozen = freeze(initialValue);
            rejectSharedMutableHandles(frozen, new IdentityHashMap<>(), 0);
            long bytes = estimateFrozenBytes(frozen);
            reserveSharedRuntimeBytes(bytes, "shared SyncCell");
            this.value = (T) frozen;
            this.reservedBytes = bytes;
        }

        public boolean closed() { return cellClosed.get(); }

        private boolean ownedBy(ActorRuntime runtime) {
            return ActorRuntime.this == runtime;
        }

        public T snapshot() {
            rejectPrivateActorSharedMemoryAccess("SyncCell.snapshot");
            boolean entered = enterSyncCell(this);
            lock.lock();
            try {
                requireOpen();
                return value;
            } finally {
                lock.unlock();
                exitSyncCell(entered);
            }
        }

        public <R> R read(Function<? super T, ? extends R> reader) {
            Objects.requireNonNull(reader);
            requireSharedActorTurn();
            boolean entered = enterSyncCell(this);
            lock.lock();
            try {
                requireOpen();
                Object frozen = freeze(reader.apply(value));
                rejectSharedMutableHandles(frozen, new IdentityHashMap<>(), 0);
                @SuppressWarnings("unchecked")
                R result = (R) frozen;
                return result;
            } finally {
                lock.unlock();
                exitSyncCell(entered);
            }
        }

        @SuppressWarnings("unchecked")
        public T update(UnaryOperator<T> updater) {
            Objects.requireNonNull(updater);
            requireSharedActorTurn();
            boolean entered = enterSyncCell(this);
            lock.lock();
            try {
                requireOpen();
                Object frozen = freeze(updater.apply(value));
                rejectSharedMutableHandles(frozen, new IdentityHashMap<>(), 0);
                requireOpen();
                if (closed.get()) throw new IllegalStateException("actor runtime is closed");
                long nextBytes = estimateFrozenBytes(frozen);
                long delta = nextBytes - reservedBytes;
                if (delta > 0) reserveSharedRuntimeBytes(delta, "shared SyncCell update");
                value = (T) frozen;
                if (delta < 0) releaseSharedRuntimeBytes(-delta);
                reservedBytes = nextBytes;
                return value;
            } finally {
                lock.unlock();
                exitSyncCell(entered);
            }
        }

        private void requireOpen() {
            if (cellClosed.get()) throw new IllegalStateException("SyncCell is closed");
        }

        @Override
        public void close() {
            rejectPrivateActorSharedMemoryAccess("SyncCell.close");
            boolean entered = enterSyncCell(this);
            try {
                closeFromRuntime();
            } finally {
                exitSyncCell(entered);
            }
        }

        private void closeFromRuntime() {
            lock.lock();
            try {
                if (!cellClosed.compareAndSet(false, true)) return;
                long bytes = reservedBytes;
                reservedBytes = 0L;
                value = null;
                releaseSharedRuntimeBytes(bytes);
                syncCells.remove(this);
            } finally {
                lock.unlock();
            }
        }

        private void invalidateFromRuntime() {
            cellClosed.set(true);
            syncCells.remove(this);
        }
    }

    @FunctionalInterface
    public interface Behavior<M> {
        void onMessage(M message, ActorContext<M> context) throws Exception;
    }

    /**
     * Compiler/interpreter callback for a one-shot source-level actor callable.
     * Guest code never receives this host callback object.
     */
    @FunctionalInterface
    public interface Invocation<M, R> {
        R run(M message, ActorContext<M> context) throws Exception;
    }

    /**
     * Compiler-facing actor constructor. The actor context is available before
     * state initialization, so private actor fields can reserve/allocate in the
     * actor's confined memory slice rather than being captured from the caller.
     */
    @FunctionalInterface
    public interface BehaviorFactory<M> {
        Behavior<M> create(ActorContext<M> context) throws Exception;
    }

    public interface ActorContext<M> {
        ActorRef<M> self();
        ActorRuntime runtime();
        IsolatePolicy policy();
        ActorKind kind();
        Optional<ActorMemorySlice> privateMemory();
    }

    public final class ActorRef<M> {
        private final ActorId id;
        private final ActorKind kind;
        private final AtomicReference<Throwable> terminationCause = new AtomicReference<>();

        private ActorRef(ActorId id, ActorKind kind) {
            this.id = id;
            this.kind = kind;
        }

        public ActorId id() { return id; }
        public ActorKind kind() { return kind; }
        private boolean ownedBy(ActorRuntime runtime) { return ActorRuntime.this == runtime; }
        public boolean isAlive() { return ActorRuntime.this.isAlive(this); }
        public Optional<Throwable> failure() { return Optional.ofNullable(terminationCause.get()); }

        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            Objects.requireNonNull(unit);
            if (timeout < 0) throw new IllegalArgumentException("timeout must be non-negative");
            ActorCell<?> cell = actors.get(id);
            if (cell == null) return true;
            cell.awaitFinalized(unit.toNanos(timeout));
            return cell.finalized();
        }

        public void send(M message) {
            ActorRuntime.this.send(this, message);
        }

        public void stop() {
            ActorRuntime.this.stop(this);
        }

        @Override
        public String toString() {
            return "ActorRef[" + kind + ":" + id.value() + "]";
        }
    }

    private void requireCallerRuntimeAffinity(String operation) {
        ActorRuntime caller = currentActorRuntime();
        if (caller != null && caller != this) {
            throw new SecurityException(
                    "actor cannot " + operation + " through another ActorRuntime");
        }
    }

    private static void requireSupervisorContext(String operation) {
        if (inActorExecution()) {
            throw new SecurityException(
                    "actor code cannot " + operation + "; this operation belongs to the host/supervisor");
        }
    }

    private IsolatePolicy defaultSpawnPolicy() {
        IsolatePolicy caller = currentActorPolicy();
        return caller == null ? policyCeiling : caller;
    }

    private void requireWithinCallerPolicy(IsolatePolicy child) {
        IsolatePolicy caller = currentActorPolicy();
        if (caller == null) return;

        if (!caller.capabilities().containsAll(child.capabilities())) {
            java.util.Set<IsolatePolicy.Capability> excess = child.capabilities().isEmpty()
                    ? java.util.EnumSet.noneOf(IsolatePolicy.Capability.class)
                    : java.util.EnumSet.copyOf(child.capabilities());
            excess.removeAll(caller.capabilities());
            throw new SecurityException("child actor policy exceeds caller actor capabilities: " + excess);
        }
        if (child.maxHeapBytes() > caller.maxHeapBytes()) {
            throw new SecurityException("child actor maxHeapBytes exceeds caller actor policy");
        }
        if (child.maxMailboxMessages() > caller.maxMailboxMessages()) {
            throw new SecurityException("child actor mailbox limit exceeds caller actor policy");
        }
        if (child.maxWallTime().compareTo(caller.maxWallTime()) > 0) {
            throw new SecurityException("child actor wall-time limit exceeds caller actor policy");
        }
        if (caller.adversarial() && !child.adversarial()) {
            throw new SecurityException("child actor cannot weaken an adversarial caller policy");
        }
    }

    /** Backward-compatible default: an unqualified runtime actor is private. */
    public <M> ActorRef<M> spawn(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnPrivate(defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawn(
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnPrivate(policy, behaviorFactory);
    }

    public <M> ActorRef<M> spawnPrivate(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnPrivate(policyCeiling, behaviorFactory);
    }

    /**
     * Trusted-host compatibility path. Compiler-generated actors should prefer
     * the context-aware BehaviorFactory overload.
     */
    public <M> ActorRef<M> spawnPrivate(
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        requireSupervisorContext("use trusted Supplier private actor construction");
        Objects.requireNonNull(behaviorFactory);
        requireTrustedSupplierPolicy(policy);
        return spawnInternal(ActorKind.PRIVATE, policy, context -> behaviorFactory.get(), true);
    }

    /**
     * Isolation-safe private actor construction. The factory itself must be
     * stateless/capture-free; mutable actor state must be created after the
     * actor context is installed and stored in actor-owned memory.
     */
    public <M> ActorRef<M> spawnPrivate(BehaviorFactory<M> behaviorFactory) {
        return spawn(ActorKind.PRIVATE, defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawnPrivate(
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        return spawn(ActorKind.PRIVATE, policy, behaviorFactory);
    }

    /**
     * Explicit host-only escape hatch for tests/embedding code that needs a
     * context-aware factory with captured Java objects. Never used by Oreslang
     * compiler lowering and forbidden for adversarial policies.
     */
    public <M> ActorRef<M> spawnPrivateTrusted(BehaviorFactory<M> behaviorFactory) {
        return spawnPrivateTrusted(defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawnPrivateTrusted(
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        requireSupervisorContext("use trusted captured private actor construction");
        Objects.requireNonNull(behaviorFactory);
        requireTrustedSupplierPolicy(policy);
        return spawnInternal(ActorKind.PRIVATE, policy, behaviorFactory, true);
    }

    public <M> ActorRef<M> spawnShared(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnShared(defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawnShared(
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        requireSupervisorContext("use trusted Supplier shared actor construction");
        Objects.requireNonNull(behaviorFactory);
        requireTrustedSupplierPolicy(policy);
        return spawnInternal(ActorKind.SHARED, policy, context -> behaviorFactory.get(), true);
    }

    public <M> ActorRef<M> spawnShared(BehaviorFactory<M> behaviorFactory) {
        return spawn(ActorKind.SHARED, defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawnShared(
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        return spawn(ActorKind.SHARED, policy, behaviorFactory);
    }

    /**
     * Explicit host-only escape hatch for context-aware shared actor factories
     * that intentionally capture host objects. Compiler lowering must never use
     * this path. Adversarial policies reject it.
     */
    public <M> ActorRef<M> spawnSharedTrusted(BehaviorFactory<M> behaviorFactory) {
        return spawnSharedTrusted(defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawnSharedTrusted(
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        requireSupervisorContext("use trusted captured shared actor construction");
        Objects.requireNonNull(behaviorFactory);
        requireTrustedSupplierPolicy(policy);
        return spawnInternal(ActorKind.SHARED, policy, behaviorFactory, true);
    }

    /** Compatibility path for trusted host callers. */
    public <M> ActorRef<M> spawn(
            ActorKind kind,
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        requireSupervisorContext("use trusted Supplier actor construction");
        Objects.requireNonNull(behaviorFactory);
        requireTrustedSupplierPolicy(policy);
        return spawnInternal(kind, policy, context -> behaviorFactory.get(), true);
    }

    private static void requireStatelessActorFactory(Object factory) {
        for (Class<?> type = factory.getClass();
             type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                boolean isStatic = java.lang.reflect.Modifier.isStatic(modifiers);
                boolean isFinal = java.lang.reflect.Modifier.isFinal(modifiers);

                if (!isStatic) {
                    throw new SecurityException(
                            "actor BehaviorFactory must be stateless; captured host state must enter through explicit actor messages/capabilities");
                }
                if (!isFinal) {
                    throw new SecurityException(
                            "actor BehaviorFactory declares mutable static JVM state '"
                                    + field.getName() + "'; actor construction cannot share static state");
                }
                if (!field.trySetAccessible()) {
                    throw new SecurityException(
                            "actor BehaviorFactory contains inaccessible static state: " + field.getName());
                }
                final Object value;
                try {
                    value = field.get(null);
                } catch (IllegalAccessException impossible) {
                    throw new SecurityException(
                            "cannot inspect actor BehaviorFactory static state: " + field.getName(),
                            impossible);
                }
                if (!isPrivateStaticConstant(value)) {
                    throw new SecurityException(
                            "actor BehaviorFactory declares shared static object '"
                                    + field.getName()
                                    + "'; only immutable scalar constants are allowed");
                }
            }
        }
    }

    private void validatePrivateBehaviorState(ActorId owner, Behavior<?> behavior) {
        for (Class<?> type = behavior.getClass();
             type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                boolean isStatic = java.lang.reflect.Modifier.isStatic(modifiers);
                boolean isFinal = java.lang.reflect.Modifier.isFinal(modifiers);

                if (isStatic) {
                    if (!isFinal) {
                        throw new SecurityException(
                                "private actor behavior class declares mutable static JVM state '"
                                        + field.getName() + "'; private actors cannot share static state");
                    }
                    if (!field.trySetAccessible()) {
                        throw new SecurityException(
                                "private actor behavior contains inaccessible static state: " + field.getName());
                    }
                    final Object staticValue;
                    try {
                        staticValue = field.get(null);
                    } catch (IllegalAccessException impossible) {
                        throw new SecurityException(
                                "cannot inspect private actor static state: " + field.getName(),
                                impossible);
                    }
                    if (!isPrivateStaticConstant(staticValue)) {
                        throw new SecurityException(
                                "private actor behavior class declares shared static object '"
                                        + field.getName()
                                        + "'; only immutable scalar constants are allowed");
                    }
                    continue;
                }

                if (!isFinal) {
                    throw new SecurityException(
                            "private actor behavior field '" + field.getName()
                                    + "' is mutable JVM state; persistent mutable state must use context.privateMemory()");
                }
                if (!field.trySetAccessible()) {
                    throw new SecurityException(
                            "private actor behavior contains inaccessible captured state: " + field.getName());
                }
                final Object value;
                try {
                    value = field.get(behavior);
                } catch (IllegalAccessException impossible) {
                    throw new SecurityException(
                            "cannot inspect private actor behavior capture: " + field.getName(),
                            impossible);
                }
                validatePrivateBehaviorCapture(owner, field.getName(), value);
            }
        }
    }

    private static boolean isPrivateStaticConstant(Object value) {
        return value == null
                || isScalar(value)
                || value instanceof Class<?>;
    }

    private void validatePrivateBehaviorCapture(ActorId owner, String fieldName, Object value) {
        if (value == null || isScalar(value) || value instanceof Class<?>) return;

        if (value instanceof PrivateMemoryBlock block) {
            if (!block.owner().equals(owner)) {
                throw new SecurityException(
                        "private actor behavior captured another actor's memory block in " + fieldName);
            }
            return;
        }
        if (value instanceof ActorMemorySlice slice) {
            if (!slice.owner().equals(owner)) {
                throw new SecurityException(
                        "private actor behavior captured another actor's memory slice in " + fieldName);
            }
            return;
        }
        if (value instanceof MemoryReservation reservation) {
            if (!reservation.owner().equals(owner)) {
                throw new SecurityException(
                        "private actor behavior captured another actor's memory reservation in " + fieldName);
            }
            return;
        }
        if (value instanceof ActorRef<?> ref) {
            if (!ref.ownedBy(this)) {
                throw new SecurityException(
                        "private actor behavior captured an ActorRef from another runtime in " + fieldName);
            }
            return;
        }
        if (value instanceof ActorContext<?> actorContext) {
            if (!actorContext.self().id().equals(owner) || actorContext.runtime() != this) {
                throw new SecurityException(
                        "private actor behavior captured a foreign actor context in " + fieldName);
            }
            return;
        }

        throw new SecurityException(
                "private actor behavior captured mutable/non-private JVM state in "
                        + fieldName + " (" + value.getClass().getName()
                        + "); allocate persistent state through context.privateMemory()");
    }

    private void requireTrustedSupplierPolicy(IsolatePolicy policy) {
        Objects.requireNonNull(policy);
        if (policy.adversarial()) {
            throw new SecurityException(
                    "adversarial actors require the context-aware BehaviorFactory path; "
                            + "Supplier factories can capture host/shared mutable references");
        }
    }

    /**
     * Creates the actor identity and private memory slice immediately. Behavior
     * initialization later runs on that actor's dispatcher with the actor
     * context already installed.
     */
    public <M> ActorRef<M> spawn(
            ActorKind kind,
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        return spawnInternal(kind, policy, behaviorFactory, false);
    }

    private <M> ActorRef<M> spawnInternal(
            ActorKind kind,
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory,
            boolean trustedFactory) {
        requireCallerRuntimeAffinity("spawn actors");
        Objects.requireNonNull(kind);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(behaviorFactory);
        requireWithinCeiling(policy);
        IsolatePolicy effectivePolicy = kind == ActorKind.PRIVATE
                ? policy.withoutCapabilities(
                        IsolatePolicy.Capability.SHARED_MEMORY,
                        IsolatePolicy.Capability.ACTOR_SHARE_READONLY,
                        IsolatePolicy.Capability.JAVA_INTEROP)
                : policy;
        requireWithinCallerPolicy(effectivePolicy);
        if (kind == ActorKind.SHARED) {
            effectivePolicy.require(IsolatePolicy.Capability.SHARED_MEMORY, "shared actor spawn");
        }
        if (!trustedFactory) {
            requireStatelessActorFactory(behaviorFactory);
        }

        synchronized (runtimeLifecycleLock) {
            if (closed.get()) throw new IllegalStateException("actor runtime is closed");
            reserveActorSlot();

            ActorId id = ActorId.create();
            ActorRef<M> ref = new ActorRef<>(id, kind);
            try {
                ActorCell<M> cell = new ActorCell<>(
                    ref, kind, effectivePolicy, behaviorFactory, trustedFactory);
                actors.put(id, cell);
                return ref;
            } catch (RuntimeException | Error failure) {
                actorCount.decrementAndGet();
                throw failure;
            }
        }
    }

    /**
     * Compiler-only lowering target for source-level actor/isoactor callables.
     *
     * A fresh actor receives exactly one transported message, computes one
     * data-only result, then terminates. Synchronous nesting from an actor turn
     * is rejected so a bounded dispatcher cannot be starved by callers waiting
     * on actors scheduled onto the same runtime.
     */
    public <M, R> R invoke(
            ActorKind kind,
            M message,
            Invocation<M, R> invocation) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(invocation, "invocation");
        requireCallerRuntimeAffinity("invoke actor callables");
        if (inActorExecution()) {
            throw new IllegalStateException(
                    "synchronous actor-callable invocation from an actor turn is forbidden; "
                            + "use mailbox-oriented actor composition");
        }

        IsolatePolicy policy = defaultSpawnPolicy();
        CompletableFuture<R> completion = new CompletableFuture<>();
        ActorRef<M> ref = spawnInternal(
                kind,
                policy,
                factoryContext -> (delivered, turnContext) -> {
                    try {
                        @SuppressWarnings("unchecked")
                        R frozen = (R) freeze(invocation.run(delivered, turnContext));
                        completion.complete(frozen);
                    } catch (VirtualMachineError fatal) {
                        completion.completeExceptionally(fatal);
                        throw fatal;
                    } catch (ThreadDeath fatal) {
                        completion.completeExceptionally(fatal);
                        throw fatal;
                    } catch (LinkageError fatal) {
                        completion.completeExceptionally(fatal);
                        throw fatal;
                    } catch (Throwable failure) {
                        completion.completeExceptionally(failure);
                    } finally {
                        turnContext.self().stop();
                    }
                },
                true);

        try {
            try {
                send(ref, message);
            } catch (ActorTerminatedException terminatedBeforeDelivery) {
                Throwable startupFailure = ref.terminationCause.get();
                if (startupFailure instanceof RuntimeException runtime) throw runtime;
                if (startupFailure instanceof Error error) throw error;
                if (startupFailure != null) throw new RuntimeException(startupFailure);
                throw terminatedBeforeDelivery;
            }
            long timeoutNanos;
            try {
                timeoutNanos = policy.maxWallTime().toNanos();
            } catch (ArithmeticException overflow) {
                timeoutNanos = Long.MAX_VALUE;
            }
            if (timeoutNanos <= 0) timeoutNanos = 1;

            long startedAt = System.nanoTime();
            R result = null;
            ExecutionException executionFailure = null;
            try {
                result = completion.get(timeoutNanos, TimeUnit.NANOSECONDS);
            } catch (ExecutionException failed) {
                // The guest result/failure is not externally complete until
                // the one-shot actor has unwound and its carrier has crossed
                // back out of TurnExecutor (TruffleContext.leave in OresVM).
                executionFailure = failed;
            }

            long elapsed = Math.max(0L, System.nanoTime() - startedAt);
            long remaining = timeoutNanos == Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : Math.max(0L, timeoutNanos - elapsed);
            if (!ref.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                throw new TimeoutException(
                        "actor callable completed its guest result but did not leave its carrier before the wall-time deadline");
            }

            if (executionFailure != null) throw executionFailure;
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("actor callable invocation interrupted");
        } catch (TimeoutException timedOut) {
            throw new IllegalStateException(
                    "actor callable exceeded max wall time " + policy.maxWallTime(),
                    timedOut);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        } finally {
            if (ref.isAlive()) {
                try {
                    stop(ref);
                } catch (IllegalStateException alreadyStopping) {
                    if (ref.isAlive()) throw alreadyStopping;
                }
            }
        }
    }

    private void reserveActorSlot() {
        while (true) {
            int current = actorCount.get();
            if (current >= dispatcherConfig.maxActors()) {
                throw new IllegalStateException(
                        "actor runtime limit exceeded: maximum " + dispatcherConfig.maxActors());
            }
            if (actorCount.compareAndSet(current, current + 1)) return;
        }
    }

    private void unregisterActor(ActorCell<?> cell) {
        if (actors.remove(cell.ref.id(), cell)) {
            int remaining = actorCount.decrementAndGet();
            if (remaining < 0) {
                actorCount.incrementAndGet();
                throw new IllegalStateException("actor count accounting underflow");
            }
        }
    }

    public <T> SyncCell<T> syncCell(T initialValue) {
        requireCallerRuntimeAffinity("create shared SyncCell values");
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        IsolatePolicy callerPolicy = currentActorPolicy();
        if (callerPolicy != null) {
            callerPolicy.require(IsolatePolicy.Capability.SHARED_MEMORY, "SyncCell");
        } else {
            policyCeiling.require(IsolatePolicy.Capability.SHARED_MEMORY, "SyncCell");
        }
        rejectPrivateActorSharedMemoryAccess("SyncCell creation");
        SyncCell<T> cell = new SyncCell<>(initialValue);
        syncCells.add(cell);
        if (closed.get()) {
            cell.close();
            throw new IllegalStateException("actor runtime is closed");
        }
        return cell;
    }

    private boolean enterSyncCell(SyncCell<?> cell) {
        SyncCell<?> held = currentSyncCell.get();
        if (held == null) {
            currentSyncCell.set(cell);
            return true;
        }
        if (held != cell) {
            throw new IllegalStateException(
                    "nested synchronization across different SyncCell values is forbidden; "
                            + "snapshot values first or use one shared cell");
        }
        return false;
    }

    private void exitSyncCell(boolean entered) {
        if (entered) currentSyncCell.remove();
    }

    private void rejectPrivateActorSharedMemoryAccess(String operation) {
        ActorCell<?> current = currentActor.get();
        if (current != null && current.kind == ActorKind.PRIVATE) {
            throw new IllegalStateException("private actors cannot access synchronized shared memory via " + operation);
        }
    }

    private void requireSharedActorTurn() {
        ActorCell<?> cell = currentActor.get();
        if (cell == null || cell.kind != ActorKind.SHARED) {
            throw new IllegalStateException("shared state mutation requires a shared actor mailbox turn");
        }
    }

    private void reservePrivateRuntimeBytes(long bytes, ActorId owner, String purpose) {
        synchronized (memoryBudgetLock) {
            long privateBytes = privateMemoryBytes.get();
            long sharedBytes = sharedMemoryBytes.get();
            long total;
            try {
                total = Math.addExact(Math.addExact(privateBytes, sharedBytes), bytes);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException(purpose + " aggregate accounting overflow");
            }
            if (total > policyCeiling.maxHeapBytes()) {
                throw new IllegalStateException(purpose + " aggregate runtime limit exceeded for " + owner
                        + ": requested=" + bytes + " privateUsed=" + privateBytes
                        + " sharedUsed=" + sharedBytes + " runtimeLimit=" + policyCeiling.maxHeapBytes());
            }
            privateMemoryBytes.addAndGet(bytes);
        }
    }

    private void releasePrivateRuntimeBytes(long bytes, ActorId owner) {
        if (bytes == 0) return;
        synchronized (memoryBudgetLock) {
            long current = privateMemoryBytes.get();
            if (bytes > current) {
                throw new IllegalStateException(
                        "private actor aggregate memory accounting underflow for " + owner
                                + ": release=" + bytes + " privateUsed=" + current);
            }
            privateMemoryBytes.set(current - bytes);
        }
    }

    private void reserveSharedRuntimeBytes(long bytes, String purpose) {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        if (bytes < 0) throw new IllegalArgumentException("shared memory reservation cannot be negative");
        if (bytes == 0) return;
        synchronized (memoryBudgetLock) {
            long privateBytes = privateMemoryBytes.get();
            long sharedBytes = sharedMemoryBytes.get();
            long total;
            try {
                total = Math.addExact(Math.addExact(privateBytes, sharedBytes), bytes);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException(purpose + " aggregate accounting overflow");
            }
            if (total > policyCeiling.maxHeapBytes()) {
                throw new IllegalStateException(purpose + " aggregate runtime limit exceeded"
                        + ": requested=" + bytes + " privateUsed=" + privateBytes
                        + " sharedUsed=" + sharedBytes + " runtimeLimit=" + policyCeiling.maxHeapBytes());
            }
            sharedMemoryBytes.addAndGet(bytes);
        }
    }

    private void releaseSharedRuntimeBytes(long bytes) {
        if (bytes == 0 || closed.get()) return;
        long remaining = sharedMemoryBytes.addAndGet(-bytes);
        if (remaining < 0) {
            sharedMemoryBytes.set(0);
            throw new IllegalStateException("shared actor memory accounting underflow");
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

    public boolean isAlive(ActorRef<?> ref) {
        Objects.requireNonNull(ref);
        if (!ref.ownedBy(this)) return false;
        ActorCell<?> cell = actors.get(ref.id());
        return cell != null && !cell.stopped.get();
    }

    public void stop(ActorRef<?> ref) {
        requireCallerRuntimeAffinity("stop actors");
        Objects.requireNonNull(ref);
        if (!ref.ownedBy(this)) {
            throw new IllegalArgumentException("ActorRef belongs to a different ActorRuntime");
        }
        ActorCell<?> cell = actors.get(ref.id());
        if (cell == null) return;

        cell.stop();

        // A host/supervisor stop is a synchronization point: once it returns,
        // private actor memory and actor-count quota have been reclaimed. A
        // self-stop from inside the actor turn cannot wait for itself; endTurn()
        // finalizes it immediately after the current turn unwinds.
        if (currentActor.get() == cell) return;

        try {
            cell.awaitFinalized(CLOSE_WAIT_NANOS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while waiting for actor " + ref.id() + " to finalize",
                    interrupted);
        }
        if (!cell.finalized()) {
            throw new IllegalStateException(
                    "actor " + ref.id() + " did not finalize within the stop deadline");
        }
    }

    private ActorTerminatedException terminated(ActorRef<?> ref) {
        return new ActorTerminatedException(ref.id(), ref.kind(), ref.terminationCause.get());
    }

    @SuppressWarnings("unchecked")
    public <M> void send(ActorRef<M> ref, M message) {
        requireCallerRuntimeAffinity("send messages");
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        Objects.requireNonNull(ref);
        if (!ref.ownedBy(this)) {
            throw new IllegalArgumentException("ActorRef belongs to a different ActorRuntime");
        }
        ActorCell<M> cell = (ActorCell<M>) actors.get(ref.id());
        if (cell == null || cell.stopped.get()) throw terminated(ref);
        if (!cell.reserveMailboxSlot()) {
            throw new IllegalStateException("actor mailbox limit exceeded for " + ref.id());
        }
        boolean mailboxSlotTransferred = false;
        try {
        validateMessageGraph(message);
        requireMutexTransport(cell, message, new IdentityHashMap<>(), 0);
        requireOwnedActorRefs(message, new IdentityHashMap<>(), 0);
        long runtimeRemaining = Math.max(0L, policyCeiling.maxHeapBytes() - actorMemoryBytes());
        if (cell.kind == ActorKind.SHARED) {
            requireOwnedSharedHandles(message, new IdentityHashMap<>(), 0);
            long actorRemaining = Math.max(0L, cell.policy.maxHeapBytes() - cell.sharedMailboxBytes.get());
            long allowed = Math.min(actorRemaining, runtimeRemaining);
            try {
                estimateSharedTransportBytes(message, new IdentityHashMap<>(), 0, allowed);
            } catch (IllegalStateException tooLarge) {
                throw new IllegalStateException(
                        "shared actor mailbox memory limit exceeded for " + ref.id() + ": " + tooLarge.getMessage(),
                        tooLarge);
            }
        } else {
            long actorRemaining = cell.memorySlice.remainingBytes();
            try {
                estimatePrivateTransportBytes(message, new IdentityHashMap<>(), 0, actorRemaining);
            } catch (IllegalStateException tooLarge) {
                throw new IllegalStateException(
                        "private actor mailbox limit exceeded for " + ref.id() + ": " + tooLarge.getMessage(),
                        tooLarge);
            }
            try {
                estimatePrivateTransportBytes(message, new IdentityHashMap<>(), 0, runtimeRemaining);
            } catch (IllegalStateException aggregateExceeded) {
                throw new IllegalStateException(
                        "private actor aggregate runtime limit exceeded for " + ref.id()
                                + ": " + aggregateExceeded.getMessage(),
                        aggregateExceeded);
            }
        }

        if (closed.get()) throw new IllegalStateException("actor runtime is closed");

        Object prepared = cell.kind == ActorKind.PRIVATE ? isolateCopy(message) : freezeForTransport(message);
        Runnable release;
        if (cell.kind == ActorKind.PRIVATE) {
            MemoryReservation reservation;
            try {
                reservation = cell.memorySlice.reserveMailbox(prepared);
            } catch (IllegalStateException exceeded) {
                throw new IllegalStateException(
                        "private actor mailbox limit exceeded for " + ref.id() + ": " + exceeded.getMessage(),
                        exceeded);
            }
            release = reservation::close;
        } else {
            long bytes = estimateSharedMailboxBytes(prepared, new IdentityHashMap<>(), 0);
            cell.reserveSharedMailbox(bytes);
            release = () -> cell.releaseSharedMailbox(bytes);
        }

        MessageEnvelope envelope = new MessageEnvelope(prepared, release);
        List<OresMutex.Shared<?>> sharedMutexReservations = List.of();
        boolean admitted = false;
        try {
            synchronized (runtimeLifecycleLock) {
                if (closed.get()) throw new IllegalStateException("actor runtime is closed");
                if (cell.kind == ActorKind.SHARED) {
                    sharedMutexReservations = reserveSharedMutexBindings(prepared);
                }
                synchronized (cell.lifecycleLock) {
                    if (cell.stopped.get()) {
                        throw terminated(ref);
                    }
                    if (!cell.mailbox.offer(envelope)) {
                        throw new IllegalStateException("actor mailbox limit exceeded for " + ref.id());
                    }
                    mailboxSlotTransferred = true;
                    admitted = true;
                    commitSharedMutexBindings(sharedMutexReservations);
                }
            }
        } finally {
            if (!admitted) {
                abortSharedMutexBindings(sharedMutexReservations);
                envelope.close();
            }
        }
        cell.schedule();
        } finally {
            if (!mailboxSlotTransferred) cell.releaseMailboxSlot();
        }
    }

    /**
     * Cooperative scheduler hook used by compiler-injected loop safepoints.
     * Carrier threads remain an implementation detail.
     */
    public void schedulerSafepoint() {
        if (closed.get()) throw new CancellationException("actor runtime is closing");
        ActorCell<?> cell = currentActor.get();
        if (cell != null && cell.stopped.get()) {
            throw new CancellationException("actor execution stopped");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("actor execution interrupted");
        }
        Thread.yield();
    }

    @SuppressWarnings("unchecked")
    public <T> Shared<T> shareReadonly(T value) {
        requireCallerRuntimeAffinity("share readonly values");
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        IsolatePolicy callerPolicy = currentActorPolicy();
        if (callerPolicy != null) {
            callerPolicy.require(IsolatePolicy.Capability.ACTOR_SHARE_READONLY, "shareReadonly");
        } else {
            policyCeiling.require(IsolatePolicy.Capability.ACTOR_SHARE_READONLY, "shareReadonly");
        }
        rejectPrivateActorSharedMemoryAccess("shareReadonly");
        requireOwnedSharedHandles(value, new IdentityHashMap<>(), 0);
        Object frozen = freeze(value);
        rejectSharedMutableHandles(frozen, new IdentityHashMap<>(), 0);
        long bytes = estimateFrozenBytes(frozen);
        reserveSharedRuntimeBytes(bytes, "shared readonly value");
        Shared<T> shared = new Shared<>((T) frozen, bytes);
        sharedValues.add(shared);
        if (closed.get()) {
            shared.closeFromRuntime();
            throw new IllegalStateException("actor runtime is closed");
        }
        return shared;
    }

    private void requireMutexTransport(
            ActorCell<?> target,
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value) || value instanceof ActorRuntime.ActorRef<?>) return;
        if (value instanceof OresMutex.Local<?>) {
            throw new IllegalArgumentException("Mutex<T> is actor-local state and cannot cross actor mailboxes");
        }
        if (value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("MutexGuard<T> is lexical and cannot cross actor mailboxes");
        }
        if (value instanceof OresMutex.Shared<?> sharedMutex) {
            if (target.kind != ActorKind.SHARED) {
                throw new SecurityException("private actors cannot receive SharedMutex<T>");
            }
            ActorKind senderKind = currentActorKind();
            if (senderKind == ActorKind.PRIVATE) {
                throw new SecurityException("private actors cannot send SharedMutex<T>");
            }
            IsolatePolicy senderPolicy = currentActorPolicy();
            if (senderPolicy != null) {
                senderPolicy.require(IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex actor send");
            } else {
                policyCeiling.require(IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex host send");
            }
            target.policy.require(IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex actor receive");
            sharedMutex.inspectForTransport(payload ->
                    requireSharedMutexPayloadSafe(
                            payload,
                            new IdentityHashMap<>(),
                            depth + 1));
            return;
        }
        if (value instanceof Shared<?> shared) {
            requireMutexTransport(target, shared.value(), visiting, depth + 1);
            return;
        }
        if (value instanceof SyncCell<?>) return;
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) requireMutexTransport(target, item, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) requireMutexTransport(target, item, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    requireMutexTransport(target, entry.getKey(), visiting, depth + 1);
                    requireMutexTransport(target, entry.getValue(), visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    requireMutexTransport(target, Array.get(value, i), visiting, depth + 1);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private void requireSharedMutexPayloadSafe(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)) return;

        if (value instanceof ActorRuntime.ActorRef<?> ref) {
            if (!ref.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "SharedMutex payload contains ActorRef from another ActorRuntime");
            }
            return;
        }
        if (value instanceof Shared<?> shared) {
            if (!shared.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "SharedMutex payload contains Shared value from another ActorRuntime");
            }
            requireSharedMutexPayloadSafe(shared.value(), visiting, depth + 1);
            return;
        }
        if (value instanceof OresMutex.Shared<?>) {
            throw new IllegalArgumentException(
                    "SharedMutex payload cannot contain another SharedMutex; nested shared locks are not transport-safe until recursive lock-order semantics are defined");
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException(
                    "SharedMutex payload cannot contain actor-local mutex state");
        }
        if (value instanceof SyncCell<?>) {
            throw new IllegalArgumentException(
                    "SharedMutex payload cannot contain SyncCell writable shared state");
        }
        if (value instanceof java.util.concurrent.CompletionStage<?>) {
            throw new IllegalArgumentException(
                    "SharedMutex payload cannot contain pending/asynchronous computation state");
        }

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic SharedMutex payload is not runtime-shared-safe");
        }
        try {
            if (value instanceof OresMutex.SharedState aggregate) {
                for (Object child : aggregate.sharedStateChildren()) {
                    requireSharedMutexPayloadSafe(child, visiting, depth + 1);
                }
                return;
            }
            if (value instanceof List<?> list) {
                for (Object item : list) requireSharedMutexPayloadSafe(item, visiting, depth + 1);
                return;
            }
            if (value instanceof Set<?> set) {
                for (Object item : set) requireSharedMutexPayloadSafe(item, visiting, depth + 1);
                return;
            }
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    requireSharedMutexPayloadSafe(entry.getKey(), visiting, depth + 1);
                    requireSharedMutexPayloadSafe(entry.getValue(), visiting, depth + 1);
                }
                return;
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    requireSharedMutexPayloadSafe(Array.get(value, i), visiting, depth + 1);
                }
                return;
            }
            throw new IllegalArgumentException(
                    "SharedMutex payload contains opaque host value of type "
                            + value.getClass().getName());
        } finally {
            visiting.remove(value);
        }
    }

    private void requireOwnedActorRefs(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)) return;
        if (value instanceof ActorRuntime.ActorRef<?> ref) {
            if (!ref.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "ActorRef belongs to a different ActorRuntime; cross-runtime actor channels require an explicit bridge");
            }
            return;
        }
        if (value instanceof Shared<?> shared) {
            requireOwnedActorRefs(shared.value(), visiting, depth + 1);
            return;
        }
        if (value instanceof SyncCell<?>) return;
        if (value instanceof OresMutex.Shared<?> sharedMutex) {
            requireOwnedActorRefs(sharedMutex.transportValue(), visiting, depth + 1);
            return;
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross actor boundaries");
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) requireOwnedActorRefs(item, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) requireOwnedActorRefs(item, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    requireOwnedActorRefs(entry.getKey(), visiting, depth + 1);
                    requireOwnedActorRefs(entry.getValue(), visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    requireOwnedActorRefs(Array.get(value, i), visiting, depth + 1);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private void requireOwnedSharedHandles(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)) return;
        if (value instanceof ActorRuntime.ActorRef<?> ref) {
            if (!ref.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "ActorRef belongs to a different ActorRuntime; cross-runtime actor channels require an explicit bridge");
            }
            return;
        }
        if (value instanceof Shared<?> shared) {
            if (!shared.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "Shared value belongs to a different ActorRuntime; copy/freeze it into the destination runtime");
            }
            shared.value();
            return;
        }
        if (value instanceof SyncCell<?> cell) {
            if (!cell.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "SyncCell belongs to a different ActorRuntime and cannot cross shared-memory domains");
            }
            if (cell.closed()) throw new IllegalArgumentException("SyncCell is closed");
            return;
        }
        if (value instanceof OresMutex.Shared<?>) {
            // Runtime affinity is reserved atomically immediately before mailbox admission.
            return;
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross shared-memory domains");
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) requireOwnedSharedHandles(item, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) requireOwnedSharedHandles(item, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    requireOwnedSharedHandles(entry.getKey(), visiting, depth + 1);
                    requireOwnedSharedHandles(entry.getValue(), visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    requireOwnedSharedHandles(Array.get(value, i), visiting, depth + 1);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private List<OresMutex.Shared<?>> reserveSharedMutexBindings(Object value) {
        Set<OresMutex.Shared<?>> unique = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        collectSharedMutexes(value, unique, new IdentityHashMap<>(), 0);

        List<OresMutex.Shared<?>> reserved = new ArrayList<>(unique.size());
        try {
            for (OresMutex.Shared<?> mutex : unique) {
                if (!mutex.reserveRuntimePublication(this)) {
                    throw new IllegalArgumentException(
                            "SharedMutex may cross actor mailboxes only within its owning ActorRuntime");
                }
                reserved.add(mutex);
            }
            return List.copyOf(reserved);
        } catch (RuntimeException | Error failure) {
            abortSharedMutexBindings(reserved);
            throw failure;
        }
    }

    private static void collectSharedMutexes(
            Object value,
            Set<OresMutex.Shared<?>> out,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)
                || value instanceof ActorRuntime.ActorRef<?>
                || value instanceof SyncCell<?>) return;
        if (value instanceof OresMutex.Shared<?> sharedMutex) {
            if (!out.add(sharedMutex)) return;
            if (visiting.put(value, Boolean.TRUE) != null) return;
            try {
                collectSharedMutexes(
                        sharedMutex.transportValue(),
                        out,
                        visiting,
                        depth + 1);
            } finally {
                visiting.remove(value);
            }
            return;
        }
        if (value instanceof Shared<?> shared) {
            collectSharedMutexes(shared.value(), out, visiting, depth + 1);
            return;
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) return;
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) collectSharedMutexes(item, out, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) collectSharedMutexes(item, out, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    collectSharedMutexes(entry.getKey(), out, visiting, depth + 1);
                    collectSharedMutexes(entry.getValue(), out, visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    collectSharedMutexes(Array.get(value, i), out, visiting, depth + 1);
                }
            }
        } finally {
            visiting.remove(value);
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

    private static void rejectSharedMutableHandles(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value) || value instanceof ActorRuntime.ActorRef<?>) return;
        if (value instanceof ActorRuntime.SyncCell<?>) {
            throw new IllegalArgumentException("SyncCell is mutable shared state and cannot be wrapped as Shared");
        }
        if (value instanceof OresMutex.Shared<?>) {
            throw new IllegalArgumentException("SharedMutex is mutable shared state and cannot be wrapped as Shared");
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot be wrapped as Shared");
        }
        if (value instanceof Shared<?> shared) {
            shared.value();
            return;
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot be shared read-only");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) rejectSharedMutableHandles(item, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) rejectSharedMutableHandles(item, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    rejectSharedMutableHandles(entry.getKey(), visiting, depth + 1);
                    rejectSharedMutableHandles(entry.getValue(), visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    rejectSharedMutableHandles(Array.get(value, i), visiting, depth + 1);
                }
            } else {
                throw new IllegalArgumentException("value of type " + value.getClass().getName()
                        + " is not a runtime-owned immutable actor value");
            }
        } finally {
            visiting.remove(value);
        }
    }

    private static void validateMessageGraph(Object value) {
        validateMessageGraph(value, new IdentityHashMap<>(), 0, new long[]{0L});
    }

    private static void validateMessageGraph(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth,
            long[] nodes) {
        requireGraphDepth(depth);
        if (++nodes[0] > MAX_MESSAGE_GRAPH_NODES) {
            throw new IllegalArgumentException(
                    "actor message graph exceeds maximum node count " + MAX_MESSAGE_GRAPH_NODES);
        }
        if (value == null || isScalar(value)
                || value instanceof ActorRuntime.ActorRef<?>
                || value instanceof Shared<?>
                || value instanceof SyncCell<?>
                || value instanceof OresMutex.Shared<?>) {
            return;
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            return; // transport-specific validation produces the semantic error.
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                requireGraphNodeCapacity(nodes[0], list.size());
                for (Object item : list) validateMessageGraph(item, visiting, depth + 1, nodes);
            } else if (value instanceof Set<?> set) {
                requireGraphNodeCapacity(nodes[0], set.size());
                for (Object item : set) validateMessageGraph(item, visiting, depth + 1, nodes);
            } else if (value instanceof Map<?, ?> map) {
                requireGraphNodeCapacity(nodes[0], Math.multiplyExact((long) map.size(), 2L));
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    validateMessageGraph(entry.getKey(), visiting, depth + 1, nodes);
                    validateMessageGraph(entry.getValue(), visiting, depth + 1, nodes);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                requireGraphNodeCapacity(nodes[0], length);
                for (int i = 0; i < length; i++) {
                    validateMessageGraph(Array.get(value, i), visiting, depth + 1, nodes);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private static void requireGraphNodeCapacity(long alreadyVisited, long additionalNodes) {
        if (additionalNodes < 0
                || additionalNodes > (long) MAX_MESSAGE_GRAPH_NODES - alreadyVisited) {
            throw new IllegalArgumentException(
                    "actor message graph exceeds maximum node count " + MAX_MESSAGE_GRAPH_NODES);
        }
    }

    private static void requireGraphDepth(int depth) {
        if (depth > MAX_MESSAGE_GRAPH_DEPTH) {
            throw new IllegalArgumentException(
                    "actor message graph exceeds maximum nesting depth " + MAX_MESSAGE_GRAPH_DEPTH);
        }
    }

    /**
     * Converts supported values into a deeply immutable/sendable graph.
     * Unknown host objects are rejected instead of being passed by reference.
     */
    public static Object freeze(Object value) {
        validateMessageGraph(value);
        rejectDataFreezeCapabilities(value, new IdentityHashMap<>(), 0);
        return freeze(value, new IdentityHashMap<>(), 0);
    }

    private static Object freezeForTransport(Object value) {
        validateMessageGraph(value);
        return freeze(value, new IdentityHashMap<>(), 0);
    }

    private static void rejectDataFreezeCapabilities(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)) return;
        if (value instanceof ActorRuntime.ActorRef<?>
                || value instanceof Shared<?>
                || value instanceof SyncCell<?>
                || value instanceof OresMutex.Lock<?>
                || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException(
                    "freeze() accepts data values only; live actor/shared capabilities require explicit actor transport");
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot be frozen");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) rejectDataFreezeCapabilities(item, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) rejectDataFreezeCapabilities(item, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    rejectDataFreezeCapabilities(entry.getKey(), visiting, depth + 1);
                    rejectDataFreezeCapabilities(entry.getValue(), visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    rejectDataFreezeCapabilities(Array.get(value, i), visiting, depth + 1);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private static Object freeze(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (isScalar(value)) return value;
        if (value instanceof Shared<?> shared) {
            shared.value();
            return shared;
        }
        if (value instanceof ActorRuntime.ActorRef<?> ref) return ref;
        if (value instanceof ActorRuntime.SyncCell<?> cell) return cell;
        if (value instanceof OresMutex.Shared<?> sharedMutex) return sharedMutex;
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross actor boundaries");
        }

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                List<Object> frozen = new ArrayList<>(list.size());
                for (Object item : list) frozen.add(freeze(item, visiting, depth + 1));
                return List.copyOf(frozen);
            }
            if (value instanceof Set<?> set) {
                LinkedHashSet<Object> frozen = new LinkedHashSet<>();
                for (Object item : set) frozen.add(freeze(item, visiting, depth + 1));
                return Collections.unmodifiableSet(frozen);
            }
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> frozen = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    frozen.put(freeze(entry.getKey(), visiting, depth + 1), freeze(entry.getValue(), visiting, depth + 1));
                }
                return Collections.unmodifiableMap(frozen);
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                List<Object> frozen = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    frozen.add(freeze(Array.get(value, i), visiting, depth + 1));
                }
                return List.copyOf(frozen);
            }
            throw new IllegalArgumentException("value of type " + value.getClass().getName()
                    + " is not Sendable; mutable host objects cannot cross actor boundaries");
        } finally {
            visiting.remove(value);
        }
    }

    /**
     * Private transport never retains a shared mutable reference. Immutable
     * shared wrappers are unwrapped and copied into the private message graph.
     */
    private static Object isolateCopy(Object value) {
        return isolateCopy(value, new IdentityHashMap<>(), 0);
    }

    private static Object isolateCopy(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (isScalar(value)) return value;
        if (value instanceof ActorRuntime.SyncCell<?>) {
            throw new IllegalArgumentException("private actors cannot receive shared SyncCell values");
        }
        if (value instanceof OresMutex.Shared<?>) {
            throw new IllegalArgumentException("private actors cannot receive SharedMutex<T>");
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross actor boundaries");
        }
        if (value instanceof Shared<?> shared) return isolateCopy(shared.value(), visiting, depth + 1);
        if (value instanceof ActorRuntime.ActorRef<?> ref) return ref;

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross private actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                List<Object> copy = new ArrayList<>(list.size());
                for (Object item : list) copy.add(isolateCopy(item, visiting, depth + 1));
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof Set<?> set) {
                LinkedHashSet<Object> copy = new LinkedHashSet<>();
                for (Object item : set) copy.add(isolateCopy(item, visiting, depth + 1));
                return Collections.unmodifiableSet(copy);
            }
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    copy.put(isolateCopy(entry.getKey(), visiting, depth + 1), isolateCopy(entry.getValue(), visiting, depth + 1));
                }
                return Collections.unmodifiableMap(copy);
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                List<Object> copy = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    copy.add(isolateCopy(Array.get(value, i), visiting, depth + 1));
                }
                return Collections.unmodifiableList(copy);
            }
            throw new IllegalArgumentException("value of type " + value.getClass().getName()
                    + " is not Sendable; mutable host objects cannot cross actor boundaries");
        } finally {
            visiting.remove(value);
        }
    }

    private static long estimateSharedTransportBytes(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth,
            long limit) {
        requireGraphDepth(depth);
        if (limit < 0) throw new IllegalStateException("message exceeds remaining actor memory");

        long scalar = scalarLogicalBytes(value);
        if (scalar >= 0) return requireWithinLimit(scalar, limit);
        if (value instanceof Shared<?>) return requireWithinLimit(48L, limit);
        if (value instanceof ActorRuntime.SyncCell<?>) return requireWithinLimit(64L, limit);
        if (value instanceof OresMutex.Shared<?>) return requireWithinLimit(64L, limit);
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross actor boundaries");
        }
        if (value instanceof ActorRuntime.ActorRef<?>) return requireWithinLimit(48L, limit);

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                long total = requireWithinLimit(containerBase(24L, 8L, list.size()), limit);
                for (Object item : list) {
                    total = addWithinLimit(total,
                            estimateSharedTransportBytes(item, visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value instanceof Set<?> set) {
                long total = requireWithinLimit(containerBase(24L, 16L, set.size()), limit);
                for (Object item : set) {
                    total = addWithinLimit(total,
                            estimateSharedTransportBytes(item, visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value instanceof Map<?, ?> map) {
                long total = requireWithinLimit(containerBase(24L, 32L, map.size()), limit);
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    total = addWithinLimit(total,
                            estimateSharedTransportBytes(entry.getKey(), visiting, depth + 1, limit - total),
                            limit);
                    total = addWithinLimit(total,
                            estimateSharedTransportBytes(entry.getValue(), visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                long total = requireWithinLimit(containerBase(24L, 8L, length), limit);
                for (int i = 0; i < length; i++) {
                    total = addWithinLimit(total,
                            estimateSharedTransportBytes(
                                    Array.get(value, i), visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            throw new IllegalArgumentException("value of type " + value.getClass().getName()
                    + " is not Sendable; mutable host objects cannot cross actor boundaries");
        } finally {
            visiting.remove(value);
        }
    }

    private static long estimateSharedMailboxBytes(
            Object value,
            IdentityHashMap<Object, Boolean> seen,
            int depth) {
        requireGraphDepth(depth);
        long scalar = scalarLogicalBytes(value);
        if (scalar >= 0) return scalar;
        if (value instanceof Shared<?>) return 48L;
        if (value instanceof ActorRuntime.SyncCell<?>) return 64L;
        if (value instanceof OresMutex.Shared<?>) return 64L;
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross actor boundaries");
        }
        if (value instanceof ActorRuntime.ActorRef<?>) return 48L;
        if (seen.put(value, Boolean.TRUE) != null) return 0L;

        long bytes = 24L;
        if (value instanceof List<?> list) {
            bytes = Math.addExact(bytes, 8L * list.size());
            for (Object item : list) {
                bytes = Math.addExact(bytes, estimateSharedMailboxBytes(item, seen, depth + 1));
            }
            return bytes;
        }
        if (value instanceof Set<?> set) {
            bytes = Math.addExact(bytes, 16L * set.size());
            for (Object item : set) {
                bytes = Math.addExact(bytes, estimateSharedMailboxBytes(item, seen, depth + 1));
            }
            return bytes;
        }
        if (value instanceof Map<?, ?> map) {
            bytes = Math.addExact(bytes, 32L * map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                bytes = Math.addExact(bytes, estimateSharedMailboxBytes(entry.getKey(), seen, depth + 1));
                bytes = Math.addExact(bytes, estimateSharedMailboxBytes(entry.getValue(), seen, depth + 1));
            }
            return bytes;
        }
        return 64L;
    }

    /**
     * Validates and estimates a private-actor message before allocating its
     * isolation copy. The walk short-circuits as soon as the destination or
     * parent-runtime budget cannot admit the logical graph.
     */
    private static long estimatePrivateTransportBytes(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth,
            long limit) {
        requireGraphDepth(depth);
        if (limit < 0) throw new IllegalStateException("message exceeds remaining actor memory");

        long scalar = scalarLogicalBytes(value);
        if (scalar >= 0) return requireWithinLimit(scalar, limit);

        if (value instanceof ActorRuntime.SyncCell<?>) {
            throw new IllegalArgumentException("private actors cannot receive shared SyncCell values");
        }
        if (value instanceof Shared<?> shared) {
            return estimatePrivateTransportBytes(shared.value(), visiting, depth + 1, limit);
        }
        if (value instanceof ActorRuntime.ActorRef<?>) return requireWithinLimit(48L, limit);

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross private actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                long total = requireWithinLimit(containerBase(24L, 8L, list.size()), limit);
                for (Object item : list) {
                    total = addWithinLimit(total,
                            estimatePrivateTransportBytes(item, visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value instanceof Set<?> set) {
                long total = requireWithinLimit(containerBase(24L, 16L, set.size()), limit);
                for (Object item : set) {
                    total = addWithinLimit(total,
                            estimatePrivateTransportBytes(item, visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value instanceof Map<?, ?> map) {
                long total = requireWithinLimit(containerBase(24L, 32L, map.size()), limit);
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    total = addWithinLimit(total,
                            estimatePrivateTransportBytes(entry.getKey(), visiting, depth + 1, limit - total),
                            limit);
                    total = addWithinLimit(total,
                            estimatePrivateTransportBytes(entry.getValue(), visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                long total = requireWithinLimit(containerBase(24L, 8L, length), limit);
                for (int i = 0; i < length; i++) {
                    total = addWithinLimit(total,
                            estimatePrivateTransportBytes(
                                    Array.get(value, i), visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            throw new IllegalArgumentException("value of type " + value.getClass().getName()
                    + " is not Sendable; mutable host objects cannot cross actor boundaries");
        } finally {
            visiting.remove(value);
        }
    }

    private static long scalarLogicalBytes(Object value) {
        if (value == null) return 8L;
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Character || value instanceof Integer || value instanceof Float) return 16L;
        if (value instanceof Long || value instanceof Double) return 24L;
        if (value instanceof BigInteger integer) return 32L + integer.toByteArray().length;
        if (value instanceof BigDecimal decimal) return 48L + decimal.unscaledValue().toByteArray().length;
        if (value instanceof String string) return 40L + (long) string.length() * 2L;
        if (value instanceof UUID || value instanceof ActorId) return 40L;
        if (value instanceof Enum<?>) return 24L;
        return -1L;
    }

    private static long containerBase(long header, long perEntry, int count) {
        try {
            return Math.addExact(header, Math.multiplyExact(perEntry, (long) count));
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("actor message size accounting overflow");
        }
    }

    private static long requireWithinLimit(long bytes, long limit) {
        if (bytes > limit) {
            throw new IllegalStateException(
                    "message requires at least " + bytes + " bytes but only " + limit + " remain");
        }
        return bytes;
    }

    private static long addWithinLimit(long left, long right, long limit) {
        long total;
        try {
            total = Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("actor message size accounting overflow");
        }
        return requireWithinLimit(total, limit);
    }

    /**
     * Conservative language-level footprint estimate. This is a quota metric,
     * not a promise about HotSpot/Graal object layout.
     */
    private static long estimateFrozenBytes(Object value) {
        return estimateFrozenBytes(value, new IdentityHashMap<>(), 0);
    }

    private static long estimateFrozenBytes(
            Object value,
            IdentityHashMap<Object, Boolean> seen,
            int depth) {
        requireGraphDepth(depth);
        if (value == null) return 8L;
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Character || value instanceof Integer || value instanceof Float) return 16L;
        if (value instanceof Long || value instanceof Double) return 24L;
        if (value instanceof BigInteger integer) return 32L + integer.toByteArray().length;
        if (value instanceof BigDecimal decimal) return 48L + decimal.unscaledValue().toByteArray().length;
        if (value instanceof String string) return 40L + (long) string.length() * 2L;
        if (value instanceof UUID || value instanceof ActorId) return 40L;
        if (value instanceof Enum<?>) return 24L;
        if (value instanceof ActorRuntime.ActorRef<?>) return 48L;
        if (value instanceof ActorRuntime.SyncCell<?>) return 64L;
        if (value instanceof OresMutex.Shared<?>) return 64L;
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot be frozen");
        }
        if (value instanceof Shared<?> shared) return estimateFrozenBytes(shared.value(), seen, depth + 1);

        if (seen.put(value, Boolean.TRUE) != null) return 0L;

        long bytes = 24L;
        if (value instanceof List<?> list) {
            bytes = Math.addExact(bytes, 8L * list.size());
            for (Object item : list) bytes = Math.addExact(bytes, estimateFrozenBytes(item, seen, depth + 1));
            return bytes;
        }
        if (value instanceof Set<?> set) {
            bytes = Math.addExact(bytes, 16L * set.size());
            for (Object item : set) bytes = Math.addExact(bytes, estimateFrozenBytes(item, seen, depth + 1));
            return bytes;
        }
        if (value instanceof Map<?, ?> map) {
            bytes = Math.addExact(bytes, 32L * map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                bytes = Math.addExact(bytes, estimateFrozenBytes(entry.getKey(), seen, depth + 1));
                bytes = Math.addExact(bytes, estimateFrozenBytes(entry.getValue(), seen, depth + 1));
            }
            return bytes;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            bytes = Math.addExact(bytes, 8L * length);
            for (int i = 0; i < length; i++) {
                bytes = Math.addExact(bytes, estimateFrozenBytes(Array.get(value, i), seen, depth + 1));
            }
            return bytes;
        }
        return 64L;
    }

    private static boolean isScalar(Object value) {
        return value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Enum<?> || value instanceof UUID || value instanceof ActorId;
    }

    @Override
    public void close() {
        requireSupervisorContext("close an ActorRuntime");

        final boolean firstClose;
        final List<ActorCell<?>> snapshot;
        synchronized (runtimeLifecycleLock) {
            firstClose = closed.compareAndSet(false, true);
            snapshot = List.copyOf(actors.values());
        }
        for (ActorCell<?> cell : snapshot) cell.stop();

        if (firstClose) {
            // Interrupt carrier workers. Actor turns that deliberately consume
            // the interrupt are still tracked below and prevent close from
            // reporting success until they actually leave the runtime.
            privateDispatcher.shutdownNow();
            sharedDispatcher.shutdownNow();

            // shutdownNow() removes queued tasks without invoking runBatch().
            // Clear those cells' scheduled bits only when no carrier actually
            // entered the TurnExecutor boundary. Active carriers retain the bit
            // until their executor finally exits.
            for (ActorCell<?> cell : snapshot) cell.cancelQueuedScheduleOnShutdown();
        }

        long deadline = System.nanoTime() + CLOSE_WAIT_NANOS;
        boolean interrupted = false;
        List<ActorId> stillRunning = new ArrayList<>();
        for (ActorCell<?> cell : snapshot) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try {
                    cell.awaitFinalized(remaining);
                } catch (InterruptedException waitInterrupted) {
                    interrupted = true;
                    break;
                }
            }
            if (!cell.finalized()) stillRunning.add(cell.ref.id());
        }

        boolean dispatchersTerminated = true;
        for (ExecutorService dispatcher : List.of(privateDispatcher, sharedDispatcher)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                dispatchersTerminated = false;
                break;
            }
            try {
                if (!dispatcher.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                    dispatchersTerminated = false;
                    break;
                }
            } catch (InterruptedException waitInterrupted) {
                interrupted = true;
                dispatchersTerminated = false;
                break;
            }
        }

        for (SyncCell<?> cell : List.copyOf(syncCells)) cell.invalidateFromRuntime();
        syncCells.clear();
        for (Shared<?> shared : List.copyOf(sharedValues)) shared.closeFromRuntime();
        sharedValues.clear();
        sharedMemoryBytes.set(0L);

        if (interrupted) Thread.currentThread().interrupt();
        if (!stillRunning.isEmpty() || !dispatchersTerminated || interrupted) {
            if (interrupted) {
                for (ActorCell<?> cell : snapshot) {
                    if (!cell.finalized() && !stillRunning.contains(cell.ref.id())) {
                        stillRunning.add(cell.ref.id());
                    }
                }
            }
            throw new IllegalStateException(
                    "ActorRuntime close did not observe full actor termination/carrier exit: "
                            + stillRunning.size() + " actor(s) still running, dispatchersTerminated="
                            + dispatchersTerminated);
        }
        actors.clear();
        actorCount.set(0);
    }

    private ExecutorService dispatcherFor(ActorKind kind) {
        return kind == ActorKind.PRIVATE ? privateDispatcher : sharedDispatcher;
    }

    private static ExecutorService newDispatcher(
            int parallelism,
            int readyQueueCapacity,
            String threadPrefix) {
        String requested = System.getProperty(CARRIER_BACKEND_PROPERTY, "auto")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (!requested.equals("auto")
                && !requested.equals("native")
                && !requested.equals("java")) {
            throw new IllegalArgumentException(
                    CARRIER_BACKEND_PROPERTY + " must be one of auto, native, java");
        }

        boolean unix = isNativeCarrierPlatform();
        if (!requested.equals("java") && (requested.equals("native") || unix)) {
            if (!unix) {
                throw new IllegalStateException(
                        "native Oreslang carriers currently require Linux or macOS");
            }
            try {
                return new NativeCarrierExecutor(
                        parallelism,
                        parallelism,
                        readyQueueCapacity,
                        threadPrefix);
            } catch (UnsatisfiedLinkError | SecurityException unavailable) {
                if (requested.equals("native")) {
                    throw new IllegalStateException(
                            "native Oreslang carrier backend was required but liboresthread could not be loaded",
                            unavailable);
                }
                // Development portability fallback only. CI and production can
                // set -Dores.runtime.carriers=native to make this fail closed.
            }
        }

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(readyQueueCapacity),
                namedFactory(threadPrefix),
                new ThreadPoolExecutor.AbortPolicy());
        // JVM workers are a portability fallback, not the preferred Oreslang
        // runtime backend. They are created lazily on first scheduled turn.
        return executor;
    }

    private static boolean isNativeCarrierPlatform() {
        String os = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT);
        return os.contains("linux") || os.contains("mac") || os.contains("darwin");
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger next = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + next.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private final class ActorCell<M> {
        private final ActorRef<M> ref;
        private final ActorKind kind;
        private final IsolatePolicy policy;
        private final BehaviorFactory<M> behaviorFactory;
        private final boolean trustedFactory;
        private final BlockingQueue<MessageEnvelope> mailbox;
        private final ActorMemorySlice memorySlice;
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicInteger queuedMessages = new AtomicInteger();
        private final AtomicLong sharedMailboxBytes = new AtomicLong();
        private final Object lifecycleLock = new Object();
        private final Object executionDomain = new Object();
        private int activeTurns;
        private boolean carrierActive;
        private boolean finalized;
        private Behavior<M> behavior;

        private ActorCell(
                ActorRef<M> ref,
                ActorKind kind,
                IsolatePolicy policy,
                BehaviorFactory<M> behaviorFactory,
                boolean trustedFactory) {
            this.ref = ref;
            this.kind = kind;
            this.policy = policy;
            this.behaviorFactory = behaviorFactory;
            this.trustedFactory = trustedFactory;
            this.mailbox = new LinkedBlockingQueue<>(policy.maxMailboxMessages());
            this.memorySlice = kind == ActorKind.PRIVATE
                    ? new ActorMemorySlice(ref.id(), policy.maxHeapBytes())
                    : null;
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

        private boolean beginTurn() {
            synchronized (lifecycleLock) {
                if (stopped.get() || finalized) return false;
                activeTurns++;
                return true;
            }
        }

        private void endTurn() {
            synchronized (lifecycleLock) {
                if (activeTurns <= 0) {
                    throw new IllegalStateException("actor active-turn accounting underflow for " + ref.id());
                }
                activeTurns--;
                if (stopped.get() && activeTurns == 0) finalizeStopLocked();
                lifecycleLock.notifyAll();
            }
        }

        private void finalizeStopLocked() {
            // External termination is not observable until the dispatcher has
            // fully crossed back out of the TurnExecutor boundary. For
            // Truffle-backed runtimes that boundary owns context enter/leave.
            if (finalized || activeTurns != 0 || scheduled.get() || carrierActive) return;
            finalized = true;
            drainMailboxReservations();
            if (memorySlice != null) memorySlice.close();
            try {
                actorExitHook.accept(executionDomain);
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Actor termination must still complete. Runtime cleanup hooks
                // are best-effort and retryable by the process collector.
            }
            unregisterActor(this);
            lifecycleLock.notifyAll();
        }

        private boolean finalized() {
            synchronized (lifecycleLock) {
                return finalized;
            }
        }

        private void awaitFinalized(long remainingNanos) throws InterruptedException {
            long deadline = System.nanoTime() + Math.max(0L, remainingNanos);
            synchronized (lifecycleLock) {
                while (!finalized) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return;
                    long millis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
                    lifecycleLock.wait(millis);
                }
            }
        }

        private void cancelQueuedScheduleOnShutdown() {
            synchronized (lifecycleLock) {
                if (scheduled.get() && !carrierActive) {
                    scheduled.set(false);
                    if (stopped.get() && activeTurns == 0) finalizeStopLocked();
                    lifecycleLock.notifyAll();
                }
            }
        }

        private void reserveSharedMailbox(long bytes) {
            if (bytes < 0) throw new IllegalArgumentException("shared mailbox reservation cannot be negative");
            synchronized (lifecycleLock) {
                if (closed.get()) throw new IllegalStateException("actor runtime is closed");
                if (stopped.get()) throw terminated(ref);
                long current = sharedMailboxBytes.get();
                long next;
                try {
                    next = Math.addExact(current, bytes);
                } catch (ArithmeticException overflow) {
                    throw new IllegalStateException("shared actor mailbox memory accounting overflow");
                }
                if (next > policy.maxHeapBytes()) {
                    throw new IllegalStateException("shared actor mailbox memory limit exceeded for " + ref.id()
                            + ": requested=" + bytes + " used=" + current + " limit=" + policy.maxHeapBytes());
                }
                reserveSharedRuntimeBytes(bytes, "shared actor mailbox");
                sharedMailboxBytes.set(next);
            }
        }

        private void releaseSharedMailbox(long bytes) {
            if (bytes == 0) return;
            synchronized (lifecycleLock) {
                long current = sharedMailboxBytes.get();
                long next = Math.max(0L, current - bytes);
                sharedMailboxBytes.set(next);
                releaseSharedRuntimeBytes(bytes);
            }
        }

        private void schedule() {
            if (stopped.get() || closed.get()) return;
            if (!scheduled.compareAndSet(false, true)) return;
            try {
                dispatcherFor(kind).execute(this::runBatch);
            } catch (RejectedExecutionException rejected) {
                scheduled.set(false);
                stop();
                if (!closed.get()) throw rejected;
            }
        }

        private void runBatch() {
            ACTOR_CARRIER.set(Boolean.TRUE);
            boolean carrierEntered = false;
            boolean reschedule = false;
            try {
                synchronized (lifecycleLock) {
                    // shutdownNow() may leave a queued executor task that races
                    // with explicit queued-schedule cancellation. If shutdown
                    // won that race, this task is an inert no-op.
                    if (!scheduled.get()) return;

                    if (stopped.get() || closed.get()) {
                        scheduled.set(false);
                        if (activeTurns == 0) finalizeStopLocked();
                        lifecycleLock.notifyAll();
                        return;
                    }

                    carrierActive = true;
                    carrierEntered = true;
                }

                turnExecutor.execute(this::runBatchEntered);
            } catch (Throwable failure) {
                fail(failure);
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                if (failure instanceof ThreadDeath fatal) throw fatal;
                if (failure instanceof LinkageError fatal) throw fatal;
            } finally {
                // The actor remains logically scheduled until the TurnExecutor
                // returns. OresContext's executor leaves the TruffleContext in
                // its own finally block, so clearing this bit any earlier lets
                // shutdown/finalization race a carrier that still owns guest
                // context state.
                synchronized (lifecycleLock) {
                    if (carrierEntered) carrierActive = false;
                    if (scheduled.get()) scheduled.set(false);
                    if (stopped.get() && activeTurns == 0) finalizeStopLocked();
                    reschedule = !stopped.get()
                            && !closed.get()
                            && !finalized
                            && !mailbox.isEmpty();
                    lifecycleLock.notifyAll();
                }
                ACTOR_CARRIER.remove();
                if (reschedule) schedule();
            }
        }

        @SuppressWarnings("unchecked")
        private void runBatchEntered() {
            currentActor.set(this);
            CURRENT_ACTOR_EXECUTION.set(new ActorExecutionContext(
                    ActorRuntime.this, ref.id(), kind, policy, executionDomain));
            boolean turnActive = beginTurn();
            try {
                if (!turnActive) return;

                ActorContext<M> context = new ActorContext<>() {
                    @Override public ActorRef<M> self() { return ref; }
                    @Override public ActorRuntime runtime() { return ActorRuntime.this; }
                    @Override public IsolatePolicy policy() { return policy; }
                    @Override public ActorKind kind() { return kind; }
                    @Override public Optional<ActorMemorySlice> privateMemory() {
                        return Optional.ofNullable(memorySlice);
                    }
                };

                if (behavior == null) {
                    Behavior<M> created = Objects.requireNonNull(
                            behaviorFactory.create(context),
                            "actor behaviorFactory returned null");
                    if (kind == ActorKind.PRIVATE && !trustedFactory) {
                        validatePrivateBehaviorState(ref.id(), created);
                    }
                    behavior = created;
                }

                int processed = 0;
                while (processed < dispatcherConfig.throughput() && !stopped.get()) {
                    MessageEnvelope envelope = mailbox.poll();
                    if (envelope == null) break;
                    releaseMailboxSlot();
                    try (envelope) {
                        behavior.onMessage((M) envelope.value(), context);
                        if (kind == ActorKind.PRIVATE && !trustedFactory) {
                            // Private state that survives a mailbox turn must
                            // remain in actor-owned storage/capabilities. This
                            // catches behavior fields that were null/immutable
                            // at construction but later retain a mutable JVM
                            // object across turns.
                            validatePrivateBehaviorState(ref.id(), behavior);
                        }
                    }
                    processed++;
                }
            } catch (Throwable failure) {
                // Fail-stop supervision for ordinary actor failures. Fatal VM
                // errors are cleaned up and then rethrown rather than swallowed.
                fail(failure);
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                if (failure instanceof ThreadDeath fatal) throw fatal;
                if (failure instanceof LinkageError fatal) throw fatal;
            } finally {
                if (turnActive) endTurn();
                CURRENT_ACTOR_EXECUTION.remove();
                currentActor.remove();
                // scheduled/finalization/rescheduling belong to runBatch(),
                // after TurnExecutor.execute(...) has returned.
            }
        }

        private void drainMailboxReservations() {
            MessageEnvelope envelope;
            while ((envelope = mailbox.poll()) != null) {
                releaseMailboxSlot();
                envelope.close();
            }
        }

        private void fail(Throwable failure) {
            synchronized (lifecycleLock) {
                ref.terminationCause.compareAndSet(null, failure);
                stopped.set(true);
                drainMailboxReservations();
                finalizeStopLocked();
            }
        }

        private void stop() {
            synchronized (lifecycleLock) {
                stopped.set(true);
                drainMailboxReservations();
                finalizeStopLocked();
            }
        }
    }
}
