# Runtime isolation, hot reload, and compilation profiles

## Invariants

1. Guest source never grants itself authority.
2. The supervisor/launcher supplies an `IsolatePolicy`.
3. Compiler admission rejects language APIs not in that policy.
4. Runtime API facades repeat the authorization check.
5. Graal host access, native access, environment access, guest-created threads, host IO, and unrestricted polyglot access are disabled by default in restricted contexts.
6. Actors cannot exceed their configured mailbox capacity.
7. Hot reload never requires loading executable native libraries.
8. Every hot-loaded generation is a fresh guest context and may be mapped to a stronger Graal/native isolate by the production host.
9. `self` cannot be rebound.
10. An actor has one mailbox and never executes two mailbox turns concurrently.
11. One actor is one worker: every actor owns exactly one long-lived virtual-thread worker and never executes on another actor's worker.
12. Private actor transport rejects synchronized shared-memory cells.
13. Shared actor state is still actor-owned; ordinary actor field mutation is serialized by the mailbox, not by implicit locks.
14. Actor `self` and move-only actor-owned state cannot escape a mailbox turn as ordinary mutable aliases.
15. Synchronized shared memory requires `SHARED_MEMORY`; strict FaaS does not grant it by default.
16. Private slices and synchronized shared cells compete for one parent actor-memory ceiling.
17. Actor message graphs are cycle-checked and bounded by depth, node count, and logical byte quotas before transport.
18. SharedMutex runtime ownership is reserved before mailbox visibility and committed only after successful admission; failed first publication rolls back.
19. Every private actor/worker starts with 1 MiB of VM-committed capacity by default; committed capacity is distinct from live used bytes.
20. Private actor memory growth may be actor-requested or VM-triggered, always passes through the memory governor, and may be denied.
21. Private committed capacity and synchronized shared memory consume the same parent runtime memory budget.

## Deployment matrix

| Profile | Host | Guest execution | Hot reload |
| --- | --- | --- | --- |
| JIT | JVM/GraalVM | interpreter -> Truffle JIT | fresh source generation |
| AOT | Native Image | precompiled interpreter | fresh source generation, no executable-code load |
| HYBRID | Native Image | interpreter -> guest JIT where supported | fresh source generation |

iOS is treated as AOT-only by the execution-profile validator. Android may use AOT or another profile where platform policy allows it.

## Why hot reload is source/IR based

Native Image is fundamentally closed-world for Java classes. Oreslang therefore does not make hot reload depend on dynamically linking new Java/native code. The runtime/interpreter is part of the shipped artifact; newly downloaded Oreslang source (and later a stable serialized Ores IR) is treated as untrusted data, validated, then executed in a new generation.

That makes the mechanism consistent across Windows, macOS, Linux, Android, and AOT-only targets. Platform-specific native dynamic linking can remain an optional trusted-host optimization, never a semantic dependency.

## Capability ownership

Capabilities belong to a launch policy, not to source code. Source may eventually declare required capabilities for diagnostics, but declarations will never grant them.

The strict production direction is:
- parent supervisor owns maximum authority;
- child isolate/actor receives an equal-or-smaller capability set;
- no child may escalate its own policy;
- cross-actor values must pass sendability/freezing rules;
- hot-loaded code gets a new generation and new policy admission.

## Receiver implementation

Method code is stored once per class declaration. Direct calls dispatch to that definition with the receiver as an implicit immutable argument. Only first-class method extraction allocates a bound method pair. This provides Go-like receiver safety without allocating a closure for every instance or every direct method invocation.


## Truffle actor-worker boundary

`ActorRuntime` creates exactly one long-lived virtual-thread worker for each actor. That worker enters/leaves the associated `TruffleContext` around guest execution. Guest source still receives no ambient thread-creation authority.

A Java virtual thread may be mounted by the JVM on different OS carrier threads over time. Those OS carrier threads are strictly below Oreslang semantics: they are not actors, not workers, own no actor state, and confer no actor identity.

Non-adversarial contexts may execute different actor/workers concurrently. Strict/adversarial contexts may serialize guest execution with a fair context-level lock to preserve the strict one-guest-thread sandbox contract; serialization does not merge workers or move an actor onto another worker.

The guest `THREAD_CREATE` capability is separate from host-created actor workers. Denying guest-created threads does not prevent the runtime from creating the one worker that constitutes each actor.


## Mutex and shared-memory model

Oreslang has two deliberately different mutex domains:

- `Mutex<T>` is actor/private-domain state. It owns the protected value, uses no JVM lock, is non-reentrant, and is confined to the creating actor/worker. The synchronization domain is the actor's `ActorId`.
- `SharedMutex<T>` is an explicit same-OS-process shared-memory capability within one `ActorRuntime`. It is non-reentrant and uses acquire/release synchronization. Only shared actors may receive/use it; private actors reject it even when the parent runtime is otherwise trusted. Sender and receiver must have `SHARED_MEMORY`. It binds transactionally to the first runtime that successfully publishes it, and later cross-runtime transport is rejected.
- The payload and declared type argument of `SharedMutex<T>` must be **SharedSafe**: concrete owned data whose reachable field graph contains no borrows, actor-local `Mutex`, `MutexGuard`, pending `Future`, closure/function values, or unresolved dynamic/generic state. This applies to signatures/fields/aliases as well as `SharedMutex.new(...)`. Until Oreslang has an explicit SharedSafe generic bound, unconstrained `SharedMutex<T>` is rejected conservatively. The type checker recursively validates class fields (including inherited generic substitutions), and the interpreter repeats runtime admission checks as defense in depth.
- `MutexGuard<T>` is a lexical linear capability. The runtime releases it on normal scope exit and poisons a shared mutex on abnormal scope exit. Guest code may call `guard.release()` for early release; there is intentionally no `mutex.unlock()`. A released guard can no longer expose its protected value.
- Guard access is deliberately non-escaping. Copy-like fields may be read, mutable fields may be replaced, and methods may be invoked directly when they return `void` or a copy-like value. Move-only nested fields and bound method values cannot be extracted through a guard. For compound mutation, use `with_lock(|state| -> { ... })`.
- `with_lock` and `recover` require an inline one-argument, `void` lambda. The callback parameter is treated as a lexical exclusive `&mut T`: it may mutate protected state but cannot move or return that state, escape it through a closure, or suspend with `await`.
- `await` while a guard is live and closure capture of a guard are compile-time ownership errors. Guard-bearing results must be bound once with `val`; they cannot be discarded, reassigned, stored in aggregates, passed through arbitrary calls, or hidden inside another mutex.
- Blocking `SharedMutex.lock()`/timed acquisition is rejected while executing an actor. `lock_async()` returns a runtime-owned, caller-cancellable `GuardFuture` and is the nonblocking acquisition primitive. Contended async acquisition is queued inside the mutex and receives the permit by direct guard handoff; it does **not** allocate one helper thread per waiter. Acquisitions reserve the actor/worker domain before waiting, so recursive async acquisition fails instead of self-deadlocking. Cancellation removes queued waiters and releases their domain reservation; poisoning drains queued async waiters with `PoisonedMutexException`. Admission is bounded by the current actor mailbox policy, an 8,192-waiter ceiling per mutex, and a 32,768-waiter JVM-process ceiling. When blocking host waiters and async waiters coexist, release alternates handoff preference so neither class monopolizes the mutex. The runtime also exposes `lockAsyncFor(Duration)`, using the same queue plus one shared daemon timeout scheduler; expiry completes with `LockTimeoutException` and removes the waiter immediately. This remains a backend/runtime API until source-level duration/timeout representation is finalized. Language `await` lowering must suspend/resume the actor turn rather than synchronously join an incomplete future; until continuation lowering is complete, actor-backed singleton/mailbox serialization is preferred over contended shared-memory locking from shared actors.
- A poisoned `SharedMutex<T>` rejects ordinary acquisition until `recover(...)` repairs invariants and clears poison. `recover` is not an ordinary lock operation: it is rejected when the mutex is healthy. Inside actor execution recovery is nonblocking; if another recovery owns the permit, the actor must retry on a later mailbox turn rather than park.

Example:

```ores
val state = Mutex.new(new Counter());
val guard = state.lock();
guard.count = guard.count + 1;
guard.release();

val shared = SharedMutex.new(new Cache());
val shared_guard = await shared.lock_async();
shared_guard.increment_hits();
shared_guard.release();

shared.with_lock(|cache| -> {
  cache.put("key", "value");
  return;
});
```

A process-wide `singleton module` is **not** raw shared memory. It is owned by one hidden singleton actor and accessed through its typed mailbox/proxy, so its mutable state is serialized by actor execution and normally requires no mutex. Do not wrap singleton-module state in `SharedMutex<T>` merely because multiple actors can call it. Use `SharedMutex<T>` only when code deliberately opts into a writable same-process memory object that multiple actor/execution domains may dereference directly.

When the private-arena/`isoactor` runtime is stacked with this work, isolated actors must run without `SHARED_MEMORY` authority. An `isoactor` may receive copied/frozen messages, but it must not receive a `SharedMutex<T>` or any other writable JVM-heap alias; otherwise the language would no longer be able to claim true actor memory isolation.

The current reference runtime uses a one-permit JVM semaphore for `SharedMutex<T>`. Java semaphore release/acquire provides the required memory-ordering edge and, unlike a thread-owned `ReentrantLock`, allows an asynchronously acquired guard to be resumed and released by the actor execution context.

Actor transport is independently hardened from mutex synchronization. Ordinary messages are recursively frozen with cycle detection and hard depth/node/byte budgets (256 levels, 100,000 nodes, 16 MiB estimated frozen size). Read-only shared wrappers are runtime-constructed and revalidated on every boundary. `ActorRef` capabilities may cross only inside their owning `ActorRuntime`; a wrapper cannot be used to smuggle a foreign actor reference into another runtime. Arbitrary host-controlled `Sendable` callbacks are not part of the transport boundary. Runtime-owned capabilities have explicit cases, while ordinary message graphs are recursively frozen/copied and validated.

Compiler-generated/context-aware `BehaviorFactory` values are capture-free for both private and shared actors. This prevents a shared actor from bypassing mailbox/capability semantics by closing over an arbitrary mutable JVM object. `spawnPrivateTrusted(...)`, `spawnSharedTrusted(...)`, and trusted `Supplier` construction are host/supervisor escape hatches only; adversarial policies reject them.

## Actor workers

Oreslang does not have a separate dispatcher-worker abstraction. **The actor is the worker and the worker is the actor.**

For each spawned actor the runtime creates one long-lived virtual thread:

- the actor/worker owns one mailbox;
- the same actor/worker processes every message for that actor;
- only one message handler executes at a time;
- private and shared are memory/capability modes; they do not change the one-actor/one-worker identity;
- stopping an actor interrupts that actor's own worker;
- destroying the actor ends that worker and reclaims actor-owned runtime resources.

The JVM is free to mount a virtual thread on different OS carrier threads. Carrier migration is an implementation detail of the JVM and must never appear in Oreslang ownership, mutex, memory, supervision, or identity semantics.


## Shared actor memory

Shared actors keep ordinary mutable fields actor-owned and mailbox-serialized. Cross-actor mutable memory is exceptional and represented by `SyncCell<T>`.

`SyncCell<T>` is runtime-owned and closeable. Its frozen state consumes shared actor-memory quota; growth reserves quota before publishing a replacement value, shrink/close returns quota, and runtime teardown closes remaining cells. The combined private-slice plus shared-cell total cannot exceed the parent `IsolatePolicy.maxHeapBytes()`.

A private actor turn cannot create, snapshot, read, update, or close synchronized shared state even if trusted host code accidentally captured a cell handle. Source admission and runtime creation both require `SHARED_MEMORY`.

Actor failures are fail-stop in this layer. The actor ref retains the failure cause for diagnostics, queued reservations are drained, and later sends receive an `ActorTerminatedException` rather than silently targeting a dead mailbox.

## Private actor memory confinement

A private actor/worker receives one `ActorMemorySlice` owned by its `ActorId`. Because the actor and worker are the same lifetime/execution entity, private memory never moves to another worker.

Private memory tracks three separate quantities:

- **used bytes** — live actor-state and mailbox reservations;
- **committed bytes** — capacity the VM has granted to that actor/worker;
- **hard limit** — the actor's `IsolatePolicy.maxHeapBytes()` ceiling.

The default initial commitment is **1 MiB**. Growth is elastic and geometric in at-least-1-MiB quanta: 1 MiB -> 2 MiB -> 4 MiB -> 8 MiB and so on, bounded by the actor hard limit and the parent runtime budget.

Growth is two-way:

1. **actor-requested** — the owning actor/worker may call `requestAdditionalMemory(bytes)` and receives an explicit approved/denied `MemoryGrowthResult`;
2. **VM-triggered** — an allocation crossing committed capacity requests required growth automatically, and crossing the configured high-water mark (80% by default) requests best-effort proactive growth.

Every growth request passes through the host `MemoryGovernor`. The governor can reject growth because of process pressure, tenant quotas, supervisor policy, deployment limits, or other runtime conditions. It can only make policy stricter: it cannot exceed the per-actor or parent runtime ceilings.

Private committed capacity—not merely live used bytes—competes with `Shared<T>`, `SyncCell<T>`, shared mailboxes, and other synchronized shared memory under the parent actor-memory ceiling. This prevents large populations of idle actors from promising more memory than the runtime can honor.

Private mailbox admission is:

1. reject explicitly shared mutable handles such as `SyncCell<T>`;
2. isolation-copy/freeze the message graph;
3. conservatively estimate its logical Oreslang heap size;
4. grow committed capacity through the VM governor when the message would exceed current headroom;
5. reserve the message bytes and enqueue only after memory admission succeeds;
6. release transient mailbox bytes after the mailbox turn completes.

Persistent generated actor state reserves from the same slice. Actor teardown occurs on the actor/worker and releases both live usage and all remaining committed capacity.

The current reference backend uses actor-ID guarded direct storage. A physical-memory backend may create an actor-owned confined arena on the actor/worker virtual thread (for example `Arena.ofConfined()` on a compatible FFM target) or use a separate Graal/native isolate. In either backend, the owner is the actor/worker; JVM OS carrier-thread identity is irrelevant.
