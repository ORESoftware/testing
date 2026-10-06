# Runtime isolation, hot reload, and compilation profiles

## Invariants

1. Guest source never grants itself authority.
2. The supervisor/launcher supplies an `IsolatePolicy`.
3. Compiler admission rejects language APIs not in that policy.
4. Runtime API facades repeat the authorization check.
5. Graal host access/class lookup, native access, environment access, guest-created threads, host IO, and unrestricted polyglot access are disabled by default in restricted contexts.
6. Actors cannot exceed their configured mailbox capacity.
7. Hot reload never requires loading executable native libraries.
8. Every hot-loaded generation is a fresh guest context and may be mapped to a stronger Graal/native isolate by the production host.
9. `self` cannot be rebound.
10. An actor has one mailbox and never executes two mailbox turns concurrently.
11. Private and shared actors use separate dispatcher thread pools.
12. Private actor transport rejects synchronized shared-memory cells.
13. Shared actor state is still actor-owned; ordinary actor field mutation is serialized by the mailbox, not by implicit locks.
14. Actor `self` and move-only actor-owned state cannot escape a mailbox turn as ordinary mutable aliases.
15. Synchronized shared memory requires `SHARED_MEMORY`; strict FaaS does not grant it by default.
16. Private slices and synchronized shared cells compete for one parent actor-memory ceiling.
17. Actor message graphs are cycle-checked and bounded by depth, node count, and logical byte quotas before transport.
18. SharedMutex runtime ownership is reserved before mailbox visibility and committed only after successful admission; failed first publication rolls back.

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

## Native-first language/runtime boundary

Java/Truffle is a reference/bootstrap backend, not the semantic definition of
Oreslang runtime features. Ores-owned facilities should lower through
backend-independent runtime intrinsics and, where practical, be implemented in
the native runtime. JNI may be used as a narrow bridge from the Java-hosted
compiler/runtime, but Java reflection, JVM class identity, and Java library
behavior must not define Oreslang semantics.

Type tests, casts, and pattern matching therefore use Oreslang type and
constructor metadata. Java host objects remain explicit capability-gated
interop values and do not acquire Oreslang nominal identity through JVM
`instanceof`.

The native ABI direction and migration rules are specified in
[NATIVE_RUNTIME_ABI.md](NATIVE_RUNTIME_ABI.md).

## Java host interop boundary

Java imports are a two-key boundary. Source may name an explicit `java:` class, but execution requires both:

1. the Oreslang `JAVA_INTEROP` capability; and
2. an exact fully-qualified host-class allowlist entry supplied by the launcher/embedder.

The runtime uses Graal host lookup rather than guest-side `Class.forName`, disables host class loading, exposes only public **declared** members of explicitly allowlisted classes, and disables access inheritance. This prevents admitting one class from automatically exposing inherited reflection such as `Object.getClass()`.

Private/memory-isolated actors have `JAVA_INTEROP` stripped from their effective policy. Java host objects are wrapped as host capabilities, are not ordinary Oreslang data, and are rejected from synchronized shared-state publication. Adversarial isolates cannot grant `JAVA_INTEROP` at all.

Class-level interop blocks VM-control/reflection infrastructure (for example `Runtime`, `System`, `Class`, class loaders, reflection/invoke, script/compiler APIs, and JDK internals). Broad JDK families additionally require their corresponding Ores capabilities: filesystem, network, thread creation, native access, or process info. Third-party classes remain the embedder's responsibility: allowlisting one explicitly grants access to its public declared host surface.

Example:

```text
oreslang-compiler --allow=JAVA_INTEROP --allow-host-class=java.util.ArrayList app.ores
```

## Capability ownership

Capabilities belong to a launch policy, not to source code. Source may eventually declare required capabilities for diagnostics, but declarations will never grant them.

The strict production direction is:
- parent supervisor owns maximum authority;
- child isolate/actor receives an equal-or-smaller capability set;
- no child may escalate its own policy;
- cross-actor values must pass sendability/freezing rules;
- hot-loaded code gets a new generation and new policy admission.

## Receiver implementation

Method code is stored once per class declaration. Object instances store state/layout data, not private copies of their methods.

A direct instance call dispatches through the statically known `(INSTANCE, name, arity)` selector and supplies the object as an implicit first argument. It does not create a bound callable.

First-class extraction such as `obj.method` is represented semantically as a compact bound-method pair containing receiver identity plus shared method identity/slot information. The current reference evaluator keeps the receiver and method-name family and selects the closed-world slot from callback arity; AOT may resolve that to a receiver pointer plus code/vtable slot. A non-escaping value may be stack/register allocated or optimized away, so the language does not require a heap allocation merely because method-value syntax was used.

The receiver is fixed when the method value is formed. Invocation never dynamically rebinds `self`.


## Proper tail-call runtime

OresVM implements proper tail calls itself instead of relying on GraalVM to infer tail-recursion optimization. A tail-position call is prepared as an internal invocation descriptor after its receiver/callee and arguments have been evaluated. The current activation unwinds, and an iterative trampoline executes the next raw activation. Self-recursion, same-evaluator mutual recursion, routines, instance methods, `static fnc`, same-unit module calls, lambdas, and evaluator-owned first-class Oreslang function values therefore share the same constant-call-stack mechanism. Untyped cross-code-unit imports are the explicit contract barrier described below.

The trampoline performs a scheduler safepoint every 64 tail transfers. This prevents a very long recursive chain from becoming an uncooperative scheduling loophole.

Tail transfer is deliberately blocked when caller-owned cleanup still exists: active `defer`, catch/finally semantics, or live mutex guards. Those calls use ordinary call/return behavior so cleanup ordering and lock lifetime remain correct. Arbitrary Java/host `Invokable` values are also not tail-transferred.

Tail-transferable first-class Oreslang callables carry the evaluator that owns their validated contract. A target owned by another linked code unit is treated as a contract barrier because imports are currently typed as `Unknown` within an individual unit. The caller waits for that imported invocation and performs its own declared return-shape validation. Internal tail calls in the target evaluator still trampoline normally. This avoids weakening runtime contracts while keeping retained heap state O(1); no return-validator chain is accumulated across recursive depth.

This mechanism is part of Oreslang semantics and runs identically inside the JVM/Graal JIT runtime, the Native Image AOT launcher, and the AOT-host/guest-JIT hybrid launcher.

## Truffle thread boundary

`ActorRuntime` owns host dispatcher threads; guest code still receives no ambient thread-creation authority. A dispatcher carrier is marked by the runtime, explicitly enters/leaves the associated `TruffleContext` for each actor batch, and only marked actor carriers are accepted for concurrent context access.

Non-adversarial contexts may therefore execute independent actor turns concurrently. Strict/adversarial contexts currently serialize guest actor turns with a fair context-level lock even though private/shared dispatcher pools remain separate. This preserves the strict one-guest-thread sandbox contract until isolated/private actor execution is backed by per-actor inner/polyglot/native contexts.

The guest `THREAD_CREATE` capability is separate from host/runtime dispatcher scheduling. Denying guest-created threads is never bypassed merely because the runtime owns carrier pools.


## Mutex and shared-memory model

Oreslang has two deliberately different mutex domains:

- `Mutex<T>` is actor/private-domain state. It owns the protected value, uses no JVM lock, is non-reentrant, and is confined to the creating semantic actor/execution domain. Shared actors may migrate between JVM workers without changing that domain.
- `SharedMutex<T>` is an explicit same-OS-process shared-memory capability within one `ActorRuntime`. It is non-reentrant and uses acquire/release synchronization. Only shared actors may receive/use it; private actors reject it even when the parent runtime is otherwise trusted. Sender and receiver must have `SHARED_MEMORY`. It binds transactionally to the first runtime that successfully publishes it, and later cross-runtime transport is rejected. Publication validation is nonblocking and runs while the mutex's physical permit is held, so transport never races a legitimate protected mutation; publishing a currently locked/contended mutex fails fast and may be retried later.
- The payload and declared type argument of `SharedMutex<T>` must be **SharedSafe**: concrete owned data whose reachable field graph contains no borrows, actor-local `Mutex`, `MutexGuard`, pending `Future`, closure/function values, or unresolved dynamic/generic state. This applies to signatures/fields/aliases as well as `SharedMutex.new(...)`. Until Oreslang has an explicit SharedSafe generic bound, unconstrained `SharedMutex<T>` is rejected conservatively. The type checker recursively validates class fields (including inherited generic substitutions), and the interpreter repeats runtime admission checks as defense in depth. Nested `SharedMutex` values are also rejected for now; recursive publication and lock-order semantics must be explicit before lock-containing-lock state is admitted.
- `MutexGuard<T>` is a lexical linear capability. The runtime releases it on normal scope exit and poisons a shared mutex on abnormal scope exit. Guest code may call `guard.release()` for early release; there is intentionally no `mutex.unlock()`. A released guard can no longer expose its protected value.
- Guard access is deliberately non-escaping. Copy-like fields may be read, mutable fields may be replaced, and methods may be invoked directly when they return `void` or a copy-like value. Move-only nested fields cannot be extracted through a guard, and instance methods are direct-call-only everywhere rather than becoming bound method values. For compound mutation, use `with_lock(|state| -> { ... })`.
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

Actor transport is independently hardened from mutex synchronization. Ordinary messages are recursively frozen with cycle detection and hard depth/node/byte budgets (256 levels, 100,000 nodes, 16 MiB estimated frozen size). Read-only shared wrappers are runtime-constructed and revalidated on every boundary. `ActorRef` capabilities may cross only inside their owning `ActorRuntime`; a wrapper cannot be used to smuggle a foreign actor reference into another runtime. Arbitrary host-controlled `Sendable` callbacks are not part of the transport boundary. Runtime-owned capabilities have explicit cases, while ordinary message graphs are recursively frozen/copied and validated.

Compiler-generated/context-aware `BehaviorFactory` values are capture-free for both private and shared actors. This prevents a shared actor from bypassing mailbox/capability semantics by closing over an arbitrary mutable JVM object. `spawnPrivateTrusted(...)`, `spawnSharedTrusted(...)`, and trusted `Supplier` construction are host/supervisor escape hatches only; adversarial policies reject them.

## Async task runtime

Ordinary `async` callables are separate from actor dispatchers. The reference interpreter owns one context-local async scheduler and returns runtime-owned `OresFuture<T>` / language `Future<T>` values immediately. Java `CompletionStage` is host interop only and is normalized one-way into `OresFuture`; it does not define guest continuation scheduling. The current compatibility execution bridge still uses Java virtual threads so blocking host/runtime operations do not consume the bounded private/shared actor worker pools. This is transitional: Oreslang source semantics are future/continuation based, not virtual-thread based.

The design intentionally mirrors the strongest C# async/await practices:

- do not make `async` synonymous with "new OS thread";
- avoid sync-over-async on bounded actor workers;
- propagate cancellation to the underlying task;
- preserve the original exception at `await`;
- keep the execution scheduler out of the source-level future contract;
- separate I/O/task concurrency from explicitly CPU-bound scheduling.

Because the current interpreter has not yet lowered `await` into a resumable state machine, an ordinary async virtual carrier may block while awaiting another future. Actor carriers are different: an incomplete `await` from an actor turn is rejected rather than parking the dispatcher. Adversarial contexts also fail closed for ordinary async execution until continuation lowering can release the strict guest-turn serialization lock at suspension points.

Async callable arguments/results are detached at the evaluator boundary. This is stricter than C#'s shared managed heap and preserves Oreslang's ownership direction: mutable task state is owned by the task instead of becoming an implicit cross-thread alias. Generic async boundaries remain closed until a Send/task-safe generic contract exists.

## HungryActor: explicit dedicated CPU carrier

`HungryActor<M>` is the deliberate exception to the ordinary multiplexed actor rule. It is a runtime primitive for sustained CPU-bound or thread-affine work and owns one dedicated **JNI-attached pthread carrier** from construction until `release()`/termination. It does not create a Java platform thread.

Its invariants are:

- exactly one dedicated native pthread carrier per live HungryActor;
- bounded nonblocking mailbox admission;
- messages are frozen before delivery;
- serial message execution;
- fail-stop behavior on callback failure;
- cooperative CPU-loop cancellation through `schedulerSafepoint()`;
- `release()`/close relinquishes the carrier, with bounded shutdown observation;
- it never consumes a private/shared ActorRuntime dispatcher worker.

A HungryActor is intentionally expensive. It is appropriate when reserving a whole carrier is the requirement—not as the default way to obtain parallelism. Ordinary actors should remain multiplexed, and ordinary `async` should remain task/future based. The current class is a host/compiler runtime primitive; exposing a richer source-level constructor must preserve the same ownership and capability checks rather than becoming a raw guest thread API.

## Actor dispatchers

The host actor runtime follows the same scheduling shape as Akka's event-based dispatcher: many actors share an executor, each actor has its own mailbox, and a scheduled actor drains only a bounded number of messages before yielding back to the executor. The configured throughput bound prevents one hot mailbox from monopolizing a worker.

Oreslang deliberately uses two executors:

- **private dispatcher** — private actors, isolation-copy message transport;
- **shared dispatcher** — shared actors, immutable sharing plus explicit `SyncCell<T>` shared state.

A per-actor atomic scheduling gate ensures only one drain task for that actor is active. The executor may run different turns on different threads; thread identity is never actor identity.

The runtime does not interrupt a carrier thread to stop one actor because that thread belongs to the dispatcher and may subsequently execute unrelated actors. Actor cancellation is observed at compiler-injected scheduler safepoints. Whole-runtime shutdown may interrupt the dispatcher executors.


## Shared actor memory

Shared actors keep ordinary mutable fields actor-owned and mailbox-serialized. Cross-actor mutable memory is exceptional and represented by `SyncCell<T>`.

`SyncCell<T>` is runtime-owned and closeable. Its frozen state consumes shared actor-memory quota; growth reserves quota before publishing a replacement value, shrink/close returns quota, and runtime teardown closes remaining cells. The combined private-slice plus shared-cell total cannot exceed the parent `IsolatePolicy.maxHeapBytes()`.

A private actor turn cannot create, snapshot, read, update, or close synchronized shared state even if trusted host code accidentally captured a cell handle. Source admission and runtime creation both require `SHARED_MEMORY`.

Actor failures are fail-stop in this layer. The actor ref retains the failure cause for diagnostics, queued reservations are drained, and later sends receive an `ActorTerminatedException` rather than silently targeting a dead mailbox.

## Private actor memory confinement

A private actor is assigned an `ActorMemorySlice` when it is created. The slice is keyed by actor identity rather than by dispatcher thread because actor turns may migrate between worker threads.

Private mailbox admission is:

1. reject explicitly shared mutable handles such as `SyncCell<T>`;
2. isolation-copy/freeze the message graph;
3. conservatively estimate its logical Oreslang heap size;
4. reserve those bytes against the destination actor slice and the parent runtime budget;
5. enqueue only after both reservations succeed;
6. release transient mailbox bytes after the mailbox turn completes.

Persistent generated actor state reserves from the same slice. Actor teardown closes the entire slice, so leaked host-side reservation handles cannot keep a dead actor's memory budget alive.

The logical size metric intentionally does not claim to equal JVM object layout. It exists to enforce Oreslang memory-domain policy while actors remain multiplexed on one JVM. A hardened backend may replace the accounting implementation with arena/region allocation or a Graal/native isolate without changing source semantics.

A private actor's memory owner is its **ActorId**, never its carrier thread. Successive mailbox turns may execute on different private-dispatcher workers. Consequently a future FFM/off-heap backend must not make `Arena.ofConfined()` carrier-thread identity part of Oreslang semantics. It should use a cross-thread-capable region whose access is guarded by the actor owner token, or map the private actor to a true Graal/native isolate when physical heap isolation is required.

## Native runtime boundary

Oreslang's preferred actor carrier backend is now a JNI bridge to a bounded pthread pool on Linux and macOS. The library is built from `src/main/c/oresthread.c`; each pthread attaches to the host VM once and then multiplexes many unrelated Oreslang actor turns. Actor identity remains independent of physical carrier identity.

The backend selector is `-Dores.runtime.carriers=auto|native|java`:

- `auto` prefers the native pthread backend on supported Unix hosts and falls back only when the native library cannot be linked;
- `native` fails closed if the JNI runtime cannot be loaded or initialized;
- `java` is an explicit compatibility/debugging backend and must not be treated as the production Oreslang scheduler.

`process.descriptor.actor_carrier_backend` reports the physical backend so tests and supervisors can verify that native execution is actually active.

This does **not** mean the whole runtime is native yet. The current actor mailbox containers, shared-memory synchronization, async virtual-thread bridge, GC timer, and several host-integration data structures still use Java runtime primitives. Those are migration targets behind Oreslang-owned abstractions; Native Image compilation by itself is not considered proof that a primitive is natively implemented. New runtime features should avoid exposing Java concurrency types in language semantics and should prefer the JNI/native substrate where a physical scheduler, clock, thread, or memory primitive is required.
