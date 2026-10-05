package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Explicit read/write synchronization for externally shared Oreslang state.
 *
 * <p>Actor isolation is intentionally asymmetric:</p>
 * <ul>
 *   <li>ordinary/root/scheduler tasks may acquire read or write guards;</li>
 *   <li>SHARED actors may acquire only read guards;</li>
 *   <li>PRIVATE/isoactors and UNTRUSTED actors cannot access this shared-memory
 *       primitive at all;</li>
 *   <li>no actor may acquire a write guard, because actor writes must remain
 *       confined to actor-owned state or explicit mailbox/output channels.</li>
 * </ul>
 *
 * <p>Read guards are a lexical capability. The Oreslang type checker must expose
 * their value as read-only and reject a guard that would live across
 * {@code await}; the JVM lock is thread-affine while Ores continuations are
 * scheduler-affine and may resume on another carrier.</p>
 */
public final class OresRwLock<T> {
    /**
     * Scheduler/actor carriers are never parked waiting for an external lock.
     * Source code may handle this explicitly or use a future async-lock layer
     * when that layer lands.
     */
    public static final class WouldBlockException extends IllegalStateException {
        public WouldBlockException(String message) {
            super(message);
        }
    }
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private ActorRuntime owningRuntime;
    private T value;

    public OresRwLock(T value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    /**
     * Acquire shared read access.
     *
     * <p>Inside an actor turn this is legal only for a SHARED actor with the
     * SHARED_MEMORY capability. The returned value is semantically read-only to
     * Oreslang guest code.</p>
     */
    public ReadGuard<T> readLock() {
        requireReadAccess();

        if (inOresExecution()) {
            if (!lock.readLock().tryLock()) {
                throw new WouldBlockException(
                        "OresRwLock read would block an Ores carrier; use tryReadLock() "
                                + "or an async lock acquisition primitive");
            }
        } else {
            lock.readLock().lock();
        }
        return new ReadGuard<>(this, Thread.currentThread());
    }

    /**
     * Nonblocking read admission. This is the preferred primitive inside an
     * actor/scheduler turn until async RW-lock acquisition is lowered directly
     * to OresFuture.
     */
    public Optional<ReadGuard<T>> tryReadLock() {
        requireReadAccess();
        if (!lock.readLock().tryLock()) return Optional.empty();
        return Optional.of(new ReadGuard<>(this, Thread.currentThread()));
    }

    /**
     * Acquire exclusive write access.
     *
     * <p>All actor execution is rejected. External mutation belongs to ordinary
     * scheduler/root code; actors observe it only through {@link #readLock()}.</p>
     */
    public WriteGuard<T> writeLock() {
        requireWriteAccess();

        if (inOresExecution()) {
            if (!lock.writeLock().tryLock()) {
                throw new WouldBlockException(
                        "OresRwLock write would block an Ores carrier; use a nonblocking "
                                + "or async lock acquisition primitive");
            }
        } else {
            lock.writeLock().lock();
        }
        return new WriteGuard<>(this, Thread.currentThread());
    }

    public Optional<WriteGuard<T>> tryWriteLock() {
        requireWriteAccess();
        if (!lock.writeLock().tryLock()) return Optional.empty();
        return Optional.of(new WriteGuard<>(this, Thread.currentThread()));
    }

    public int readLockCount() {
        return lock.getReadLockCount();
    }

    public boolean isWriteLocked() {
        return lock.isWriteLocked();
    }

    synchronized boolean bindToRuntime(ActorRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        if (owningRuntime == null) {
            owningRuntime = runtime;
            return true;
        }
        return owningRuntime == runtime;
    }

    synchronized boolean ownedBy(ActorRuntime runtime) {
        return owningRuntime == null || owningRuntime == runtime;
    }

    private static boolean inOresExecution() {
        return ActorRuntime.inActorExecution() || OresScheduler.current() != null;
    }

    private void requireReadAccess() {
        ActorRuntime.ActorKind kind = ActorRuntime.currentActorKind();
        if (kind == null) return;

        if (kind == ActorRuntime.ActorKind.PRIVATE) {
            throw new SecurityException(
                    "iso/private actors cannot read externally shared memory; "
                            + "copy immutable input through a message/capability instead");
        }
        if (kind == ActorRuntime.ActorKind.UNTRUSTED) {
            throw new SecurityException(
                    "untrusted actors cannot access externally shared memory");
        }

        IsolatePolicy policy = ActorRuntime.currentActorPolicy();
        if (policy != null) {
            policy.require(
                    IsolatePolicy.Capability.SHARED_MEMORY,
                    "OresRwLock.readLock");
        }
    }

    private void requireWriteAccess() {
        if (ActorRuntime.inActorExecution()) {
            throw new SecurityException(
                    "actors cannot mutate external state through OresRwLock; "
                            + "write actor-owned state or send/emit a message");
        }
    }

    private T readValue() {
        return value;
    }

    private void replaceValue(T next) {
        value = Objects.requireNonNull(next, "next");
    }

    public static final class ReadGuard<T> implements AutoCloseable {
        private final OresRwLock<T> owner;
        private final Thread carrier;
        private boolean closed;

        private ReadGuard(OresRwLock<T> owner, Thread carrier) {
            this.owner = owner;
            this.carrier = carrier;
        }

        /**
         * Read-only projection in Oreslang source. The Java runtime cannot make
         * an arbitrary host object deeply immutable, so compiler/interop
         * lowering must not expose mutating operations through this reference.
         */
        public T value() {
            requireOpenCarrier();
            return owner.readValue();
        }

        public boolean closed() {
            return closed;
        }

        @Override
        public void close() {
            if (closed) return;
            requireCarrier();
            closed = true;
            owner.lock.readLock().unlock();
        }

        private void requireOpenCarrier() {
            if (closed) {
                throw new IllegalStateException("OresRwLock read guard is closed");
            }
            requireCarrier();
        }

        private void requireCarrier() {
            if (Thread.currentThread() != carrier) {
                throw new IllegalStateException(
                        "OresRwLock guard cannot cross an await/carrier migration");
            }
        }
    }

    public static final class WriteGuard<T> implements AutoCloseable {
        private final OresRwLock<T> owner;
        private final Thread carrier;
        private boolean closed;

        private WriteGuard(OresRwLock<T> owner, Thread carrier) {
            this.owner = owner;
            this.carrier = carrier;
        }

        public T value() {
            requireOpenCarrier();
            return owner.readValue();
        }

        public void replace(T next) {
            requireOpenCarrier();
            owner.replaceValue(next);
        }

        public boolean closed() {
            return closed;
        }

        @Override
        public void close() {
            if (closed) return;
            requireCarrier();
            closed = true;
            owner.lock.writeLock().unlock();
        }

        private void requireOpenCarrier() {
            if (closed) {
                throw new IllegalStateException("OresRwLock write guard is closed");
            }
            requireCarrier();
        }

        private void requireCarrier() {
            if (Thread.currentThread() != carrier) {
                throw new IllegalStateException(
                        "OresRwLock guard cannot cross an await/carrier migration");
            }
        }
    }
}
