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
import module foo from "../xyz";
import module {foo, bar} from "../xyz";
import class {x} from '../xyz';
import fnc * as funcs from '../xyz';
import * as x from './xyz';
```

Wildcard imports always require a namespace alias. This avoids silently injecting an unbounded set of names into the local scope. Import paths are part of the AST/compiler contract; filesystem/package resolution is a host build/bundling concern so strict isolates do not gain ambient filesystem access merely by using `import`.

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

Destructuring carries mutability per element:

```ores
[const code, let body] = (200, "ok");
```

## Classes, receivers, multiple inheritance, and interfaces

Methods omit `fnc`. Instance methods always have an implicit receiver named `self`.

```ores
define class Box<T>
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
define class Combined extends Cacheable, Serializable implements HasId, Named
end
```

Parent order is significant and is the deterministic v0.2 method-resolution order after child methods: the first declared parent is searched before the next parent. The static checker rejects inheritance cycles and incompatible inherited member shapes. Child members may override inherited members only with compatible types.

`Object` and `List` are extensible base classes:

```ores
define class RecordBag extends Object
end

define class Names extends List
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

Tuples preserve per-position static types:

```ores
val pair = (1, "one");
[const number, let label] = pair;
```

## Structural typing and interfaces

Interfaces are structural contracts. Explicit `implements` asks the compiler to prove conformance and documents intent; structural compatibility does not require nominal ancestry in every context.

```ores
define interface Named
  String name;
end

define class User implements Named
  pub val String name;
end
```

Class interface satisfaction uses public members, including inherited public members.

## Option and null

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

`Option<null>` is accepted only as an explicit type-level escape hatch when an interoperability boundary truly needs to preserve a null marker. The `null` marker cannot escape that direct `Option<null>` position. `Option<void>` is invalid; use `void` when a function returns no value.

## Numbers

The reference runtime currently exposes only numeric types whose semantics it can enforce exactly:

- `int` / `i64` — checked signed 64-bit integers;
- `float` / `f64` — finite IEEE-754 binary64 values;
- `complex` / `complex128` — finite pairs of binary64 components.

Imaginary literals use `i`:

```ores
const complex z = 3 + 4i;
```

Names such as `i8/i16/i32`, unsigned integer families, `bigint`, `f32`, `decimal`, and `complex64` are reserved for future exact implementations and are rejected today. Oreslang does not alias those names onto Java `long`/`double` and pretend their width, range, precision, or overflow semantics exist.

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
It means that a callable cannot capture bindings owned by an enclosing runtime
activation. The compiler/runtime can therefore build lambdas in that region
without retaining or snapshotting an outer local environment.

```ores
define module math
  pub fnc offset(int x) => int {
    return x + 10;
  }
end

pub routine main() => void {
  val int outer_bias = 100;

  val Fnc<int, int> lexical = |int x| -> {
    return x + outer_bias;          // allowed: normal lexical capture
  };

  val Fnc<int, int> isolated = nlex |int x| -> {
    val int outer_bias = 1;         // local shadows the outer name
    return math.offset(x) + outer_bias;
  };
}
```

Inside an `nlex` region:

- parameters and locals declared inside the callable are available normally;
- a local binding shadows a module/global/import binding with the same name;
- module members, imported symbols, top-level functions/classes, and built-ins
  such as `stdio`, `process`, and `print` remain available;
- an enclosing activation-local binding is not available for capture;
- lambdas created inside an `nlex fnc`, `nlex routine`, or `nlex` lambda
  inherit the capture barrier recursively.

For named top-level/module `fnc` and `routine` declarations, `nlex` is
mainly a compile-time guarantee about any closures created in their bodies:
those named callables already begin with their own invocation frame. The
runtime benefit is at lambda creation. Ordinary lambdas snapshot/retain the
lexical environment they need; an `nlex` lambda takes the no-environment
path and skips that closure-environment snapshot.

This distinction also resolves the import question. JavaScript/TypeScript
imports are presented as lexical module bindings, while Java imports/static
members are modeled differently, but neither requires retaining a particular
function invocation. Oreslang classifies capture eligibility by **storage
lifetime**, not merely source nesting: module/import/global bindings are
code-unit bindings, while outer locals belong to an activation frame.

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

Structured `try/catch/finally` and lexical `defer` are supported:

```ores
try {
  risky();
} catch (err) {
  log_error(err);
} finally {
  cleanup();
}
```

`recover`, `panic`, `raise`, and `throw` are not claimed by this contract yet. When those language primitives are added, they must remain separate from host security and scheduler-cancellation control flow.

`defer` executes in LIFO order when its lexical scope unwinds, including returns and exceptional exits. Deferred expressions capture bindings at registration time rather than reading later mutations. Move-only captures transfer ownership into the deferred cleanup immediately; borrowed captures and direct deferred mutation of captured bindings are rejected. This avoids hidden use-after-move and Go-style late-binding surprises.

Guest `catch` handles application/runtime failures, but host security denial and scheduler/context cancellation are not ordinary language exceptions and cannot be swallowed by guest code. Future `throw`/`raise`/`panic`/`recover` semantics must preserve that boundary.

## Async / await

`async` is a real type/runtime distinction, not a marker-only modifier. Calling an `async fnc` or async static method produces `Future<T>`; the callable body itself is checked as returning `T`. `await` accepts only `Future<T>` and yields `T`; awaiting an ordinary value is a compile-time/runtime error rather than an identity operation.

Async work is executed by context-owned virtual threads in the Java/Truffle backend, but guest code has no ambient thread API. Task admission is bounded per isolate, live tasks are tracked, compiler-inserted safepoints enforce the isolate wall-time budget, and context shutdown cancels/interrupts owned async work. A bare async call whose `Future<T>` result is discarded is rejected, and `defer async_call()` is rejected unless the async result is explicitly awaited. This prevents accidental Go-style detached-task leaks.

Async callables may not accept or return `Borrow<T>` / `BorrowMut<T>`, because those borrows are scoped to the synchronous call boundary. Async instance methods are currently rejected as well: until Oreslang has an explicit owned-async receiver model, an instance receiver may not outlive the call boundary on another task.

`await` unwraps future failures without exposing Java `CompletionException`/`ExecutionException` wrapper semantics: interruption is preserved as cancellation and the original runtime failure is rethrown when possible.

## Numeric determinism

Signed integer arithmetic is checked consistently in every execution profile. `+`, `-`, `*`, unary negation, integer division overflow, division by zero, and remainder by zero do not silently inherit Java/Go wrapping behavior. `int / int` evaluates as integer division, matching the static `int` result type.

If wrapping or saturating arithmetic is added later it must be explicit; ordinary arithmetic must not change semantics between JIT, AOT, debug, or optimized builds.

## Actors

Actors own their mutable heaps **semantically**. Cross-actor communication occurs only through mailboxes. Ordinary messages are deep-frozen/copied at the boundary; cyclic mutable graphs and arbitrary host objects are rejected. Mailboxes are bounded by both message count and estimated bytes.

Each actor has an independently enforced logical heap budget. Persistent actor state, queued mailbox data, and the currently executing message are accounted separately but must fit under the same actor heap ceiling. `actor.status(ref)` exposes `owned_state_bytes`, `mailbox_bytes`, `active_message_bytes`, `estimated_actor_heap_bytes`, `heap_backend`, and whether physical heap isolation is active. Actor termination zeroes the actor-owned accounting as one lifecycle event.

The Java/Truffle reference runtime still uses the JVM heap as its physical backing store, so the current backend reports `heap_backend = "logical_jvm"` and `physical_heap_isolation = false`. That is an implementation detail, not permission to share mutable guest objects: actor-local allocation/lifetime ownership is enforced by the language contract, while the compiler uses affine ownership and lexical drop points to eliminate most garbage before tracing collection is needed. A later arena/native actor-heap backend can replace `logical_jvm` without changing source-level actor APIs.

Actor entrypoints are named functions or `nlex` lambdas. A lexical lambda that captures caller-local state cannot be spawned as an actor because that would create a mutable cross-actor alias.

```ores
fnc worker(str message) => void {
  stdio.stdout.write(message);
  return;
}

pub routine main() => void {
  val ref = actor.spawn(worker);
  actor.send(ref, "hello");
  actor.stop(ref);
  actor.join(ref);
  return;
}
```

For large immutable payloads, `process.share_readonly(value)` freezes the graph once and returns an opaque shared-region handle. Sending that handle reuses the immutable backing graph instead of copying it again.

Actors may also keep explicit private state without closing over caller memory. `actor.spawn(handler, initial_state)` deep-freezes/copies the initial state into the actor boundary, then calls `handler(message, state)` for each mailbox item; the handler's return value becomes the next actor-local state. The one-argument `actor.spawn(handler)` form remains stateless.

```ores
fnc counter(str message, int state) => int {
  stdio.stdout.write(state);
  return state + 1;
}

val counter_ref = actor.spawn(counter, 0);
actor.send(counter_ref, "tick");
actor.send(counter_ref, "tick");
```

`ActorRef<T>` values are PID-like immutable/copyable handles with a statically checked mailbox type. `actor.spawn(handler)` infers `T` from the handler's first parameter; `actor.send(ref, value)` rejects values that are not assignable to `T`. A `Shared<T>` handle is accepted where `T` is expected because the runtime sends the immutable backing value. Sending is non-consuming: the sender retains its own value while the runtime freezes/copies the transport representation.

Actor/monitor/shared handles are runtime/isolate-scoped capabilities. A foreign tenant's handle is rejected even when its opaque id is well-formed. Normal source actor lowering attaches typed `Protocol<M>` admission metadata derived from the handler's message type; explicit dynamic interoperability uses a dynamic protocol.

The mailbox primitive is FIFO dequeue rather than Erlang-style selective receive scanning. Backpressure is explicit: `trySend` reports admission failure and fail-fast `send` never grows an unbounded queue. Request/reply is implemented with a temporary one-slot reply actor through `ask`, not shared mutable promise state. Typed monitor watchers must admit the runtime `DOWN` message shape; monitor registration fails closed otherwise, and an admitted monitor event that cannot enter a full watcher mailbox fails the watcher rather than silently losing supervision. See [ACTOR_MODEL.md](ACTOR_MODEL.md).


Stateful handlers are checked as `(Message, State) -> State`; their initial state must match `State`. Inline actor lambdas must be `nlex` and explicitly type the message parameter. Their state parameter may infer from the supplied initial state. Named `fnc` handlers remain the simplest actor entrypoint.

Literal singleton names are compile-unit contracts: after `actor.singleton("cache", handler)` fixes a handler signature, another use of the literal name `"cache"` with an incompatible actor contract is rejected. Dynamic singleton names are still checked at the ordinary actor/message boundaries.

### Monitors and supervision

`actor.monitor(target)` creates a one-way Erlang-style monitor from the current actor to `target`. Runtime/control-plane code may use `actor.monitor(target, watcher)` explicitly. When the target exits, the watcher receives one immutable message with at least:

- `type = "DOWN"` on the transport map, plus `event = "DOWN"` as the typed Oreslang-friendly alias
- `monitor_id`
- `actor_id`
- `reason = "normal" | "failed" | "noproc"`

Failures also include `error_type` and `error_message`. `actor.demonitor(...)` removes a monitor. This is deliberately lower-level than a built-in restart strategy: supervisors can implement one-for-one, one-for-all, rest-for-one, backoff, and escalation as ordinary Oreslang actor logic on top of typed monitor messages.

### Singleton actors

`actor.singleton(name, handler)` returns one named actor instance per Oreslang runtime/context. This is the safe singleton primitive for mutable state: the state remains inside that actor's heap and every other actor accesses it by sending messages.

A `singleton` keyword that means "shared mutable object in every actor" is intentionally **not** part of the language. If source syntax is added later, `singleton actor ...` should lower to the same named-actor primitive. Read-only data sharing remains explicit through `process.share_readonly`.

## Memory reclamation

Oreslang is ownership/drop first and GC second:

1. affine ownership and move checking make lifetimes statically knowable where possible;
2. lexical scope exit releases owned interpreter bindings promptly;
3. actor termination drops the actor's reachable state as a unit at the language-semantic level;
4. cycles, dynamic graphs, hot-loaded generations, and backing-runtime objects are reclaimed by automatic collection;
5. `process.gc()` gives trusted/developer code an explicit collection request.

In the current Java reference runtime, `process.gc()` is a logical collection/telemetry request by default. A JVM-wide `System.gc()` hint is **disabled unless the embedding host explicitly opts in** with `-Doreslang.gc.host-hint=true`; when enabled it remains globally rate-limited. This prevents Java global-GC pauses from becoming Oreslang language semantics. The call remains capability-gated by `GC_CONTROL`.

## Isolates

An isolate is stricter than an actor and is intended as a FaaS/tenant security boundary. Strict isolate contexts deny host reflection, native access, arbitrary filesystem/IO, child-process creation, guest-created threads, environment access, and unrestricted polyglot access unless an explicit capability is granted by the host.

Resource ceilings are part of the isolate contract, not advisory metadata: `maxHeapBytes`, `maxMailboxMessages`, `maxActors`, and `maxWallTime` are propagated into the guest context. Actor creation reserves an isolate actor slot atomically before publication, so concurrent spawns cannot race past `maxActors`; termination releases the slot. Child actor policies may narrow but never widen the parent ceiling.

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
  define class y
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
define class Lookup
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
define class Bag
  [Symbol.iterator]() => Array<int> {
    return arr[1, 2, 3];
  }
end
```

The compiler/runtime inserts a scheduler safepoint on **every loop iteration**. The current runtime hook checks cancellation/interruption and yields execution; it is intentionally centralized so actor supervisor/control-mailbox polling can evolve without changing source syntax. User code does not receive ambient thread-control capability.

This means Oreslang does not require recursion as the only way to loop, while still giving actor/isolate schedulers a compulsory cooperation point inside generated loop execution.

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

`STDIN`, `STDOUT`, `PROCESS_INFO`, `ACTOR_SPAWN`, `ACTOR_SEND`, `ACTOR_CONTROL`, `ACTOR_SHARE_READONLY`, `GC_CONTROL`, `NETWORK`, `FILESYSTEM_READ`, `FILESYSTEM_WRITE`, `ENVIRONMENT`, `HOT_CODE_LOAD`, `FFI`, `NATIVE`, `REFLECTION`, `CHILD_PROCESS`, `THREAD_CREATE`, and `POLYGLOT`.

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

A generation can be pinned with a `HotReloadManager.Lease`. Retirement immediately denies new leases and removes the generation from active routing, but does not close its Truffle context until the last existing actor/request lease is released. Failed or fully drained generations remove themselves from manager retention, allowing the backing JVM/native collector to reclaim the old code generation without a global reload pause.

A deployment may still aggregate many code units into one Native Image for startup/distribution reasons. That aggregate is never the only compiler artifact and must not erase per-unit identities or dependency metadata.

## Static class functions

Instance methods continue to omit `fnc`:

```ores
define class Counter
  read() => int {
    return self.value;
  }
end
```

Class-level functions are not methods. They are declared with the explicit `static fnc` form:

```ores
define class Counter
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

Oreslang uses affine ownership for mutable/heap-backed values, but deliberately does **not** copy Rust's pointer-looking `&` / `&mut` surface or user-written lifetime annotations. Borrows are explicit language values:

- `Borrow<T>` — shared immutable borrow, created with `borrow(value)`;
- `BorrowMut<T>` — exclusive mutable borrow, created with `borrow_mut(value)`;
- `take(value)` — makes an ownership transfer explicit;
- `copy(value)` — duplicates only values that are statically guaranteed `Copy`;
- `share(value)` — creates a capability-gated immutable `Shared<T>` transport handle.

The lexer rejects `&` as borrow/reference syntax. Oreslang does not expose raw pointers or require lifetime parameter syntax.

The initial `Copy` family is:

- integer types;
- floating/decimal/complex scalar types;
- booleans;
- immutable strings;
- copyable runtime capability handles such as `ActorRef`, `MonitorRef`, and `Shared`;
- composite `Option`/tuple/record values only when all contained values are themselves statically Copy.

Class instances, mutable arrays/lists, and closures are move-only by default. `copy(classValue)` therefore fails closed until that class participates in a future explicit copy contract; the compiler never guesses that a heap object is safely cloneable.

A by-value binding, argument, or return consumes a non-`Copy` value. `take` can make that intent visible:

```ores
fnc consume(Bar value) => void {
  return;
}

fnc example() => void {
  let Bar b = new Bar();
  consume(take(b));
  // b.foo; // compile-time error: use of moved value
  return;
}
```

Shared immutable borrowing:

```ores
fnc inspect(Borrow<Bar> value) => void {
  stdio.println(value.foo);
  return;
}

fnc example() => void {
  let Bar b = new Bar();
  inspect(borrow(b));
  stdio.println(b.foo);
  return;
}
```

Exclusive mutable borrowing:

```ores
fnc change(BorrowMut<Bar> value) => void {
  value.foo = "changed";
  return;
}

fnc example() => void {
  let Bar b = new Bar();
  change(borrow_mut(b));
  stdio.println(b.foo); // owner is usable again after the call
  return;
}
```

Borrow rules:

- any number of immutable borrows may coexist;
- a mutable borrow is exclusive;
- mutation/move of the owner is forbidden while any borrow is active;
- reading the owner is forbidden while an exclusive mutable borrow is active;
- `borrow_mut` requires a mutable owner;
- a borrow of a local value may not escape the owner's lifetime;
- temporary call borrows end at the call boundary;
- borrows stored in local bindings remain active until that binding's lexical scope ends;
- lifetime checking is compiler-inferred; programs do not spell lifetime names.

The checker is deliberately conservative around complex branch/loop lifetime shortening. It rejects uncertain aliasing rather than silently accepting it. Later control-flow/NLL-style analysis may accept more programs without changing source syntax or weakening these invariants.

Structural parameters remain read-only views and therefore do not consume the supplied value.

## Multi-threaded targets

Actors/isolate message passing remains the primary concurrency model, but the ownership contract is backend-independent.

The same compiled program can target a secondary multi-threaded runtime because:

- mutable state has one owner unless temporarily accessed through an exclusive `BorrowMut<T>`;
- shared aliases are immutable;
- move-only values cannot remain accessible from both sides of an ownership transfer;
- closures cannot smuggle an outstanding borrow into a longer-lived task;
- actor messages continue to cross actor boundaries only through the frozen/sendable contract.

When explicit thread/task spawning is added, cross-thread transfer will require an Oreslang sendability bound and shared cross-thread references will require an Oreslang share-safety bound. Those bounds must be source-level guarantees, not Java-style erased generics or implicit Go-style concurrency permission. The current source language has no ambient raw-thread API, so there is no unchecked escape hatch to bypass ownership.
