# Garbage collection and lifetimes

Oreslang uses ownership and borrowing as the primary memory-safety model. GC is a fallback runtime service for reflection, FFI, host handles, interop wrappers, compiler/runtime metadata, and other resources outside the ordinary guest ownership graph.

## Ownership first

Ordinary values remain governed by move/borrow rules. Borrows are lexically inferred by default; explicit lifetime syntax is only needed if future inter-procedural cases cannot be inferred safely. The runtime collector must never make an otherwise-invalid ownership program valid.

## Runtime cleanup registry

Host/interop resources may register an idempotent cleanup hook with `RuntimeGarbageCollector.track(owner, cleanup)`. The owner is weakly referenced, so registration does not extend guest lifetime. Cleanup closures must not strongly capture the owner.

The registry is bounded per context to prevent unbounded metadata growth. Failed cleanup hooks remain registered and may be retried by later sweeps, so hooks must be idempotent.

`track` also returns a deterministic `CleanupHandle`; interop wrappers should close that handle on an explicit release path and rely on GC only as a leak fallback.

## Periodic sweeps

A process-shared daemon timer schedules context sweeps, so Oreslang does not park one sleeping sweeper thread per language context. Weak owners are registered with a `ReferenceQueue`; normal periodic/process sweeps drain queued dead owners instead of rescanning the entire cleanup registry.

Periodic sweeps never call `System.gc()`; normal Java heap tracing remains under the JVM collector. Cleanup failures are retained in a retry set, preserving the idempotent retry contract even after a weak reference has already been dequeued.

## Explicit collection

`process.gc()` requires `GC_CONTROL`. It performs a best-effort process/context sweep and may request JVM collection. JVM requests are throttled per context so guest code cannot turn `process.gc()` into a high-frequency global pressure point. Strict FaaS does not grant `GC_CONTROL`.

`actor.gc()` is actor-domain local and never requests JVM-wide collection. Calling it outside an actor mailbox turn is an error. Actor-domain identity comes from the actor runtime's stable semantic execution domain, not carrier-thread identity. Actor cleanup entries are indexed by that domain, and one `actor.gc()` call inspects at most 256 entries so a guest cannot hide an unbounded O(context-registry) scan behind one actor operation.

Actor termination retires that semantic domain deterministically. Runtime/interop cleanup hooks registered by the actor become eligible immediately at actor exit even if stale host references remain, while failures stay retryable from later process/periodic sweeps. This makes actor lifetime—not JVM reachability—the upper bound for actor-local host resources.

## Shutdown

Context shutdown closes the actor runtime first, stops the periodic sweeper, then drains remaining runtime cleanup hooks. Shutdown cleanup is best-effort; durable external resources should still use explicit close/release APIs.
