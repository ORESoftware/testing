package dev.oreslang.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Secondary runtime reclamation for host/interop resources that are not governed
 * solely by Oreslang ownership. Guest memory safety remains ownership/borrow
 * based; this collector cannot make an invalid ownership program valid.
 */
public final class RuntimeGarbageCollector implements AutoCloseable {
    private static final Duration DEFAULT_PERIOD = Duration.ofSeconds(30);
    private static final Duration DEFAULT_MIN_PROCESS_GC_INTERVAL = Duration.ofSeconds(5);
    private static final int DEFAULT_MAX_TRACKED = 100_000;
    private static final int DEFAULT_MAX_TRACKED_PER_ACTOR = 4_096;
    private static final int DEFAULT_MAX_ACTOR_SWEEP_ENTRIES = 256;
    private static final Object PROCESS_DOMAIN = new Object();
    private static final ScheduledExecutorService SWEEP_TIMER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "ores-gc-timer");
                thread.setDaemon(true);
                return thread;
            });

    public record CollectionReport(
            String scope,
            long collection,
            int trackedBefore,
            int inspected,
            int cleaned,
            int trackedAfter,
            int cleanupFailures,
            boolean jvmGcRequested) {
        public Map<String,Object> asMap() {
            return Map.of(
                    "scope", scope,
                    "collection", collection,
                    "tracked_before", trackedBefore,
                    "inspected", inspected,
                    "cleaned", cleaned,
                    "tracked_after", trackedAfter,
                    "cleanup_failures", cleanupFailures,
                    "jvm_gc_requested", jvmGcRequested);
        }
    }

    private static final class TrackedCleanup extends WeakReference<Object> {
        private final Object domain;
        private final Runnable cleanup;
        private final AtomicBoolean cleaning = new AtomicBoolean();
        private final AtomicBoolean cleaned = new AtomicBoolean();

        private TrackedCleanup(
                Object owner,
                Object domain,
                Runnable cleanup,
                ReferenceQueue<Object> queue) {
            super(Objects.requireNonNull(owner), queue);
            this.domain = Objects.requireNonNull(domain);
            this.cleanup = Objects.requireNonNull(cleanup);
        }

        private boolean eligible(Set<Object> retiredDomains) {
            return get() == null || retiredDomains.contains(domain);
        }

        private boolean tryClean() {
            if (cleaned.get() || !cleaning.compareAndSet(false, true)) return false;
            try {
                cleanup.run();
                cleaned.set(true);
                return true;
            } finally {
                cleaning.set(false);
            }
        }
    }

    public final class CleanupHandle implements AutoCloseable {
        private final TrackedCleanup entry;
        private CleanupHandle(TrackedCleanup entry) { this.entry = entry; }

        @Override
        public void close() {
            if (entry.cleaned.get()) {
                removeTracked(entry);
                return;
            }
            boolean cleanedNow = entry.tryClean();
            if (cleanedNow) removeTracked(entry);
        }
    }

    private final Set<TrackedCleanup> tracked = ConcurrentHashMap.newKeySet();
    private final Set<TrackedCleanup> retryableFailures = ConcurrentHashMap.newKeySet();
    private final Map<Object, Set<TrackedCleanup>> trackedByDomain = new ConcurrentHashMap<>();
    private final Set<Object> retiredDomains = ConcurrentHashMap.newKeySet();
    private final ReferenceQueue<Object> referenceQueue = new ReferenceQueue<>();
    private final AtomicLong collections = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong lastJvmGcNanos = new AtomicLong(Long.MIN_VALUE);
    private final Object lifecycleLock = new Object();
    private final Runnable jvmGcRequest;
    private final Duration minProcessGcInterval;
    private final int maxTracked;
    private final int maxTrackedPerActor;
    private final int maxActorSweepEntries;
    private final ScheduledFuture<?> periodicSweep;

    public RuntimeGarbageCollector() {
        this(
                System::gc,
                DEFAULT_PERIOD,
                DEFAULT_MIN_PROCESS_GC_INTERVAL,
                DEFAULT_MAX_TRACKED,
                DEFAULT_MAX_TRACKED_PER_ACTOR,
                DEFAULT_MAX_ACTOR_SWEEP_ENTRIES);
    }

    RuntimeGarbageCollector(Runnable jvmGcRequest, Duration period) {
        this(
                jvmGcRequest,
                period,
                DEFAULT_MIN_PROCESS_GC_INTERVAL,
                DEFAULT_MAX_TRACKED,
                DEFAULT_MAX_TRACKED_PER_ACTOR,
                DEFAULT_MAX_ACTOR_SWEEP_ENTRIES);
    }

    RuntimeGarbageCollector(
            Runnable jvmGcRequest,
            Duration period,
            Duration minProcessGcInterval,
            int maxTracked) {
        this(
                jvmGcRequest,
                period,
                minProcessGcInterval,
                maxTracked,
                Math.min(maxTracked, DEFAULT_MAX_TRACKED_PER_ACTOR),
                DEFAULT_MAX_ACTOR_SWEEP_ENTRIES);
    }

    RuntimeGarbageCollector(
            Runnable jvmGcRequest,
            Duration period,
            Duration minProcessGcInterval,
            int maxTracked,
            int maxTrackedPerActor,
            int maxActorSweepEntries) {
        this.jvmGcRequest = Objects.requireNonNull(jvmGcRequest);
        this.minProcessGcInterval = requirePositive(minProcessGcInterval, "minimum process GC interval");
        requirePositive(period, "GC sweep period");
        if (maxTracked <= 0) throw new IllegalArgumentException("maxTracked must be positive");
        if (maxTrackedPerActor <= 0) throw new IllegalArgumentException("maxTrackedPerActor must be positive");
        if (maxTrackedPerActor > maxTracked) throw new IllegalArgumentException("maxTrackedPerActor cannot exceed maxTracked");
        if (maxActorSweepEntries <= 0) throw new IllegalArgumentException("maxActorSweepEntries must be positive");
        this.maxTracked = maxTracked;
        this.maxTrackedPerActor = maxTrackedPerActor;
        this.maxActorSweepEntries = maxActorSweepEntries;
        long periodNanos = period.toNanos();
        this.periodicSweep = SWEEP_TIMER.scheduleWithFixedDelay(
                this::safePeriodicSweep,
                periodNanos,
                periodNanos,
                TimeUnit.NANOSECONDS);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value);
        if (value.isNegative() || value.isZero()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    /**
     * Registers an idempotent cleanup hook. The owner is weakly referenced;
     * callers must not capture the owner strongly from the cleanup closure.
     */
    public CleanupHandle track(Object owner, Runnable cleanup) {
        synchronized (lifecycleLock) {
            ensureOpen();
            if (tracked.size() >= maxTracked) {
                throw new IllegalStateException("runtime cleanup registry limit exceeded: " + maxTracked);
            }
            Object actorDomain = ActorRuntime.currentActorExecutionDomain();
            Object domain = actorDomain == null ? PROCESS_DOMAIN : actorDomain;
            Set<TrackedCleanup> domainEntries =
                    trackedByDomain.computeIfAbsent(domain, ignored -> ConcurrentHashMap.newKeySet());
            if (actorDomain != null && domainEntries.size() >= maxTrackedPerActor) {
                throw new IllegalStateException(
                        "actor cleanup registry limit exceeded: " + maxTrackedPerActor);
            }
            TrackedCleanup entry = new TrackedCleanup(owner, domain, cleanup, referenceQueue);
            tracked.add(entry);
            domainEntries.add(entry);
            return new CleanupHandle(entry);
        }
    }

    public CollectionReport collectProcess() {
        ensureOpen();
        boolean requested = requestJvmGcIfAllowed();
        return sweep(null, requested, Integer.MAX_VALUE);
    }

    public CollectionReport collectCurrentActor() {
        ensureOpen();
        Object actorDomain = ActorRuntime.currentActorExecutionDomain();
        if (actorDomain == null) throw new IllegalStateException("actor.gc() requires execution inside an actor");
        return sweep(actorDomain, false, maxActorSweepEntries);
    }

    public CollectionReport collectPeriodic() {
        ensureOpen();
        return sweep(null, false, Integer.MAX_VALUE);
    }

    /**
     * Retires one semantic actor domain. Actor-local runtime resources are
     * deterministic actor-lifetime resources: actor termination makes them
     * cleanup-eligible even if a stale host reference still exists.
     *
     * Failed cleanup hooks remain registered and are retried by later process
     * or periodic sweeps. The per-actor registry cap bounds exit work.
     */
    public CollectionReport retireActorDomain(Object actorDomain) {
        Objects.requireNonNull(actorDomain, "actorDomain");
        ensureOpen();
        retiredDomains.add(actorDomain);
        return sweep(actorDomain, false, Integer.MAX_VALUE);
    }

    private boolean requestJvmGcIfAllowed() {
        long now = System.nanoTime();
        long previous = lastJvmGcNanos.get();
        long minGap = minProcessGcInterval.toNanos();
        if (previous != Long.MIN_VALUE && now - previous < minGap) return false;
        if (!lastJvmGcNanos.compareAndSet(previous, now)) return false;
        try {
            jvmGcRequest.run();
            return true;
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private CollectionReport sweep(
            Object requestedDomain,
            boolean jvmGcRequested,
            int maxEntriesToInspect) {
        int before = requestedDomain == null
                ? tracked.size()
                : trackedByDomain.getOrDefault(requestedDomain, Set.of()).size();
        int inspected = 0;
        int cleanedCount = 0;
        int cleanupFailures = 0;

        if (requestedDomain == null) {
            for (TrackedCleanup entry : Set.copyOf(retryableFailures)) {
                if (inspected >= maxEntriesToInspect) break;
                if (!tracked.contains(entry)) {
                    retryableFailures.remove(entry);
                    continue;
                }
                inspected++;
                try {
                    if (entry.tryClean()) {
                        cleanedCount++;
                        removeTracked(entry);
                    }
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable cleanupFailure) {
                    cleanupFailures++;
                }
            }

            while (inspected < maxEntriesToInspect) {
                TrackedCleanup entry = (TrackedCleanup) referenceQueue.poll();
                if (entry == null) break;
                if (!tracked.contains(entry)) continue;
                inspected++;
                try {
                    if (entry.tryClean()) {
                        cleanedCount++;
                        removeTracked(entry);
                    }
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable cleanupFailure) {
                    retryableFailures.add(entry);
                    cleanupFailures++;
                }
            }

            // Retired actor domains are deterministically cleanup-eligible even
            // if a stale host reference keeps an owner strongly reachable.
            for (Object retired : Set.copyOf(retiredDomains)) {
                if (inspected >= maxEntriesToInspect) break;
                Set<TrackedCleanup> entries = trackedByDomain.getOrDefault(retired, Set.of());
                for (TrackedCleanup entry : entries) {
                    if (inspected >= maxEntriesToInspect) break;
                    inspected++;
                    try {
                        if (entry.tryClean()) {
                            cleanedCount++;
                            removeTracked(entry);
                        }
                    } catch (VirtualMachineError | ThreadDeath fatal) {
                        throw fatal;
                    } catch (Throwable cleanupFailure) {
                        retryableFailures.add(entry);
                        cleanupFailures++;
                    }
                }
            }
        } else {
            Set<TrackedCleanup> candidates =
                    trackedByDomain.getOrDefault(requestedDomain, Set.of());
            for (TrackedCleanup entry : candidates) {
                if (inspected >= maxEntriesToInspect) break;
                inspected++;
                if (!entry.eligible(retiredDomains)) continue;
                try {
                    if (entry.tryClean()) {
                        cleanedCount++;
                        removeTracked(entry);
                    }
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable cleanupFailure) {
                    retryableFailures.add(entry);
                    cleanupFailures++;
                }
            }
        }

        int after = requestedDomain == null
                ? tracked.size()
                : trackedByDomain.getOrDefault(requestedDomain, Set.of()).size();
        return new CollectionReport(
                requestedDomain == null ? "process" : "actor",
                collections.incrementAndGet(),
                before,
                inspected,
                cleanedCount,
                after,
                cleanupFailures,
                jvmGcRequested);
    }

    private void removeTracked(TrackedCleanup entry) {
        synchronized (lifecycleLock) {
            tracked.remove(entry);
            retryableFailures.remove(entry);
            Set<TrackedCleanup> domainEntries = trackedByDomain.get(entry.domain);
            if (domainEntries == null) return;
            domainEntries.remove(entry);
            if (domainEntries.isEmpty()) {
                trackedByDomain.remove(entry.domain, domainEntries);
                retiredDomains.remove(entry.domain);
            }
        }
    }

    private void safePeriodicSweep() {
        if (closed.get()) return;
        try {
            collectPeriodic();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Periodic housekeeping is best-effort. Cleanup hook failures are
            // retained for retry and must not kill the shared timer thread.
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("garbage collector is closed");
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return;
        }
        periodicSweep.cancel(false);
        for (TrackedCleanup entry : tracked) {
            try {
                if (entry.tryClean()) removeTracked(entry);
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Context shutdown is best-effort for host cleanup hooks.
            }
        }
        synchronized (lifecycleLock) {
            tracked.clear();
            retryableFailures.clear();
            trackedByDomain.clear();
            retiredDomains.clear();
            while (referenceQueue.poll() != null) { /* drain */ }
        }
    }
}
