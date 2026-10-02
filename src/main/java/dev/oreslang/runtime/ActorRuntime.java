package dev.oreslang.runtime;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Host-side actor substrate used by the first interpreter.
 *
 * The language contract is stronger than the JVM backing implementation:
 * mutable state is actor-owned, cross-actor values are copied/frozen, and
 * explicitly shared regions are deeply immutable. The JVM heap remains the
 * backing store for this prototype; ActorRuntime therefore enforces semantic
 * heap isolation and mailbox budgets while the compiler/runtime continue to
 * move toward fully actor-local allocation arenas.
 */
public final class ActorRuntime implements AutoCloseable {
    private static final int MAX_FREEZE_DEPTH = 256;
    private static final int MAX_FREEZE_NODES = 1_000_000;
    private static final int MAX_FAILURE_TOMBSTONES = 4096;
    private static final int MAX_FAILURE_MESSAGE_CHARS = 4096;
    private static final int MONITORS_PER_ACTOR_BUDGET = 8;
    private static final long SHARED_HANDLE_BYTES = 64L;

    private final UUID runtimeId = UUID.randomUUID();
    private final Map<ActorId, ActorCell<?>> actors = new ConcurrentHashMap<>();
    private final Map<String, SingletonRegistration> singletonActors = new ConcurrentHashMap<>();
    private final Map<ActorId, ActorFailure> failures = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<ActorId> failureOrder =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Map<ActorId, Map<UUID, ActorId>> monitorsByTarget = new ConcurrentHashMap<>();
    private final ThreadLocal<ActorId> currentActor = new ThreadLocal<>();
    private final ThreadLocal<Long> actorDeadlineNanos = new ThreadLocal<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger activeActorSlots = new AtomicInteger();
    private final AtomicInteger activeMonitorSlots = new AtomicInteger();
    private final IsolatePolicy policyCeiling;

    public ActorRuntime() {
        this(IsolatePolicy.developer());
    }

    public ActorRuntime(IsolatePolicy policyCeiling) {
        this.policyCeiling = java.util.Objects.requireNonNull(policyCeiling);
    }

    public UUID runtimeId() { return runtimeId; }
    public IsolatePolicy policyCeiling() { return policyCeiling; }

    public record ActorId(UUID value) {
        public ActorId {
            java.util.Objects.requireNonNull(value, "value");
        }
        public static ActorId create() { return new ActorId(UUID.randomUUID()); }
        @Override public String toString() { return value.toString(); }
    }

    public enum ActorState {
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    public record ActorFailure(String type, String message) { }

    private record SingletonRegistration(ActorId actorId, String contract) { }

    public record MonitorRef(UUID value, ActorId target, UUID runtimeId) {
        public MonitorRef {
            java.util.Objects.requireNonNull(value, "value");
            java.util.Objects.requireNonNull(target, "target");
            java.util.Objects.requireNonNull(runtimeId, "runtimeId");
        }
    }

    public record ActorSnapshot(
            ActorId id,
            ActorState state,
            int mailboxMessages,
            long mailboxBytes,
            long activeMessageBytes,
            long ownedStateBytes,
            long manualGcRequests,
            ActorFailure failure) {
        public Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id.toString());
            result.put("state", state.name().toLowerCase(java.util.Locale.ROOT));
            result.put("mailbox_messages", mailboxMessages);
            result.put("mailbox_bytes", mailboxBytes);
            result.put("active_message_bytes", activeMessageBytes);
            result.put("owned_state_bytes", ownedStateBytes);
            result.put(
                    "estimated_actor_heap_bytes",
                    safeAdd(safeAdd(mailboxBytes, activeMessageBytes), ownedStateBytes));
            result.put("heap_backend", "logical_jvm");
            result.put("physical_heap_isolation", false);
            result.put("manual_gc_requests", manualGcRequests);
            if (failure != null) {
                result.put("failure_type", failure.type());
                result.put("failure_message", failure.message() == null ? "" : failure.message());
            }
            return immutableLinkedMap(result);
        }
    }

    /**
     * Opaque handle to a deeply immutable graph that may be reused by multiple
     * actors without copying the backing graph again.
     */
    public static final class Shared<T> {
        private final UUID regionId;
        private final UUID runtimeId;
        private final T value;
        private final long estimatedBytes;

        private Shared(UUID runtimeId, T value, long estimatedBytes) {
            this.regionId = UUID.randomUUID();
            this.runtimeId = java.util.Objects.requireNonNull(runtimeId, "runtimeId");
            this.value = value;
            this.estimatedBytes = estimatedBytes;
        }

        public UUID regionId() { return regionId; }
        public UUID runtimeId() { return runtimeId; }
        public T value() { return value; }
        public long estimatedBytes() { return estimatedBytes; }

        @Override
        public String toString() {
            return "Shared[" + regionId + ", estimatedBytes=" + estimatedBytes + "]";
        }
    }

    public record Protocol<M>(String name, Predicate<Object> validator) {
        public Protocol {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("protocol name cannot be blank");
            java.util.Objects.requireNonNull(validator, "validator");
        }
        public boolean accepts(Object value) { return validator.test(value); }
        public static <M> Protocol<M> any(String name) { return new Protocol<>(name, ignored -> true); }
        public static <M> Protocol<M> ofClass(String name, Class<?> messageClass) {
            java.util.Objects.requireNonNull(messageClass, "messageClass");
            return new Protocol<>(name, messageClass::isInstance);
        }
    }

    public enum SendResult {
        SENT,
        MAILBOX_FULL,
        MAILBOX_MEMORY_EXCEEDED,
        ACTOR_STOPPING,
        RUNTIME_CLOSED,
        UNKNOWN_ACTOR,
        FOREIGN_RUNTIME,
        PROTOCOL_MISMATCH
    }

    @FunctionalInterface
    public interface Behavior<M> {
        void onMessage(M message, ActorContext<M> context) throws Exception;
    }

    public interface ActorContext<M> {
        ActorRef<M> self();
        ActorRuntime runtime();
        IsolatePolicy policy();
        default Protocol<M> protocol() { return self().protocol(); }
        long ownedStateBytes();
        void replaceOwnedStateBytes(long estimatedBytes);
    }

    public final class ActorRef<M> {
        private final ActorId id;
        private final UUID ownerRuntimeId;
        private final Protocol<M> protocol;
        private final CompletableFuture<Void> terminated = new CompletableFuture<>();

        private ActorRef(ActorId id, Protocol<M> protocol) {
            this.id = id;
            this.ownerRuntimeId = runtimeId;
            this.protocol = java.util.Objects.requireNonNull(protocol, "protocol");
        }

        public ActorId id() { return id; }
        public UUID runtimeId() { return ownerRuntimeId; }
        public Protocol<M> protocol() { return protocol; }

        public void send(M message) {
            ActorRuntime.this.send(this, message);
        }

        public ActorSnapshot snapshot() {
            return ActorRuntime.this.snapshot(this);
        }

        public SendResult trySend(M message) { return ActorRuntime.this.trySend(this, message); }

        @Override
        public String toString() {
            return "ActorRef[" + protocol.name() + ":" + id.value() + "]";
        }
    }

    /**
     * Creates actor-local behavior inside the actor thread. The Supplier should
     * be generated by Oreslang lowering, not supplied from untrusted guest code.
     */
    public <M> ActorRef<M> spawn(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnOwned(policyCeiling, 0L, Protocol.any("dynamic"), behaviorFactory);
    }

    public <M> ActorRef<M> spawn(IsolatePolicy policy, Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnOwned(policy, 0L, Protocol.any("dynamic"), behaviorFactory);
    }

    public <M> ActorRef<M> spawn(Protocol<M> protocol, Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnOwned(policyCeiling, 0L, protocol, behaviorFactory);
    }

    public <M> ActorRef<M> spawn(
            IsolatePolicy policy,
            Protocol<M> protocol,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnOwned(policy, 0L, protocol, behaviorFactory);
    }

    public <M> ActorRef<M> spawnOwned(
            IsolatePolicy policy,
            long initialOwnedStateBytes,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnOwned(policy, initialOwnedStateBytes, Protocol.any("dynamic"), behaviorFactory);
    }

    public <M> ActorRef<M> spawnOwned(
            IsolatePolicy policy,
            long initialOwnedStateBytes,
            Protocol<M> protocol,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        java.util.Objects.requireNonNull(policy, "policy");
        java.util.Objects.requireNonNull(protocol, "protocol");
        java.util.Objects.requireNonNull(behaviorFactory, "behaviorFactory");
        requireWithinCeiling(policy);
        requireOwnedStateBytes(policy, initialOwnedStateBytes);
        reserveActorSlot();
        ActorId id = ActorId.create();
        ActorRef<M> ref = new ActorRef<>(id, protocol);
        ActorCell<M> cell = new ActorCell<>(ref, policy, initialOwnedStateBytes, behaviorFactory);
        boolean inserted = false;
        try {
            ActorCell<?> previous = actors.putIfAbsent(id, cell);
            if (previous != null) throw new IllegalStateException("actor id collision");
            inserted = true;
            cell.start();
            return ref;
        } catch (Throwable failure) {
            if (inserted) removeActorCell(id, cell);
            else releaseActorSlot();
            throw failure;
        }
    }

    /**
     * Returns one named actor for this runtime/context. Mutable singleton state
     * remains owned by that actor; callers still communicate exclusively by
     * message passing.
     */
    @SuppressWarnings("unchecked")
    public synchronized <M> ActorRef<M> spawnSingleton(
            String name,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnSingletonOwned(name, 0L, null, Protocol.any("dynamic"), behaviorFactory);
    }

    public synchronized <M> ActorRef<M> spawnSingleton(
            String name,
            Protocol<M> protocol,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnSingletonOwned(name, 0L, null, protocol, behaviorFactory);
    }

    @SuppressWarnings("unchecked")
    public synchronized <M> ActorRef<M> spawnSingletonOwned(
            String name,
            long initialOwnedStateBytes,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnSingletonOwned(name, initialOwnedStateBytes, null, Protocol.any("dynamic"), behaviorFactory);
    }

    public synchronized <M> ActorRef<M> spawnSingletonOwned(
            String name,
            long initialOwnedStateBytes,
            Protocol<M> protocol,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnSingletonOwned(name, initialOwnedStateBytes, null, protocol, behaviorFactory);
    }

    @SuppressWarnings("unchecked")
    public synchronized <M> ActorRef<M> spawnSingletonOwned(
            String name,
            long initialOwnedStateBytes,
            String contract,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnSingletonOwned(name, initialOwnedStateBytes, contract, Protocol.any("dynamic"), behaviorFactory);
    }

    @SuppressWarnings("unchecked")
    public synchronized <M> ActorRef<M> spawnSingletonOwned(
            String name,
            long initialOwnedStateBytes,
            String contract,
            Protocol<M> protocol,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        validateSingletonName(name);
        java.util.Objects.requireNonNull(protocol, "protocol");
        requireOwnedStateBytes(policyCeiling, initialOwnedStateBytes);
        SingletonRegistration registration = singletonActors.get(name);
        if (registration != null) {
            ActorCell<?> existing = actors.get(registration.actorId());
            if (existing != null) {
                ActorState existingState = existing.state;
                if (existingState == ActorState.STOPPING) {
                    throw new IllegalStateException(
                            "singleton actor '" + name + "' is stopping; a replacement cannot overlap the live instance");
                }
                if (existingState == ActorState.STARTING || existingState == ActorState.RUNNING) {
                    if (!java.util.Objects.equals(contract, registration.contract())) {
                        throw new IllegalStateException(
                                "singleton actor '" + name + "' already exists with an incompatible runtime contract");
                    }
                    if (!existing.ref.protocol().name().equals(protocol.name())) {
                        throw new IllegalStateException(
                                "singleton actor '" + name + "' already exists with protocol "
                                        + existing.ref.protocol().name() + ", not " + protocol.name());
                    }
                    return (ActorRef<M>) existing.ref;
                }
            }
            singletonActors.remove(name, registration);
        }

        ActorRef<M> created = spawnOwned(policyCeiling, initialOwnedStateBytes, protocol, behaviorFactory);
        SingletonRegistration createdRegistration = new SingletonRegistration(created.id(), contract);
        singletonActors.put(name, createdRegistration);

        // The virtual thread may fail during behavior construction before the
        // registry insertion above. Remove that stale name immediately; if it
        // exits after this check its normal lifecycle cleanup removes the name.
        if (!actors.containsKey(created.id())) {
            singletonActors.remove(name, createdRegistration);
        }
        return created;
    }

    @SuppressWarnings("unchecked")
    public <M> Optional<ActorRef<M>> lookupSingleton(String name) {
        SingletonRegistration registration = singletonActors.get(name);
        if (registration == null) return Optional.empty();
        ActorCell<?> cell = actors.get(registration.actorId());
        if (cell == null) {
            singletonActors.remove(name, registration);
            return Optional.empty();
        }
        ActorState state = cell.state;
        if (state == ActorState.STOPPING || state == ActorState.STOPPED || state == ActorState.FAILED) {
            return Optional.empty();
        }
        return Optional.of((ActorRef<M>) cell.ref);
    }

    public Optional<ActorId> currentActorId() {
        return Optional.ofNullable(currentActor.get());
    }

    @SuppressWarnings("unchecked")
    public <M> Optional<ActorRef<M>> currentActorRef() {
        ActorId id = currentActor.get();
        if (id == null) return Optional.empty();
        ActorCell<?> cell = actors.get(id);
        if (cell == null) return Optional.empty();
        return Optional.of((ActorRef<M>) cell.ref);
    }

    public int activeActorCount() {
        return activeActorSlots.get();
    }

    public int activeMonitorCount() {
        return activeMonitorSlots.get();
    }

    private int maxMonitorSlots() {
        long budget = (long) policyCeiling.maxActors() * MONITORS_PER_ACTOR_BUDGET;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, budget));
    }

    private void reserveMonitorSlot() {
        int limit = maxMonitorSlots();
        while (true) {
            int current = activeMonitorSlots.get();
            if (current >= limit) {
                throw new IllegalStateException("monitor limit exceeded for isolate: maxMonitors=" + limit);
            }
            if (activeMonitorSlots.compareAndSet(current, current + 1)) return;
        }
    }

    private void releaseMonitorSlot() {
        int remaining = activeMonitorSlots.decrementAndGet();
        if (remaining < 0) {
            activeMonitorSlots.incrementAndGet();
            throw new IllegalStateException("monitor slot underflow");
        }
    }

    private void reserveActorSlot() {
        while (true) {
            int current = activeActorSlots.get();
            if (current >= policyCeiling.maxActors()) {
                throw new IllegalStateException(
                        "actor limit exceeded for isolate: maxActors=" + policyCeiling.maxActors());
            }
            if (activeActorSlots.compareAndSet(current, current + 1)) return;
        }
    }

    private void releaseActorSlot() {
        int remaining = activeActorSlots.decrementAndGet();
        if (remaining < 0) {
            activeActorSlots.incrementAndGet();
            throw new IllegalStateException("actor slot underflow");
        }
    }

    private void removeActorCell(ActorId id, ActorCell<?> cell) {
        if (actors.remove(id, cell)) releaseActorSlot();
        cell.ref.terminated.complete(null);
    }

    private void requireOwnedRef(ActorRef<?> ref, String api) {
        java.util.Objects.requireNonNull(ref, "ref");
        if (!runtimeId.equals(ref.runtimeId())) {
            throw new SecurityException(api + " rejected an ActorRef minted by another runtime/isolate");
        }
    }

    private void requireOwnedMonitor(MonitorRef monitor, String api) {
        java.util.Objects.requireNonNull(monitor, "monitor");
        if (!runtimeId.equals(monitor.runtimeId())) {
            throw new SecurityException(api + " rejected a MonitorRef minted by another runtime/isolate");
        }
    }

    /**
     * Erlang-style unidirectional monitor. The watcher receives one immutable
     * DOWN message when the target exits. Monitoring an already-dead actor
     * immediately queues a DOWN message with reason "noproc" or "failed".
     */
    public MonitorRef monitor(ActorRef<?> watcher, ActorRef<?> target) {
        requireOwnedRef(watcher, "actor.monitor");
        requireOwnedRef(target, "actor.monitor");
        if (watcher.id().equals(target.id())) {
            throw new IllegalArgumentException("an actor cannot monitor itself");
        }
        ActorCell<?> watcherCell = actors.get(watcher.id());
        if (watcherCell == null) {
            throw new IllegalStateException("monitor watcher is not a live actor");
        }

        Map<String, Object> downProbe = Map.of(
                "type", "DOWN",
                "event", "DOWN",
                "monitor_id", "00000000-0000-0000-0000-000000000000",
                "actor_id", target.id().toString(),
                "reason", "normal");
        if (!protocolAccepts(watcher.protocol(), downProbe)) {
            throw new IllegalArgumentException(
                    "watcher protocol '" + watcher.protocol().name()
                            + "' does not admit required DOWN monitor messages");
        }

        MonitorRef monitor = new MonitorRef(UUID.randomUUID(), target.id(), runtimeId);
        ActorCell<?> targetCell = actors.get(target.id());
        if (targetCell == null) {
            ActorFailure failure = failures.get(target.id());
            sendDown(watcher.id(), monitor, failure == null ? "noproc" : "failed", failure);
            return monitor;
        }

        reserveMonitorSlot();
        Map<UUID, ActorId> targetMonitors =
                monitorsByTarget.computeIfAbsent(target.id(), ignored -> new ConcurrentHashMap<>());
        targetMonitors.put(monitor.value(), watcher.id());

        // Close the race where the target exits after the liveness check but
        // before monitor registration. Whichever side removes the registration
        // owns releasing its bounded monitor slot.
        if (targetCell.isTerminal()) {
            Map<UUID, ActorId> monitors = monitorsByTarget.get(target.id());
            if (monitors != null && monitors.remove(monitor.value(), watcher.id())) {
                releaseMonitorSlot();
                if (monitors.isEmpty()) monitorsByTarget.remove(target.id(), monitors);
                ActorFailure failure = failures.get(target.id());
                sendDown(watcher.id(), monitor, failure == null ? "normal" : "failed", failure);
            }
        }
        return monitor;
    }

    public boolean demonitor(ActorRef<?> watcher, MonitorRef monitor) {
        requireOwnedRef(watcher, "actor.demonitor");
        requireOwnedMonitor(monitor, "actor.demonitor");
        Map<UUID, ActorId> monitors = monitorsByTarget.get(monitor.target());
        if (monitors == null) return false;
        boolean removed = monitors.remove(monitor.value(), watcher.id());
        if (removed) releaseMonitorSlot();
        if (monitors.isEmpty()) monitorsByTarget.remove(monitor.target(), monitors);
        return removed;
    }

    private static void requireOwnedStateBytes(IsolatePolicy policy, long bytes) {
        if (bytes < 0L) throw new IllegalArgumentException("owned state bytes cannot be negative");
        if (bytes > policy.maxHeapBytes()) {
            throw new IllegalArgumentException("actor owned state exceeds actor heap policy");
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
        if (child.maxActors() > policyCeiling.maxActors()) {
            throw new SecurityException("child actor maxActors exceeds parent policy");
        }
        if (child.maxAsyncTasks() > policyCeiling.maxAsyncTasks()) {
            throw new SecurityException("child actor maxAsyncTasks exceeds parent policy");
        }
        if (child.maxWallTime().compareTo(policyCeiling.maxWallTime()) > 0) {
            throw new SecurityException("child actor wall-time limit exceeds parent policy");
        }
        if (policyCeiling.adversarial() && !child.adversarial()) {
            throw new SecurityException("child actor cannot weaken an adversarial parent policy");
        }
    }

    public <M> void send(ActorRef<M> ref, M message) {
        SendResult result = trySend(ref, message);
        if (result != SendResult.SENT) throw sendFailure(ref, result);
    }

    @SuppressWarnings("unchecked")
    public <M> SendResult trySend(ActorRef<M> ref, M message) {
        java.util.Objects.requireNonNull(ref, "ref");
        if (closed.get()) return SendResult.RUNTIME_CLOSED;
        if (!runtimeId.equals(ref.runtimeId())) return SendResult.FOREIGN_RUNTIME;
        ActorCell<M> cell = (ActorCell<M>) actors.get(ref.id());
        if (cell == null) return SendResult.UNKNOWN_ACTOR;

        if (message instanceof Shared<?> shared && !runtimeId.equals(shared.runtimeId())) {
            return SendResult.FOREIGN_RUNTIME;
        }

        Object sourceCandidate = message instanceof Shared<?> shared ? shared.value() : message;
        if (!protocolAccepts(ref.protocol(), sourceCandidate)) return SendResult.PROTOCOL_MISMATCH;

        Envelope envelope = prepareEnvelope(cell, message);
        if (!protocolAccepts(ref.protocol(), envelope.value())) return SendResult.PROTOCOL_MISMATCH;
        return cell.offer(envelope);
    }

    private static boolean protocolAccepts(Protocol<?> protocol, Object value) {
        try {
            return protocol.accepts(value);
        } catch (RuntimeException validatorFailure) {
            return false;
        }
    }

    private RuntimeException sendFailure(ActorRef<?> ref, SendResult result) {
        if (result == SendResult.FOREIGN_RUNTIME) {
            return new SecurityException("actor send rejected a capability minted by another runtime/isolate");
        }
        if (result == SendResult.UNKNOWN_ACTOR && ref != null && runtimeId.equals(ref.runtimeId())) {
            ActorFailure failure = failures.get(ref.id());
            if (failure != null) {
                return new IllegalStateException("actor " + ref.id() + " failed: "
                        + failure.type() + ": " + failure.message());
            }
        }
        if (result == SendResult.MAILBOX_MEMORY_EXCEEDED) {
            return new IllegalStateException("actor mailbox memory admission failed for " + ref);
        }
        if (result == SendResult.MAILBOX_FULL) {
            return new IllegalStateException("actor mailbox is full for " + ref);
        }
        if (result == SendResult.ACTOR_STOPPING) {
            return new IllegalStateException("actor is stopping and no longer accepts messages: " + ref);
        }
        return new IllegalStateException("actor send failed for " + ref + ": " + result);
    }

    private Envelope prepareEnvelope(ActorCell<?> cell, Object message) {
        final Object deliveryValue;
        final long mailboxBytes;
        if (message instanceof Shared<?> shared) {
            if (!runtimeId.equals(shared.runtimeId())) {
                throw new SecurityException("Shared value was minted by another runtime/isolate");
            }
            deliveryValue = shared.value();
            mailboxBytes = SHARED_HANDLE_BYTES;
        } else {
            long maxMessageBytes = Math.max(
                    1024L * 1024L,
                    Math.min(cell.policy.maxHeapBytes() / 2L, 64L * 1024L * 1024L));
            long sourceEstimate = estimatedFrozenBytes(message);
            if (sourceEstimate > maxMessageBytes) {
                throw new IllegalArgumentException(
                        "message is too large for actor policy; use process.share_readonly for large immutable payloads");
            }
            deliveryValue = freezeForMessage(message);
            mailboxBytes = estimatedFrozenBytes(deliveryValue);
            if (mailboxBytes > maxMessageBytes) {
                throw new IllegalArgumentException(
                        "frozen message is too large for actor policy; use process.share_readonly for large immutable payloads");
            }
        }
        return new Envelope(deliveryValue, mailboxBytes);
    }

    public <M, R> CompletionStage<R> ask(
            ActorRef<M> target,
            Function<ActorRef<R>, M> requestFactory,
            Duration timeout) {
        return ask(target, requestFactory, Protocol.any("ask-reply"), timeout);
    }

    public <M, R> CompletionStage<R> ask(
            ActorRef<M> target,
            Function<ActorRef<R>, M> requestFactory,
            Protocol<R> replyProtocol,
            Duration timeout) {
        requireOwnedRef(target, "actor.ask");
        java.util.Objects.requireNonNull(requestFactory, "requestFactory");
        java.util.Objects.requireNonNull(replyProtocol, "replyProtocol");
        java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");

        Duration effectiveTimeout = timeout.compareTo(policyCeiling.maxWallTime()) > 0
                ? policyCeiling.maxWallTime()
                : timeout;
        IsolatePolicy replyPolicy = new IsolatePolicy(
                Set.of(),
                16L * 1024L * 1024L,
                1,
                1,
                effectiveTimeout,
                policyCeiling.adversarial());

        CompletableFuture<R> reply = new CompletableFuture<>();
        ActorRef<R> replyActor = spawn(replyPolicy, replyProtocol, () -> (message, context) -> reply.complete(message));
        reply.orTimeout(durationToNanosSaturated(effectiveTimeout), TimeUnit.NANOSECONDS);
        reply.whenComplete((ignored, failure) -> stop(replyActor));
        try {
            target.send(requestFactory.apply(replyActor));
        } catch (Throwable failure) {
            reply.completeExceptionally(failure);
        }
        return reply;
    }

    /**
     * Creates an explicitly read-only shared value. The returned graph is a
     * frozen representation; no mutable source object itself is exposed.
     */
    @SuppressWarnings("unchecked")
    public <T> Shared<T> shareReadonly(T value) {
        long sourceEstimate = estimatedFrozenBytes(value);
        if (sourceEstimate > policyCeiling.maxHeapBytes()) {
            throw new IllegalArgumentException("shared value exceeds isolate heap policy");
        }
        T frozen = (T) freezeReadonly(value);
        long frozenBytes = estimatedFrozenBytes(frozen);
        if (frozenBytes > policyCeiling.maxHeapBytes()) {
            throw new IllegalArgumentException("frozen shared value exceeds isolate heap policy");
        }
        return new Shared<>(runtimeId, frozen, frozenBytes);
    }

    /**
     * Cooperative scheduler hook used by compiler-injected loop safepoints.
     */
    public void schedulerSafepoint() {
        if (closed.get()) throw new CancellationException("actor runtime is closing");
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("actor execution interrupted");
        Long deadline = actorDeadlineNanos.get();
        if (deadline != null && System.nanoTime() - deadline >= 0L) {
            throw new CancellationException("actor message exceeded its wall-time policy");
        }
        Thread.yield();
    }

    private static long durationToNanosSaturated(Duration duration) {
        try {
            return Math.max(1L, duration.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE / 4L;
        }
    }

    private static long durationToMillisSaturated(Duration duration) {
        try {
            return Math.max(1L, duration.toMillis());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE / 4L;
        }
    }

    private static long deadlineAfter(Duration duration) {
        long delta = Math.min(durationToNanosSaturated(duration), Long.MAX_VALUE / 4L);
        return System.nanoTime() + delta;
    }

    public boolean stop(ActorRef<?> ref) {
        requireOwnedRef(ref, "actor.stop");
        ActorCell<?> cell = actors.get(ref.id());
        return cell != null && cell.requestGracefulStop();
    }

    public boolean join(ActorRef<?> ref, Duration timeout) {
        requireOwnedRef(ref, "actor.join");
        java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        if (ref.id().equals(currentActor.get())) {
            throw new IllegalStateException("an actor cannot join itself");
        }
        try {
            ref.terminated.get(durationToNanosSaturated(timeout), TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException timedOut) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException impossible) {
            throw new IllegalStateException("actor termination signal failed unexpectedly", impossible.getCause());
        }
    }

    public ActorSnapshot snapshot(ActorRef<?> ref) {
        requireOwnedRef(ref, "actor.status");
        ActorCell<?> cell = actors.get(ref.id());
        if (cell != null) return cell.snapshot();
        ActorFailure failure = failures.get(ref.id());
        return new ActorSnapshot(
                ref.id(),
                failure == null ? ActorState.STOPPED : ActorState.FAILED,
                0,
                0L,
                0L,
                0L,
                0L,
                failure);
    }

    /** Called by process.gc() so actor-local pressure/telemetry can be tracked. */
    public void noteManualGcRequest() {
        ActorId id = currentActor.get();
        if (id == null) return;
        ActorCell<?> cell = actors.get(id);
        if (cell != null) cell.manualGcRequests.incrementAndGet();
    }

    /**
     * Converts supported values into a deeply immutable/sendable graph.
     * Unknown host objects are rejected instead of being passed by reference.
     * Cyclic mutable graphs are rejected; explicit shared regions must be
     * acyclic immutable values.
     */
    public static Object freeze(Object value) {
        return freezeValue(value, null, false, new IdentityHashMap<>(), 0, true, new GraphBudget());
    }

    public Object freezeForActorState(Object value) {
        return freezeValue(value, runtimeId, true, new IdentityHashMap<>(), 0, false, new GraphBudget());
    }

    public <T> T sharedValueForActorState(Shared<T> shared) {
        java.util.Objects.requireNonNull(shared, "shared");
        if (!runtimeId.equals(shared.runtimeId())) {
            throw new SecurityException("Shared value was minted by another runtime/isolate");
        }
        return shared.value();
    }

    private Object freezeForMessage(Object value) {
        return freezeValue(value, runtimeId, true, new IdentityHashMap<>(), 0, false, new GraphBudget());
    }

    private static Object freezeReadonly(Object value) {
        return freezeValue(value, null, false, new IdentityHashMap<>(), 0, true, new GraphBudget());
    }

    private static Object freezeValue(
            Object value,
            UUID allowedRuntimeId,
            boolean allowCapabilities,
            IdentityHashMap<Object, Boolean> path,
            int depth,
            boolean readOnlyShared,
            GraphBudget budget) {
        budget.visit();
        if (depth > MAX_FREEZE_DEPTH) throw new IllegalArgumentException("message graph exceeds maximum freeze depth " + MAX_FREEZE_DEPTH);
        if (value == null) {
            throw new IllegalArgumentException("raw null cannot cross an actor boundary; use Option<T>");
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Enum<?> || value instanceof UUID || value instanceof ActorId) {
            return value;
        }
        if (value instanceof MonitorRef monitor) {
            requireCapabilityRuntime("MonitorRef", monitor.runtimeId(), allowedRuntimeId, allowCapabilities);
            return monitor;
        }
        if (value instanceof Shared<?> shared) {
            requireCapabilityRuntime("Shared", shared.runtimeId(), allowedRuntimeId, allowCapabilities);
            return shared;
        }
        if (value instanceof ActorRuntime.ActorRef<?> ref) {
            requireCapabilityRuntime("ActorRef", ref.runtimeId(), allowedRuntimeId, allowCapabilities);
            return ref;
        }

        if (path.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic mutable graphs cannot cross actor boundaries");
        }

        try {
            if (value instanceof List<?> list) {
                List<Object> frozen = new ArrayList<>();
                for (Object item : list) {
                    frozen.add(freezeValue(
                            item, allowedRuntimeId, allowCapabilities,
                            path, depth + 1, readOnlyShared, budget));
                }
                return readOnlyShared ? List.copyOf(frozen) : frozen;
            }
            if (value instanceof Set<?> set) {
                java.util.LinkedHashSet<Object> frozen = new java.util.LinkedHashSet<>();
                for (Object item : set) {
                    Object next = freezeValue(
                            item, allowedRuntimeId, allowCapabilities, path, depth + 1, readOnlyShared, budget);
                    if (!frozen.add(next)) {
                        throw new IllegalArgumentException(
                                "distinct set elements collapse to the same frozen value");
                    }
                }
                return readOnlyShared ? immutableLinkedSet(frozen) : frozen;
            }
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> frozen = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = freezeValue(
                            entry.getKey(), allowedRuntimeId, allowCapabilities,
                            path, depth + 1, readOnlyShared, budget);
                    Object item = freezeValue(
                            entry.getValue(), allowedRuntimeId, allowCapabilities,
                            path, depth + 1, readOnlyShared, budget);
                    if (frozen.containsKey(key)) {
                        throw new IllegalArgumentException(
                                "distinct map keys collapse to the same frozen key");
                    }
                    frozen.put(key, item);
                }
                return readOnlyShared ? immutableLinkedMap(frozen) : frozen;
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                List<Object> frozen = new ArrayList<>();
                for (int i = 0; i < length; i++) {
                    frozen.add(freezeValue(
                            Array.get(value, i), allowedRuntimeId, allowCapabilities,
                            path, depth + 1, readOnlyShared, budget));
                }
                return readOnlyShared ? List.copyOf(frozen) : frozen;
            }
            if (value instanceof Sendable sendable) {
                Object replacement = sendable.freezeForSend(
                        nested -> freezeValue(
                                nested, allowedRuntimeId, allowCapabilities,
                                path, depth + 1, readOnlyShared, budget),
                        readOnlyShared);
                if (replacement == value) {
                    throw new IllegalArgumentException(
                            "Sendable.freezeForSend() must return an actor-local copy or immutable transport value, not this");
                }

                // Sendable is a trusted/generated runtime ABI. Implementations
                // must freeze every nested value through the supplied freezer.
                // A new Sendable transport object is therefore already frozen;
                // recursively invoking freezeForSend on it would re-project
                // values such as OresObject indefinitely. Non-Sendable roots
                // and capability roots are still recursively validated here.
                if (replacement instanceof Sendable) return replacement;
                return freezeValue(
                        replacement, allowedRuntimeId, allowCapabilities,
                        path, depth + 1, readOnlyShared, budget);
            }
            throw new IllegalArgumentException("value of type " + value.getClass().getName()
                    + " is not Sendable; mutable host objects cannot cross actor boundaries");
        } finally {
            path.remove(value);
        }
    }

    private static void requireCapabilityRuntime(
            String kind,
            UUID capabilityRuntimeId,
            UUID allowedRuntimeId,
            boolean allowCapabilities) {
        if (!allowCapabilities || allowedRuntimeId == null || !allowedRuntimeId.equals(capabilityRuntimeId)) {
            throw new SecurityException(kind + " is runtime/isolate-scoped and cannot cross this boundary");
        }
    }

    private static final class GraphBudget {
        private int nodes;
        private void visit() {
            nodes++;
            if (nodes > MAX_FREEZE_NODES) {
                throw new IllegalArgumentException("message graph exceeds maximum node count " + MAX_FREEZE_NODES);
            }
        }
    }

    @FunctionalInterface
    public interface SendFreezer {
        Object freeze(Object value);
    }

    @FunctionalInterface
    public interface SendSizer {
        long estimatedBytes(Object value);
    }

    /**
     * Implemented only by trusted/generated Oreslang runtime values.
     * Implementations must create a transport-safe actor-local copy and must
     * recursively process nested values through the supplied freezer so cycle
     * detection and depth limits remain one graph traversal.
     */
    public interface Sendable {
        Object freezeForSend(SendFreezer freezer, boolean readOnlyShared);

        default long estimatedSendBytes(SendSizer sizer) {
            return 64L;
        }
    }

    public static long estimatedFrozenBytes(Object value) {
        return estimatedFrozenBytes(value, new IdentityHashMap<>(), 0, new GraphBudget());
    }

    private static long estimatedFrozenBytes(
            Object value,
            IdentityHashMap<Object, Boolean> path,
            int depth,
            GraphBudget budget) {
        budget.visit();
        if (depth > MAX_FREEZE_DEPTH) return Long.MAX_VALUE;
        if (value == null) {
            throw new IllegalArgumentException("raw null cannot cross an actor boundary; use Option<T>");
        }
        if (value instanceof Shared<?>) return SHARED_HANDLE_BYTES;
        if (value instanceof String string) return safeAdd(24L, safeMultiply(2L, string.length()));
        if (value instanceof Boolean || value instanceof Byte) return 16L;
        if (value instanceof Character || value instanceof Short) return 16L;
        if (value instanceof Integer || value instanceof Float) return 16L;
        if (value instanceof Long || value instanceof Double || value instanceof UUID
                || value instanceof ActorId || value instanceof MonitorRef) return 32L;
        if (value instanceof BigInteger integer) return safeAdd(32L, integer.bitLength() / 8L + 1L);
        if (value instanceof BigDecimal decimal) {
            return safeAdd(48L, estimatedFrozenBytes(decimal.unscaledValue(), path, depth + 1, budget));
        }
        if (value instanceof Enum<?>) return 24L;
        if (value instanceof ActorRef<?>) return SHARED_HANDLE_BYTES;

        boolean composite = value instanceof List<?>
                || value instanceof Set<?>
                || value instanceof Map<?, ?>
                || value.getClass().isArray()
                || value instanceof Sendable;
        if (composite && path.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic mutable graphs cannot cross actor boundaries");
        }

        try {
            if (value instanceof List<?> list) {
                long total = 24L;
                for (Object item : list) {
                    total = safeAdd(total, estimatedFrozenBytes(item, path, depth + 1, budget));
                }
                return total;
            }
            if (value instanceof Set<?> set) {
                long total = 48L;
                for (Object item : set) {
                    total = safeAdd(total, estimatedFrozenBytes(item, path, depth + 1, budget));
                }
                return total;
            }
            if (value instanceof Map<?, ?> map) {
                long total = 64L;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    total = safeAdd(total, estimatedFrozenBytes(entry.getKey(), path, depth + 1, budget));
                    total = safeAdd(total, estimatedFrozenBytes(entry.getValue(), path, depth + 1, budget));
                }
                return total;
            }
            if (value.getClass().isArray()) {
                long total = 24L;
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    total = safeAdd(total, estimatedFrozenBytes(Array.get(value, i), path, depth + 1, budget));
                }
                return total;
            }
            if (value instanceof Sendable sendable) {
                return sendable.estimatedSendBytes(
                        nested -> estimatedFrozenBytes(nested, path, depth + 1, budget));
            }
            return 64L;
        } finally {
            if (composite) path.remove(value);
        }
    }

    private static String boundedText(String value, int maxChars) {
        if (value == null) return "";
        if (value.length() <= maxChars) return value;
        int keep = Math.max(0, maxChars - 16);
        return value.substring(0, keep) + "...[truncated]";
    }

    private static <K, V> Map<K, V> immutableLinkedMap(Map<? extends K, ? extends V> source) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static <T> Set<T> immutableLinkedSet(Set<? extends T> source) {
        return java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(source));
    }

    private static long safeAdd(long left, long right) {
        if (left == Long.MAX_VALUE || right == Long.MAX_VALUE || right > Long.MAX_VALUE - left) return Long.MAX_VALUE;
        return left + right;
    }

    private static long safeMultiply(long left, long right) {
        if (left == 0L || right == 0L) return 0L;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private static void validateSingletonName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("singleton actor name cannot be blank");
        if (name.length() > 128) throw new IllegalArgumentException("singleton actor name is too long");
        if (!name.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException("singleton actor name contains unsupported characters");
        }
    }

    @SuppressWarnings("unchecked")
    private void notifyMonitors(ActorId target, String reason, ActorFailure failure) {
        Map<UUID, ActorId> monitors = monitorsByTarget.remove(target);
        if (monitors == null || monitors.isEmpty()) return;
        for (Map.Entry<UUID, ActorId> entry : List.copyOf(monitors.entrySet())) {
            if (monitors.remove(entry.getKey(), entry.getValue())) {
                releaseMonitorSlot();
                sendDown(entry.getValue(), new MonitorRef(entry.getKey(), target, runtimeId), reason, failure);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void sendDown(ActorId watcherId, MonitorRef monitor, String reason, ActorFailure failure) {
        if (closed.get()) return;
        ActorCell<?> watcherCell = actors.get(watcherId);
        if (watcherCell == null) return;

        Map<String, Object> down = new LinkedHashMap<>();
        down.put("type", "DOWN");
        down.put("event", "DOWN");
        down.put("monitor_id", monitor.value().toString());
        down.put("actor_id", monitor.target().toString());
        down.put("reason", reason);
        if (failure != null) {
            down.put("error_type", failure.type());
            down.put("error_message", failure.message() == null ? "" : failure.message());
        }
        try {
            SendResult result = trySend((ActorRef<Object>) watcherCell.ref, immutableLinkedMap(down));
            if (result != SendResult.SENT) {
                watcherCell.failControlPlane(new IllegalStateException(
                        "monitor DOWN delivery failed: " + result));
            }
        } catch (RuntimeException deliveryFailure) {
            watcherCell.failControlPlane(new IllegalStateException(
                    "monitor DOWN delivery failed", deliveryFailure));
        }
    }

    private void removeWatchesOwnedBy(ActorId watcher) {
        for (Map.Entry<ActorId, Map<UUID, ActorId>> entry : monitorsByTarget.entrySet()) {
            Map<UUID, ActorId> monitors = entry.getValue();
            for (Map.Entry<UUID, ActorId> monitor : List.copyOf(monitors.entrySet())) {
                if (monitor.getValue().equals(watcher)
                        && monitors.remove(monitor.getKey(), watcher)) {
                    releaseMonitorSlot();
                }
            }
            if (monitors.isEmpty()) monitorsByTarget.remove(entry.getKey(), monitors);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        List<ActorCell<?>> cells = List.copyOf(actors.values());
        for (ActorCell<?> cell : cells) cell.forceStop();
        for (ActorCell<?> cell : cells) {
            Thread thread = cell.thread;
            if (thread == null) continue;
            try {
                thread.join(1000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        actors.clear();
        activeActorSlots.set(0);
        singletonActors.clear();
        monitorsByTarget.clear();
        activeMonitorSlots.set(0);
        failures.clear();
        failureOrder.clear();
    }

    private record Envelope(Object value, long bytes) { }

    private final class ActorCell<M> {
        private static final Object STOP = new Object();

        private final ActorRef<M> ref;
        private final IsolatePolicy policy;
        private final Supplier<? extends Behavior<M>> behaviorFactory;
        private final BlockingQueue<Object> mailbox;
        private final AtomicLong mailboxBytes = new AtomicLong();
        private final AtomicLong activeMessageBytes = new AtomicLong();
        private final AtomicLong ownedStateBytes = new AtomicLong();
        private final AtomicLong manualGcRequests = new AtomicLong();
        private final long maxMailboxBytes;
        private volatile Thread thread;
        private volatile ActorState state = ActorState.STARTING;
        private volatile ActorFailure failureRecord;

        private ActorCell(
                ActorRef<M> ref,
                IsolatePolicy policy,
                long initialOwnedStateBytes,
                Supplier<? extends Behavior<M>> behaviorFactory) {
            this.ref = ref;
            this.policy = policy;
            this.behaviorFactory = behaviorFactory;
            this.ownedStateBytes.set(initialOwnedStateBytes);
            this.mailbox = new LinkedBlockingQueue<>(policy.maxMailboxMessages());
            this.maxMailboxBytes = Math.max(1024L * 1024L, Math.min(policy.maxHeapBytes() / 4L, 64L * 1024L * 1024L));
        }

        private void start() {
            thread = Thread.ofVirtual().name("ores-actor-" + ref.id().value()).start(this::run);
        }

        private synchronized SendResult offer(Envelope envelope) {
            if (state == ActorState.STOPPING || state == ActorState.STOPPED || state == ActorState.FAILED) {
                return SendResult.ACTOR_STOPPING;
            }
            long next;
            do {
                long current = mailboxBytes.get();
                if (envelope.bytes() > maxMailboxBytes - current) {
                    return SendResult.MAILBOX_MEMORY_EXCEEDED;
                }
                next = current + envelope.bytes();
                long liveBytes = safeAdd(ownedStateBytes.get(), activeMessageBytes.get());
                if (liveBytes > policy.maxHeapBytes() - next) {
                    return SendResult.MAILBOX_MEMORY_EXCEEDED;
                }
                if (mailboxBytes.compareAndSet(current, next)) break;
            } while (true);

            if (mailbox.offer(envelope)) return SendResult.SENT;
            mailboxBytes.addAndGet(-envelope.bytes());
            return SendResult.MAILBOX_FULL;
        }

        @SuppressWarnings("unchecked")
        private void run() {
            currentActor.set(ref.id());
            markStarted();
            final Behavior<M> behavior;
            try {
                behavior = java.util.Objects.requireNonNull(behaviorFactory.get(), "behaviorFactory returned null");
            } catch (Throwable failure) {
                recordFailure(failure);
                currentActor.remove();
                activeMessageBytes.set(0L);
                ownedStateBytes.set(0L);
                removeSingletonMapping();
                removeWatchesOwnedBy(ref.id());
                notifyMonitors(ref.id(), "failed", failureRecord);
                // Keep the cell discoverable through monitor delivery so join()
                // cannot race ahead and tear down the watcher.
                removeActorCell(ref.id(), this);
                return;
            }

            final ActorContext<M> context = new ActorContext<>() {
                @Override public ActorRef<M> self() { return ref; }
                @Override public ActorRuntime runtime() { return ActorRuntime.this; }
                @Override public IsolatePolicy policy() { return policy; }
                @Override public long ownedStateBytes() { return ownedStateBytes.get(); }
                @Override public void replaceOwnedStateBytes(long estimatedBytes) {
                    if (closed.get() || state == ActorState.STOPPED || state == ActorState.FAILED) {
                        throw new CancellationException("actor is terminated and cannot publish new owned state");
                    }
                    requireOwnedStateBytes(policy, estimatedBytes);
                    long transientBytes = safeAdd(mailboxBytes.get(), activeMessageBytes.get());
                    if (estimatedBytes > policy.maxHeapBytes() - transientBytes) {
                        throw new IllegalStateException("actor heap limit exceeded by persistent state");
                    }
                    ownedStateBytes.set(estimatedBytes);
                }
            };

            try {
                while (true) {
                    if (state == ActorState.FAILED) return;
                    if (state == ActorState.STOPPING && mailbox.isEmpty()) {
                        state = ActorState.STOPPED;
                        return;
                    }
                    Object queued = mailbox.take();
                    if (queued == STOP) {
                        state = ActorState.STOPPED;
                        return;
                    }
                    Envelope envelope = (Envelope) queued;
                    mailboxBytes.addAndGet(-envelope.bytes());
                    activeMessageBytes.set(envelope.bytes());
                    actorDeadlineNanos.set(deadlineAfter(policy.maxWallTime()));
                    try {
                        behavior.onMessage((M) envelope.value(), context);
                    } finally {
                        actorDeadlineNanos.remove();
                        activeMessageBytes.set(0L);
                    }
                    if (state == ActorState.FAILED) return;
                    if (state == ActorState.STOPPING && mailbox.isEmpty()) {
                        state = ActorState.STOPPED;
                        return;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (state != ActorState.FAILED) state = ActorState.STOPPED;
            } catch (Throwable failure) {
                recordFailure(failure);
            } finally {
                currentActor.remove();
                activeMessageBytes.set(0L);
                ownedStateBytes.set(0L);
                removeSingletonMapping();
                removeWatchesOwnedBy(ref.id());
                ActorFailure actorFailure = failureRecord;
                notifyMonitors(ref.id(), actorFailure == null ? "normal" : "failed", actorFailure);
                // Publish termination only after all lifecycle notifications
                // have been queued. This gives join() a real happens-before
                // boundary for supervision instead of a fail-open lookup race.
                removeActorCell(ref.id(), this);
            }
        }

        private synchronized void markStarted() {
            if (state == ActorState.STARTING) state = ActorState.RUNNING;
        }

        private boolean isTerminal() {
            ActorState observed = state;
            return observed == ActorState.STOPPED || observed == ActorState.FAILED;
        }

        private synchronized void recordFailure(Throwable failure) {
            state = ActorState.FAILED;
            ActorFailure record = new ActorFailure(
                    boundedText(failure.getClass().getName(), 512),
                    boundedText(failure.getMessage(), MAX_FAILURE_MESSAGE_CHARS));
            failureRecord = record;
            if (closed.get()) return;
            failures.put(ref.id(), record);
            failureOrder.add(ref.id());
            while (failures.size() > MAX_FAILURE_TOMBSTONES) {
                ActorId oldest = failureOrder.poll();
                if (oldest == null) break;
                failures.remove(oldest);
            }
        }

        private void failControlPlane(Throwable failure) {
            synchronized (this) {
                if (state == ActorState.STOPPED || state == ActorState.FAILED) return;
                recordFailure(failure);
            }
            Thread t = thread;
            if (t != null) t.interrupt();
        }

        private void removeSingletonMapping() {
            singletonActors.entrySet().removeIf(entry -> entry.getValue().actorId().equals(ref.id()));
        }

        private synchronized boolean requestGracefulStop() {
            if (state == ActorState.STOPPED || state == ActorState.FAILED) return true;
            state = ActorState.STOPPING;
            mailbox.offer(STOP);
            return true;
        }

        private synchronized void forceStop() {
            state = ActorState.STOPPING;
            mailbox.clear();
            mailboxBytes.set(0L);
            ownedStateBytes.set(0L);
            mailbox.offer(STOP);
            Thread t = thread;
            if (t != null) t.interrupt();
        }

        private ActorSnapshot snapshot() {
            return new ActorSnapshot(
                    ref.id(),
                    state,
                    mailbox.size(),
                    mailboxBytes.get(),
                    activeMessageBytes.get(),
                    ownedStateBytes.get(),
                    manualGcRequests.get(),
                    failureRecord);
        }
    }
}
