# Reserved `pure` keyword and effect contract

Status: **draft design / compiler contract**

This document specifies the proposed reserved `pure` keyword for Oreslang. The first implementation target is named `fnc` and `routine` declarations. The keyword is a compiler-enforced semantic contract, not documentation and not an optimizer hint.

This design is intentionally built on an internal effect system so Oreslang can add richer effect declarations later without weakening or redesigning `pure`.

## Goals

A declaration that successfully compiles with `pure` must be unable to:

- observe ambient mutable state;
- mutate state reachable outside the invocation;
- perform I/O;
- observe time, randomness, scheduler state, mailbox state, process state, environment variables, or other nondeterministic ambient inputs;
- spawn actors, send or receive actor messages, or mutate actor/process/singleton state;
- escape through Java/JNI/native/host calls whose effects are not compiler-trusted;
- hide an impure operation behind a helper, closure, callback, dynamic dispatch, generic instantiation, imported package, or recursive call graph.

The guarantee is transitive and fail-closed.

## Non-goals

`pure` does **not** mean:

- total;
- guaranteed to terminate;
- non-throwing, non-raising, or non-panicking;
- allocation-free;
- recursion-free;
- immutable implementation syntax;
- automatically memoizable;
- automatically safe for common-subexpression elimination;
- automatically safe to reorder across exceptions/divergence;
- constant-time or side-channel-safe;
- bounded in CPU, memory, stack, allocation count, or wall time;
- immune to runtime termination such as OOM, stack exhaustion, sandbox fuel exhaustion, OS kill, or hardware failure;
- full referential transparency for identity-bearing freshly allocated results.

Those properties are separate compiler facts. `pure` is an observational language-level effect contract: no forbidden ambient observation and no forbidden externally visible mutation/effect. Fresh allocation and exceptional control remain separate facts, so the optimizer must not silently strengthen `pure` into a stronger mathematical property.

## Syntax

`pure` is a globally reserved keyword.

Canonical forms:

```ores
pub pure fnc add(int a, int b): int {
  return a + b;
}

pub pure routine normalize(): void {
  // side-effect-free orchestration is legal;
  // routine's existing no-recursion rule still applies.
  return;
}
```

The canonical named-callable modifier order is:

```text
[visibility] pure [nlex] fnc ...
[visibility] pure [nlex] routine ...
```

Class-level static functions use the existing class-static position:

```ores
define class Math as
  pub static pure fnc twice(int value): int {
    return value * 2;
  }
end
```

Lambda-style named declarations, where accepted by the callable grammar, carry the same contract:

```ores
pub pure routine normalize = || -> void {
  return;
}
```

`fnc` retains its existing recursive semantics. `routine` retains its existing prohibition on direct or indirect recursive cycles. Purity is orthogonal to recursion. `nlex` is compatible with `pure`; it adds the stronger no-activation-capture rule but does not replace effect checking.

V1 deliberately rejects `pure async fnc`, `pure async routine`, and `pure actor fnc`. Async/future completion and actor execution are scheduler/effect boundaries. A synchronous pure helper may be called freely from async or actor code.

The first implementation does not require public syntax for `pure` instance methods. The compiler must still infer method effects so calls from a pure callable cannot escape through an impure method. A later language revision may expose `pure` on methods/interfaces once method effect contracts are specified.

Other callable modifiers such as the separately designed `trap` modifier may compose with `pure` only when their control-flow semantics preserve the effect contract. Modifier composition is checked semantically, not accepted merely because the parser can order the words.

`pure` is not a callable-only compatibility keyword. It cannot be used as an ordinary identifier, parameter, binding, type, class, module, field, bare callable reference name, or unquoted object key.

## Semantic definition

A pure invocation may compute using:

- explicit input values;
- immutable/read-only views of explicit input values;
- uniquely owned values moved into the invocation;
- locally created state;
- values returned by other compiler-proven pure computations;
- compile-time constants embedded in checked IR.

It may not depend on ambient mutable or nondeterministic state. Time, random values, locale, configuration, IDs, or other nondeterministic information are allowed when they arrive as ordinary explicit data values rather than being observed through an ambient capability.

A pure invocation may mutate storage only when the ownership checker proves that storage is private to the invocation for the duration of that mutation. The mutation must not be visible through any simultaneously live external alias.

This makes local imperative implementation compatible with side-effect freedom without pretending that fresh object identity, allocation cost, or exceptional control are mathematically invisible.

### Allowed local mutation

```ores
pub pure fnc sum(List<int> values): int {
  let int total = 0;

  for (val value of values) {
    total += value;
  }

  return total;
}
```

Local mutation is allowed when the mutated storage is invocation-private.

### Forbidden external mutation

Conceptually, mutation through a caller-visible mutable borrow is rejected:

```ores
pub pure fnc increment(&mut Counter counter): void {
  counter.value += 1; // ERROR: caller-visible mutation
}
```

### Owned/moved inputs

Oreslang's affine ownership model lets the checker be more precise than a blanket "arguments are immutable" rule.

A by-value non-shared value whose ownership has moved into the pure invocation may be mutated as invocation-private storage when the borrow checker proves there are no live aliases. This is equivalent to mutating a local temporary.

An exclusive mutable borrow remains forbidden because its mutation is observable through the caller's retained ownership.

The built-in ownership operations therefore interact with purity as follows:

- `copy(struct)`: compiler-derived recursive copy is pure only when every nested copy step is pure;
- `copy(class)`: invokes the class copy contract and is pure only when that concrete class copy implementation is compiler-proven pure and satisfies the fresh/non-aliasing copy rules;
- `take`: allowed as an ownership transfer; subsequent mutation is allowed only under unique ownership;
- `borrow` / `&T`: allowed for read-only access to explicit input state;
- mutable borrow / `&mut T`: forbidden in a pure contract because the caller retains observable ownership;
- `share`: allowed only for a deeply immutable/frozen snapshot; access to a live mutable shared region is forbidden.

This aligns purity with the struct/class split: structs receive compiler-derived value-copy semantics; classes never become pure-copyable merely because a method named `copy` exists.

## Escape and ownership rule

Purity forbids **alias escape**, not the useful return of uniquely owned values.

Allowed examples include:

- returning a newly allocated mutable object that has no alias outside the invocation;
- consuming a uniquely owned input with `take`, mutating it locally, and returning that same uniquely owned value;
- returning a closure whose mutable captured state was freshly created inside the invocation, provided the returned closure's own callable effect metadata correctly records that later invocation is stateful/impure.

For example, an in-place transform can remain pure when ownership is transferred:

```ores
pub pure fnc sort_owned(List<int> mut values): List<int> {
  // values is uniquely owned and explicitly mutable in this invocation.
  values.sort_in_place();
  return values;
}
```

The checker must reject any return/capture/store that exposes a mutable alias to storage that was already externally reachable or remains reachable through another live alias. It must also reject escape through:

- assignment to module/global/singleton/actor state;
- actor messages or process/global registries;
- callbacks registered with or retained by an external runtime service;
- foreign/native handles that alias externally mutable storage;
- returned borrows whose owner does not outlive the result;
- nested aggregates that launder a prohibited borrow/alias;
- any other longer-lived alias that violates ownership provenance.

A returned value need not be deeply immutable merely because its factory was pure. The result's later methods/callables keep their own effect summaries.

Fresh allocation may still carry runtime identity. Therefore `pure` alone is not permission for identity-changing optimizations such as memoization or allocation CSE. Those require a separate value-semantics/identity analysis.

## Ambient reads

Read-only syntax does not imply purity.

These are ambient reads and are forbidden even when the returned value is not mutated:

- mutable module/global/thread-local state;
- singleton state;
- actor state not supplied as an explicit value snapshot;
- environment variables and process/system properties;
- current working directory;
- process metadata;
- locale, timezone, default charset, or other host defaults;
- current time or hardware/performance counters;
- ambient randomness or process-seeded randomized hashing;
- scheduler/thread identity, cancellation state, or task/future completion state;
- mailbox/channel state;
- lock/atomic/volatile state;
- GC state, heap statistics, weak-reference liveness, or finalization state;
- caller/call-stack inspection, loaded-module enumeration, classloader state, or similar runtime reflection;
- raw addresses, pointer values, or externally mutable foreign memory;
- external files, sockets, databases, devices, memory maps, or host state.

Identity equality between explicit object references can be pure because it depends on explicit inputs. Producing or inspecting raw addresses/process-assigned identity numbers is not.

A stable content hash with a specified algorithm is pure. A hash whose seed comes from process/runtime randomness is not unless the seed is supplied explicitly.

A deterministic PRNG is pure when its state/seed is explicit and the function returns the updated PRNG state as data. Calling an ambient/global RNG is not pure.

The conservative v1 exception for ambient bindings is a compile-time constant whose value is embedded in checked IR. Arbitrary runtime `const` objects are not assumed pure merely because their binding cannot be reassigned.

## Closures

Closures are allowed. They are not a purity escape hatch.

A closure used by or returned from a pure callable must have compiler-proven effects compatible with the pure contract.

Allowed:

- capture of immutable/value-semantic data;
- immutable borrowing of explicit input state while the borrow is valid;
- mutation of closure-local state that is invocation-private;
- returning a stateful closure when all mutable captured state is freshly owned by that returned closure and the closure's own effect summary is not falsely marked pure.

Rejected:

- mutation of an outer caller-visible binding;
- capture of mutable shared/module/singleton/actor state;
- escaping a borrow whose provenance/lifetime cannot be represented safely;
- laundering captured mutable aliases through nested closures;
- invoking a closure whose effect summary is unknown or incompatible with purity.

Creating or passing an impure callable value is not itself an effect. Invoking it, registering it with an external service, or causing it to escape through an effectful runtime boundary is what matters.

## Hidden calls, desugaring, and implicit behavior

Purity is checked on resolved/desugared semantics, not surface syntax. No implicit call may bypass the effect checker.

The compiler must include effects from:

- constructors and field/default initializers;
- class `copy()` hooks and any future clone/conversion hooks;
- overloaded operators and comparisons;
- equality/hash implementations;
- property getters/setters and indexers;
- `[Symbol.iterator]()`, iterator `next`, and consuming iteration;
- implicit coercions/conversions;
- string/template interpolation and formatting hooks;
- destructuring helpers;
- user-defined assertion/error construction hooks;
- `defer`, `finally`, cleanup/drop hooks, and any future destructor/finalizer semantics.

A type whose guest-visible finalizer/destructor can perform a forbidden external effect cannot be created in a pure callable merely because that effect happens later. GC-timed externally visible finalization would make the allocation itself semantically effectful.

Effect collection must therefore happen after name resolution/desugaring has identified every callable edge, while ownership provenance is still available.

## Actors, isolates, async, and scheduling

The following effects are forbidden in a pure callable:

- `spawn`;
- mailbox/channel send or receive;
- actor lookup with observable mutable state;
- actor state mutation;
- actor state reads unless the state was first copied/frozen and passed as an explicit value;
- creating or awaiting live futures/promises/tasks in v1;
- observing completion, cancellation, deadlines, queue depth, or scheduler state;
- scheduler queries;
- thread identity;
- sleep/yield/park;
- locks;
- mutable atomics or volatile memory;
- isolate/process capability operations.

The initial syntax rejects `pure actor fnc`, `pure async fnc`, and `pure async routine`. Actor and async entrypoints are effectful boundaries. Pure helpers may be called from actor/async code normally.

V1 also rejects `await` inside a pure callable even if the awaited computation is believed to be pure. A later design may admit effect-typed immediate/deterministic futures, but the first checker fails closed.

A common pattern is:

```ores
val snapshot = copy state.snapshot();
val result = calculate(snapshot);

pub pure fnc calculate(StateSnapshot snapshot): ResultValue {
  // deterministic/value-only work
}
```

## I/O and runtime capabilities

Pure callables cannot perform:

- `stdio`;
- filesystem I/O;
- network I/O;
- HTTP/socket operations;
- database operations;
- environment/process reads;
- device access;
- memory-mapped/volatile external-memory access;
- capability acquisition or authority escalation;
- externally visible logging/tracing/metrics/audit emission.

Passing an opaque capability value through a pure callable without invoking/acquiring/registering it is not automatically an effect; using the capability is.

A pure callable may construct or transform ordinary data representing a request, path, packet, query, log record, timestamp, random seed, capability descriptor, etc. It simply cannot execute the external effect represented by that data.

## Exceptions and divergence

Purity is separate from totality.

A guest-language `throw`, `raise`, or `panic` may occur in a pure callable when constructing/propagating the control signal performs no forbidden external effect. A `trap`/recovery boundary may likewise remain pure when every executed handler/cleanup path is pure.

A recursive `fnc` may diverge and still be pure.

The compiler must therefore track at least `throw`, `raise`, `panic`, and `diverge` separately from forbidden external effects. Handlers may discharge the corresponding control effect, but they do not erase external effects performed while constructing the signal, unwinding, running `defer`/`finally`, or handling it.

Optimizations must not assume that `pure` means `nothrow`, `noraises`, `nopanic`, or `terminates`.

Call-stack/debug metadata attached by the runtime is diagnostic metadata, not an ambient capability granted to pure code. Reading caller stack, thread, source-loader state, or similar runtime metadata inside a pure callable is an introspection effect and is forbidden.

See `docs/TRAP.md` for the detailed `trap` / `throw` / `raise` / `panic` / `recover` contract.

Host/FFI failures are not automatically trusted as pure guest control effects and remain covered by foreign-effect rules. Fatal VM/native corruption, OS termination, hardware faults, OOM, stack exhaustion, or sandbox fuel exhaustion are outside the source-level purity guarantee.

## Java, JNI, native, and host interop

Foreign code is fail-closed.

A call into Java/JNI/native/host code has effect `ffi_unknown` unless one of the following is true:

1. the operation is a compiler/runtime-owned intrinsic with audited effect metadata; or
2. a future trusted ABI/manifest mechanism establishes a purity contract.

There is no user-level `assume_pure` escape hatch in v1.

Examples of compiler-owned classifications:

```text
integer.add        pure
string.length      pure
math.sin           pure
socket.write       io
clock.now          clock
random.next        random
thread.yield       scheduler
unknown JNI call   ffi_unknown
```

## Internal effect model

Every callable receives an internal effect summary whether or not source code declares `pure`.

Initial effect kinds should include at least:

| Effect | Meaning | Allowed by `pure` |
| --- | --- | --- |
| `local_mutation` | mutation of invocation-private storage | yes |
| `allocation` | local allocation | yes |
| `throw` | ordinary guest exception/control effect | yes |
| `raise` | trap-bypassing recovery signal | yes |
| `panic` | invariant/runtime recovery signal | yes |
| `diverge` | possible nontermination | yes |
| `ambient_read` | read mutable/ambient runtime state | no |
| `external_mutation` | mutate caller/global/shared reachable state | no |
| `io` | stdio/filesystem/network/database/device I/O | no |
| `environment` | env/process/cwd/locale/timezone/host configuration | no |
| `clock` | wall/monotonic/hardware time observation | no |
| `random` | nondeterministic ambient random source | no |
| `actor` | actor/mailbox/process-state interaction | no |
| `scheduler` | async/thread/scheduler/sleep/yield/cancellation state | no |
| `synchronization` | locks/mutable atomics/volatile/external synchronization | no |
| `introspection` | caller stack/runtime/loader/GC/heap/identity-address observation | no |
| `capability` | acquire/register/use external authority | no |
| `external_memory` | access live foreign/MMIO/shared memory without frozen ownership proof | no |
| `ffi_unknown` | foreign code with no trusted summary | no |
| `unsafe_external` | unchecked external memory/authority effect | no |

A closed bitset alone is **not sufficient** for mutation soundness. The compiler also needs region/parameter-relative read/write footprints and higher-order effect variables. The source-language contract is defined by semantics, not the internal representation.

## Region- and parameter-relative mutation effects

A callee's mutation effect must say **what region it may mutate**, not merely that "some mutation" occurs.

Conceptually:

```text
List.sort_in_place(self)   => write(self)
append(mut param0, value)  => write(param0)
make_list(...)             => allocation + write(fresh)
global_cache_put(...)      => external_mutation(global_cache)
```

At each call site, the checker substitutes ownership provenance:

- `write(fresh)` -> `local_mutation`, allowed;
- `write(self)` where `self` is uniquely owned/moved into the invocation -> `local_mutation`, allowed;
- `write(param0)` where param0 is an owned `Type mut name` parameter consumed by the invocation -> local/owned mutation, allowed;
- `write(self/param)` through a caller-visible `&mut`, shared alias, captured alias, singleton/global region, or unknown provenance -> `external_mutation`, forbidden;
- unknown or unsupported provenance -> fail closed.

This is required for code such as:

```ores
pub pure fnc sort_owned(List<int> mut values): List<int> {
  values.sort_in_place(); // write(self), discharged as local because values is unique
  return values;
}
```

without incorrectly blessing:

```ores
pub pure fnc sort_borrowed(&mut List<int> values): void {
  values.sort_in_place(); // ERROR: write(self) targets caller-visible storage
}
```

Effect summaries therefore need, at minimum:

- closed external/control effect bits;
- region/receiver/parameter read-write footprints;
- ownership/precondition information needed to discharge those footprints;
- higher-order effect variables/constraints.

These footprints must survive generics, method extraction, dynamic dispatch, imports, compiled metadata, hot reload, and inlining. A method override cannot widen a pure/region-safe mutation footprint without invalidating callers.

## Transitive effect inference

Purity must be proven over the complete resolved call graph.

Example:

```ores
pub pure fnc a(): int {
  return b();
}

fnc b(): int {
  return c();
}

fnc c(): int {
  return stdio.stdin.read_int();
}
```

Compilation must fail even though the direct body of `a` contains no obvious I/O.

The compiler must compute a fixed point over call-graph strongly connected components so recursive `fnc` groups receive complete effect summaries.

A declared pure callable succeeds only when every reachable operation is compatible with the pure contract.

## Indirect calls and higher-order functions

Purity must be part of callable type metadata internally from the first implementation.

A pure callable may invoke a function value/callback only when the compiler can prove that the invoked target effect set is compatible with purity.

Effect metadata must support **effect variables/constraints**, not only a single closed bitset. Otherwise generic higher-order helpers become either unsound or unnecessarily unusable. Conceptually:

```text
map(values, f) effects = effects(f) ∪ local_allocation
```

A concrete `map(values, pure_callback)` may therefore be pure while `map(values, io_callback)` is not. If v1 source syntax cannot express a pure/effect-bounded callback parameter, exported generic code must fail closed rather than erase the dependency.

A pure callable may still accept, pass through, store in invocation-local data, or return an impure callable value when it never invokes/registers it and ownership rules permit the value to escape.

This covers:

- closures;
- function values;
- generic callable parameters;
- interface dispatch;
- trait dispatch;
- virtual/class dispatch;
- imported callable values.

If the target effect is unknown, a declared pure caller fails closed.

The exact source spelling for a "pure callable type" is intentionally left for the callable-type syntax design. The compiler metadata must support it now so higher-order calls cannot punch a hole through v1 purity.

## Dynamic dispatch

For a direct/final target, the compiler may use the inferred target effect summary.

For open dynamic dispatch, a pure caller may invoke the slot only if every legal runtime target is proven compatible with purity.

If the dispatch set cannot be closed, the contract must eventually live on the interface/trait/method slot itself. Until that surface exists, unknown/open dispatch from a pure caller is rejected.

A future `pure` method/interface contract must obey variance by guarantee:

- a pure interface/base slot may only be implemented/overridden by a pure implementation;
- an implementation may strengthen an impure/unspecified slot by being pure.

## Imports and compiled metadata

Effect summaries are part of the checked interface of a compiled Oreslang unit.

The compiler must serialize enough effect metadata to prove cross-file/package calls without re-parsing implementation bodies.

Rules:

- exported `pure` is part of the public contract;
- removing `pure` from an exported callable is a contract weakening and must be treated as an API compatibility change;
- adding `pure` is a strengthening of the guarantee;
- imported artifacts lacking trusted effect metadata are `unknown`, never silently assumed pure;
- metadata version skew fails closed for explicit pure callers;
- effect metadata is compiler-produced/verified metadata, never a user-authored sidecar assertion;
- metadata must be cryptographically/content-address tied to the exact implementation artifact or source/code-unit digest it summarizes;
- the metadata schema/effect-lattice version and trusted intrinsic-table version are part of compatibility;
- reverse dependencies are invalidated when a callee's effect summary or public effect contract changes;
- hot reload is generation-scoped: an active pure caller cannot begin dispatching to a newly loaded impure generation without revalidation;
- a pure interface/slot contract cannot be weakened by hot replacement;
- binary-only/foreign code whose implementation cannot be validated remains `ffi_unknown` unless covered by a separately trusted ABI attestation mechanism.

Purity is therefore a property of a checked code generation/artifact, not a timeless promise about a symbol name.

## Diagnostics

Purity diagnostics must report the effect path rather than only the final declaration.

Example:

```text
error[E-PURE-004]: pure fnc 'a' reaches an I/O effect

  a
  └── calls b
      └── calls c
          └── calls stdio.stdin.read_int
              └── effect: io
```

Proposed stable diagnostic families:

- `E-PURE-001`: direct forbidden effect;
- `E-PURE-002`: ambient mutable/nondeterministic read;
- `E-PURE-003`: externally reachable mutation;
- `E-PURE-004`: transitive impure call;
- `E-PURE-005`: unknown foreign/imported effect;
- `E-PURE-006`: escaping mutable state;
- `E-PURE-007`: impure/unknown callback or dynamic target;
- `E-PURE-008`: invalid modifier combination;
- `E-PURE-009`: purity contract override/implementation mismatch.

Diagnostics should identify the first useful source location and include a shortest effect trace when the violation is transitive. Hidden/desugared calls should name both the surface operation and the resolved callee, e.g. `for-of -> Symbol.iterator -> next -> io`.

## Tree shaking, build defines, and unreachable code

Purity checking is a correctness pass, not a tree-shaking optimization.

Consistent with the build/tree-shaking contract:

1. parse and resolve the complete source program;
2. type/ownership/effect-check it;
3. only then perform reachability pruning/tree shaking.

A branch that is merely unreachable after optimizer folding cannot hide an impure operation inside a declared pure callable. This keeps the public purity contract stable across build configurations and avoids using DCE as an effect escape hatch.

Compiler build defines may still provide ordinary compile-time constant **values** to pure code because guest code is not reading the compiler process environment at runtime. The checked artifact/code-unit digest must bind the effective build configuration used to produce the IR.

## Runtime implementation effects

The purity contract governs guest-visible semantics, not invisible implementation bookkeeping.

The runtime/JIT may use internal counters, profiling, allocation metadata, caches, GC barriers, or sandbox fuel accounting while executing pure guest code only when that state is not exposed back to guest code as an ambient observable.

Oreslang's mandatory loop safepoints are included in this rule. A pure loop may still execute injected scheduler/fuel/cancellation safepoints. Those hooks are runtime control machinery, not guest effects, provided pure guest code cannot read their state, branch on scheduler identity/queue state, or explicitly request yield/sleep/park. External cancellation or sandbox termination may stop a pure computation, but that does not grant the computation an ambient scheduler capability.

User-visible tracing, logging, metrics, hooks, callbacks, weak-reference state, allocation counters, or profiling APIs remain effects when guest code can observe or trigger them.

## Compiler pipeline

Recommended implementation order:

1. reserve and parse `pure`;
2. record purity declaration on callable AST/symbols;
3. add internal effect metadata (closed effects plus effect variables/constraints) to every callable;
4. classify built-ins/runtime primitives per execution backend;
5. resolve/desugar implicit calls before effect collection;
6. perform intraprocedural effect collection on the resolved CFG, including receiver/parameter region footprints;
7. integrate ownership, alias, borrow, and escape facts and discharge region writes at call sites;
8. compute transitive call-graph effects/footprints to a fixed point over SCCs;
9. validate explicit `pure` contracts;
10. add higher-order/dynamic-dispatch effect constraints;
11. serialize versioned/content-bound effect summaries into compiled-unit metadata;
12. add hot-reload/reverse-dependency invalidation;
13. add foreign/native fail-closed metadata and JIT/AOT/native parity checks;
14. only after correctness is established, consume purity in optimizers.

## Required ownership-checker integration

The purity pass must not duplicate or guess aliasing facts that belong to the ownership/borrow checker.

The passes must share enough information to determine:

- allocation origin;
- ownership;
- move state;
- immutable vs mutable borrowing;
- alias count/reachability;
- shared/frozen status;
- capture origin;
- escape paths;
- return reachability;
- whether returned mutable storage is fresh/uniquely owned versus an alias of pre-existing storage;
- nested aggregate provenance;
- destructor/finalizer/drop behavior where applicable.

Purity decisions based only on syntax are insufficient. Unsupported provenance fails closed rather than being guessed.

## Required tests

### Compile-pass

- arithmetic-only pure `fnc`;
- pure `routine`;
- recursive pure `fnc`;
- local loop accumulator mutation;
- local temporary object/buffer mutation with no escape;
- read-only borrow of explicit input;
- uniquely owned moved input mutated locally;
- owned `Type mut name` parameter mutated locally;
- receiver-relative helper mutation discharged against a unique local receiver;
- immutable closure capture;
- pure helper called transitively;
- mutually recursive pure `fnc` SCC;
- deterministic guest exception;
- immutable/frozen return value;
- fresh uniquely owned mutable return value;
- `take` + local mutation + return of the consumed unique value;
- creation/return of a stateful closure with fresh private state, while the closure itself remains effect-typed as stateful;
- explicit timestamp/random-seed values transformed as ordinary data;
- deterministic explicit-state PRNG step;
- stable content hash with fixed algorithm/seed;
- identity equality over explicit input references;
- `pure nlex fnc`;
- compiler-trusted pure intrinsic.

### Compile-fail

- use `pure` as an identifier;
- `pure actor fnc`;
- `pure async fnc` / `pure async routine` in v1;
- `await` / future completion / cancellation observation in pure v1;
- stdout/stderr/stdin;
- file/network/database/socket I/O;
- environment variable read;
- cwd/process metadata read;
- wall/monotonic clock read;
- randomness;
- actor spawn;
- mailbox send/receive;
- actor/singleton/module mutable state read;
- actor/singleton/module state write;
- lock/mutable atomic/volatile/thread/scheduler query;
- runtime locale/timezone/default-charset read;
- caller stack/runtime reflection/GC/weak-reference/heap-state read;
- randomized process-seeded hash;
- raw address / pointer identity observation;
- mutable borrow parameter/receiver mutation that remains caller-visible;
- receiver-relative helper mutation against shared/borrowed/unknown provenance;
- return of an alias to pre-existing externally reachable mutable storage;
- returned closure that captures an externally reachable mutable alias;
- constructor/initializer with hidden effect;
- overloaded operator/comparison/hash with hidden effect;
- iterator/getter/indexer/coercion/interpolation with hidden effect;
- effectful defer/finally/drop/finalizer path;
- transitive impure helper;
- indirect impure callback;
- unknown callback effect;
- open dynamic dispatch with unknown targets;
- imported callable with missing effect metadata;
- Java/JNI/native call with unknown effect;
- foreign callback retention;
- unsafe external memory access.

### Regression/adversarial

- effect hidden behind several helper layers;
- effect hidden inside generic instantiation;
- effect hidden in mutually recursive SCC;
- effect hidden in closure capture;
- effect hidden in virtual dispatch;
- effect hidden across package boundary;
- effect metadata version mismatch;
- alias created before a `take`;
- region-write summary incorrectly reused across unique versus borrowed receivers;
- receiver/parameter provenance lost through generic instantiation or method extraction;
- borrowed alias laundered through tuple/array/object/constructor field;
- nested closure capture laundering;
- mutable state frozen before explicit input transfer;
- returned fresh mutable graph accepted while returned alias of pre-existing graph is rejected;
- class `copy()` that is effectful or aliases source storage;
- struct copy whose nested class copy is impure;
- exception/raise/panic path that performs I/O;
- deferred cleanup that performs I/O;
- finally block that performs I/O;
- effectful constructor reachable only through an implicit conversion;
- effectful iterator hidden behind `for ... of`;
- process-randomized hash hidden behind map/set iteration;
- stale/forged/mismatched effect metadata;
- hot reload that attempts to weaken a pure dependency/dispatch slot;
- JIT vs AOT vs native intrinsic classification drift;
- explicit impure callback passed through but not invoked remains allowed;
- callback invoked through an effect-polymorphic generic propagates its effect;
- unreachable source branch containing forbidden effects remains rejected before optimizer/tree-shaking removal.

## Optimizer contract

`pure` is useful compiler information, but it is only one prerequisite.

A compiler may not infer any of the following from `pure` alone:

- call elimination when the result is unused, because the call may throw, raise, panic, or diverge;
- memoization, because identity-bearing or freshly mutable allocations may be returned;
- common-subexpression elimination across identity-sensitive results;
- arbitrary reordering, because throw/raise/panic/divergence ordering may change;
- speculative duplication, because cost/divergence/resource behavior may change;
- automatic parallelization when scheduling, cancellation, allocation pressure, or exceptional ordering would become guest-observable;
- constant-time or side-channel safety.

Stronger optimizations require additional proven facts such as `nothrow`, `noraises`, `nopanic`, termination, value semantics, identity irrelevance, and cost/resource constraints.

## Compatibility with a future effect system

The source language should remain simple in v1:

```ores
pub pure fnc ...
pub pure routine ...
```

Internally, however, all callables carry effect summaries. This permits future explicit capabilities/effects such as I/O, actor operations, clock, or environment access without redefining `pure`.

The invariant to preserve is:

> If an Oreslang `fnc` or `routine` successfully compiles with `pure`, no execution path—direct, transitive, recursive, generic, dynamically dispatched, captured, imported, actor-mediated, Java-mediated, JNI-mediated, or native-mediated—may observe forbidden ambient state or produce a forbidden externally observable effect.
