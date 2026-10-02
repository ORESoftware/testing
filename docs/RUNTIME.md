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


## Mutex and shared-memory model

Oreslang has two deliberately different mutex domains:

- `Mutex<T>` is actor/private-domain state. It owns the protected value, uses no JVM lock, is non-reentrant, and is confined to the creating semantic actor/execution domain. Shared actors may migrate between JVM workers without changing that domain.
- `SharedMutex<T>` is an explicit same-OS-process shared-memory capability within one `ActorRuntime`. It is non-reentrant, uses acquire/release synchronization, may cross actor mailboxes only when both sides have `SHARED_MEMORY`, and binds to the first runtime that successfully publishes it. Later cross-runtime transport is rejected. It must never be treated as a distributed or cross-isolate lock.
- The payload and declared type argument of `SharedMutex<T>` must be **SharedSafe**: concrete owned data whose reachable field graph contains no borrows, actor-local `Mutex`, `MutexGuard`, pending `Future`, closure/function values, or unresolved dynamic/generic state. This applies to signatures/fields/aliases as well as `SharedMutex.new(...)`. Until Oreslang has an explicit SharedSafe generic bound, unconstrained `SharedMutex<T>` is rejected conservatively. The type checker recursively validates class fields (including inherited generic substitutions), and the interpreter repeats runtime admission checks as defense in depth.
- `MutexGuard<T>` is a lexical linear capability. The runtime releases it on normal scope exit and poisons a shared mutex on abnormal scope exit. Guest code may call `guard.release()` for early release; there is intentionally no `mutex.unlock()`. A released guard can no longer expose its protected value.
- Guard access is deliberately non-escaping. Copy-like fields may be read, mutable fields may be replaced, and methods may be invoked directly when they return `void` or a copy-like value. Move-only nested fields and bound method values cannot be extracted through a guard. For compound mutation, use `with_lock(|state| -> { ... })`.
- `with_lock` and `recover` require an inline one-argument, `void` lambda. The callback parameter is treated as a lexical exclusive `&mut T`: it may mutate protected state but cannot move or return that state, escape it through a closure, or suspend with `await`.
- `await` while a guard is live and closure capture of a guard are compile-time ownership errors. Guard-bearing results must be bound once with `val`; they cannot be discarded, reassigned, stored in aggregates, passed through arbitrary calls, or hidden inside another mutex.
- Blocking `SharedMutex.lock()`/timed acquisition is rejected while executing an actor. `lock_async()` returns a runtime-owned, caller-cancellable `GuardFuture` and is the nonblocking acquisition primitive. Contended async acquisition is queued inside the mutex and receives the permit by direct guard handoff; it does **not** allocate one virtual thread per waiter. Acquisitions reserve the semantic actor/execution domain before waiting, so recursive async acquisition fails instead of self-deadlocking even if an actor migrates JVM workers. Cancellation removes queued waiters and releases their domain reservation; poisoning drains queued async waiters with `PoisonedMutexException`. Admission is bounded by the current actor mailbox policy, an 8,192-waiter ceiling per mutex, and a 32,768-waiter JVM-process ceiling. When blocking host waiters and async waiters coexist, release alternates handoff preference so neither class monopolizes the mutex. When integrated with the bounded shared-actor platform-thread pool, the language `await` lowering must suspend/resume the actor turn rather than synchronously join the future; until that continuation lowering is in place, actor-backed singleton/mailbox serialization is preferred over contended shared-memory locking from shared actors.
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

Actor transport is independently hardened from mutex synchronization. Ordinary messages are recursively frozen with cycle detection and hard depth/node/byte budgets (256 levels, 100,000 nodes, 16 MiB estimated frozen size). Read-only shared wrappers are runtime-constructed and revalidated on every boundary. `ActorRef` capabilities may cross only inside their owning `ActorRuntime`; a wrapper cannot be used to smuggle a foreign actor reference into another runtime. Generated `Sendable` values are not trusted blindly—the representation returned by `freezeForSend()` is recursively frozen and validated again.

## GPU placement boundary

`gpu` source is a distinct execution target, not a performance annotation on ordinary Truffle execution.

The trusted compiler pipeline is:

1. parse and type/ownership check;
2. run GPU admission against the conservative device subset;
3. lower every admitted GPU callable/lambda to deterministic OpenCL C 1.2;
4. retain kernel/ABI/batch metadata and a device-source digest in the compilation result and incremental code unit.

Named GPU callables lower to a reusable device helper and a kernel entrypoint. The GPU lowering layer preserves exact source ABI widths even though the general front-end currently groups numeric families more coarsely; the generated device artifact therefore rejects ambiguous implicit narrowing/mixed-sign operations instead of relying on OpenCL C's implicit-conversion rules. Scalar results use an explicit `__global` result pointer. Flat array/list parameters lower to `__global` pointers, with `const` and `restrict` qualifiers derived from source mutability/ownership.

A canonical top-level elementwise loop can lower from serial source semantics to a 1-D SPMD kernel using `get_global_id(0)`, but only after the compiler proves the loop induction shape and verifies that accesses to mutable buffers are per-work-item. If that proof fails, the generated kernel retains single-work-item execution rather than risking a data race.

A zero-argument/void `gpu parallel` batch gets individual kernel entrypoints plus a fused dispatcher kernel. The dispatcher launches one logical work-item per function and switches on `get_global_id(0)`. This is a logical GPU work-item mapping, not physical core affinity.

The GPU artifact declares device requirements explicitly. In particular, `cl_khr_fp64` is requested only when the emitted code uses `double`, avoiding an unnecessary compatibility requirement for integer/f32-only programs.

The current Truffle evaluator does not emulate or CPU-fallback GPU execution. If a GPU-targeted named function, static function, lambda, or batch reaches the evaluator without a configured physical GPU launcher, execution fails closed. A future launcher should consume the already-generated `GpuProgram` artifact, compile/cache the OpenCL source for the selected device, bind buffers/scalars according to the manifest, and launch according to `SINGLE_WORK_ITEM`, `DATA_PARALLEL_1D`, or batch-dispatch metadata. It must also honor launch-safety flags: clamp signed negative global-work extents to zero, size error/result slots from the launch plan, and reject overlapping global-buffer bindings whenever the manifest marks the kernel as requiring no-alias enforcement.

Oreslang does not expose stable "GPU core N" affinity because that is not a portable hardware abstraction. Backends remain responsible for lanes/warps/wavefronts/work-groups, occupancy, queueing, and physical device selection. Multi-GPU affinity can be added separately once the runtime has a real device scheduler.

## Quantum placement boundary

`quantum` is a distinct execution target alongside the default CPU target and `gpu`. The front end records the placement as callable metadata; it is not a JIT hint.

The current evaluator has no quantum backend. Invoking a quantum-targeted named function or static function therefore fails closed before entering the body and never falls back to CPU or GPU execution.

A future QPU backend should lower checked quantum code into a circuit/job representation, submit it through an explicit provider/device boundary, and return measured classical results. Device selection, shot count, circuit capability checks, simulator policy, and error-mitigation policy belong in that backend rather than changing the execution target implicitly.

