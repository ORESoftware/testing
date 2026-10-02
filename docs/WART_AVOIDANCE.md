# Oreslang cross-language wart avoidance contract

Oreslang borrows proven ideas from Erlang/BEAM, Rust, Go, and Java without inheriting their accidental complexity or unsafe defaults. A future feature that conflicts with these rules needs an explicit language-design decision; it must not arrive accidentally through the JVM, Truffle, a backend, or compatibility sugar.

## Universal invariants

1. No ambient authority: filesystem, network, environment, native code, reflection, child processes, threads, sharing, and GC control are capability-gated.
2. No raw null: source absence is `Option<T>`; interop null must be converted at the boundary.
3. No unbounded runtime resource by default: actors, mailbox count/bytes, monitors, failure tombstones, message graph depth/nodes, hot-reload generations, heap, wall time, and sandbox output are bounded.
4. No hidden shared mutable state: mutable guest state has one owner; cross-actor sharing is immutable and capability-scoped.
5. No backend semantic leakage: Java exceptions, JVM GC, thread interruption, `CompletableFuture`, host collection iteration order, or native loader behavior are implementation details.
6. No silent control-plane loss: supervision, cancellation, and resource failures fail closed and remain observable.
7. No type-erasure escape at trust boundaries: actor/shared transport requires a concrete send-safe type; unknown and unconstrained generic message types are rejected until explicit safe bounds exist.
8. Determinism by default: compiler symbol registries, structural records, frozen maps, runtime descriptors, and hot-reload generation maps preserve stable insertion order.

## Erlang / BEAM

Keep cheap isolated actors, immutable message passing, monitors, supervision, crash containment, and source/IR upgrades.

Do not copy unbounded mailboxes, selective-receive scans, dynamically typed PID protocols as the default, finite global atom tables, process dictionaries, ambient ETS-style mutable tables, trusted-cluster distribution assumptions, location-transparent remote refs, or supervision events that disappear under overload.

Oreslang enforces bounded FIFO mailboxes, `ActorRef<T>` plus runtime `Protocol<M>`, runtime-scoped capabilities, bounded monitors, fail-closed `DOWN` delivery, and a future distinct authenticated `RemoteActorRef<T>` contract.

## Rust

Keep affine ownership, move checking, exclusive mutation, deterministic drop opportunities, exhaustive option/result handling, and zero-copy analysis.

Do not copy pointer-looking `&T` / `&mut T` syntax, user-written lifetime-name proliferation, general-purpose `unsafe`, accidental missing Send/Sync-style bounds, Copy/Clone ambiguity for heap objects, `Pin` leaking into normal async source, or panic-heavy convenience APIs as the default.

Oreslang uses `Borrow<T>` / `BorrowMut<T>` with `borrow(value)` / `borrow_mut(value)`. `take(value)` makes transfer intent explicit. `copy(value)` currently succeeds only for statically Copy values; heap/class copying needs an explicit future copy contract. `share(value)` creates immutable `Shared<T>`. The lexer rejects `&` borrow syntax and lifetimes are compiler-inferred.

## Go

Keep simple syntax, cheap concurrency, explicit values, and fast tooling.

Do not copy nil/typed-nil traps, goroutine leaks, easy shared-memory races, observable random map iteration, optional cancellation convention, fire-and-forget resource ownership, or invalid resource zero-values.

Oreslang forbids standalone null, bounds actor/control-plane resources, exposes no ambient raw-thread API, preserves ownership, makes map/record iteration deterministic where observable, and uses scheduler safepoints for cancellation/wall-time enforcement.

## Java / JVM

Keep mature GC as a fallback implementation tool, nominal types, ecosystem access behind controlled interop, and virtual threads as one possible backend primitive.

Do not copy pervasive null, shared mutable heaps as the concurrency default, checked-exception/`CompletionException` leakage, guest-visible global `System.gc()` semantics, ambient reflection/native access, erased generics weakening trust boundaries, unspecified hash iteration order, finalizer-style correctness, or unlimited staged runtime contexts.

Oreslang uses `Option<T>`, actor-owned mutation, transparent `await` failure unwrapping, host-opt-in JVM GC hints, deny-by-default Graal capabilities, deterministic linked immutable maps, and bounded hot-reload generations with explicit leases.

## Async/task contract

`async` must never mean an untracked Java thread or detached Go-style goroutine. Full async lowering must provide structured ownership/cancellation, bounded task/queue counts, preserved Oreslang stack/cause information, and source types that do not expose `CompletableFuture`, virtual-thread handles, continuations, or `Pin`.

## Error contract

Expected failure belongs in typed values such as `Result<T,E>` / `Option<T>`. Exceptional control flow is for exceptional failures. Actor failure metadata is bounded before retention so error text cannot become an unbounded tombstone or supervision-message memory sink.

## Resource-lifecycle contract

Correctness must not depend on GC/finalizers closing semantic resources. Actor stop/join is explicit, monitors are accounted, hot-reload generations use leases and retained-generation caps, contexts close deterministically, and future sockets/files/database handles must follow explicit ownership.

## Review checklist

For every new feature ask:

- Can it create unbounded work, memory, handles, monitors, tasks, or queues?
- Can a foreign runtime capability cross the boundary?
- Can `Unknown`, a generic, reflection, or host interop bypass static safety?
- Can a mutable alias survive an ownership transfer?
- Can cancellation/failure/control messages disappear silently?
- Does behavior depend on host hash iteration, scheduling, GC timing, or exception wrapper classes?
- Does source syntax expose backend concepts such as `&`, raw pointers, Java futures, JVM threads, or `Pin`?
- Is behavior fail-closed when policy metadata is absent or malformed?
- Is behavior deterministic enough for reproducible fixtures and hashes?
- Is there a regression test for the invariant?

If any answer is unsafe or ambiguous, the feature is not ready to merge.