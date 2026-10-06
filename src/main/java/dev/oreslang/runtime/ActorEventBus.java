package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime-owned, group-scoped event bus for tightly coupled actor workloads.
 *
 * <p>The bus is deliberately not an actor. Publishing never routes through a
 * central mailbox or executes subscriber guest code inline. Each subscription
 * owns a bounded Oreslang channel, so reliable delivery can apply scheduler-
 * friendly backpressure while latest/lossy topics remain non-blocking.</p>
 *
 * <p>Event payloads are frozen once by {@link ActorRuntime} and the same
 * immutable value is fanned out to subscribers. Mutable shared state is never
 * exposed through this API.</p>
 */
public final class ActorEventBus implements AutoCloseable {
    public enum DeliveryPolicy {
        /** Every active subscriber receives every event; publishers may await backpressure. */
        RELIABLE,
        /** Each subscriber retains only the newest unread event. */
        LATEST,
        /** New events are dropped for subscribers whose bounded queue is full. */
        LOSSY
    }

    public enum DoubleReductionKind {
        MIN,
        MAX
    }

    public static final class EventBackpressureException extends IllegalStateException {
        private final String topic;
        private final ActorRuntime.ActorId subscriber;

        private EventBackpressureException(
                String topic,
                ActorRuntime.ActorId subscriber,
                int limit) {
            super("reliable ActorGroup event backlog is full for topic "
                    + topic + ", subscriber=" + subscriber + ", limit=" + limit);
            this.topic = topic;
            this.subscriber = subscriber;
        }

        public String topic() { return topic; }
        public ActorRuntime.ActorId subscriber() { return subscriber; }
    }

    public static final class Event<T> {
        private final String topic;
        private final long sequence;
        private final ActorRuntime.ActorId publisher;
        private final long publishedNanos;
        private final T value;
        private final ActorRuntime.ActorGroupEventReservation reservation;
        private final AtomicInteger retentionRefs = new AtomicInteger(1);

        private Event(
                String topic,
                long sequence,
                ActorRuntime.ActorId publisher,
                long publishedNanos,
                T value,
                ActorRuntime.ActorGroupEventReservation reservation) {
            this.topic = Objects.requireNonNull(topic, "topic");
            this.sequence = sequence;
            this.publisher = publisher;
            this.publishedNanos = publishedNanos;
            this.value = Objects.requireNonNull(
                    value, "Oreslang events cannot carry null; use Option<T>");
            this.reservation = Objects.requireNonNull(reservation, "reservation");
        }

        public String topic() { return topic; }
        public long sequence() { return sequence; }
        public ActorRuntime.ActorId publisher() { return publisher; }
        public long publishedNanos() { return publishedNanos; }
        public T value() { return value; }

        public boolean systemPublished() {
            return publisher == null;
        }

        private void retainDelivery() {
            while (true) {
                int current = retentionRefs.get();
                if (current <= 0) {
                    throw new IllegalStateException("cannot retain a released ActorGroup event");
                }
                if (current == Integer.MAX_VALUE) {
                    throw new IllegalStateException("ActorGroup event retention overflow");
                }
                if (retentionRefs.compareAndSet(current, current + 1)) return;
            }
        }

        private void releaseDelivery() {
            int remaining = retentionRefs.decrementAndGet();
            if (remaining < 0) {
                retentionRefs.incrementAndGet();
                throw new IllegalStateException("ActorGroup event retention underflow");
            }
            if (remaining == 0) reservation.close();
        }
    }

    /**
     * Publication metadata is available immediately. completion() resolves when
     * all RELIABLE subscriber writes have been admitted. LATEST/LOSSY complete
     * immediately.
     */
    public record PublishReceipt(
            int subscribers,
            int accepted,
            int dropped,
            int coalesced,
            OresFuture<List<Void>> completion) {
        public PublishReceipt {
            if (subscribers < 0 || accepted < 0 || dropped < 0 || coalesced < 0) {
                throw new IllegalArgumentException("publication counts cannot be negative");
            }
            Objects.requireNonNull(completion, "completion");
        }
    }

    public record DoubleReductionResult(
            double value,
            boolean changed,
            PublishReceipt publication) {
        public DoubleReductionResult {
            if (Double.isNaN(value)) throw new IllegalArgumentException("reduction value cannot be NaN");
            Objects.requireNonNull(publication, "publication");
        }
    }

    public record TopicInfo(
            String name,
            DeliveryPolicy policy,
            int capacity,
            int subscribers,
            long published,
            long dropped,
            long coalesced) { }

    private final ActorRuntime runtime;
    private final ActorRuntime.ActorGroup group;
    private final Map<String, TopicState> topics = new ConcurrentHashMap<>();
    private final Map<String, DoubleReductionState> doubleReductions = new ConcurrentHashMap<>();
    private final Object definitionLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    ActorEventBus(ActorRuntime runtime, ActorRuntime.ActorGroup group) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.group = Objects.requireNonNull(group, "group");
    }

    public boolean closed() {
        return closed.get();
    }

    public int topicCount() {
        return topics.size();
    }

    public List<TopicInfo> topics() {
        return topics.values().stream()
                .map(TopicState::info)
                .sorted((left, right) -> left.name().compareTo(right.name()))
                .toList();
    }

    public void defineTopic(String name, DeliveryPolicy policy, int capacity) {
        group.requireManager("define ActorGroup event topics");
        String topicName = normalizeTopicName(name);
        Objects.requireNonNull(policy, "policy");
        validateCapacity(policy, capacity);

        synchronized (definitionLock) {
            requireOpen();
            int topicLimit = group.eventTopicLimit();
            if (topics.size() >= topicLimit && !topics.containsKey(topicName)) {
                throw new IllegalStateException(
                        "ActorGroup event topic limit exceeded: " + topicLimit);
            }

            TopicState created = new TopicState(topicName, policy, capacity);
            TopicState existing = topics.putIfAbsent(topicName, created);
            if (existing != null
                    && (existing.policy != policy || existing.capacity != capacity)) {
                throw new IllegalStateException(
                        "event topic already defined with different policy/capacity: " + topicName);
            }
        }
    }

    /**
     * Defines a monotonic runtime-owned double reduction and a coalescing LATEST
     * notification topic with the same name.
     */
    public void defineDoubleReduction(
            String name,
            DoubleReductionKind kind,
            double initialValue) {
        group.requireManager("define ActorGroup reductions");
        Objects.requireNonNull(kind, "kind");
        if (Double.isNaN(initialValue)) {
            throw new IllegalArgumentException("double reduction initial value cannot be NaN");
        }

        String topicName = normalizeTopicName(name);
        synchronized (definitionLock) {
            requireOpen();
            defineTopic(topicName, DeliveryPolicy.LATEST, 1);
            DoubleReductionState created =
                    new DoubleReductionState(topicName, kind, initialValue);
            DoubleReductionState existing =
                    doubleReductions.putIfAbsent(topicName, created);
            if (existing != null && !existing.sameDefinition(kind, initialValue)) {
                throw new IllegalStateException(
                        "double reduction already defined with different semantics: " + topicName);
            }
        }
    }

    public <T> Subscription<T> subscribe(String name) {
        requireOpen();
        ActorRuntime.ActorId subscriber =
                group.requireCurrentMember("subscribe to ActorGroup events");
        if (ActorRuntime.currentActorKind() != ActorRuntime.ActorKind.SHARED) {
            throw new SecurityException(
                    "private/isolated actors cannot subscribe to the zero-copy ActorGroup event bus yet; "
                            + "use actor mailboxes until isolated event delivery is available");
        }
        TopicState topic = requireTopic(name);

        int actorLimit =
                Math.max(1, ActorRuntime.currentActorPolicy().maxMailboxMessages());
        int capacity = Math.min(topic.capacity, actorLimit);

        synchronized (topic.deliveryLock) {
            requireOpen();
            if (topics.get(topic.name) != topic) {
                throw new IllegalStateException(
                        "ActorGroup event topic is no longer registered: " + topic.name);
            }

            for (;;) {
                @SuppressWarnings("unchecked")
                Subscription<T> existing =
                        (Subscription<T>) topic.subscriptions.get(subscriber);
                if (existing != null) {
                    if (!existing.closed()) return existing;
                    topic.subscriptions.remove(subscriber, existing);
                    continue;
                }

                Subscription<T> created =
                        new Subscription<>(topic, subscriber, capacity);
                Subscription<?> raced =
                        topic.subscriptions.putIfAbsent(subscriber, created);
                if (raced != null) {
                    created.closeChannelOnly();
                    if (raced.closed()) {
                        topic.subscriptions.remove(subscriber, raced);
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Subscription<T> typed = (Subscription<T>) raced;
                    return typed;
                }

                if (!group.isMember(subscriber) || closed.get()) {
                    topic.subscriptions.remove(subscriber, created);
                    created.closeChannelOnly();
                    throw new IllegalStateException(
                            "ActorGroup membership ended during subscription");
                }
                return created;
            }
        }
    }

    public PublishReceipt publish(String name, Object value) {
        requireOpen();
        ActorRuntime.ActorId publisher = group.currentMemberOrSystem("publish ActorGroup events");
        return publishFrozen(
                requireTopic(name),
                runtime.freezeActorGroupEventPayload(value),
                publisher);
    }

    /**
     * Host/supervisor publication path. Actor code cannot impersonate the
     * runtime/system publisher.
     */
    public PublishReceipt publishSystem(String name, Object value) {
        group.requireSupervisor("publish system ActorGroup events");
        requireOpen();
        return publishFrozen(
                requireTopic(name),
                runtime.freezeActorGroupEventPayload(value),
                null);
    }

    public DoubleReductionResult reduceDouble(String name, double candidate) {
        requireOpen();
        if (Double.isNaN(candidate)) {
            throw new IllegalArgumentException("double reduction candidate cannot be NaN");
        }
        ActorRuntime.ActorId publisher = group.currentMemberOrSystem("update ActorGroup reductions");
        DoubleReductionState reduction = requireDoubleReduction(name);
        TopicState topic = requireTopic(name);

        /*
         * Keep state mutation and its LATEST notification in one reduction-local
         * critical section. Without this, two concurrent improvements could
         * publish out of order (for example MIN: 90 after 80), briefly exposing
         * a stale bound even though the atomic state itself was correct.
         *
         * This lock is per reduction, not global to the bus, so unrelated topics
         * and reductions remain fully concurrent.
         */
        synchronized (reduction) {
            double current = reduction.current();
            if (!reduction.improves(candidate, current)) {
                return new DoubleReductionResult(
                        current,
                        false,
                        new PublishReceipt(
                                topic.subscriptions.size(),
                                0,
                                0,
                                0,
                                OresFuture.completed(List.of())));
            }

            ActorRuntime.FrozenActorGroupEventPayload frozen =
                    runtime.freezeActorGroupEventPayload(candidate);
            reduction.set(candidate);
            PublishReceipt receipt = publishFrozen(topic, frozen, publisher);
            return new DoubleReductionResult(candidate, true, receipt);
        }
    }

    public double readDoubleReduction(String name) {
        requireOpen();
        group.currentMemberOrSystem("read ActorGroup reductions");
        DoubleReductionState reduction = requireDoubleReduction(name);
        synchronized (reduction) {
            return reduction.current();
        }
    }

    void removeActor(ActorRuntime.ActorId actorId) {
        for (TopicState topic : topics.values()) {
            synchronized (topic.deliveryLock) {
                Subscription<?> subscription =
                        topic.subscriptions.remove(actorId);
                if (subscription != null) subscription.closeChannelOnly();
            }
        }
    }

    @Override
    public void close() {
        group.requireManager("close an ActorGroup event bus");
        closeFromGroup();
    }

    void closeFromGroup() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (definitionLock) {
            for (TopicState topic : topics.values()) {
                synchronized (topic.deliveryLock) {
                    for (Subscription<?> subscription
                            : List.copyOf(topic.subscriptions.values())) {
                        subscription.closeChannelOnly();
                    }
                    topic.subscriptions.clear();
                }
            }
            topics.clear();
            doubleReductions.clear();
        }
    }

    private PublishReceipt publishFrozen(
            TopicState topic,
            ActorRuntime.FrozenActorGroupEventPayload frozenPayload,
            ActorRuntime.ActorId publisher) {
        Event<Object> event = null;
        try {
            synchronized (topic.deliveryLock) {
                requireOpen();
                if (topics.get(topic.name) != topic) {
                    throw new IllegalStateException(
                            "ActorGroup event topic is no longer registered: " + topic.name);
                }

                long sequence = topic.sequence.incrementAndGet();
                @SuppressWarnings("unchecked")
                Event<Object> created = new Event<>(
                        topic.name,
                        sequence,
                        publisher,
                        System.nanoTime(),
                        frozenPayload.value(),
                        frozenPayload.reservation());
                event = created;

                List<Subscription<?>> snapshot =
                        List.copyOf(topic.subscriptions.values());
                if (snapshot.isEmpty()) {
                    topic.published.incrementAndGet();
                    return new PublishReceipt(
                            0, 0, 0, 0, OresFuture.completed(List.of()));
                }

                List<Subscription<?>> active =
                        new ArrayList<>(snapshot.size());
                for (Subscription<?> subscription : snapshot) {
                    if (subscription.closed()
                            || !group.isMember(subscription.subscriber)) {
                        topic.subscriptions.remove(
                                subscription.subscriber, subscription);
                        subscription.closeChannelOnly();
                    } else {
                        active.add(subscription);
                    }
                }
                if (active.isEmpty()) {
                    topic.published.incrementAndGet();
                    return new PublishReceipt(
                            0, 0, 0, 0, OresFuture.completed(List.of()));
                }

                if (topic.policy == DeliveryPolicy.RELIABLE) {
                    List<Subscription<?>> reserved =
                            new ArrayList<>(active.size());
                    for (Subscription<?> subscription : active) {
                        if (!subscription.reserveReliableEvent()) {
                            for (Subscription<?> admitted : reserved) {
                                admitted.releaseReliableEvent();
                            }
                            throw new EventBackpressureException(
                                    topic.name,
                                    subscription.subscriber,
                                    subscription.reliableOutstandingLimit);
                        }
                        reserved.add(subscription);
                    }

                    List<OresFuture<Void>> writes =
                            new ArrayList<>(reserved.size());
                    int started = 0;
                    try {
                        for (; started < reserved.size(); started++) {
                            @SuppressWarnings("unchecked")
                            Subscription<Object> subscription =
                                    (Subscription<Object>) reserved.get(started);
                            writes.add(
                                    subscription.writeReliableReserved(event));
                        }
                    } catch (Throwable failure) {
                        for (OresFuture<Void> write : writes) {
                            if (!write.isDone()) write.cancel(false);
                        }
                        for (int i = started + 1; i < reserved.size(); i++) {
                            reserved.get(i).releaseReliableEvent();
                        }
                        throw failure;
                    }

                    topic.published.incrementAndGet();
                    return new PublishReceipt(
                            reserved.size(),
                            reserved.size(),
                            0,
                            0,
                            combineReliableWrites(writes));
                }

                int accepted = 0;
                int dropped = 0;
                int coalesced = 0;
                for (Subscription<?> raw : active) {
                    @SuppressWarnings("unchecked")
                    Subscription<Object> subscription =
                            (Subscription<Object>) raw;
                    switch (topic.policy) {
                        case LATEST -> {
                            int replaced = subscription.writeLatest(event);
                            if (replaced >= 0) {
                                accepted++;
                                coalesced += replaced;
                            } else {
                                dropped++;
                            }
                        }
                        case LOSSY -> {
                            if (subscription.writeLossy(event)) accepted++;
                            else dropped++;
                        }
                        case RELIABLE ->
                                throw new AssertionError(
                                        "reliable publication handled above");
                    }
                }

                topic.published.incrementAndGet();
                topic.dropped.addAndGet(dropped);
                topic.coalesced.addAndGet(coalesced);
                return new PublishReceipt(
                        active.size(),
                        accepted,
                        dropped,
                        coalesced,
                        OresFuture.completed(List.of()));
            }
        } finally {
            if (event == null) {
                frozenPayload.reservation().close();
            } else {
                // Drop the publisher's temporary hold. Queued subscribers
                // retain their own references until read/coalesce/close.
                event.releaseDelivery();
            }
        }
    }

    private OresFuture<List<Void>> combineReliableWrites(
            List<OresFuture<Void>> writes) {
        OresFuture<List<Void>> combined = OresFutures.<Void>all(writes);
        combined.whenCompleteRuntime((ignored, failure) -> {
            if (failure == null) return;
            for (OresFuture<Void> write : writes) {
                if (!write.isDone()) write.cancel(false);
            }
        });
        return combined;
    }

    private TopicState requireTopic(String name) {
        String topicName = normalizeTopicName(name);
        TopicState topic = topics.get(topicName);
        if (topic == null) throw new IllegalArgumentException("unknown ActorGroup event topic: " + topicName);
        return topic;
    }

    private DoubleReductionState requireDoubleReduction(String name) {
        String topicName = normalizeTopicName(name);
        DoubleReductionState reduction = doubleReductions.get(topicName);
        if (reduction == null) {
            throw new IllegalArgumentException("unknown ActorGroup double reduction: " + topicName);
        }
        return reduction;
    }

    private void requireOpen() {
        if (closed.get() || group.closed()) {
            throw new IllegalStateException("ActorGroup event bus is closed");
        }
    }

    private static String normalizeTopicName(String name) {
        Objects.requireNonNull(name, "name");
        String normalized = name.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("event topic name cannot be empty");
        if (normalized.length() > 256) throw new IllegalArgumentException("event topic name exceeds 256 characters");
        return normalized;
    }

    private static void validateCapacity(DeliveryPolicy policy, int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("event topic capacity must be > 0");
        if (policy == DeliveryPolicy.LATEST && capacity != 1) {
            throw new IllegalArgumentException("LATEST event topics require capacity=1");
        }
    }

    private final class TopicState {
        private final String name;
        private final DeliveryPolicy policy;
        private final int capacity;
        private final Map<ActorRuntime.ActorId, Subscription<?>> subscriptions =
                new ConcurrentHashMap<>();
        private final Object deliveryLock = new Object();
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicLong published = new AtomicLong();
        private final AtomicLong dropped = new AtomicLong();
        private final AtomicLong coalesced = new AtomicLong();

        private TopicState(String name, DeliveryPolicy policy, int capacity) {
            this.name = name;
            this.policy = policy;
            this.capacity = capacity;
        }

        private TopicInfo info() {
            return new TopicInfo(
                    name,
                    policy,
                    capacity,
                    subscriptions.size(),
                    published.get(),
                    dropped.get(),
                    coalesced.get());
        }
    }

    private static final class DoubleReductionState {
        private final String name;
        private final DoubleReductionKind kind;
        private final long initialBits;
        private final AtomicLong bits;

        private DoubleReductionState(
                String name,
                DoubleReductionKind kind,
                double initialValue) {
            this.name = name;
            this.kind = kind;
            this.initialBits = Double.doubleToRawLongBits(initialValue);
            this.bits = new AtomicLong(initialBits);
        }

        private double current() {
            return Double.longBitsToDouble(bits.get());
        }

        private boolean sameDefinition(
                DoubleReductionKind requestedKind,
                double requestedInitialValue) {
            return kind == requestedKind
                    && initialBits == Double.doubleToRawLongBits(requestedInitialValue);
        }

        private boolean improves(double candidate, double observed) {
            return switch (kind) {
                case MIN -> Double.compare(candidate, observed) < 0;
                case MAX -> Double.compare(candidate, observed) > 0;
            };
        }

        private void set(double candidate) {
            bits.set(Double.doubleToRawLongBits(candidate));
        }
    }

    public final class Subscription<T> implements AutoCloseable {
        private final TopicState topic;
        private final ActorRuntime.ActorId subscriber;
        private final ChannelRuntime.Channel<Event<T>> channel;
        private final Object latestLock = new Object();
        private final AtomicInteger reliableOutstanding = new AtomicInteger();
        private final int reliableOutstandingLimit;
        private final AtomicBoolean subscriptionClosed = new AtomicBoolean();

        private Subscription(
                TopicState topic,
                ActorRuntime.ActorId subscriber,
                int capacity) {
            this.topic = topic;
            this.subscriber = subscriber;
            this.channel = new ChannelRuntime.Channel<>(capacity);
            long bounded = Math.max((long) capacity + 1L, (long) capacity * 2L);
            this.reliableOutstandingLimit = (int) Math.min(Integer.MAX_VALUE, bounded);
        }

        public String topic() {
            return topic.name;
        }

        public ActorRuntime.ActorId subscriber() {
            return subscriber;
        }

        public DeliveryPolicy policy() {
            return topic.policy;
        }

        public int capacity() {
            return channel.capacity();
        }

        public int pending() {
            return channel.size();
        }

        public boolean closed() {
            return subscriptionClosed.get();
        }

        public Optional<Event<T>> tryRead() {
            requireReadable();
            Optional<Event<T>> event = channel.tryRead();
            if (event.isPresent()) {
                if (topic.policy == DeliveryPolicy.RELIABLE) {
                    releaseReliableEvent();
                }
                event.get().releaseDelivery();
            }
            return event;
        }

        public OresFuture<Event<T>> readAsync() {
            requireReadable();
            OresFuture<Event<T>> read = channel.readAsync();
            read.whenCompleteRuntime((value, failure) -> {
                if (failure == null) {
                    if (topic.policy == DeliveryPolicy.RELIABLE) {
                        releaseReliableEvent();
                    }
                    value.releaseDelivery();
                }
            });
            return read;
        }

        private boolean reserveReliableEvent() {
            if (subscriptionClosed.get()) return false;
            while (true) {
                int current = reliableOutstanding.get();
                if (current >= reliableOutstandingLimit) return false;
                if (reliableOutstanding.compareAndSet(current, current + 1)) {
                    if (!subscriptionClosed.get()) return true;
                    releaseReliableEvent();
                    return false;
                }
            }
        }

        private void releaseReliableEvent() {
            while (true) {
                int current = reliableOutstanding.get();
                if (current == 0) return;
                if (reliableOutstanding.compareAndSet(current, current - 1)) return;
            }
        }

        private OresFuture<Void> writeReliableReserved(Event<T> event) {
            event.retainDelivery();
            try {
                if (subscriptionClosed.get()) {
                    event.releaseDelivery();
                    releaseReliableEvent();
                    return OresFuture.failed(
                            new IllegalStateException("event subscription is closed"));
                }
                OresFuture<Void> write = channel.writeAsync(event);
                write.whenCompleteRuntime((ignored, failure) -> {
                    if (failure != null) {
                        event.releaseDelivery();
                        releaseReliableEvent();
                    }
                });
                return write;
            } catch (Throwable failure) {
                event.releaseDelivery();
                releaseReliableEvent();
                throw failure;
            }
        }

        private boolean writeLossy(Event<T> event) {
            if (subscriptionClosed.get()) return false;
            event.retainDelivery();
            try {
                boolean admitted = channel.tryWrite(event);
                if (!admitted) event.releaseDelivery();
                return admitted;
            } catch (ChannelRuntime.ChannelClosedException closedChannel) {
                event.releaseDelivery();
                return false;
            } catch (Throwable failure) {
                event.releaseDelivery();
                throw failure;
            }
        }

        /**
         * Returns 1 when an older unread event was coalesced, 0 when admitted
         * without replacement, and -1 when a close race prevented admission.
         */
        private int writeLatest(Event<T> event) {
            if (subscriptionClosed.get()) return -1;
            synchronized (latestLock) {
                event.retainDelivery();
                boolean retained = true;
                try {
                    if (channel.tryWrite(event)) return 0;

                    Optional<Event<T>> previous = channel.tryRead();
                    if (previous.isPresent()) {
                        previous.get().releaseDelivery();
                    }

                    if (channel.tryWrite(event)) {
                        return previous.isPresent() ? 1 : 0;
                    }

                    event.releaseDelivery();
                    retained = false;
                    if (subscriptionClosed.get()) return -1;
                    throw new IllegalStateException(
                            "LATEST event subscription failed to admit replacement value");
                } catch (ChannelRuntime.ChannelClosedException closedChannel) {
                    if (retained) event.releaseDelivery();
                    return -1;
                } catch (Throwable failure) {
                    if (retained) event.releaseDelivery();
                    throw failure;
                }
            }
        }

        private void requireReadable() {
            if (subscriptionClosed.get()) throw new IllegalStateException("event subscription is closed");
            ActorRuntime.ActorId current = group.requireCurrentMember("read ActorGroup events");
            if (!current.equals(subscriber)) {
                throw new SecurityException("an actor may only read its own ActorGroup event subscription");
            }
        }

        @Override
        public void close() {
            ActorRuntime.ActorId current =
                    group.requireCurrentMember(
                            "close ActorGroup event subscriptions");
            if (!current.equals(subscriber)) {
                throw new SecurityException(
                        "an actor may only close its own ActorGroup event subscription");
            }
            synchronized (topic.deliveryLock) {
                if (topic.subscriptions.remove(subscriber, this)) {
                    closeChannelOnly();
                }
            }
        }

        private void closeChannelOnly() {
            if (!subscriptionClosed.compareAndSet(false, true)) return;
            channel.close();

            // Use the runtime-only drain primitive rather than re-entering the
            // guest-visible tryRead() path while holding the channel monitor.
            // That keeps closed-and-empty teardown terminal rather than
            // exceptional and releases delivery/accounting callbacks outside
            // the channel lock.
            while (channel.drainOne(buffered -> {
                if (topic.policy == DeliveryPolicy.RELIABLE) {
                    releaseReliableEvent();
                }
                buffered.releaseDelivery();
            })) {
                // Drain every already-buffered immutable delivery. Pending
                // writers were failed by channel.close() and release their own
                // delivery/accounting reservations from completion callbacks.
            }
        }
    }
}
