# Oreslang language design (v0.6)

Oreslang is a statically typed guest language for GraalVM/Truffle. Named types are nominal by default; structural compatibility is explicit at selected boundaries. Its core invariants are explicit mutation, actor-owned mutable heaps, message-only actor communication, read-only sharing, and a stricter isolate profile for untrusted FaaS execution.

## Files, modules, and imports

A source file may contain multiple named modules. A module is a namespace: exported members are accessed through the module name, such as `math.add(1, 2)`.

```ores
define module math
  pub fnc add(int a, int b) => int {
    return a + b;
  }
end

define module app
  pub fnc main() => void {
    val answer = math.add(40, 2);
    stdio.println(answer);
    return;
  }
end
```

Imports are explicit about what kind of symbol is entering the compilation unit:

```ores
import module Bar from "../xyz";
import actor Foo from "../actors";
import actor (Foo as F) from "../actors";
import actor {Worker: W, Supervisor as S} from "../actors";
import class {Config as ExternalConfig} from "../types";
import fnc {solve as linear_solve} from "../solver";
import fnc * as funcs from "../xyz";
import * as x from "./xyz";
```

There are no JavaScript-style ordinary default exports. Named imports stay
reviewable, and wildcard imports always require a namespace alias so an
unbounded set of declarations is never silently injected into local scope.
Both `as` and `:` are accepted inside selected-import lists as alias
spellings.

Hot-loaded/plugin code has a deliberately separate single-entry ABI:

```ores
define actor Worker as
  pub receive(m: Job): void {
    // one mailbox turn
    return;
  }
end

export entry Worker;
```

A loader, or another compilation unit acting as a loader adapter, may resolve it
without knowing the declaration's source name:

```ores
import entry as Plugin from "./worker";
```

A code unit has zero or one `export entry`. This is an explicit hot-load
entrypoint, **not** a default export: it does not change normal named-import
resolution and cannot be used to create multiple competing defaults.

Import paths are part of the AST/compiler contract; filesystem/package
resolution is a host build/bundling concern so strict isolates do not gain
ambient filesystem access merely by using `import`.

### Circular imports and explicit initialization

Import cycles are legal. Oreslang does not reject a program merely because its
file/module graph contains a cycle such as `a.ores -> b.ores -> a.ores`.

The loader uses an inert staged link lifecycle:

1. parse and statically validate the complete reachable source graph;
2. resolve/link imports for every code unit;
3. compute strongly connected components (SCCs) of the import graph;
4. for each dependency-first SCC, verify that **all** members are linked;
5. invoke only the application/program/plugin entry explicitly selected by the host.

There is currently **no implicit file/module/class `init` hook**. Importing or
linking a code unit never executes user code merely because a function is named
`init`. Initialization is ordinary explicit Oreslang code called by the
application, supervisor, or selected entry point. A function named `init` is an
ordinary callable and may have ordinary parameters/results subject to the normal
function rules.

This keeps import/linking deterministic, tree-shake friendly, and AOT friendly
while still guaranteeing that cyclic peers are linked before cross-unit use.

## Module interfaces / OCaml-style module signatures

Interfaces can describe the structural public shape required of a module. A module opts into checking with `@AdheresTo(...)`:

```ores
define module contracts
  define interface MathApi
    fnc add(int a, int b) => int;
    String name;
  end
end

@AdheresTo(contracts.MathApi)
define module math
  pub fnc add(int a, int b) => int { return a + b; }
  pub val String name = "math";
end
```

Only exported (`pub`) module members satisfy an adherence contract. `@AdheresTo(A, B)` may name more than one interface.

## Functions and returns

Functions use `fnc` and are private by default. `pub` exports them. Return statements are always explicit; a non-`void` function must return on every control-flow path.

```ores
fnc add(int a, int b) => int {
  return a + b;
}

@Ret<int>
fnc answer() {
  return 42;
}
```

`@Ret<T>` and `=> T` are equivalent. If both are present they must agree. A function returns exactly one value; multiple logical values are represented by a tuple, array, object, class value, or another aggregate.

Return types may be unions, homogeneous arrays, finite tuple types, or structural record types:

```ores
type intOrBoolOrString = bool | int | string;

fnc mixed() => Array<type intOrBoolOrString> {
  return [3, true, "yes"];
}

fnc fixed() => [int, bool, string] {
  return [3, true, "yes"];
}

fnc named() => {foo: int, bar: string} {
  return obj{foo: 5, bar: "x"};
}
```

The `type` marker inside `Array<type intOrBoolOrString>` is accepted as an explicit alias marker; `Array<intOrBoolOrString>` is equivalent. A finite tuple type records exact arity and the type of each position even though the interpreter represents the value with a JVM `List`. A record type names required members; extra members remain compatible with the structural type system.

## Bindings

Every local binding is declared as exactly one of:

- `const`: compile-time constant; cannot be reassigned.
- `val`: runtime single-assignment binding; cannot be reassigned.
- `let`: mutable binding and the only local binding kind that may be reassigned.

```ores
const max = 10;
val request_id = process.context_id;
let retries = 0;
retries = retries + 1;
```

Destructuring carries mutability per element. A binding kind propagates through later unqualified names until another explicit binding kind appears. A binding kind may also prefix the whole pattern.

```ores
[const number, flag, answer] = fixed();   // all const
const [x, y, z] = fixed();               // all const
[const head, let middle, tail] = fixed(); // head const; middle/tail let

const {foo, bar} = named();
// Equivalent per-item spelling, shown in a separate scope:
// {const foo, const bar} = named();
```

A bare `_` is a sequence discard pattern: it consumes that array/tuple position without declaring a variable, so it can be repeated in the same sequence pattern or reused by later destructures. Object patterns do not accept bare `_` because object destructuring is key-based rather than positional.

```ores
[const code, _, let body] = (200, "ignored", "ok");
[const next, _, let tail] = (201, "ignored again", "done");
[_, _, const final] = (1, 2, 3);
```

`_` is not readable after the destructure because no lexical binding is created for it. A destructured `const` is an immutable runtime binding; unlike a standalone `const x = ...` declaration, the aggregate being destructured does not need to be a compile-time constant.

Sequence destructuring requires a returned tuple or array/list. Finite tuples are checked for exact arity and per-position type. Object destructuring requires a record/map-like value and every requested key must exist. If the returned type is a union, destructuring is allowed only when every union alternative supports the requested pattern; each extracted binding receives the union of the corresponding alternative member types. Function-parameter destructuring is intentionally not part of this syntax yet.

## Classes, receivers, multiple inheritance, and interfaces

Class headers use `as` as the required body delimiter. The canonical form is `define class Name as ... end`; when `extends` or `implements` are present, `as` follows the complete class header.

Methods omit `fnc`. Instance methods always have an implicit receiver named `self`.

```ores
define class Box<T> as
  val T value;

  @Ret<self>
  identity() {
    return self;
  }
end
```

The explicit receiver form remains available:

```ores
find(self Box)(int key) => self {
  return self;
}
```

The receiver variable name is always `self`.

A class may list multiple parent classes and multiple interfaces:

```ores
define class Combined extends Cacheable, Serializable implements HasId, Named as
end
```

Parent order is significant and is the deterministic v0.2 method-resolution order after child methods: the first declared parent is searched before the next parent. The static checker rejects inheritance cycles and incompatible inherited member shapes. Child members may override inherited members only with compatible types.

`Object` and `List` are extensible base classes:

```ores
define class RecordBag extends Object as
end

define class Names extends List as
end
```

Inline object and array literals are values, not classes, and cannot be inherited from.

## Inline values

Structural inline object:

```ores
val user = obj{name: "Ada", age: 37};
stdio.println(user.name);
```

Inline array:

```ores
val values = arr[10, 20, 30];
val first = values[0];
```

`arr[...]` is the canonical inline-array spelling. The original bare `[...]` literal remains accepted for source compatibility and destructuring migration.

Tuples preserve per-position static types. Parenthesized tuple literals and list-backed values returned against a finite tuple type both retain the declared positional types:

```ores
val pair = (1, "one");
[const number, let label] = pair;

fnc result() => [int, bool, string] {
  return [3, true, "yes"];
}

const [num, ok, answer] = result();
```

## Structural typing and interfaces

Interfaces are structural contracts. Explicit `implements` asks the compiler to prove conformance and documents intent; structural compatibility does not require nominal ancestry in every context.

```ores
define interface Named
  String name;
end

define class User implements Named as
  pub val String name;
end
```

Class interface satisfaction uses public members, including inherited public members.

## Option, Result, and null

Oreslang does **not** have ambient nullable references. A bare `null` value is a compile-time error, and `null` is not a standalone variable/parameter/return type.

Optionality is explicit, using Rust-style `Some(value)` and `None`:

```ores
fnc lookup(bool found) => Option<int> {
  if found; do
    return Some(42);
  else
    return None;
  fi
}
```

Fallible operations use `Result<T, E>`, constructed with `Ok(value)` or `Err(error)`. `Result` always has exactly two explicit type arguments.

```ores
val Option<int> present = Some(42);
val number = present.unwrap();

val Option<int> missing = None;
val safe = missing.unwrap_safe();          // Err(OptionUnwrapError(...))

val Result<int, String> parsed = Ok(123);
val same = parsed.unwrap_safe();            // Ok(123)
```

- `Option<T>.unwrap() -> T` returns the `Some` payload and panics on `None`.
- `Option<T>.unwrap_safe() -> Result<T, OptionUnwrapError>` never panics for absence.
- `Result<T,E>.unwrap() -> T` returns the `Ok` payload and panics on `Err`.
- `Result<T,E>.unwrap_safe() -> Result<T,E>` never panics; it preserves the error-as-value carrier.
- `expect(String)` is the descriptive panicking form; `unwrap_or(T)` supplies a fallback.
- `is_some()/is_none()` and `is_ok()/is_err()` inspect variants without extraction.

Like Rust methods that take `self`, extraction consumes a move-only `Option` or `Result`. `Option<T>` is `Copy` exactly when `T` is `Copy`; `Result<T,E>` is `Copy` exactly when both payload types are `Copy`.

Owned sum values cannot hide lexical borrows until explicit lifetime parameters exist, so `Some(&value)`, `Ok(&value)`, and `Err(&value)` are rejected.

Panics are distinct from ordinary recoverable errors. Normal `try/catch` does not swallow an unwrap panic, while lexical cleanup and `finally` still execute during unwind. Use `unwrap_safe()`, matching, or explicit variant inspection when absence/failure should remain data.

`Option<null>` is accepted only as an explicit type-level escape hatch when an interoperability boundary truly needs to preserve a null marker. The `null` marker cannot escape that direct `Option<null>` position. `Option<void>` is invalid; use `void` when a function returns no value.

## Numbers

Built-in numeric families include integral, floating, decimal, and complex types. Imaginary literals use `i`:

```ores
const complex z = 3 + 4i;
```

Numeric widening is loss-aware; real values can widen toward complex values, but silent lossy narrowing is not performed.

## Lambdas

Lambdas are lexical closures by default and use `->`. The canonical block
form keeps returns explicit:

```ores
val Fnc<int, int> inc = |int x| -> {
  return x + 1;
};
```

A normal lambda may capture activation-local bindings from its enclosing
function or block. Captured mutable state remains part of the closure.

## Non-lexical callables (`nlex`)

`nlex` is an opt-in **capture barrier**, not a ban on global/module lookup.
It prevents a callable from capturing bindings owned by an enclosing runtime
activation, so an `nlex` lambda does not retain or snapshot an outer local
environment.

Inside an `nlex` region:

- parameters and locals declared inside the callable remain available;
- locals shadow module/global/import bindings normally;
- module members, imports, top-level callables/classes, and built-ins remain
  statically resolvable;
- enclosing activation-local bindings cannot be captured;
- lambdas nested in an `nlex fnc`, `nlex routine`, or `nlex` lambda inherit
  the barrier.

Actor entry points remain governed by their actor isolation rules. `nlex` may
add a capture-free guarantee to an actor fnc, but it does not replace mailbox,
private-slice, or shared-actor isolation.

## Conditionals

`fi` is a real, distinct conditional terminator. It is not an alias for module/class `end`.

```ores
if ready, authorized | process.is_admin; do
  return serve();
elseif retryable; do
  return retry();
else
  return reject();
fi
```

Within a condition, comma means AND and `|` means OR. Comma binds more tightly.

## Exceptions and defer

Both structured exceptions and lexical `defer` are supported:

```ores
try {
  risky();
} catch (err) {
  recover(err);
} finally {
  cleanup();
}
```

`defer` executes in LIFO order when its lexical scope unwinds, including returns and exceptional exits.

## Async / await

`Future<T>` is Oreslang's local asynchronous result handle. It is deliberately
not actor-sendable and not shared-safe: a pending computation belongs to the
execution domain that created it. `await future` is the only operation that
extracts the future's result; Oreslang does not expose a blocking
`Future.get()` / `join()` equivalent.

The built-in `Futures` control-flow facade provides:

```ores
// first and second are Future<Response> values returned by an async API.
val responses = await Futures.all([first, second]);
```

- `Futures.all([...])` returns one future, preserves input order, and fails if
  one constituent future fails.
- `Futures.race([...])` completes from the first constituent completion.
- `future.is_done()`, `future.is_cancelled()`, and `future.cancel()` are
  nonblocking state/control operations.
- cancellation is cooperative with the host operation. A sandbox resource
  permit is not considered free merely because guest code requested
  cancellation; the underlying host operation must actually finish.

For actor code, `await` is a **suspension point, never a carrier-thread
blocking point**. Compiler backends must lower an incomplete actor await to a
resumable continuation: the carrier returns to its dispatcher, the actor's
current mailbox turn remains logically in progress, and no later mailbox
message may mutate that actor's state until the continuation resumes and
finishes. The reference JVM interpreter therefore rejects an incomplete
actor-side `await` unless that continuation lowering is active rather than
silently blocking a dispatcher worker.

The scheduler remains separate from the language surface so actor isolation
does not depend on a specific OS-thread implementation.

## Actors

An Oreslang actor is a **compiler/runtime-enforced specialization of the class
model**, not a second object system. `define actor Worker ...` and
`define class Worker extends Actor ...` normalize to the same actor-class AST
and runtime semantics.

A persistent actor exposes a typed public method protocol:

```ores
define class Worker extends Actor as
  let int count = 0;

  constructor(initial: int) {
    self.count = initial;
  }

  private normalized(value: int): int {
    return value;
  }

  pub add(value: int): void {
    self.count = self.count + self.normalized(value);
    return;
  }

  pub current(): int {
    return self.count;
  }
end
```

Actor fields remain private actor-owned state. Private instance methods are
ordinary direct `self` calls inside the actor turn. Public instance methods
form the actor's source-level protocol.

`Actor`, `IsoActor`, and `UntrustedActor` are compiler-intrinsic execution
domain markers:

- `actor` / `Actor` is the SHARED actor domain;
- `isoactor` / `IsoActor` owns confined/private actor memory while trusted
  code remains in the primary Graal isolate;
- `untrusted actor` / `UntrustedActor` is the adversarial sandbox domain and
  is the actor form that crosses into the subsequent/nested Graal isolate.

These intrinsic bases define **execution/isolation domain only**. They do not
take `<Message, Reply, Error>` protocol arguments. Protocol shape comes from
the public methods and, when useful, implemented interfaces.

### Typed protocol, one runtime-owned mailbox

Public actor methods are not separate concurrent entrypoints. The compiler
lowers the complete public method set into one hidden mailbox message
union/dispatcher. OresVM owns the permanent mailbox/event loop and holds at most
one execution lease for that actor.

Outside code holds an `ActorRef<Worker>`:

```ores
val worker = spawn Worker(0);

await worker.add(2);
val value = await worker.current();
```

Conceptually:

```text
Worker.add(int): void
ActorRef<Worker>.add(int): Future<void>

Worker.current(): int
ActorRef<Worker>.current(): Future<int>
```

The actor reference does not expose the mutable actor object or a public
mailbox handle. There is no ambient raw source-level `send`/`receive` API for
actor classes. If an actor explicitly declares a public method named `send` or
`receive`, that name is an ordinary typed protocol endpoint and dispatches
through the same hidden mailbox as every other protocol method. `id`,
`is_alive`, and `mailbox` are reserved ActorRef control-member names.

Protocol methods are currently monomorphic at method level so the generated
message ABI remains closed and AOT-safe. Generic actor classes remain allowed.
Protocol parameters cannot be `mut`, and every parameter and return type must
pass actor-boundary sendability checks.

An actor may implement a multi-method interface and callers may narrow a
concrete reference:

```ores
define interface WorkerAPI
  fnc add(value: int): void;
  fnc current(): int;
end

val ActorRef<WorkerAPI> worker = spawn Worker(0);
```

This is also the preferred hot-loading boundary: the implementation may remain
opaque behind the declared interface/ABI.

Actor inheritance preserves the execution domain and protocol visibility. A
child may inherit its entire protocol or add endpoints, but it may not narrow an
inherited public endpoint to private.

Actor classes do not support static functions. Put reusable non-actor code in
ordinary top-level/module `fnc` declarations with their own checked effects.
Actor restrictions propagate transitively through ordinary helpers called by
actor code; a helper is not an effect escape hatch.

External mutable state is read-only from actor code unless the runtime grants a
narrow explicit capability. Source actors cannot create, receive, or acquire
writable `SharedMutex<T>` state. Shared actors may use approved runtime-owned
read capabilities such as `OresRwLock<T>`; private/untrusted actors cannot use
those handles to cross their memory boundary.

Callable declarations canonically use `: T` or `-> T` for return types.
`=>` is reserved canonically for type-level callable signatures; historical
callable-return `=> T` remains parser-compatible only during migration.

Actor protocol methods are not first-class bound callbacks. `self.helper(...)`
is a direct call during the active actor turn, while extracting
`worker.add` as a bound callback is invalid; use an explicit closure if a
callback is required.

### Actor ownership/effects

Actor isolation is **not** equivalent to `@Pure` and is not implemented by
`nlex`. Actor methods are allowed to mutate their own state. The invariant is
ownership-domain based:

```text
allowed:
  read/write self-owned state
  read/write fresh actor-turn local state
  consume/copy/transfer admitted message values according to their type
  send mailbox messages / emit group output
  use explicitly granted runtime capabilities
  read synchronized external state through an explicit read capability

forbidden:
  mutate arbitrary module/global mutable state
  mutate caller-owned or another actor's object graph
  leak self or a mutable self-rooted alias
  retain a turn-scoped borrow across await
  smuggle runtime/VM/scheduler implementation authority through actor data
```

Actor fields are private mailbox-owned state. `let` actor fields may mutate
during the actor's exclusive turn without a lock. Public endpoint parameter and
return types are checked at the actor boundary; unsafe borrows, guards, futures,
and domain-incompatible shared-mutable capabilities are rejected.

A constructor is runtime-only initialization, not a mailbox endpoint. Actor
constructors are non-public, non-static, non-generic, non-suspending, return
`void`, and may not leak `self`. Ordinary `new ActorClass(...)` is
forbidden; actor identity/state must be created by the actor runtime.

### References, factories, and launch

Code outside an actor never receives the mutable actor object. A concrete actor
spawn returns an `ActorRef<ActorClass>`. The reference grants identity/lifecycle
operations plus the class/interface's typed protocol methods; each public method
call is one bounded mailbox admission and returns a runtime-owned `Future<T>`
for the logical method result.

Generated/runtime actor factories remain useful as launch adapters for actor
groups, configured factory catalogs, hot loading, and dependency injection.
They execute under the target actor context and must be capture-safe. This
runtime factory concept is distinct from source `actor fnc`/`actor routine`,
which are one-shot spawned callables.

The persistent actor ABI is therefore the actor identity plus its generated
typed protocol dispatcher—not a raw class pointer and not a user-visible
mailbox object.

This model preserves ActorGroup/ActorMailman architecture: typed actor protocol
calls enqueue bounded runtime-private messages; actors may additionally emit
bounded group output; one logical serialized mailman consumes the group's
outbox; supervisors remain responsible for lifecycle/restart policy.

Private and untrusted actors do not accept explicitly shared mutable memory. Each private actor owns a **confined memory slice** identified by its actor id, independent of whichever dispatcher thread happens to execute a mailbox turn. Incoming messages are isolation-copied into that actor domain and charged against the destination slice before mailbox admission. Compiler-managed actor state allocations use the same slice.

The slice has two simultaneous limits:

- a per-actor limit from that actor's `IsolatePolicy.maxHeapBytes()`;
- an aggregate private-actor memory budget from the parent runtime policy.

This prevents many private actors from multiplying the parent's memory ceiling. Destroying the actor closes its slice and releases its accounting.

The JVM backend's slice is a language/runtime ownership and accounting boundary, not a separate Java GC heap. The slice follows the actor id across dispatcher workers; it is not thread-local state. When physical heap separation is required for adversarial tenant code, the same private-actor semantics must be backed by a cross-thread-capable private region or a separate Graal polyglot/native isolate.

Shared actors may additionally receive:

1. deeply immutable `Shared<T>` values; and
2. explicit synchronized shared cells.

The runtime primitive for the second case is `SyncCell<T>`. A cell stores only frozen state and serializes replacement updates under a lock. Shared-cell state is quota-accounted against the same parent actor-memory ceiling as private actor slices. Private actor turns cannot create, inspect, mutate, or close a `SyncCell<T>`. This is the intended lowering target for a future `sync` language construct; `sync` is **not** an implicit lock around actor methods.

Actor message graphs are cyclicity-checked and bounded by nesting depth, node count, and logical byte quotas before admission so malicious container graphs cannot turn actor transport into unbounded recursion, CPU, or memory use.

This preserves the central invariant:

> Actor state is mutated through mailbox ownership. Shared mutable state outside an actor is exceptional and must use an explicit synchronization abstraction.

Arbitrary mutable host objects remain invalid actor messages. Actor kind is part of the public ABI, so changing a normal callable/class into a private, shared, or untrusted actor invalidates dependent compiled units.

An untrusted actor cannot obtain ambient network access. Its only permitted
outbound network primitive is a host-owned **stateless HTTP/HTTPS capability**.
The default hard per-actor limit is **5 in-flight outbound HTTP calls** and may
be configured downward or upward by the supervisor within the runtime hard
ceiling. The sixth call is rejected before it reaches the host transport; it is
not hidden in an unbounded guest queue. `CONNECT`, WebSocket/protocol upgrades,
non-HTTP schemes, raw TCP sockets, actor-visible connection-pool handles, cookie
jars, and stateful session connections are forbidden. A host may reuse
connections internally for normal HTTP efficiency, but that state never
becomes an actor capability.

This gives an untrusted actor useful I/O parallelism without letting it spawn
more actors. It can start up to its HTTP limit, compose those futures with
`Futures.all`, and suspend at `await`; the network operations continue while
the actor consumes no carrier thread.

For HTTP request handling, the host may instead bind exactly one accepted request/response exchange to the actor. The runtime exposes bounded, owner-only request-body and response-body stream capabilities through the actor turn context, allowing the HTTP server to stream directly from/to its socket or event-loop buffers without copying bulk body data through actor mailboxes. Body bytes and HTTP metadata have independent limits; request method/path/header access and response headers are bounded so metadata cannot be used to evade the body/mailbox quotas. When body data is staged in an actor-owned native block, the runtime can read/write that FFM-backed region directly through the HTTP capability without an intermediate heap byte array. The capability is deliberately higher-level than a raw fd so it remains safe for multiplexed HTTP/2 and HTTP/3 connections. HTTP capability handles themselves are non-Sendable and cannot escape through actor messages.

If an untrusted actor is explicitly given an `ActorRef`, that grant authorizes bounded message sending, not lifecycle control: it cannot stop another actor or synchronously wait for another actor's termination. Prefer the narrower `Recipient<M>` capability for parent replies and one-way channels. A `Recipient<M>` can send only; it has no stop/wait/failure API and cannot cross into a different `ActorRuntime`. Supervisory control remains outside the untrusted actor.

Shared writable handles use transactional publication. A `SharedMutex<T>` is reserved to the destination runtime before mailbox visibility, committed only after queue admission, and unbound again when first publication fails. This prevents failed sends from accidentally claiming a writable capability for the wrong runtime.

## Isolates

An isolate is stricter than an actor and is intended as a FaaS/tenant security boundary. Strict isolate contexts deny host reflection, native access, arbitrary filesystem/IO, child-process creation, guest-created threads, environment access, and unrestricted polyglot access unless an explicit capability is granted by the host.

Actors may run inside an isolate. Actor semantics never weaken isolate policy.

## Built-in globals

`process` is an Oreslang runtime descriptor/capability facade, not unrestricted OS process access. `stdio` is capability-scoped standard IO. `print(value)` is shorthand for the output facade.

## Compiler pipeline

1. UTF-8 source -> lexer.
2. lexer -> parser / AST.
3. imports and declarations are collected without executing user code.
4. generic, structural, module-interface, inheritance, mutability, and return-flow checks run.
5. actor/isolate sendability constraints are enforced at relevant runtime boundaries.
6. checked source is lowered/executed as Truffle guest code.

The parser and static checker execute no user code.


## File-level entrypoints

Named modules remain the normal namespace unit, but a source file may also contain file-level callables such as an entrypoint. The compiler places those declarations in an internal file-root namespace; that namespace is not written by user code.

```ores
define module x
  define class y as
  end
end

pub routine main() => void {
  val y = new x.y();
  stdio.stdout.write(y)
}
```

Qualified names such as `x.y` retain their module namespace.

## `fnc` versus `routine`

`fnc` is the recursive/function form. It may participate in recursive call graphs. Tail-position calls from `fnc` are optimization-eligible, but v0.3 deliberately does **not** promise that every recursive `fnc` executes in constant stack space yet.

`routine` is the non-recursive procedural form:

```ores
pub routine main() => void {
  run_app();
}
```

The static checker rejects direct or indirect call cycles that contain a routine. Routines are not tail-call-optimization targets. This makes entrypoints, orchestration steps, and lifecycle procedures explicit.

Lambdas may recurse when their binding supplies an explicit function type so the closure's own signature is available while its body is checked:

```ores
let Fnc<int, int> fact = |int n| -> {
  return n == 0 ? 1 : n * fact(n - 1);
};
```

## Semicolons

Semicolons are strongly recommended. They remain the canonical formatter output.

They may be omitted only where the parser has an unambiguous structural boundary, such as the final expression immediately before `}`, `fi`, or `end`. Oreslang does not use broad JavaScript-style automatic semicolon insertion.

```ores
pub routine main() => void {
  stdio.stdout.write("done")
}
```

## Nominal typing and opt-in structural parameters

Named classes and interfaces are nominal by default. Structural matching at an API boundary is explicit with `@Structural`:

```ores
pub interface Brand {
  markerBrand: 'marking/branding'
}

fnc consume(@Structural Brand value) => String {
  return value.markerBrand;
}
```

A value does not need to nominally implement `Brand` for that parameter, but its public/static shape must satisfy the interface. Without `@Structural`, the normal nominal implementation/inheritance rules apply.

Interfaces may inherit from other interfaces and support literal-string marker fields:

```ores
pub interface Bar {
  markerBrand: 'marking/branding'
}

pub interface Foo extends Bar {
}
```

Explicit `implements` and module `@AdheresTo(...)` checks remain structural conformance proofs.

## Method overloads

Only methods overload, and only by arity:

```ores
define class Lookup as
  find() => Option<int> {
    return None;
  }

  find(int id) => Option<int> {
    return Some(id);
  }
end
```

Two methods with the same name and same arity are a compile-time error even when their parameter types differ. Top-level/module `fnc` and `routine` declarations never overload.

## Ternary expressions

The ternary operator is right-associative and lazy in its selected branch:

```ores
fnc find(bool found) => Option<int> {
  return found ? Some(42) : None;
}
```

## Loops, iterators, and scheduler safepoints

Oreslang supports conventional imperative loops:

```ores
for (let i = 0; i < 10; i = i + 1) {
  work(i);
}
```

and iterator-style loops:

```ores
for (val item of values) {
  work(item);
}
```

Classes can expose a JavaScript-like iterator symbol:

```ores
define class Bag as
  [Symbol.iterator]() => Array<int> {
    return arr[1, 2, 3];
  }
end
```

The compiler/runtime inserts a scheduler safepoint on **every loop iteration**. For untrusted actors, statement/expression evaluation and callable/recursive execution are also metered. Each checkpoint rechecks the actor deadline and consumes execution fuel; exhausting fuel fails the actor. The runtime may yield a carrier as a scheduling optimization, but untrusted-system liveness does **not** depend on source code voluntarily calling `yield`.

This means Oreslang does not require recursion as the only way to loop, and recursive code is not a loophole around sandbox scheduling. User code receives no ambient thread-control capability.

## Standard output

In addition to `stdio.print` and `stdio.println`, the stream-shaped form is available:

```ores
stdio.stdout.write(value);
stdio.stdout.println(value);
```


## Execution profiles: JIT, AOT, and hybrid

The same Oreslang source model supports three deployment profiles:

- **JIT** — normal GraalVM/JVM host with Truffle JIT available.
- **AOT** — Native Image host with the Truffle interpreter retained and guest JIT disabled. This is the conservative mobile/FaaS profile and still supports source hot reload because new Oreslang source is data consumed by the precompiled interpreter.
- **HYBRID** — Native Image host plus Truffle guest JIT on targets where executable-code generation is permitted.

The CLI accepts `--mode=jit|aot|hybrid` and `--platform=server|windows|macos|linux|android|ios`. The iOS execution contract is intentionally AOT-only. Source hot reload does not depend on executable dynamic libraries, JNI, or NFI.

Maven profiles:
- `mvn -Pnative-aot -DskipTests package`
- `mvn -Pnative-hybrid -DskipTests package`

## Capability-secure isolates

Security is layered. Oreslang uses a deny-by-default language capability policy **in addition to** Graal/Native Image isolation and the host OS/mobile sandbox.

An isolate policy can independently allow or deny:

`STDIN`, `STDOUT`, `PROCESS_INFO`, `ACTOR_SHARE_READONLY`, `SHARED_MEMORY`, `NETWORK`, `FILESYSTEM_READ`, `FILESYSTEM_WRITE`, `ENVIRONMENT`, `HOT_CODE_LOAD`, `FFI`, `NATIVE`, `REFLECTION`, `CHILD_PROCESS`, `THREAD_CREATE`, and `POLYGLOT`.

The trusted compiler API can reject forbidden API usage before execution:

```java
OresCompiler.validateForIsolate(source, policy);
```

Runtime facades perform the same check again. A source file therefore cannot grant itself a capability. The launcher/supervisor chooses policy.

The strict FaaS baseline permits only stdout. Host reflection, native access, unrestricted polyglot access, environment access, guest-created threads, and host IO remain disabled at the Graal context boundary.

Actor cells may receive a policy stricter than their parent runtime. Their mailbox capacity is also bounded by that policy.

## Hot reload without FFI

`HotReloadManager` loads each code revision into a new versioned Polyglot context/generation:

1. source arrives as data;
2. syntax/type/capability checks run;
3. a fresh restricted guest context is created;
4. the validated generation is staged and atomically becomes active without executing guest code;
5. the supervisor explicitly starts the generation when its actor/request boundary is ready;
6. the previous generation may remain alive while requests/actors drain;
7. the supervisor explicitly retires it.

Each generation receives a monotonically increasing id and SHA-256 source digest.

This model does not require `dlopen`, `LoadLibrary`, JNI, or Truffle NFI. A production server may additionally map each context to a Graal polyglot/native isolate. On AOT-only targets the precompiled interpreter executes newly loaded Oreslang source; on JIT-capable targets the same source may warm into optimized machine code.

## Explicit structural calls

Structural compatibility is never silently enabled for a nominal parameter. These three spellings are equivalent:

```ores
pub interface Bar {
  marker: 'brand'
}

pub interface Foo extends Bar {
  markerBrand: 'marking/branding'
}

fnc a(@Structural Foo y) => void {
  return;
}

fnc b(y structural Foo) => void {
  return;
}

@AllowStructural(y)
fnc c(y Foo) => void {
  return;
}
```

All three may accept:

```ores
val branded = obj{
  marker: "brand",
  markerBrand: "marking/branding"
};

a(branded);
b(branded);
c(branded);
```

Without one of those explicit structural opt-ins, passing that object to a nominal `Foo` parameter is a compile-time error.

`structural` is a contextual keyword, so existing identifiers named `structural` remain legal elsewhere.

## Receiver identity and method values

`self` is injected by the compiler/runtime as an immutable receiver binding. It cannot be declared as a local parameter name or reassigned.

Direct method calls do not create per-instance closures:

```ores
box.get();
```

The runtime resolves the shared class method definition and passes the receiver as the hidden first argument.

When a method is extracted as a first-class value:

```ores
val Fnc<int> callback = box.get;
```

Oreslang creates a small bound-method value containing only the receiver plus method identity. The underlying method definition remains shared by every instance. Calling `callback()` always uses the original `box`; there is no JavaScript-style dynamic `this` rebinding.


## Incremental compilation and code units

Oreslang's canonical compiler output is **decomposable**. A monolithic native executable is a packaging choice, not the semantic compilation unit.

Each source file is a separately versioned **code unit**:

- source digest;
- checked AST / future serialized Ores IR;
- explicit import dependencies;
- package identity;
- zero or more flat modules;
- optional flat source namespace.

With no explicit namespace, the file/code-unit identity is its default package identity. An explicit namespace is written once at the top of the file:

```ores
namespace payments;

import fnc {authorize} from "./auth.ores";

pub fnc charge() => void {
  return;
}
```

Namespaces are flat. `namespace company.payments;` is illegal. Modules are also flat: a module name is one identifier and a module may not contain another module.

The incremental compiler uses separate **source** and **ABI** digests:

1. hash every source unit;
2. derive a deterministic exported ABI digest from public functions/bindings, class public members and static functions, interfaces, type aliases, inheritance, structural markers, and module adherence contracts;
3. rebuild a unit whenever its source or resolved dependency set changes;
4. when a dependency ABI digest changes, invalidate the transitive reverse-import closure conservatively;
5. reuse every importer artifact across implementation-only dependency edits;
6. keep unchanged/unaffected compiled-unit objects intact.

Public inferred bindings are fingerprinted conservatively from their initializer AST until the compiler materializes their inferred exported type in the unit manifest.

This separates code-generation dirtiness from public-contract dirtiness. An implementation edit such as changing a function body from `return 42;` to `return 43;` recompiles that file without recompiling importers when the exported signature is unchanged. Until the cross-unit linker records exact public imported-symbol dependencies, ABI changes intentionally propagate transitively for correctness.

Actors and isolates consume versioned code-unit generations. `HotReloadManager` tracks the active generation **per code-unit id**, so staging `worker.ores` does not replace the active `helper.ores` generation. A hot reload therefore does **not** require rebuilding or replacing every actor: changed units receive new generations, unchanged units remain active/shared, and supervisors migrate actors/requests according to policy.

A deployment may still aggregate many code units into one Native Image for startup/distribution reasons. That aggregate is never the only compiler artifact and must not erase per-unit identities or dependency metadata.

## Static class functions

Instance methods continue to omit `fnc`:

```ores
define class Counter as
  read() => int {
    return self.value;
  }
end
```

Class-level functions are not methods. They are declared with the explicit `static fnc` form:

```ores
define class Counter as
  pub static fnc twice(int value) => int {
    return value * 2;
  }
end

val doubled = Counter.twice(21);
```

A static class function:

- is resolved through the class namespace;
- has no implicit or explicit `self`;
- cannot be invoked through an instance;
- may be extracted as a function value from the class namespace;
- has one shared definition, just like any other named function.

Static data fields are intentionally not part of v0.5 yet; `static` on a class binding is rejected rather than silently acquiring Java-like global mutable state semantics.

## Function types, functors, and arrows

The arrows have distinct jobs:

- `=>` declares the return type of a **named callable**.
- `->` forms a **function type** or **lambda**.

Function aliases can use `typeof fnc`:

```ores
type F = typeof fnc() -> int;
type Predicate = typeof fnc(bool value) -> bool;
```

The shorter inline function type is also valid:

```ores
fnc sink() => ((bool foo) -> void) {
  return |foo| -> {
    stdio.println(foo);
    return;
  };
}
```

Parameter names inside function types are documentation-only; structural function compatibility is determined by parameter/result types.

The canonical lambda syntax is pipe-delimited and block-only:

```ores
fnc find(bool found) => F {
  return || -> {
    return found ? 5 : 6;
  };
}

fnc callback() => ((bool foo) -> void) {
  return |foo| -> {
    stdio.println(foo);
    return;
  };
}
```

Lambda parameters may be inferred from a contextual function type (`|foo|`) or typed explicitly (`|bool foo|`).

There are no expression-body lambdas. Every lambda has braces. When the contextual result type is non-void, every control-flow path must contain an explicit `return <value>;`. Void lambdas may use `return;`.

This means higher-order functions and functors do not introduce a second return convention: named functions, methods, static functions, and anonymous functions all use the same explicit `return` statement semantics.


## Lexical closures

Closures are lexical. A lambda resolves free variables from the scope where the lambda is created, not from the scope where it is called.

```ores
fnc makeCounter() => (() -> int) {
  let int count = 0;

  return || -> {
    count = count + 1;
    return count;
  };
}
```

The returned closure owns the captured lexical environment, so repeated calls observe the same captured `count`.

Capture rules are ownership-aware:

- immutable `Copy` captures are copied into the closure environment;
- non-`Copy` captures transfer ownership into the closure;
- a capture that the closure mutates also transfers the mutable lexical slot into the closure;
- after a move-only/mutable capture is transferred, the outer binding cannot be used;
- an already borrowed value may not be captured by an escaping closure; pass the borrow as a lambda parameter or capture the owner by value.

This makes returned closures safe without retaining raw stack references.

## Parameter immutability and `mut`

Parameters are immutable by default.

```ores
fnc bad(Bar b) => void {
  b.foo = "foobar"; // compile-time error
  return;
}
```

An owned parameter may explicitly opt into mutation by putting `mut` between the type and parameter name:

```ores
fnc change(Bar mut b) => Bar {
  b.foo = "foobar";
  return b;
}
```

`Bar mut b` still receives `Bar` **by value**. For a non-`Copy` value, the caller transfers ownership to `change`; returning the value transfers ownership back.

Local mutation continues to use `let`. `val` and `const` remain immutable.

Class fields follow the same bias: a field declared with `val` or `const` cannot be assigned after construction. Mutable object state must use a `let` field and mutable access to the owning value.

## Ownership, moves, and borrows

Oreslang uses Rust-style affine ownership for mutable/heap-backed values.

The initial `Copy` family is:

- integer types;
- floating/decimal/complex scalar types;
- booleans;
- immutable strings.

Class instances, arrays/lists, object records, and closures are move-only by default.

A by-value binding, argument, or return consumes a non-`Copy` value:

```ores
fnc consume(Bar value) => void {
  return;
}

fnc example() => void {
  let Bar b = new Bar();
  consume(b);
  // b.foo; // compile-time error: use of moved value
  return;
}
```

Shared immutable borrowing uses `&T`:

```ores
fnc inspect(&Bar value) => void {
  stdio.println(value.foo);
  return;
}
```

Exclusive mutable borrowing uses `&mut T`:

```ores
fnc change(&mut Bar value) => void {
  value.foo = "changed";
  return;
}

fnc example() => void {
  let Bar b = new Bar();
  change(&mut b);
  stdio.println(b.foo); // owner is usable again after the call
  return;
}
```

Borrow rules:

- any number of immutable borrows may coexist;
- a mutable borrow is exclusive;
- mutation/move of the owner is forbidden while any borrow is active;
- reading the owner is forbidden while an exclusive mutable borrow is active;
- mutable borrowing requires a mutable owner;
- a borrow of a local value may not escape the owner's lifetime;
- temporary call borrows end at the call boundary;
- borrows stored in local bindings remain active until that binding's lexical scope ends.

The initial checker is deliberately conservative around complex branch/loop lifetime shortening. It rejects uncertain aliasing rather than silently accepting it. Later control-flow/NLL work may accept more programs without weakening these invariants.

Structural parameters remain read-only views and therefore do not consume the supplied value.

## Multi-threaded targets

Actors/isolate message passing remains the primary concurrency model, but the ownership contract is backend-independent.

The same compiled program can target a secondary multi-threaded runtime because:

- mutable state has one owner unless temporarily accessed through an exclusive `&mut` borrow;
- shared aliases are immutable;
- move-only values cannot remain accessible from both sides of an ownership transfer;
- closures cannot smuggle an outstanding stack borrow into a longer-lived task;
- actor messages continue to cross actor boundaries only through the existing frozen/sendable contract.

When explicit thread/task spawning is added, cross-thread transfer will require move semantics and a `Send`-equivalent capability; shared cross-thread references will additionally require a `Sync`-equivalent guarantee. Those marker traits are intentionally a future surface feature—the current source language has no ambient raw-thread API, so there is no unchecked escape hatch to bypass ownership.
