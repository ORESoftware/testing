package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Oreslang-owned channel and select substrate.
 *
 * <p>Language-level channel waits never require a carrier thread to park.
 * {@link Channel#readAsync()} and {@link Channel#writeAsync(Object)} return
 * runtime-owned {@link OresFuture} values. Compiler lowering decides whether
 * source-level {@code readch}/{@code writech}/{@code select} awaits those
 * Futures or keeps them armed via an {@code nb} form.</p>
 *
 * <p>Static and dynamic select both lower to {@link SelectSet}. FAIR selection
 * is deterministic round-robin over a stable SelectSet; PRIORITY always probes
 * lexical/list order; RANDOM is explicit opt-in and is never the default.</p>
 */
public final class ChannelRuntime {
    private ChannelRuntime() { }

    public enum SelectPolicy {
        FAIR,
        PRIORITY,
        RANDOM
    }

    public enum SelectOperation {
        READ,
        WRITE,
        DEFAULT
    }

    public record SelectResult(
            int index,
            SelectOperation operation,
            Object value) {
        public SelectResult {
            if (index < 0) throw new IllegalArgumentException("select result index must be non-negative");
            Objects.requireNonNull(operation, "operation");
        }
    }

    public static final class ChannelClosedException extends IllegalStateException {
        public ChannelClosedException(String message) {
            super(message);
        }
    }

    private static final class ReadWaiter<T> {
        private final AtomicBoolean active = new AtomicBoolean(true);
        private OresFuture<T> future;
    }

    private static final class WriteWaiter<T> {
        private final T value;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private OresFuture<Void> future;

        private WriteWaiter(T value) {
            this.value = value;
        }
    }

    /**
     * Bounded MPMC channel. Capacity zero is a rendezvous channel.
     *
     * <p>Null is deliberately rejected because Oreslang has no standalone null
     * value. Closed and empty therefore never need to be encoded as a fake
     * payload value.</p>
     */
    public static final class Channel<T> implements AutoCloseable {
        private final int capacity;
        private final ArrayDeque<T> buffer = new ArrayDeque<>();
        private final ArrayDeque<ReadWaiter<T>> readers = new ArrayDeque<>();
        private final ArrayDeque<WriteWaiter<T>> writers = new ArrayDeque<>();
        private final Set<SelectRegistration> selectWaiters = new LinkedHashSet<>();
        private boolean closed;
        private Throwable closeCause;

        public Channel(int capacity) {
            if (capacity < 0) throw new IllegalArgumentException("channel capacity cannot be negative");
            this.capacity = capacity;
        }

        public int capacity() {
            return capacity;
        }

        public synchronized int size() {
            return buffer.size();
        }

        public synchronized boolean isEmpty() {
            return buffer.isEmpty();
        }

        public synchronized boolean isClosed() {
            return closed;
        }

        /**
         * Immediate receive probe. This never registers a waiter.
         */
        public Optional<T> tryRead() {
            OresFuture<T> attempt = readAsync();
            if (!attempt.isDone() && attempt.cancel(false)) {
                return Optional.empty();
            }
            return Optional.of(attempt.join());
        }

        /**
         * Immediate send probe. This never registers a waiter.
         */
        public boolean tryWrite(T value) {
            requireValue(value);
            OresFuture<Void> attempt = writeAsync(value);
            if (!attempt.isDone() && attempt.cancel(false)) {
                return false;
            }
            attempt.join();
            return true;
        }

        /**
         * Scheduler-friendly receive registration.
         */
        public OresFuture<T> readAsync() {
            WriteWaiter<T> writer = null;
            T immediate = null;
            List<SelectRegistration> wake = List.of();
            ReadWaiter<T> waiter = null;

            synchronized (this) {
                pruneReaders();
                pruneWriters();

                if (!buffer.isEmpty()) {
                    immediate = buffer.removeFirst();
                    writer = pollActiveWriter();
                    if (writer != null && capacity > 0) buffer.addLast(writer.value);
                    wake = selectSnapshot();
                } else {
                    writer = pollActiveWriter();
                    if (writer != null) {
                        immediate = writer.value;
                        wake = selectSnapshot();
                    } else if (closed) {
                        return OresFuture.failed(closedFailure());
                    } else {
                        waiter = new ReadWaiter<>();
                        ReadWaiter<T> registered = waiter;
                        waiter.future = new OresFuture<>(
                                () -> cancelRead(registered),
                                () -> { });
                        readers.addLast(waiter);
                        wake = selectSnapshot();
                    }
                }
            }

            if (writer != null) writer.future.completeFromRuntime(null);
            signal(wake);
            return waiter == null
                    ? OresFuture.completed(immediate)
                    : waiter.future;
        }

        /**
         * Scheduler-friendly send registration.
         */
        public OresFuture<Void> writeAsync(T value) {
            requireValue(value);
            ReadWaiter<T> reader;
            List<SelectRegistration> wake;
            WriteWaiter<T> waiter = null;

            synchronized (this) {
                if (closed) return OresFuture.failed(closedFailure());
                pruneReaders();
                pruneWriters();

                reader = pollActiveReader();
                if (reader == null && buffer.size() >= capacity) {
                    waiter = new WriteWaiter<>(value);
                    WriteWaiter<T> registered = waiter;
                    waiter.future = new OresFuture<>(
                                () -> cancelWrite(registered),
                                () -> { });
                    writers.addLast(waiter);
                    wake = selectSnapshot();
                } else {
                    if (reader == null) buffer.addLast(value);
                    wake = selectSnapshot();
                }
            }

            if (reader != null) reader.future.completeFromRuntime(value);
            signal(wake);
            return waiter == null
                    ? OresFuture.completed(null)
                    : waiter.future;
        }

        private boolean cancelRead(ReadWaiter<T> waiter) {
            synchronized (this) {
                if (!waiter.active.compareAndSet(true, false)) return false;
                readers.remove(waiter);
                return true;
            }
        }

        private boolean cancelWrite(WriteWaiter<T> waiter) {
            synchronized (this) {
                if (!waiter.active.compareAndSet(true, false)) return false;
                writers.remove(waiter);
                return true;
            }
        }

        private ReadWaiter<T> pollActiveReader() {
            ReadWaiter<T> waiter;
            while ((waiter = readers.pollFirst()) != null) {
                if (waiter.active.compareAndSet(true, false)) return waiter;
            }
            return null;
        }

        private WriteWaiter<T> pollActiveWriter() {
            WriteWaiter<T> waiter;
            while ((waiter = writers.pollFirst()) != null) {
                if (waiter.active.compareAndSet(true, false)) return waiter;
            }
            return null;
        }

        private void pruneReaders() {
            readers.removeIf(waiter -> !waiter.active.get() || waiter.future.isDone());
        }

        private void pruneWriters() {
            writers.removeIf(waiter -> !waiter.active.get() || waiter.future.isDone());
        }

        private void requireValue(T value) {
            Objects.requireNonNull(value, "Oreslang channels cannot carry null; use Option<T>");
        }

        private ChannelClosedException closedFailure() {
            ChannelClosedException failure =
                    new ChannelClosedException("channel is closed");
            if (closeCause != null) failure.initCause(closeCause);
            return failure;
        }

        private void registerSelect(SelectRegistration registration) {
            boolean wake;
            synchronized (this) {
                if (registration.decided()) return;
                selectWaiters.add(registration);
                wake = closed || !buffer.isEmpty() || !readers.isEmpty() || !writers.isEmpty();
            }
            if (wake) registration.signal();
        }

        private synchronized void unregisterSelect(SelectRegistration registration) {
            selectWaiters.remove(registration);
        }

        private synchronized List<SelectRegistration> selectSnapshot() {
            if (selectWaiters.isEmpty()) return List.of();
            return List.copyOf(selectWaiters);
        }

        private ProbeResult probeRead(SelectRegistration registration, int index) {
            WriteWaiter<T> writer = null;
            T value = null;
            Throwable failure = null;
            boolean won = false;
            List<SelectRegistration> wake = List.of();

            synchronized (this) {
                if (registration.decided()) return ProbeResult.NOT_READY;
                pruneWriters();

                if (!buffer.isEmpty()) {
                    if (!registration.tryClaim()) return ProbeResult.LOST;
                    won = true;
                    value = buffer.removeFirst();
                    writer = pollActiveWriter();
                    if (writer != null && capacity > 0) buffer.addLast(writer.value);
                    wake = selectSnapshot();
                } else {
                    writer = pollActiveWriter();
                    if (writer != null) {
                        if (!registration.tryClaim()) {
                            // Put the still-active writer back at the head. Its
                            // active bit was claimed by pollActiveWriter, so
                            // restore it before requeueing.
                            writer.active.set(true);
                            writers.addFirst(writer);
                            return ProbeResult.LOST;
                        }
                        won = true;
                        value = writer.value;
                        wake = selectSnapshot();
                    } else if (closed) {
                        if (!registration.tryClaim()) return ProbeResult.LOST;
                        won = true;
                        failure = closedFailure();
                    } else {
                        return ProbeResult.NOT_READY;
                    }
                }
            }

            if (!won) return ProbeResult.NOT_READY;
            if (writer != null) writer.future.completeFromRuntime(null);
            signal(wake);
            if (failure == null) {
                registration.finish(new SelectResult(index, SelectOperation.READ, value), null);
            } else {
                registration.finish(null, failure);
            }
            return ProbeResult.WON;
        }

        private ProbeResult probeWrite(
                SelectRegistration registration,
                int index,
                Object rawValue) {
            @SuppressWarnings("unchecked")
            T value = (T) Objects.requireNonNull(
                    rawValue, "Oreslang channels cannot carry null; use Option<T>");
            ReadWaiter<T> reader = null;
            Throwable failure = null;
            boolean won = false;
            List<SelectRegistration> wake = List.of();

            synchronized (this) {
                if (registration.decided()) return ProbeResult.NOT_READY;
                pruneReaders();

                if (closed) {
                    if (!registration.tryClaim()) return ProbeResult.LOST;
                    won = true;
                    failure = closedFailure();
                } else {
                    reader = pollActiveReader();
                    boolean bufferedReady = reader == null && buffer.size() < capacity;
                    if (reader == null && !bufferedReady) return ProbeResult.NOT_READY;

                    if (!registration.tryClaim()) {
                        if (reader != null) {
                            reader.active.set(true);
                            readers.addFirst(reader);
                        }
                        return ProbeResult.LOST;
                    }
                    won = true;
                    if (reader == null) buffer.addLast(value);
                    wake = selectSnapshot();
                }
            }

            if (!won) return ProbeResult.NOT_READY;
            if (reader != null) reader.future.completeFromRuntime(value);
            signal(wake);
            if (failure == null) {
                registration.finish(new SelectResult(index, SelectOperation.WRITE, null), null);
            } else {
                registration.finish(null, failure);
            }
            return ProbeResult.WON;
        }

        /**
         * Close the channel, preserving already-buffered values for later reads
         * and failing pending registrations that cannot be satisfied.
         */
        @Override
        public void close() {
            close(null);
        }

        public void close(Throwable cause) {
            List<ReadWaiter<T>> failedReaders = new ArrayList<>();
            List<WriteWaiter<T>> failedWriters = new ArrayList<>();
            List<SelectRegistration> wake;

            synchronized (this) {
                if (closed) return;
                closed = true;
                closeCause = cause;

                pruneReaders();
                pruneWriters();

                // Buffered values remain readable after close. Satisfy as many
                // already-waiting readers as possible before failing the rest.
                while (!buffer.isEmpty()) {
                    ReadWaiter<T> reader = pollActiveReader();
                    if (reader == null) break;
                    T value = buffer.removeFirst();
                    reader.future.completeFromRuntime(value);
                }

                ReadWaiter<T> reader;
                while ((reader = pollActiveReader()) != null) failedReaders.add(reader);
                WriteWaiter<T> writer;
                while ((writer = pollActiveWriter()) != null) failedWriters.add(writer);
                wake = selectSnapshot();
            }

            ChannelClosedException failure = closedFailure();
            for (ReadWaiter<T> reader : failedReaders) reader.future.failFromRuntime(failure);
            for (WriteWaiter<T> writer : failedWriters) writer.future.failFromRuntime(failure);
            signal(wake);
        }
    }

    public sealed interface SelectCase permits ReadCase, WriteCase, DefaultCase { }

    public record ReadCase<T>(Channel<T> channel) implements SelectCase {
        public ReadCase {
            Objects.requireNonNull(channel, "channel");
        }
    }

    public record WriteCase<T>(Channel<T> channel, T value) implements SelectCase {
        public WriteCase {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(value, "Oreslang channels cannot carry null; use Option<T>");
        }
    }

    public record DefaultCase() implements SelectCase { }

    public static <T> ReadCase<T> read(Channel<T> channel) {
        return new ReadCase<>(channel);
    }

    public static <T> WriteCase<T> write(Channel<T> channel, T value) {
        return new WriteCase<>(channel, value);
    }

    public static DefaultCase defaultCase() {
        return new DefaultCase();
    }

    /**
     * Reusable select descriptor. Dynamic select is simply a SelectSet built
     * from a runtime list/map instead of compiler-emitted static cases.
     */
    public static final class SelectSet {
        private final List<SelectCase> cases;
        private final AtomicLong fairCursor = new AtomicLong();

        public SelectSet(Collection<? extends SelectCase> cases) {
            this(cases, 0L);
        }

        /**
         * Runtime/compiler hook for static select sites that retain a
         * deterministic fairness ticket across repeated executions while
         * rebuilding their evaluated channel/value cases each time.
         */
        public SelectSet(
                Collection<? extends SelectCase> cases,
                long initialFairCursor) {
            Objects.requireNonNull(cases, "cases");
            if (cases.isEmpty()) throw new IllegalArgumentException("select requires at least one case");
            this.cases = List.copyOf(cases);
            this.fairCursor.set(initialFairCursor);
            long defaults = this.cases.stream().filter(DefaultCase.class::isInstance).count();
            if (defaults > 1) throw new IllegalArgumentException("select permits at most one default case");
        }

        public static SelectSet of(SelectCase... cases) {
            return new SelectSet(List.of(cases));
        }

        public static SelectSet from(Iterable<? extends SelectCase> cases) {
            ArrayList<SelectCase> copy = new ArrayList<>();
            for (SelectCase selectCase : cases) copy.add(Objects.requireNonNull(selectCase));
            return new SelectSet(copy);
        }

        /**
         * Map form preserves insertion order when supplied a LinkedHashMap.
         * Keys remain compiler/user metadata; selection indices follow values().
         */
        public static SelectSet fromMap(Map<?, ? extends SelectCase> cases) {
            return new SelectSet(cases.values());
        }

        public List<SelectCase> cases() {
            return cases;
        }

        public OresFuture<SelectResult> selectAsync() {
            return selectAsync(SelectPolicy.FAIR);
        }

        public OresFuture<SelectResult> selectAsync(SelectPolicy policy) {
            SelectRegistration registration =
                    new SelectRegistration(this, Objects.requireNonNull(policy));
            registration.start();
            return registration.future;
        }

        public Optional<SelectResult> trySelect() {
            return trySelect(SelectPolicy.FAIR);
        }

        public Optional<SelectResult> trySelect(SelectPolicy policy) {
            SelectRegistration registration =
                    new SelectRegistration(this, Objects.requireNonNull(policy));
            registration.probe(false);
            if (!registration.future.isDone()) {
                registration.abandonProbe();
                return Optional.empty();
            }
            try {
                return Optional.of(registration.future.join());
            } catch (RuntimeException failed) {
                throw failed;
            }
        }

        private int[] probeOrder(SelectPolicy policy) {
            int count = cases.size();
            int[] order = new int[count];
            if (policy == SelectPolicy.PRIORITY) {
                for (int i = 0; i < count; i++) order[i] = i;
                return order;
            }

            int start;
            if (policy == SelectPolicy.RANDOM) {
                start = ThreadLocalRandom.current().nextInt(count);
            } else {
                start = Math.floorMod(fairCursor.getAndIncrement(), count);
            }
            for (int i = 0; i < count; i++) order[i] = (start + i) % count;
            return order;
        }
    }

    private enum ProbeResult {
        WON,
        LOST,
        NOT_READY
    }

    private static final class SelectRegistration {
        private final SelectSet set;
        private final SelectPolicy policy;
        private final AtomicBoolean decided = new AtomicBoolean();
        private final AtomicBoolean attached = new AtomicBoolean();
        private final OresFuture<SelectResult> future;

        private SelectRegistration(SelectSet set, SelectPolicy policy) {
            this.set = set;
            this.policy = policy;
            this.future = new OresFuture<>(
                    this::claimCancellation,
                    this::detach);
        }

        private boolean decided() {
            return decided.get();
        }

        private boolean tryClaim() {
            return decided.compareAndSet(false, true);
        }

        private boolean claimCancellation() {
            return decided.compareAndSet(false, true);
        }

        private void start() {
            if (probe(true)) return;
            attach();
            // Close the race between the first probe and registration.
            if (!decided()) probe(true);
        }

        /**
         * Returns true when a case completed or failed the selection.
         */
        private boolean probe(boolean allowDefault) {
            if (decided()) return true;
            int defaultIndex = -1;

            for (int index : set.probeOrder(policy)) {
                SelectCase selectCase = set.cases.get(index);
                if (selectCase instanceof DefaultCase) {
                    defaultIndex = index;
                    continue;
                }

                ProbeResult result;
                if (selectCase instanceof ReadCase<?> read) {
                    result = read.channel().probeRead(this, index);
                } else if (selectCase instanceof WriteCase<?> write) {
                    result = write.channel().probeWrite(this, index, write.value());
                } else {
                    throw new AssertionError("unknown select case " + selectCase);
                }
                if (result == ProbeResult.WON || decided()) return true;
            }

            if (allowDefault && defaultIndex >= 0 && tryClaim()) {
                finish(new SelectResult(defaultIndex, SelectOperation.DEFAULT, null), null);
                return true;
            }
            return decided();
        }

        private void attach() {
            if (!attached.compareAndSet(false, true)) return;
            for (Channel<?> channel : channels()) {
                if (decided()) break;
                channel.registerSelect(this);
            }
            if (decided()) detach();
        }

        private void detach() {
            if (!attached.compareAndSet(true, false)) return;
            for (Channel<?> channel : channels()) channel.unregisterSelect(this);
        }

        private Set<Channel<?>> channels() {
            LinkedHashSet<Channel<?>> channels = new LinkedHashSet<>();
            for (SelectCase selectCase : set.cases) {
                if (selectCase instanceof ReadCase<?> read) channels.add(read.channel());
                else if (selectCase instanceof WriteCase<?> write) channels.add(write.channel());
            }
            return channels;
        }

        private void signal() {
            if (decided()) return;
            probe(false);
        }

        private void finish(SelectResult result, Throwable failure) {
            detach();
            if (failure == null) future.completeFromRuntime(result);
            else future.failFromRuntime(failure);
        }

        private void abandonProbe() {
            if (decided.compareAndSet(false, true)) detach();
        }
    }

    private static void signal(List<SelectRegistration> registrations) {
        for (SelectRegistration registration : registrations) registration.signal();
    }
}
