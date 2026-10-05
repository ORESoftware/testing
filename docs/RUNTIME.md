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
11. Private, shared, and untrusted actors use separate dispatcher thread pools.
12. Private and untrusted actor transport rejects synchronized shared-memory cells.
13. Shared actor state is still actor-owned; ordinary actor field mutation is serialized by the mailbox, not by implicit locks.
14. Actor `self` and move-only actor-owned state cannot escape a mailbox turn as ordinary mutable aliases.
15. Synchronized shared memory requires `SHARED_MEMORY`; strict FaaS does not grant it by default.
16. Private slices and synchronized shared cells compete for one parent actor-memory ceiling.
17. Actor message graphs are cycle-checked and bounded by depth, node count, and logical byte quotas before transport.
18. SharedMutex runtime ownership is reserved before mailbox visibility and committed only after successful admission; failed first publication rolls back.
19. An untrusted actor has a hard lifetime ceiling of 300 seconds, a per-message execution-fuel budget, a bounded mailbox-return budget, and no ambient filesystem/network/FFI/process/thread authority.
20. Large HTTP request/response bodies use owner-bound per-exchange stream capabilities rather than actor mailboxes or raw file-descriptor authority.
21. Actor-owned native memory regions are zeroed and their FFM arenas are closed during actor teardown; logical reservations are released in the same teardown path.

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


## Truffle thread boundary

`ActorRuntime` owns host dispatcher threads; guest code still receives no ambient thread-creation authority. A dispatcher carrier is marked by the runtime, explicitly enters/leaves the associated `TruffleContext` for each actor batch, and only marked actor carriers are accepted for concurrent context access.

Non-adversarial contexts may therefore execute independent actor turns concurrently. Strict/adversarial contexts currently serialize guest actor turns with a fair context-level lock even though private/shared dispatcher pools remain separate. This preserves the strict one-guest-thread sandbox contract until isolated/private actor execution is backed by per-actor inner/polyglot/native contexts.

The guest `THREAD_CREATE` capability is separate from host/runtime dispatcher scheduling. Denying guest-created threads is never bypassed merely because the runtime owns carrier pools.

## Actor scheduler, fairness, and starvation contract

Oreslang has exactly three **actor carrier domains**:

1. **shared/host domain** — shared actors **and the root/main process**. Root init/main guest execution is dispatched onto the shared carrier pool; metadata linking remains on the host entry thread;
2. **trusted isolated domain** — private/isolated actors;
3. **untrusted domain** — untrusted actors only.

The pools are bulkheads. Untrusted work can never spill into a trusted pool, and the runtime never uses a caller-runs rejection policy because that would execute an actor on an arbitrary sender thread and break the trust boundary.

Within a pool, actor scheduling is FIFO at mailbox-batch boundaries. One actor owns at most one scheduled ready token and may never execute two mailbox turns concurrently. A hot actor processes at most the configured message throughput **and** at most the configured wall-clock batch quantum (2 ms by default) before it is requeued at the tail. Untrusted actors have a stricter one-message batch regardless of the trusted throughput setting. These bounds prevent a permanently non-empty mailbox from monopolizing a carrier.

Production defaults **partition** the base CPU budget across the three domains rather than giving each pool `availableProcessors()` workers. The shared/root domain keeps at least two base carriers so a root task synchronously awaiting a shared actor cannot consume its only carrier. Small machines necessarily oversubscribe because all three security domains still require an independent execution lane. Compensating carriers are separate from the base budget and are strictly bounded.

Cooperative scheduling follows the BEAM idea of mandatory accounting points: every actor observes scheduler control at statement/loop boundaries, while untrusted expressions additionally consume explicit fuel. A trusted or untrusted mailbox message also has a hard turn deadline (250 ms by production default). If a turn exceeds it, the actor is fail-stopped and its active carrier is interrupted. Because arbitrary host/JVM code may ignore interruption, the affected domain may temporarily activate one of a **bounded number of compensating carriers** so peer actors continue to run. Compensation is retired when the stuck stack finally returns. This is containment, not unsafe asynchronous stack destruction.

The remaining gap to true BEAM-style reduction preemption is resumability. Today a cooperative checkpoint can terminate a turn but cannot suspend an arbitrary trusted stack, save its program counter/environment, requeue it, and later resume from that exact point. A future fiber/continuation or compiler state-machine lowering should turn reduction-budget exhaustion into suspension rather than failure. Until then, bounded turn deadlines plus compensation protect peer progress without pretending Java interruption is a safe preemption primitive.

Untrusted code keeps the stronger sandbox path: compiler/evaluator checkpoints charge execution fuel, a monotonic lifetime deadline is checked independently, and the Graal untrusted sandbox supplies hard CPU/heap/thread/output limits. Watchdog interruption is only a wake-up/control signal; deployment security must still use a killable isolate boundary for truly hostile native/non-cooperative execution.

The untrusted carrier pool is a CPU bulkhead, **not** the sandbox boundary itself. Production source-level `untrusted actor` instances must each execute through an independently killable Graal isolate/context (or equivalent process sandbox), rather than serializing hostile guests through one shared adversarial Truffle context. A shared context lock can preserve Truffle safety, but it cannot provide hard peer-progress guarantees if one guest owns that context and never returns. Context-entry waits are therefore interruptible and covered by the actor turn watchdog, while physical per-actor isolate wiring remains the required production containment boundary.

Root/main execution is also scheduler-controlled. Root work runs only in the shared domain, a fair semaphore reserves at least one base shared carrier for shared actors, and each root task inherits its launch policy's `maxWallTime`. Cooperative root code observes expiry at compiler/runtime safepoints; an uncooperative root is interrupted and may trigger the same bounded shared-pool compensation used for stuck actor turns. A root slot is released only after the underlying stack actually unwinds.

Carrier code must not block on another actor, a contended shared mutex, synchronous child execution, or arbitrary blocking host/FFI work. Such operations must suspend/resume the actor or be mediated by an explicitly bounded host service. The runtime already rejects synchronous actor invocation from an actor turn and blocking shared-mutex acquisition in actor execution.

Memory fairness is admission-controlled separately from CPU fairness: mailboxes are bounded by message count and logical bytes; private/untrusted actors have actor-owned memory slices; the runtime has an aggregate memory ceiling; and untrusted isolate heap is independently bounded. By default actor-accounted memory is capped at 75% of the JVM max heap, reserving 25% for Truffle/code metadata, scheduler/control queues, GC bookkeeping, and other runtime progress. An explicit `ores.actor.process-memory-bytes` override must remain within the JVM max heap. Untrusted actor **cardinality** is also capped independently (one eighth of the configured actor ceiling by default) so hostile sandbox creation cannot consume every actor identity and prevent trusted actors from spawning. Arbitrary trusted JVM transient allocation is not yet a hard per-actor heap boundary, so production trusted-isolate execution must ultimately map private actors to physically bounded isolate heaps or route every guest allocation through metered actor-owned allocation.


## Mutex and shared-memory model

Oreslang has two deliberately different mutex domains:

- `Mutex<T>` is actor/private-domain state. It owns the protected value, uses no JVM lock, is non-reentrant, and is confined to the creating semantic actor/execution domain. Shared actors may migrate between JVM workers without changing that domain.
- `SharedMutex<T>` is an explicit same-OS-process shared-memory capability within one `ActorRuntime`. It is non-reentrant and uses acquire/release synchronization. Only shared actors may receive/use it; private actors reject it even when the parent runtime is otherwise trusted. Sender and receiver must have `SHARED_MEMORY`. It binds transactionally to the first runtime that successfully publishes it, and later cross-runtime transport is rejected. Publication validation is nonblocking and runs while the mutex's physical permit is held, so transport never races a legitimate protected mutation; publishing a currently locked/contended mutex fails fast and may be retried later.
- The payload and declared type argument of `SharedMutex<T>` must be **SharedSafe**: concrete owned data whose reachable field graph contains no borrows, actor-local `Mutex`, `MutexGuard`, pending `Future`, closure/function values, or unresolved dynamic/generic state. This applies to signatures/fields/aliases as well as `SharedMutex.new(...)`. Until Oreslang has an explicit SharedSafe generic bound, unconstrained `SharedMutex<T>` is rejected conservatively. The type checker recursively validates class fields (including inherited generic substitutions), and the interpreter repeats runtime admission checks as defense in depth. Nested `SharedMutex` values are also rejected for now; recursive publication and lock-order semantics must be explicit before lock-containing-lock state is admitted.
- `MutexGuard<T>` is a lexical linear capability. The runtime releases it on normal scope exit and poisons a shared mutex on abnormal scope exit. Guest code may call `guard.release()` for early release; there is intentionally no `mutex.unlock()`. A released guard can no longer expose its protected value.
- Guard access is deliberately non-escaping. Copy-like fields may be read, mutable fields may be replaced, and methods may be invoked directly when they return `void` or a copy-like value. Move-only nested fields and bound method values cannot be extracted through a guard. For compound mutation, use `with_lock(|state| -> { ... })`.
- `with_lock` and `recover` require an inline one-argument, `void` lambda. The callback parameter is treated as a lexical exclusive `&mut T`: it may mutate protected state but cannot move or return that state, escape it through a closure, or suspend with `await`.
- `await` while a guard is live and closure capture of a guard are compile-time ownership errors. Guard-bearing results must be bound once with `val`; they cannot be discarded, reassigned, stored in aggregates, passed through arbitrary calls, or hidden inside another mutex.
- Blocking `SharedMutex.lock()`/timed acquisition is rejected while executing an actor. `lock_async()` returns a runtime-owned, caller-cancellable `GuardFuture` and is the nonblocking acquisition primitive. Contended async acquisition is queued inside the mutex and receives the permit by direct guard handoff; it does **not** allocate one helper thread per waiter. Acquisitions reserve the semantic actor/execution domain before waiting, so recursive async acquisition fails instead of self-deadlocking even if an actor migrates JVM workers. Cancellation removes queued waiters and releases their domain reservation; poisoning drains queued async waiters with `PoisonedMutexException`. Admission is bounded by the current actor mailbox policy, an 8,192-waiter ceiling per mutex, and a 32,768-waiter JVM-process ceiling. When blocking host waiters and async waiters coexist, release alternates handoff preference so neither class monopolizes the mutex. The runtime also exposes `lockAsyncFor(Duration)`, using the same queue plus one shared daemon timeout scheduler; expiry completes with `LockTimeoutException` and removes the waiter immediately. This remains a backend/runtime API until source-level duration/timeout representation is finalized. Language `await` lowering must suspend/resume the actor turn rather than synchronously join an incomplete future; until continuation lowering is complete, actor-backed singleton/mailbox serialization is preferred over contended shared-memory locking from shared actors.
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

Actor transport is independently hardened from mutex synchronization. Ordinary messages are recursively frozen with cycle detection and hard depth/node/byte budgets (256 levels, 100,000 nodes, 16 MiB estimated frozen size). Read-only shared wrappers are runtime-constructed and revalidated on every boundary. `ActorRef` capabilities may cross only inside their owning `ActorRuntime`; a wrapper cannot be used to smuggle a foreign actor reference into another runtime. When an `ActorRef` is explicitly granted to an untrusted actor it is a bounded messaging capability only: untrusted code may not use it to stop another actor or synchronously wait for that actor's termination. Prefer `ActorRef.recipient()` / `Recipient<M>` when only send authority is required; `Recipient<M>` exposes no lifecycle, wait, or failure-inspection surface and is validated as a runtime-owned channel capability at mailbox boundaries. Arbitrary host-controlled `Sendable` callbacks are not part of the transport boundary. Runtime-owned capabilities have explicit cases, while ordinary message graphs are recursively frozen/copied and validated.

Compiler-generated/context-aware `BehaviorFactory` values are capture-free for both private and shared actors. This prevents a shared actor from bypassing mailbox/capability semantics by closing over an arbitrary mutable JVM object. `spawnPrivateTrusted(...)`, `spawnSharedTrusted(...)`, and trusted `Supplier` construction are host/supervisor escape hatches only; adversarial policies reject them.

## Actor dispatchers

The host actor runtime follows the same scheduling shape as Akka's event-based dispatcher: many actors share an executor, each actor has its own mailbox, and a scheduled actor drains only a bounded number of messages before yielding back to the executor. The configured throughput bound prevents one hot mailbox from monopolizing a worker.

Oreslang deliberately uses three executors:

- **private dispatcher** — private actors, isolation-copy message transport;
- **shared dispatcher** — shared actors, immutable sharing plus explicit `SyncCell<T>` shared state;
- **untrusted dispatcher** — adversarial, memory-isolated actors with enforced fuel/deadline checks, separated so hostile work cannot consume the trusted/private carrier pool.

A per-actor atomic scheduling gate ensures only one drain task for that actor is active. The executor may run different turns on different threads; thread identity is never actor identity.

### Two-phase actor spawn

Source-level actor callables use a two-phase launch contract:

```text
RESERVED -> STARTING -> READY -> TERMINATED
                  \-> FAILED_TO_START
```

`spawn` returns at the RESERVED/admitted boundary with a lightweight
`ActorSpawn` ticket. The synchronous path reserves the stable `ActorId`,
creates the control futures, admits the initial message, and schedules the actor;
it does not wait for behavior initialization or user actor code. This path is
designed to be comfortably below 1 ms in the uncontended normal case for
small/ordinary argument graphs, but the language does **not** promise a
wall-clock deadline because OS scheduling, contention, sandbox admission, and
host load are external variables. Initial actor-call arguments are validated
and snapshotted/admitted synchronously so caller mutation cannot race actor
startup; therefore spawn latency is also intentionally proportional to an
unusually large argument graph. Bulk data should use bounded streaming,
owned-region transfer, or other explicit capabilities rather than giant spawn
arguments.

The ticket's `ready` future completes only after actor behavior initialization
succeeds and the mailbox/control endpoint is usable. `await spawn ...` is
defined as awaiting that readiness future, not actor completion. A separate
`done: Future<bool>` tracks callable completion for both actor functions and
actor routines. Any non-void actor callable, whether `fnc` or `routine`,
additionally exposes a `result` future. Startup failure settles readiness,
done, and result exceptionally; no
failed startup may leave a pending control future behind.

Ordinary cancellation is observed at compiler-injected scheduler safepoints. A separate per-message watchdog enforces the configured hard turn deadline for all actor domains and can activate bounded compensation when a carrier remains stuck. Untrusted actors additionally have the independent lifetime watchdog and per-turn fuel budget. Every runtime-owned carrier clears watchdog interrupts before reuse. Security does not depend on the guest voluntarily yielding: untrusted statement/expression dispatch, loop backedges, and callable/recursion execution consume runtime fuel and recheck the deadline.


## Untrusted actor sandbox

`UNTRUSTED` is a third actor kind, not merely a flag on a shared actor. It inherits the private actor's isolation-copy transport and actor-owned memory slice, but it is always adversarial and receives a strictly reduced capability set. It cannot create child actors or gain `SHARED_MEMORY`, generic `NETWORK`, filesystem access, environment access, FFI/native access, reflection, child-process creation, guest thread creation, hot-code loading, or polyglot access.

The default sandbox limits are:

- maximum lifetime: **300 seconds** (an absolute upper bound; a host may choose less);
- execution fuel: **100,000 units per mailbox turn**;
- outbound actor-mailbox payload: **1 MiB**;
- HTTP request body read budget: **16 MiB**;
- HTTP request metadata budget: **64 KiB**, including an **8 KiB** path ceiling and at most **128** header lookups;
- HTTP response body write budget: **16 MiB**;
- HTTP response metadata budget: **128 headers / 64 KiB**;
- concurrent outbound HTTP/HTTPS calls: **5 per actor by default** (configurable, runtime-enforced);
- isolated actor heap: **64 MiB**;
- mailbox: **128 messages**.

Outbound networking is also object-capability based. If the supervisor supplies an
`OutboundHttpTransport`, the untrusted actor receives an owner-only
`OutboundHttpCapability`. It exposes asynchronous HTTP/HTTPS request futures,
not sockets. The capability uses a per-actor semaphore whose default is five.
Admission is fail-fast: once five host operations are actually in flight, a
sixth future completes with `HttpConcurrencyLimitExceededException` without
invoking the transport. Cancellation does not release that semaphore permit
until the underlying transport itself completes, preventing cancel/retry churn
from exceeding the real network concurrency budget. Actor teardown cancels all
tracked outbound operations.

The capability rejects `CONNECT`, protocol-upgrade/WebSocket headers,
non-HTTP(S) schemes, and user-info-bearing URLs. Request and response bodies use
the same bounded HTTP byte budgets as the sandbox. The host may maintain
HTTP/1.1 keep-alive or HTTP/2/3 connection pooling internally, but no raw TCP
handle, pool handle, stateful session, or cookie jar is exposed to guest code.

`OresFuture<T>` is backed by `CompletionStage` at the host boundary, and
`OresFutures.all` / `race` compose operations without creating more actors
or OS threads. Actor-side `await` must be lowered to a suspended continuation;
blocking a carrier on `get()` or `join()` is forbidden.

HTTP is granted as an **object capability to one accepted exchange**, not as ambient network access. A host may bind `HttpRequestTransport` / `HttpResponseTransport` to its HTTP parser, event loop, `SocketChannel`, or equivalent. The actor receives owner-bound `HttpRequestCapability` / `HttpResponseCapability` handles through its turn context. Request-body chunks are read from the host request stream and response-body chunks are written to the host response stream directly; they are not serialized through the actor mailbox. `PrivateMemoryBlock.readFrom(...)` and `writeTo(...)` can bind those streams directly to the actor's FFM-backed native region, avoiding an intermediate JVM heap byte array as well. The handles are not Sendable, cannot be transferred to another actor, and expose no general socket/file-descriptor operations. This also keeps the model valid for HTTP/2 or HTTP/3, where a raw connection fd would be the wrong abstraction.

An untrusted actor's language-managed native blocks are allocated from closeable cross-thread FFM arenas. Teardown zeroes each block, closes its arena deterministically, drains mailbox reservations, drops behavior roots, cancels/aborts attached HTTP streams, and releases the actor slice's accounting. JVM metadata/wrapper objects remain ordinary managed objects; Oreslang therefore promises deterministic release of actor-owned native regions and runtime quotas, not the impossible claim that every JVM bookkeeping object disappears synchronously.

The watchdog provides a wall-clock kill boundary, while mandatory compiler/runtime checkpoints provide safe preemption of guest computation. Arbitrary native/FFI callbacks are forbidden precisely because a host call that ignores interruption and never returns could defeat language-level checkpoints; stronger deployment backends may additionally place an untrusted actor in a killable Graal/native/OS isolation unit without changing these source semantics.

## Shared actor memory

Shared actors keep ordinary mutable fields actor-owned and mailbox-serialized. Cross-actor mutable memory is exceptional and represented by `SyncCell<T>`.

`SyncCell<T>` is runtime-owned and closeable. Its frozen state consumes shared actor-memory quota; growth reserves quota before publishing a replacement value, shrink/close returns quota, and runtime teardown closes remaining cells. The combined private-slice plus shared-cell total cannot exceed the parent `IsolatePolicy.maxHeapBytes()`.

A private actor turn cannot create, snapshot, read, update, or close synchronized shared state even if trusted host code accidentally captured a cell handle. Source admission and runtime creation both require `SHARED_MEMORY`.

Actor failures are fail-stop in this layer. The actor ref retains the failure cause for diagnostics, queued reservations are drained, and later sends receive an `ActorTerminatedException` rather than silently targeting a dead mailbox.

## Private actor memory confinement

A private or untrusted actor is assigned an `ActorMemorySlice` when it is created. The slice is keyed by actor identity rather than by dispatcher thread because actor turns may migrate between worker threads.

Private mailbox admission is:

1. reject explicitly shared mutable handles such as `SyncCell<T>`;
2. isolation-copy/freeze the message graph;
3. conservatively estimate its logical Oreslang heap size;
4. reserve those bytes against the destination actor slice and the parent runtime budget;
5. enqueue only after both reservations succeed;
6. release transient mailbox bytes after the mailbox turn completes.

Persistent generated actor state reserves from the same slice. Actor teardown closes the entire slice, so leaked host-side reservation handles cannot keep a dead actor's memory budget alive.

The logical size metric intentionally does not claim to equal JVM object layout. It exists to enforce Oreslang memory-domain policy while actors remain multiplexed on one JVM. A hardened backend may replace the accounting implementation with arena/region allocation or a Graal/native isolate without changing source semantics.

A memory-isolated actor's memory owner is its **ActorId**, never its carrier thread. Successive mailbox turns may execute on different private-dispatcher workers. Consequently a future FFM/off-heap backend must not make `Arena.ofConfined()` carrier-thread identity part of Oreslang semantics. It should use a cross-thread-capable region whose access is guarded by the actor owner token, or map the private actor to a true Graal/native isolate when physical heap isolation is required.
