package dev.oreslang.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manual collection control exposed by process.gc().
 *
 * Oreslang's semantic lifetime model is ownership/drop first, collector second.
 * The current Java reference runtime still uses the JVM heap as its backing
 * store. A guest collection request is logical/telemetry-first; a JVM-wide
 * System.gc() hint is disabled by default and only runs when the embedding host
 * explicitly opts in with -Doreslang.gc.host-hint=true. This avoids making a
 * Java global-GC pause part of Oreslang language semantics.
 */
public final class GcController {
    private static final long MIN_HOST_GC_INTERVAL_NANOS = 250_000_000L;
    private static final AtomicLong GLOBAL_LAST_HOST_GC_NANOS = new AtomicLong(Long.MIN_VALUE);

    private final ActorRuntime actors;
    private final AtomicLong processRequests = new AtomicLong();
    private final AtomicLong actorRequests = new AtomicLong();
    private final AtomicLong executedHostHints = new AtomicLong();
    private final AtomicLong coalescedRequests = new AtomicLong();

    public GcController(ActorRuntime actors) {
        this.actors = java.util.Objects.requireNonNull(actors);
    }

    /** Context/backing-runtime collection request used by process.gc(). */
    public Map<String, Object> collect() {
        return collectProcess();
    }

    public Map<String, Object> collectProcess() {
        long request = processRequests.incrementAndGet();

        long before = usedHeapBytes();
        boolean hostHintEnabled = Boolean.parseBoolean(System.getProperty("oreslang.gc.host-hint", "false"));
        boolean executed = hostHintEnabled && reserveHostGcSlot(System.nanoTime());

        long started = System.nanoTime();
        if (executed) {
            System.gc();
            executedHostHints.incrementAndGet();
        } else {
            coalescedRequests.incrementAndGet();
        }
        long elapsed = System.nanoTime() - started;
        long after = usedHeapBytes();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request", request);
        result.put("scope", "context");
        result.put("semantic_policy", "ownership_drop_then_gc");
        result.put("backing_collector", "jvm");
        result.put("host_gc_hint_enabled", hostHintEnabled);
        result.put("host_gc_hint_executed", executed);
        result.put("actor_local_physical_collection", false);
        result.put("used_bytes_before", before);
        result.put("used_bytes_after", after);
        result.put("elapsed_nanos", elapsed);
        result.put("executed_host_hints", executedHostHints.get());
        result.put("coalesced_requests", coalescedRequests.get());
        return Map.copyOf(result);
    }

    /**
     * Actor-local collection request used by actor.gc(). The logical JVM actor
     * heap has no independent physical collector, so this must never disguise a
     * global System.gc() as actor-local work.
     */
    public Map<String, Object> collectActor() {
        ActorRuntime.ActorId actorId = actors.currentActorId()
                .orElseThrow(() -> new IllegalStateException("actor.gc() is only available inside an actor"));
        long request = actorRequests.incrementAndGet();
        actors.noteManualGcRequest();

        ActorRuntime.ActorRef<?> current = actors.currentActorRef()
                .orElseThrow(() -> new IllegalStateException("current actor is no longer registered"));
        ActorRuntime.ActorSnapshot snapshot = actors.snapshot(current);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request", request);
        result.put("scope", "actor:" + actorId);
        result.put("semantic_policy", "ownership_drop_then_gc");
        result.put("backing_collector", "logical_jvm");
        result.put("host_gc_hint_enabled", false);
        result.put("host_gc_hint_executed", false);
        result.put("actor_local_physical_collection", false);
        result.put("estimated_actor_heap_bytes", snapshot.asMap().get("estimated_actor_heap_bytes"));
        result.put("owned_state_bytes", snapshot.ownedStateBytes());
        result.put("mailbox_bytes", snapshot.mailboxBytes());
        result.put("active_message_bytes", snapshot.activeMessageBytes());
        return Map.copyOf(result);
    }

    public long requests() {
        return processRequests.get() + actorRequests.get();
    }

    public long processRequests() { return processRequests.get(); }
    public long actorRequests() { return actorRequests.get(); }

    private boolean reserveHostGcSlot(long now) {
        while (true) {
            long previous = GLOBAL_LAST_HOST_GC_NANOS.get();
            if (previous != Long.MIN_VALUE && now - previous < MIN_HOST_GC_INTERVAL_NANOS) return false;
            if (GLOBAL_LAST_HOST_GC_NANOS.compareAndSet(previous, now)) return true;
        }
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
    }
}
