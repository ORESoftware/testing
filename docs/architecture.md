# Architecture

## Runtime topology

`meta-agent-control-plane` is deliberately one binary. `Daemon::bind` validates configuration and binds HTTP, TCP, and UDP before any listener begins serving, avoiding partial startup. `Daemon::serve` runs the three listener futures under one cancellation tree.

```text
OpenAI / Anthropic / Google / local / custom runtimes
                  │
       ┌──────────┼──────────┬──────────┐
       │ HTTP     │ WS       │ TCP      │ UDP telemetry
       └──────────┴──────────┴──────────┘
                  │
        validate + authorize + normalize
                  │
          deterministic reducer
                  │
  bounded LRU projections + idempotency window
                  │
       snapshot / metrics / UI updates
                  │
             Leptos dashboard
```

Transport adapters own framing, authentication entry points, limits, and acknowledgements. They do not own business semantics. All accepted events enter `Store::ingest`, which validates the canonical envelope, deduplicates by event ID, applies one reducer, advances a server revision, retains a recent event record, and broadcasts a compact invalidation record.

## Modules

- `model` — protocol envelope, event payloads, validation, privacy boundary, and UDP allowlist.
- `store` — bounded LRU projections, independent idempotency window, semantic reducer, snapshots, counters, and update broadcast.
- `auth` — shared-token authorization with constant-time comparison and redacted `Debug` output.
- `http` — Axum routes, agent/UI WebSockets, security headers, read/ingest authorization, metrics, and runtime OpenAPI.
- `tcp` — newline-delimited JSON with frame limits, connection semaphore, per-frame acknowledgements, and cancellation.
- `udp` — bounded JSON datagrams and a low-authority telemetry allowlist.
- `ui` — Leptos SSR markup plus a small same-origin live-update client.
- `daemon` — listener binding and one-process lifecycle.
- `config` — Clap/environment configuration and fail-closed remote-binding rules.

## Semantic conflict resolution

The reducer does not select a winner merely because one message arrived last. It combines compatible facts while applying domain-specific authority and event-time rules.

### Inferred versus authoritative tasks

Progress, errors, and reflections can arrive before `task_created`. The reducer creates an inferred task so the observation is not discarded. A later authoritative definition replaces only task-definition fields and preserves execution state. After an authoritative definition exists, an older definition cannot overwrite it.

### Completion corrections and reopening

Repeated completion events with the same outcome are idempotent at the derived-counter level even when they have distinct event IDs. A corrected outcome removes the previous counter contribution before applying the new one. A newer start or progress event reopens the task, clears completion-only fields, and removes the prior completion counter contribution.

### Scope

Externally supplied task, goal, and lesson IDs are scoped by `agent_id` internally. Two agents may both use `task-1` without overwriting each other.

### Bounded idempotency

Recent event records are optimized for the UI and can be evicted aggressively. Event-ID deduplication uses an independent, usually larger LRU so UI timeline pressure does not immediately permit a replayed event to mutate state again. The window remains bounded; durable exactly-once behavior is explicitly out of scope until a persistent backend exists.

## Concurrency

One Tokio `RwLock` protects a coherent projection. Ingestion holds the write lock only for synchronous validation-independent mutation and never across an `.await`. Snapshots take a single read lock and therefore carry a coherent revision watermark. Broadcast happens after releasing the lock, preventing slow subscribers from blocking state mutation.

The single-lock design prioritizes correctness for the MVP. A later implementation can shard projections or introduce an actor/event log behind the same `Store` API after profiling demonstrates contention.

## Evolution path

The first durable backend should preserve the event envelope and reducer semantics:

1. append accepted events transactionally with unique event IDs;
2. persist server revision and projection checkpoints;
3. rebuild projections by deterministic replay;
4. expose retention/tombstone semantics explicitly;
5. add tenant-scoped credentials and authorization before multi-user remote hosting.
