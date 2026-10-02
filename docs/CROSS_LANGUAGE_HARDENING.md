# Oreslang cross-language hardening contract

Oreslang borrows useful ideas from Erlang/BEAM, Rust, Go, and Java, but does not
treat compatibility with their historical behavior as a goal. When a host
runtime offers weaker or more ambiguous semantics, the Oreslang language
contract wins.

## Non-negotiable language invariants

1. **No ambient nullability.** Bare `null` is not a normal value. Optionality is
   represented with `Option<T>`.
2. **No raw pointer/reference surface.** User code does not spell `*`, `&`,
   `&mut`, pointer arithmetic, or user-written lifetime annotations.
3. **No implicit shared mutable heap.** Mutable data has one owner. Shared values
   are deeply read-only and capability scoped.
4. **No unbounded concurrency primitive.** Actors have count/mailbox/heap limits;
   async tasks are admission controlled and owned by the isolate context.
5. **No ambient host capability.** Filesystem, network, environment, process
   spawning, reflection, FFI/native access, thread creation, and polyglot access
   are denied unless the host policy explicitly grants the capability.
6. **No build-mode arithmetic split.** Signed integer overflow is checked in
   every execution profile. Debug and release/AOT/JIT must not disagree.
7. **No cosmetic async.** `async` callables produce `Future<T>`; `await`
   requires a future and unwraps it. Awaiting an ordinary value is an error.
8. **No catch-all host control flow.** Guest `catch` cannot swallow isolate
   security failures or runtime cancellation/deadline enforcement.
9. **No silent supervision loss.** A monitor event that cannot be delivered is
   observable; it is never silently discarded.
10. **No location-transparent network fiction.** A future remote actor reference
    must be a distinct capability/type with explicit network failure semantics.
11. **No hidden dynamic protocol for normal actors.** Source actors lower their
    mailbox type into runtime protocol admission metadata as defense in depth.
12. **No runtime type rule stricter than the static language rule.** Runtime
    protocol checks must preserve legal numeric widening, inheritance, and other
    statically valid assignability.

## Erlang / BEAM behaviors we intentionally do not copy

- unbounded mailboxes;
- selective-receive scans through old unmatched messages;
- untyped PID/message protocols as the normal source-level API;
- a globally interned user-creatable atom table;
- process dictionaries / hidden actor-local globals;
- ambient ETS-style shared mutable state;
- treating process fault isolation as a hostile-code security sandbox;
- trusted-cluster assumptions for future distributed actors;
- silently losing supervision events under overload.

We retain the useful ideas: cheap isolated actors, message passing, monitors,
supervision as ordinary actor logic, immutable/frozen transport, and
cooperative scheduler safepoints.

## Rust behaviors we intentionally do not copy

- raw pointer-looking borrow syntax in ordinary source code;
- user-written lifetime parameter syntax for normal application code;
- an `unsafe` escape hatch that bypasses ownership guarantees inside guest
  code;
- debug-mode overflow panics paired with release-mode wrapping;
- requiring every async caller to understand executor pinning/runtime details;
- implicit cross-thread transfer merely because the JVM object happens to be
  reachable.

Oreslang keeps affine ownership, explicit move/borrow operations, mutable-alias
exclusion, non-escaping local borrows, and ownership-aware closure capture.

## Go behaviors we intentionally do not copy

- `nil` as a broadly inhabitable value;
- unlimited goroutine creation with no ownership or lifecycle relationship;
- channels/mailboxes that can accidentally become unbounded architecture;
- silent integer wraparound;
- supervision implemented by convention with no typed monitor relationship;
- pretending network calls and local calls have the same failure surface.

Async work is owned by the Oreslang context and is interrupted on context
shutdown. Actors and async tasks are bounded separately from source-level
application data.

## Java / JVM behaviors we intentionally do not copy

- nullable references by default;
- silent signed integer overflow;
- ambient thread creation;
- ambient reflection/classloading/native access;
- checked-vs-unchecked exception taxonomy leaking directly into language
  semantics;
- catching JVM security/cancellation control exceptions as ordinary guest
  errors;
- shared mutable JVM reachability being treated as permission to share guest
  state;
- `async` implemented as a library convention with no language-level type
  distinction.

The Java/Truffle backend is an implementation substrate. Java object reachability
does not weaken Oreslang ownership, actor, or capability rules.

## Async contract

An `async fnc` or async static method returns `Future<T>` at the call boundary while
its body is type-checked as returning `T`. Async instance methods remain rejected
until receiver ownership across task lifetime is explicit.

`await` accepts only `Future<T>` and yields `T`.

The reference runtime executes async work on virtual threads, but those threads
are not exposed to guest code. Task admission is bounded per isolate, bound
futures are must-consume resources, compiler-inserted safepoints enforce the
isolate wall-time budget, and live tasks are registered with the context so
shutdown interrupts them instead of leaking background work.

Async failure remains attached to the future. Await unwraps the original runtime
cause when possible rather than exposing Java `CompletionException`/
`ExecutionException` wrappers as Oreslang semantics.

## Deferred cleanup contract

`defer` captures its referenced bindings at registration time. Move-only values
transfer into the deferred action immediately; borrowed captures and direct
mutation of captured bindings are rejected. This avoids both use-after-move and
late-binding cleanup behavior where later assignments unexpectedly change what
the deferred operation observes.

## Arithmetic contract

Signed integer `+`, `-`, `*`, and unary negation are checked. Overflow is
an error in JIT, AOT, hybrid, tests, and production.

If wrapping/saturating arithmetic is added later it must use explicit operations
or types such as `wrapping_add` / `saturating_add`; ordinary arithmetic never
changes behavior with optimization mode.

## Exception boundary

Oreslang language exceptions and host/runtime control exceptions are separate
domains.

Ordinary guest `try/catch` may handle language/runtime application failures,
but it may not intercept:

- isolate capability/security denial;
- scheduler cancellation;
- actor deadline cancellation;
- context shutdown cancellation.

Future `throw`, `raise`, `panic`, and `recover` syntax must preserve this
separation. In particular, recovery constructs must never turn a security
boundary or forced cancellation into catchable application control flow.

## Future-feature admission checklist

Before adding a concurrency, FFI, reflection, memory, error, or distribution
feature, answer all of these:

- What owns its lifetime?
- What bounds its memory/queue/task count?
- Can it create mutable aliases?
- Can it cross an isolate?
- What capability authorizes it?
- Can cancellation always make progress?
- Can ordinary guest catch/recover suppress cancellation or security policy?
- Is local-vs-remote failure explicit?
- Is behavior identical across JIT/AOT/debug/release profiles?
- Does the static type system express the guarantee, with runtime checks only as
  defense in depth?

A feature that cannot answer these questions should not become a first-class
Oreslang primitive yet.
