# Reserved `trap` keyword and error-control contract

Status: **draft design / compiler contract**

This document specifies the proposed reserved `trap` modifier for Oreslang callables and the control-flow distinction between `throw`, `raise`, and `panic`.

The central guarantee is:

> A successfully compiled `trap` callable never lets an ordinary `throw` cross its call boundary. The compiler converts the uncaught throw into an explicit two-slot result. `raise` and `panic` are deliberately different control effects: they bypass `trap` and unwind to the nearest `recover` boundary.

This is a language and ABI contract, not merely parser sugar around a handwritten `try/catch`.

## Goals

- reserve `trap` as a first-class callable modifier;
- guarantee that uncaught `throw` cannot escape a `trap` invocation;
- make success and trapped failure explicit in the call-expression type;
- require callers to consume the trap result rather than silently discard errors;
- preserve a deliberate escape mechanism for exceptional control flow through `raise` and `panic`;
- make `throw`, `raise`, `panic`, `trap`, and `recover` distinct compiler effects so optimization, inlining, callbacks, interfaces, foreign code, actors, and AOT/JIT lowering cannot erase the boundary;
- integrate with Oreslang's existing one-return-value rule: tuples/arrays/records remain ordinary single values.

## Syntax

`trap` is globally reserved.

Canonical declarations:

```ores
pub trap fnc load_user(UserId id): User {
  return db_load(id);
}

pub trap routine initialize(): void {
  perform_initialization();
  return;
}
```

For instance methods, which intentionally omit `fnc`/`routine`:

```ores
define class Loader as

  pub trap load(UserId id): User {
    return self.lookup(id);
  }

end
```

Canonical modifier ordering:

```text
[visibility] [pure] trap fnc ...
[visibility] [pure] trap routine ...
[visibility] [pure] trap method(...)
```

The parser may accept equivalent modifier ordering during migration, but formatting and diagnostics should normalize to the canonical order.

## Declared return type versus call-expression type

The declared return type is the **success value**.

Example:

```ores
pub trap fnc load_user(UserId id): User {
  ...
}
```

The body type-checks as though successful `return` statements return `User`.

The call expression has a compiler-owned nominal result type:

```text
TrapResult<User>
```

Its mandatory two-slot destructuring view is:

```text
[
  Option<[User]>,
  Option<TrapError>
]
```

This distinction is deliberate. A raw structural tuple of two `Option` values would permit user code to fabricate invalid trap states. `TrapResult<T>` is compiler/runtime-owned and cannot be constructed with an ordinary tuple/list literal, object literal, unchecked cast, or user-defined constructor.

Oreslang still has one return value. The `[User]` in the success projection is the normalized success bundle used by trap destructuring, not language-level multiple return values.

For `void`:

```text
TrapResult<void>
  destructures as
[
  Option<[]>,
  Option<TrapError>
]
```

A successful void trap exposes `Some([])` in the success slot.

## Trap-result invariant

Every `TrapResult<T>` has exactly one populated side.

Success:

```text
[Some([value]), None]
```

Failure:

```text
[None, Some(error)]
```

For `void` success:

```text
[Some([]), None]
```

These states are unrepresentable as `TrapResult<T>`:

```text
[None, None]
[Some(...), Some(...)]
```

The runtime should store the value as a tagged success/error representation and synthesize the two Option projections for destructuring. Backends may choose a compact tagged ABI, but they may not expose a writable pair of independent option fields.

A manually created ordinary tuple that happens to have the same visible element types is **not** a `TrapResult<T>` and is not implicitly assignable to one.

## Caller destructuring

The ordinary form keeps the success bundle intact:

```ores
const [results, err] = load_user(id);
```

with:

```text
results : Option<[User]>
err     : Option<TrapError>
```

Oreslang also supports Option-aware nested trap destructuring for the common single-success-value case:

```ores
[const [user], const err] = load_user(id);
```

with:

```text
user : Option<User>
err  : Option<TrapError>
```

On success:

```text
user = Some(User)
err  = None
```

On trapped failure:

```text
user = None
err  = Some(TrapError)
```

This syntax does **not** mean the function has multiple return values. It is compiler-supported destructuring/lifting of the optional normalized success bundle.

The parser/AST must therefore support a recursive destructuring pattern for this form. The current flat `DestructureStmt(List<DestructureBinding>)` representation is not sufficient; trap implementation must introduce a recursive pattern node rather than special-casing token text after parsing.

If the declared return type itself is a tuple, that tuple remains one success value. The trap layer does not flatten the user's returned tuple:

```ores
pub trap fnc pair(): [int, String] {
  return [7, "seven"];
}

[const [pair_value], const err] = pair();
// pair_value : Option<[int, String]>
```

There is no ambiguity between the compiler-owned outer success bundle and a tuple returned by user code.

### Ownership of trap projections

Trap destructuring obeys the ordinary ownership model.

- `TrapResult<T>` owns either the success payload or the `TrapError`;
- destructuring consumes/moves the selected payload exactly once unless the surrounding operation is an explicit borrow;
- nested Option-aware destructuring does not implicitly copy a class, closure, buffer, actor capability, or other non-`Copy` value;
- after a consuming destructure, the original `TrapResult<T>` cannot be reused;
- borrowing a trap result may expose borrowed projections, but a borrow cannot outlive the result owner;
- the absent branch carries no hidden alias to the present payload.

For a non-`Copy` `User`, this:

```ores
[const [user], const err] = load_user(id);
```

moves the success `User` into the resulting `Option<User>` on success. It does not clone the user object.

## `throw`: the trappable error channel

`throw` is the ordinary exception/error control effect.

Inside a `trap` invocation, any `throw` that is not handled by a nearer explicit `try/catch` is absorbed by the nearest dynamic trap boundary.

Example:

```ores
pub trap fnc foo(): String {
  throw new Error("bad");
}
```

Conceptual lowering uses an **internal throw-only boundary**, not a source-level broad `try/catch`:

```text
foo$trap_lowered() : TrapResult<String> {
  trap_boundary {
    execute foo body
  }
  on ThrowSignal(err) {
    return TrapResult.error(TrapError.from(err))
  }
}
```

The lowering must not be implemented as `catch (RuntimeException)`, `catch (Throwable)`, or another host-language catch that could accidentally intercept `RaiseSignal`, `PanicSignal`, cancellation, termination, or VM-fatal failures.

A successful return:

```ores
return "ok";
```

conceptually becomes:

```text
[Some(["ok"]), None]
```

A `throw` in a nested non-trap helper also belongs to the **dynamic execution extent** of the trap boundary:

```ores
fnc helper(): User {
  throw new Error("missing");
}

pub trap fnc load(): User {
  return helper();
}
```

The caller of `load()` receives the error side. The exception does not escape `load`.

This is dynamic, not merely transitive/static. A synchronous callback invoked while the trap frame is active is covered. Work that is detached and executed later on another task/actor/domain does **not** inherit the old trap frame. It follows its own task/actor supervision rules unless it establishes its own trap boundary.

If no dynamic `trap` boundary exists, `throw` retains normal Oreslang `try/catch` / unhandled-exception behavior.

## `raise`: deliberate trap escape

`raise` is a distinct reserved control-flow keyword/effect.

A `raise`:

- is **not** converted into `TrapError`;
- is **not** absorbed by `trap`;
- unwinds across nested `trap` callables;
- continues until the nearest matching `recover` boundary;
- runs required `defer` / `finally` cleanup while unwinding;
- remains distinguishable from `panic`.

Example:

```ores
pub trap fnc parse_config(): Config {
  if unrecoverable_configuration_state {
    raise new ConfigAbort("cannot continue");
  }

  if malformed_user_value {
    throw new ParseError("bad value");
  }

  return config;
}
```

The malformed-value `throw` becomes:

```text
[None, Some(TrapError(...))]
```

The `raise` crosses the trap boundary and keeps unwinding toward `recover`.

This gives library/application code an explicit way to say:

> This failure must not be normalized into the local trap result.

## `panic`: invariant/runtime escape

`panic` is also a distinct reserved control-flow keyword/effect.

Like `raise`, `panic` bypasses `trap` and unwinds to the nearest `recover` boundary.

It exists for invariant violations, impossible states, compiler/runtime assertions exposed to guest semantics, and failures that should not be mistaken for an ordinary trappable application error.

Example:

```ores
pub trap fnc decode(Packet p): Message {
  if compiler_proven_impossible_state_became_reachable {
    panic new InvariantError("decoder invariant broken");
  }

  ...
}
```

A panic must never silently become the ordinary `err` slot of the trap result.

`raise` and `panic` may share an internal unwinding mechanism, but their kinds must remain distinct in diagnostics, metadata, tracing, and `recover` handling.

## `recover` boundary

`recover` is the dynamic boundary that can intercept `raise` and `panic`.

The trap design requires the compiler IR to represent a recover boundary explicitly so that optimizer/inlining transformations cannot accidentally turn a non-trappable unwind into a trapped `throw`.

The exact final surface syntax for typed/selective recovery may evolve independently, but these semantics are fixed:

1. `throw` does not skip a trap boundary.
2. `raise` skips every trap boundary until recover.
3. `panic` skips every trap boundary until recover.
4. `defer` / `finally` execute according to normal unwinding rules.
5. If no recover boundary exists, an unhandled `raise` or `panic` terminates the current top-level execution domain according to runtime policy (process, actor, isolate, or untrusted actor), rather than being fabricated into a trap result.

A future/selective `recover` syntax may choose to recover only `raise`, only `panic`, or specific payload types. The semantic distinction must already exist in compiler metadata.

A recover handler is **outside** the boundary it handles. If the handler executes another `raise` or `panic`, that new signal cannot be caught again by the same recover frame; it continues to the next matching outer recover boundary. This prevents self-recursive recovery loops.

Recovery may explicitly convert a signal into ordinary control flow, including returning a value or deliberately issuing a new ordinary `throw`. That conversion is explicit; the runtime never silently downgrades `raise`/`panic` into `throw`.

## Interaction with explicit `try/catch`

Ordinary `try/catch` handles the `throw` channel.

It does not implicitly downgrade `raise` or `panic` into `throw`.

Therefore:

```text
try/catch  -> throw
trap       -> uncaught throw at callable boundary
recover    -> raise / panic
```

This separation prevents a broad local catch from accidentally swallowing a deliberate trap escape.

A future explicit syntax may allow a recover block to classify or rethrow a raised/panicked signal.

## Cleanup precedence: `defer` / `finally`

Cleanup must not accidentally weaken a stronger in-flight control signal.

The runtime uses this precedence when cleanup itself fails:

```text
fatal termination > panic > raise > throw > return/success
```

Rules:

- a cleanup `throw` cannot replace an already-unwinding `raise` or `panic`; it is attached as a suppressed/secondary failure;
- a cleanup `raise` may supersede an in-flight `throw`, but not an in-flight `panic`;
- a cleanup `panic` supersedes an in-flight `throw` or `raise`;
- fatal runtime termination/cancellation cannot be suppressed by user cleanup;
- when two failures have the same precedence, preserve the original in-flight failure as primary and attach the later cleanup failure as suppressed;
- all applicable cleanup still runs in the required LIFO/order semantics unless the execution domain is no longer capable of safely executing guest code.

This makes trap bypass monotonic: once execution is unwinding as `raise` or `panic`, an incidental cleanup `throw` cannot turn it back into something an outer `trap` consumes.

## Cancellation, budget exhaustion, and forced termination

Scheduler/runtime control is **not** the ordinary throw channel.

The following must bypass `try/catch` and `trap` and cannot be converted into `TrapError` merely because guest code surrounds work with a trap:

- actor/task cancellation used to stop execution;
- untrusted-actor fuel exhaustion;
- untrusted-actor wall-clock lifetime expiration;
- hard memory-budget enforcement;
- isolate revocation/termination;
- supervisor-forced shutdown;
- process/VM fatal termination.

Application-level cancellation represented deliberately as a normal guest `throw` is still trappable. Runtime-enforced cancellation is a separate internal control effect (for example `CancelSignal` / `TerminateSignal`) and must remain non-catchable by ordinary guest code unless a future capability explicitly permits cooperative cancellation handling.

This rule is required so untrusted or buggy code cannot defeat resource limits with:

```ores
pub trap fnc keep_running_forever(): void {
  // runtime cancellation must not become err=None/Some and then be ignored
}
```

## Error type

The trap error should be structured, not reduced to a string:

```ores
struct TrapError {
  kind: TrapErrorKind,
  message: String,
  cause: Option<TrapError>,
  stack: Option<StackTrace>,
  source: Option<SourceLocation>
}
```

Initial trappable categories may include:

```text
Thrown
Assertion
Arithmetic
Bounds
ForeignException
Io
Runtime
```

Runtime-enforced cancellation/termination is intentionally absent. `Raised` and `Panic` are also **not** ordinary `TrapErrorKind` values because they do not travel through the trap error slot.

`TrapError` must be a guest-safe immutable value. It must not retain a raw Java `Throwable`, native pointer, file descriptor, actor capability, mutable host object, or other ambient authority. Host causes are normalized/redacted into guest-safe metadata before crossing the trap boundary. Cause/suppressed chains must be cycle-safe and bounded by runtime policy.

The stack/source location is captured at the original throw/failure point, before trap conversion, so the error does not misleadingly appear to originate at the trap boundary itself.

## Returned Error objects remain data

Only control flow determines whether a value is trapped.

Example:

```ores
pub trap fnc inspect(): Error {
  return new Error("this is data");
}
```

Result:

```text
[Some([Error("this is data")]), None]
```

Returning an `Error` value is not equivalent to `throw`.

## Trap results may not be silently discarded

A trap call used as a bare expression is a compile error:

```ores
load_user(id); // ERROR: trap result discarded
```

The caller must bind/consume the result:

```ores
const [results, err] = load_user(id);
```

or:

```ores
[const [user], const err] = load_user(id);
```

A future explicit discard operator may be introduced, but silent discard is forbidden.

Using the existing destructuring discard token `_` for the **error slot** is also rejected in v1:

```ores
[const results, _] = load_user(id); // ERROR in v1
```

The language may later add an unmistakably explicit `discard trap_error`/annotation if intentional loss is needed, but ordinary sequence destructuring must not become a loophole around mandatory error acknowledgement.

## Async/await and futures

`trap` must have defined asynchronous semantics because Oreslang already has `async` / `await`.

For an async trap callable:

```ores
pub async trap fnc fetch(): Payload {
  ...
}
```

the call expression type is:

```text
Future<TrapResult<Payload>>
```

The trap boundary spans the complete asynchronous computation, including code resumed after suspension.

- an ordinary `throw` before or after an `await` completes the future successfully with `TrapResult.error(...)`;
- a `raise`/`panic` completes the future with its distinct non-trappable control signal;
- awaiting that future re-emits the same `raise`/`panic` at the await site so a surrounding recover boundary can handle it;
- a detached/unawaited task has no dynamic caller recover frame, so unrecovered `raise`/`panic` is routed to task supervision rather than fabricated into `TrapError`;
- runtime cancellation of the future remains the non-trappable cancellation channel described above;
- callbacks/tasks explicitly detached from the async computation do not inherit its trap/recover frames.

This preserves the same semantics across suspension instead of making `trap` only cover the synchronous prefix of an async function.

Generator/yield + `trap` is rejected until generator suspension semantics receive an equally explicit contract.

## Callable types and ABI

`trap` is part of the callable contract.

These are not the same callable type:

```text
fnc(): User
trap fnc(): User
```

The latter has an explicit trap-result call ABI and a no-`throw`-escape guarantee.

The compiler must preserve this distinction through:

- function values;
- callbacks;
- generics;
- closures;
- interface slots;
- trait slots;
- virtual dispatch;
- imported/compiled package metadata;
- Java/JNI/native interop;
- actor messages/callbacks;
- JIT and AOT lowering.

Unknown metadata fails closed when an exact trap guarantee is required.

## Overrides and interfaces

A trap contract cannot be erased by implementation.

If an interface/base slot requires a trap callable, an override/implementation must preserve the trap guarantee.

An implementation may eventually be allowed to strengthen a non-trap slot into a trap slot only if callable variance/ABI rules make the conversion explicit and safe. V1 should require exact trap-effect compatibility for overrides and interface implementation.

## `pure trap`

`pure` and `trap` are orthogonal and may be combined:

```ores
pub pure trap fnc divide(int a, int b): int {
  return a / b;
}
```

A deterministic arithmetic failure may enter the trappable `throw` channel without making the computation impure.

Likewise, `raise` and `panic` are control effects rather than automatically external side effects. A callable may remain pure if constructing and propagating the signal performs no forbidden effect.

Therefore `pure` does not imply `nothrow`, `noraises`, or `nopanic`.

The internal effect system must track these properties independently.

## Internal effect model

The compiler effect model must distinguish at least:

| Effect | Meaning | Absorbed by `trap` |
| --- | --- | --- |
| `throw` | ordinary guest exception/error | yes, if uncaught before boundary |
| `raise` | deliberate non-trappable exceptional escape | no |
| `panic` | invariant/runtime non-trappable escape | no |
| `diverge` | possible nontermination | no |
| other effects | I/O, mutation, actor, scheduler, FFI, etc. | unrelated |

A single undifferentiated "exception" bit is insufficient.

Transitive effect inference must preserve whether a call may throw, raise, or panic.

Example:

```ores
fnc inner(): User {
  raise new Abort("stop");
}

pub trap fnc outer(): User {
  return inner();
}
```

`outer` has no escaping `throw` after trap lowering, but it **does** retain a transitive `raise` effect.

## Compiler IR requirements

Do not immediately desugar `trap` into arbitrary source-level `try/catch` and then lose its identity.

Recommended IR nodes/effects:

```text
TrapBoundary
ThrowSignal
RaiseSignal
PanicSignal
CancelSignal
TerminateSignal
RecoverBoundary
```

These are language/runtime control signals, not an invitation to model every case as an arbitrary host exception class.

The trap boundary must survive long enough for:

- effect checking;
- control-flow analysis;
- cleanup-edge construction;
- inlining;
- exception-table generation;
- actor/isolate boundary lowering;
- JVM/Graal lowering;
- native/AOT lowering.

After semantics are fixed, a backend may implement the trap boundary with exception tables, tagged returns, CPS, setjmp-like native machinery, or another representation, provided observable behavior is identical.

The current interpreter already has an internal `OresPanic` used by operations such as `Option.unwrap` / `Result.unwrap`, and current `try/catch` deliberately rethrows it. Implementation should preserve that useful behavior while replacing the ad-hoc Java-class distinction with explicit first-class panic/control metadata. In particular, ordinary Oreslang `try/catch` must never rely on a broad `RuntimeException` catch once these channels are implemented.

Tail-call optimization must also preserve boundaries. A tail call from a trap callable to a potentially-throwing callee may not erase the caller's `TrapBoundary`. A recursive trap implementation may optimize the recursion only when the observable nearest trap/recover behavior and stack/error metadata contract remain unchanged.

## Optimizer rules

Inlining may not erase or move a trap/recover boundary in a way that changes which signal catches which failure.

In particular:

- a `throw` from an inlined callee must still be absorbed by the same nearest trap boundary;
- a `raise` from an inlined callee must still bypass trap;
- a `panic` from an inlined callee must still bypass trap;
- cleanup ordering must remain unchanged;
- dead-code elimination may not assume `trap` means the call cannot `raise`, `panic`, or diverge;
- pure-call optimizations must continue to respect throw/raise/panic/divergence ordering.

## Java/JNI/native/host interop

Foreign failures require explicit classification.

Default fail-closed behavior:

- only an explicitly mapped, expected foreign exception class/category may enter the ordinary `throw` channel and therefore be trapped;
- a foreign fatal/invariant condition must not automatically be mislabeled as a trappable throw;
- unknown host failures are not silently swallowed;
- Java `Error`-class VM failures (for example OOM/stack/VM linkage failures) are not blanket-converted to `TrapError`;
- thread interruption/cancellation used by the scheduler is not blanket-converted to `TrapError`;
- process-kill, VM corruption, hardware failure, or OS termination cannot be promised recoverable merely because source contains `trap`.

Compiler/runtime-owned adapters must classify foreign failures into the correct Oreslang control effect before they enter guest control flow.

The runtime must use dedicated internal carrier types/tags for `ThrowSignal`, `RaiseSignal`, `PanicSignal`, cancellation, and termination. No generic `catch (RuntimeException)` / `catch (Throwable)` is allowed to define language semantics.

Raw native crashes must never be described as ordinary `TrapError` unless the runtime actually isolated and converted them safely.

## Actors, isolates, and untrusted actors

The same semantic channels apply inside actor execution.

A `throw` leaving a trap actor helper/entrypoint becomes its trap error result.

A `raise` or `panic` bypasses trap and unwinds to the nearest recover boundary **within that execution domain**.

A recover boundary never implicitly spans an actor, isolate, process, or detached-task boundary. Those boundaries are supervision/transport boundaries, not shared call stacks. An unrecovered signal from a child domain is converted into the domain's typed exit/supervision outcome; the original mutable error object/capability is not smuggled into the parent. Safe immutable metadata may be reported according to policy.

If unrecovered:

- a normal/shared actor follows actor-supervision failure policy;
- an isolated actor terminates its isolated execution domain safely;
- an untrusted actor is terminated and its confined memory/capabilities are reclaimed according to sandbox policy.

The runtime must not forge a successful trap result after an unrecovered raise/panic merely to keep the caller alive. Actor supervision may separately report actor termination as a transport/supervision outcome.

## Diagnostics

Proposed stable diagnostic families:

- `E-TRAP-001`: invalid `trap` modifier placement;
- `E-TRAP-002`: trap result discarded;
- `E-TRAP-003`: invalid trap-result destructuring;
- `E-TRAP-004`: trap callable override/interface mismatch;
- `E-TRAP-005`: incompatible trap callable value/callback assignment;
- `E-TRAP-006`: compiler cannot classify foreign failure semantics;
- `E-TRAP-007`: invalid trap result state exposed by compiler/runtime lowering;
- `E-TRAP-008`: illegal attempt to treat `raise`/`panic` as ordinary trappable throw;
- `E-TRAP-009`: missing/invalid recover metadata for a construct that requires it;
- `E-TRAP-010`: attempt to forge/coerce a raw tuple into `TrapResult<T>`;
- `E-TRAP-011`: attempt to discard the trap error slot;
- `E-TRAP-012`: unsupported trap suspension form (for example generator/yield before specified);
- `E-TRAP-013`: runtime cancellation/termination incorrectly classified as trappable.

Diagnostics for propagated effects should identify the shortest useful path.

Example:

```text
error[E-TRAP-008]: 'raise' bypasses trap and cannot be converted to TrapError

  load_user
  └── calls validate_session
      └── calls abort_session
          └── effect: raise SessionAbort
```

## Required compiler work

1. reserve `trap`, `raise`, `panic`, and the recover boundary keyword/symbol;
2. parse `trap` on `fnc`, `routine`, instance methods, and compatible interface/abstract slots;
3. record trap contract in callable AST/symbol/type metadata;
4. split compiler control effects into `throw`, `raise`, `panic`, cancellation, termination, and divergence;
5. represent `TrapBoundary` and `RecoverBoundary` explicitly in IR;
6. introduce nominal compiler-owned `TrapResult<T>` with a read-only two-slot destructuring projection;
7. replace/extend flat destructuring AST with recursive destructuring patterns and implement Option-aware nested trap destructuring;
8. reject silently discarded trap results and error-slot `_` discards;
9. lower ordinary successful returns into the success variant;
10. lower uncaught `throw` at the trap boundary into a guest-safe immutable `TrapError`;
11. ensure `raise`, `panic`, cancellation, and termination bypass trap;
12. preserve `defer` / `finally` cleanup and enforce monotonic cleanup precedence on every unwind path;
13. define async `Future<TrapResult<T>>` lowering across suspension/await;
14. enforce callable/override/interface/callback trap compatibility;
15. serialize trap and throw/raise/panic/cancellation effect metadata across compiled units;
16. classify Java/JNI/native failure mappings explicitly and remove broad host-exception catches from language semantics;
17. harden optimizer/inliner/tail-call/AOT/JIT passes against boundary erasure;
18. integrate unrecovered raise/panic/cancellation with task/actor/isolate/untrusted-actor termination/supervision policy.

## Required tests

### Compile-pass / runtime-pass

- `trap fnc` success;
- `trap routine` success;
- trap instance method success;
- void success produces `Some([])`;
- one value produces `Some([value])`;
- ordinary `throw` becomes `TrapError`;
- throw from nested non-trap helper is trapped;
- inner explicit `try/catch` may handle throw before trap;
- returned `Error` object remains ordinary success data;
- `[const results, const err]` destructuring;
- `[const [result], const err]` Option-aware destructuring;
- `pure trap fnc`;
- trap callable through function value with compatible metadata;
- interface/override preserving trap contract;
- deterministic mapped foreign exception becomes trapped throw;
- defer/finally executes before trapped throw result is produced;
- manually constructed two-Option tuple cannot masquerade as `TrapResult<T>`;
- trap error stack points to the original throw site;
- async trap catches a throw after an await;
- awaited async raise/panic re-emits the same signal kind at the await site.

### Raise/panic escape

- `raise` directly inside trap bypasses trap;
- `panic` directly inside trap bypasses trap;
- raise from nested helper bypasses outer trap;
- panic from nested helper bypasses outer trap;
- raise/panic cross several nested trap callables;
- nearest recover boundary receives the correct signal;
- raise remains distinguishable from panic;
- defer/finally runs while raise/panic unwinds;
- optimizer/inlining does not change nearest trap/recover boundary;
- JIT and AOT behavior match;
- actor/isolate execution does not rewrite unrecovered raise/panic into the trap error slot;
- raise in a recover handler skips that same recover frame and reaches the next outer matching frame;
- cleanup throw cannot downgrade an in-flight raise/panic;
- cleanup raise can supersede throw but not panic;
- cleanup panic supersedes throw/raise;
- synchronous callback throw is caught by the active trap, while a later detached callback throw is not;
- consuming trap destructuring moves a non-`Copy` success payload exactly once.

### Compile-fail

- use `trap` as an identifier;
- invalid modifier target;
- discard trap result;
- destructure trap result with invalid arity/shape;
- assign trap callable to incompatible non-trap callable type without explicit supported adaptation;
- implement trap interface slot with incompatible non-trap callable;
- attempt compiler lowering that routes raise/panic into `TrapError`;
- foreign call whose failure classification is required but unknown;
- `[const results, _] = trap_call()` error-slot discard;
- raw tuple -> `TrapResult<T>` coercion/assignment;
- trap + generator/yield until suspension semantics are specified.

### Adversarial

- throw hidden behind recursive `fnc` SCC;
- raise hidden behind recursive `fnc` SCC;
- panic hidden behind callback;
- throw/raise/panic through generic callable parameters;
- dynamic dispatch with mixed control effects;
- imported compiled unit with stale/missing effect metadata;
- exception from Java callback invoked under trap;
- nested trap inside recover;
- recover inside trap;
- trap inside trap;
- finally block that itself throws;
- finally block that raises;
- finally block that panics;
- untrusted actor raise/panic during HTTP response ownership;
- AOT exception-table optimization preserves exact boundary semantics;
- tail-call optimization cannot erase a trap boundary;
- forced untrusted-actor cancellation/fuel exhaustion bypasses trap;
- OOM/VM-fatal host failure is not converted to `TrapError`;
- cause/suppressed chains are bounded and cycle-safe;
- cross-actor/isolate signal handling reports supervision metadata without leaking raw host/guest capabilities;
- trap result cannot be reused after consuming destructure of a non-`Copy` payload;
- borrowed trap projections cannot outlive the owning `TrapResult<T>`.

## Implementation staging

A safe implementation sequence is:

1. documentation + reserved-word/parser tests;
2. AST/callable metadata;
3. effect split (`throw` / `raise` / `panic`);
4. explicit trap/recover IR boundaries;
5. trap call-result typing;
6. destructuring/lifting rules;
7. throw-to-result lowering;
8. raise/panic unwind lowering;
9. cleanup-edge hardening;
10. callable/interface/callback compatibility;
11. foreign failure classification;
12. actor/isolate integration;
13. optimizer/JIT/AOT hardening;
14. full adversarial matrix.

Do not enable the surface keyword while any backend can still route `raise` or `panic` through the ordinary trap error slot.

## Non-goals

This contract does not promise that `trap` can recover from:

- OS `SIGKILL`;
- process termination;
- hardware faults;
- corrupted native memory;
- VM failure;
- host failures that prevent the runtime from executing the trap boundary.

The source-level guarantee is specifically about Oreslang's classified `throw` channel.

`raise` and `panic` deliberately escape that guarantee.

## Core invariant

The implementation must preserve this distinction everywhere:

```text
throw  -> nearest explicit catch, otherwise nearest trap -> TrapError result
raise  -> bypass catch/trap -> nearest recover
panic  -> bypass catch/trap -> nearest recover
```

No parser rewrite, optimizer pass, foreign adapter, actor boundary, JIT optimization, or native backend may collapse these three channels into one.
