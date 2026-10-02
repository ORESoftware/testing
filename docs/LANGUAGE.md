# Oreslang language design (v0.6)

Oreslang is a statically typed guest language for GraalVM/Truffle. Named types are nominal by default; structural compatibility is explicit at selected boundaries. Its core invariants are explicit mutation, actor-owned mutable heaps, message-only actor communication, read-only sharing, and a stricter isolate profile for untrusted FaaS execution.

## Files, modules, and imports

A source file may contain multiple named modules. A normal module is a namespace: exported members are accessed through the module name, such as `math.add(1, 2)`.

A process-wide singleton module is declared with `define singleton module NAME as`. Its language-level contract is exactly one runtime state cell per canonical module identity per OS process, owned by a hidden serial actor/service. Every caller receives only a proxy/handle; importing or referencing the module never copies its mutable state. Canonical identity includes the defining source code-unit identity, namespace, and module name. Process-singleton access requires the `PROCESS_SINGLETON` capability.

The local JVM backend satisfies this contract only when callers share the host runtime heap. A spawned Graal isolate has a separate heap, so the current adversarial isolate profile refuses `PROCESS_SINGLETON`. A future/embedding-specific trusted supervisor coordinator must route those requests outside tenant heaps; Oreslang does not silently degrade `singleton` to one instance per isolate.

```ores
define module math as
  pub fnc add(int a, int b) => int {
    return a + b;
  }
end

define module app as
  pub fnc main() => void {
    val answer = math.add(40, 2);
    stdio.println(answer);
    return;
  }
end
```

Singleton state is actor-private. Public functions are asynchronous request/reply boundaries. In addition, a singleton module may export an immutable `val`/`const` class instance as a typed process-object proxy; the raw class instance never leaves the owner actor:

```ores
define singleton module counter as
  let int count = 0;

  pub fnc next() => int {
    count = count + 1;
    return count;
  }
end

define module app as
  pub fnc main() => void {
    val int n = await counter.next();
    stdio.println(n);
    return;
  }
end
```

```ores
define class Foo as
  val int value = 42;

  pub read() => int {
    return self.value;
  }
end

define singleton module X as
  pub val Foo f = new Foo();
end

define module app as
  pub routine main() => void {
    val int value = await X.f.read();
    stdio.println(value);
    return;
  }
end
```

Outside `X`, `X.f` is not an ordinary local `Foo`: it is a capability proxy. Its public method calls execute in the singleton owner actor and must be immediately awaited. Object fields, raw references, method extraction, and rebinding the proxy into an ordinary local are rejected. Other public singleton fields remain illegal.

Singleton state fields require explicit process-stable types because they form a process-lifetime hot-reload schema. Ordinary state initializers must be context-free: literals and pure expressions over already initialized stable singleton fields are allowed, while capability access, arbitrary function calls, `await`, mutation, and closures are not. The narrow exception is an exported immutable class-instance proxy initialized directly with a context-free `new Class(...)`. Type aliases are currently excluded from process state so redefining an alias cannot silently reinterpret an existing layout.

The exported transport surface is intentionally strict. Public singleton functions may use sendable scalar values, `Option<T>`, and `Array/List<T>` of sendable values. Until explicit `Send` constraints are part of the type system, exported singleton functions cannot be generic or `async`, cannot accept `mut` or structural parameters, and cannot transport borrows, class instances, functions/closures, or unresolved actor-local values. Classes declared inside a singleton module are actor-private helpers and cannot be accessed through the external module/class namespace.

Process-owned code also has a stricter effect boundary. Singleton functions and process-owned object methods may use their own singleton state, helper functions/classes declared in the same singleton module, and explicit calls to other singleton services. They may not depend on caller/context-local modules, ordinary helper functions, imports, `process`, `stdio`, or `print`. Exported process-object classes declared outside the singleton module are checked under the same rule. This avoids making process state or behavior depend on whichever actor/context invoked it. A future explicit `process-safe`/effect declaration can widen this surface deliberately.

Every external singleton call must be immediately awaited. Calls from one singleton function to another function in the same singleton are direct and keep the same actor-owned state, including helper method/static-function dispatch. Calls between different singleton actors use request/reply mailboxes. The runtime detects wait cycles before they can deadlock, applies caller mailbox/backpressure limits, and treats queueing time as part of the caller's wall-time budget.

A hot-reloaded generation with the same singleton field schema reuses the existing actor state while executing the new function bodies. Incompatible singleton field-schema changes are rejected until an explicit migration mechanism is provided; the runtime never silently treats old process state as a new layout. Replacing singleton code against live state requires `HOT_CODE_LOAD`; managed generations are process-monotonic and stale code cannot roll active singleton behavior backward.

## Initialization lifecycle

Oreslang has one explicit lifecycle declaration:

```ores
init routine() => void {
  // initialization work
  return;
}
```

Its scope determines its lifetime:

- **file/root scope:** once per executing actor when reached from an actor; otherwise once for the owning Graal context;
- **ordinary module:** once per executing actor when first activated by that actor; otherwise once for the owning Graal context;
- **singleton module:** once when the OS-process singleton state cell is first created.

Actor-local module state is stored on the actor cell itself and disappears when that actor dies. Non-actor/main execution uses context-lifetime storage, so repeatedly invoking the same checked source in one Graal context does not rerun ordinary init. Ordinary module state is keyed by the checked code digest; changed code gets fresh ordinary state rather than silently reinterpreting an old layout.

Module/file field initializers run first, in declaration order, and the `init routine` runs afterward. A scope may declare at most one init routine. It has no parameters, must explicitly declare `=> void`, cannot be public/async, and classes cannot declare it. Process-global initialization is intentionally not available as a free-floating file hook: it belongs inside a `singleton module`, where ownership and serialization are explicit.

Singleton-module init is deliberately deterministic and context-free: it may derive and assign singleton state from already-declared singleton values, but it cannot use ambient capabilities, arbitrary calls, `await`, object creation, or caller-local state. This prevents whichever tenant first touches the singleton from defining process-global state accidentally.

Hot reload does not rerun an already-created singleton module's process init. Compatible process state is retained; incompatible schema changes require an explicit migration.

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

Interfaces are storage-free function contracts and can also describe the callable public shape required of a module. A module opts into checking with `@AdheresTo(...)`:

```ores
define module contracts as
  define interface MathApi as
    fnc add(int a, int b) => int;
  end
end

@AdheresTo(contracts.MathApi)
define module math as
  pub fnc add(int a, int b) => int { return a + b; }
end
```

Only exported (`pub`) callable members satisfy an interface contract. `@AdheresTo(A, B)` may name more than one interface. Data layout is intentionally not part of an interface; reusable stored state belongs in a trait or class.

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


## Callable-local type declarations

`struct` and `interface` declarations may appear inside callable and method blocks. They live in a lexical **type namespace**, separate from value bindings. Direct declarations in a callable body are type-hoisted across that callable so its signature can name a type declared textually inside the body without publishing that name into module/file scope.

```ores
pub fnc getat() => T {
  struct T {
    foo: string
  }

  return obj{
    foo: "hello"
  };
}

pub fnc use_it() => void {
  val result = getat();
  stdio.println(result.foo);
  return;
}
```

The name `T` is not visible in `use_it`. The compiler gives the escaped local struct an internal nominal identity and carries its shape through the return type, so callers may infer the result and use its fields/methods without adding `T` to shared file/module scope. Two unrelated callables may each declare a different local `T` without collision.

Nested block type declarations remain block-scoped. Local interfaces are still storage-free method contracts. Local traits are compile-time-only composition units: they may contribute initialized state and behavior to local structs, are flattened before later compiler/runtime passes, and cannot be used as runtime value types. Generic local structs, traits, and interfaces use the same explicit generic rules as their module-level equivalents. Callable-local `type` aliases are also supported; aliases erase to their resolved type when they escape, so the alias name itself never enters module/file scope.

Both compact and long forms are accepted:

```ores
struct Point {
  x: int
  y: int
}

define struct Point as
  x: int
  y: int
end

trait Printable {
  pub print() => void {
    stdio.println("value");
    return;
  }
}

interface Readable {
  fnc read() => string;
}

define interface Readable as
  fnc read() => string;
end
```


Local aliases use the normal alias syntax and are hoisted within their lexical block:

```ores
pub fnc answer() => Answer {
  type Answer = int;
  return 42;
}

pub fnc boxed() => View<int> {
  interface View<T> {
    fnc get() => T;
  }

  struct Box is View<int> {
    value: int;

    pub get() => int {
      return self.value;
    }
  }

  return Box { value = 7 };
}
```

Callers may infer and use the resolved return shapes, but names such as `Answer`, `View`, and `Box` are not visible outside the declaring callable. A nested block may shadow an outer local type name; the shadow ends with that block.

The type namespace is separate from the value namespace, so a block may contain both a local type `T` and a value binding named `T`. Generic type parameters are part of the type namespace, however, so a callable-local `struct T`, `interface T`, or `type T = ...` may not reuse an in-scope generic parameter named `T`. This is rejected instead of relying on name-resolution order.


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

## Interface, trait, struct, and class

Oreslang keeps four distinct concepts instead of blending them:

- `interface` — storage-free method contract only.
- `trait` — non-instantiable compile-time composition of reusable instance state and behavior.
- `struct` — concrete value-semantic aggregate with nominal named identity.
- `class` — concrete reference/identity type with lifecycle/allocation semantics.

`is` is the canonical interface-conformance spelling. `with` composes traits. Legacy `implements` / `impl` remain accepted during migration.

## Classes and receivers

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

bump(self &mut self)() => int {
  self.count = self.count + 1;
  return self.count;
}
```

The receiver variable name is always `self`. Mutating instance/trait state requires an explicit mutable receiver such as `self &mut self`; ownership analysis treats ordinary implicit `self` as immutable.

A class may list multiple parent classes, satisfy interfaces with `is`, and compose reusable state/behavior with `with`:

```ores
define class Combined
    extends Cacheable, Serializable
    is HasId, Named
    with Metrics, RetryState
as
end
```

`implements` and `impl` remain accepted as migration aliases for `is`; new source should use `is`.

Parent order is significant and is the deterministic v0.2 method-resolution order after child methods: the first declared parent is searched before the next parent. The static checker rejects inheritance cycles and incompatible inherited member shapes. Child members may override inherited members only with compatible types.

`Object` and `List` are extensible base classes:

```ores
define class RecordBag extends Object as
end

define class Names extends List as
end
```

Inline object and array literals are values, not classes, and cannot be inherited from.


## Structs

Named structs are value types: they have fields and may have methods, but they do not have class identity and are not allocated with `new`. They cannot extend classes. Named structs remain nominal even when two structs have identical fields.

```ores
struct Point {
  x: f64
  y: f64

  pub length_squared() => f64 {
    return self.x * self.x + self.y * self.y;
  }
}

val p = Point { x = 3.0, y = 4.0 };
```

The long spelling is equivalent:

```ores
define struct Point as
  x: f64
  y: f64
end
```

Struct fields support the compact name-first form (`x: f64`) as well as the existing type-first forms. A bare struct field is immutable (`val`) unless `let` is written explicitly.

Anonymous struct values are structural:

```ores
val p = struct { x = 3, y = 4 };
```

An `obj{...}` literal may be contextually promoted to an expected named struct at an explicit typed boundary such as a typed binding or return. That conversion requires the exact field set and assignable field types; arbitrary already-typed records do not silently become a nominal struct merely because they have the same shape.

Struct equality is value equality within the same named struct identity. Structs remain affine/move-only by default so large values are never duplicated accidentally, but `copy(value)` is built in for structs. The compiler derives the copy recipe recursively from the struct's fields (including trait-composed state). If a nested field is a class, that class must provide the explicit class copy contract described below.


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

## Interfaces and stateful traits

Interfaces are storage-free method contracts. They contain function signatures only: no instance fields, no backing storage, and no object identity. A class uses `is` to ask the compiler to prove conformance.

```ores
define interface Named as
  fnc name() => String;
end

define class User is Named as
  private val String first = "Ada";
  private val String last = "Lovelace";

  pub name() => String {
    return self.first + " " + self.last;
  }
end
```

Stateful reuse is a distinct construct: `trait`. A trait may contain initialized fields, concrete methods, abstract method requirements, and interface obligations. It cannot be instantiated and contributes no separate runtime object identity. Traits are not nominal runtime types: they cannot be used as parameter, field, return, alias, or generic argument types. Use an interface for a contract and a class for a value type.

```ores
define interface CounterApi as
  fnc bump() => int;
end

define trait Counter is CounterApi as
  private let int count = 0;

  pub bump(self &mut self)() => int {
    self.count = self.count + 1;
    return self.count;
  }
end

define class Worker with Counter as
end
```

The compiler flattens trait storage and behavior into the host class before ownership/capability/runtime lowering. Trait fields require initializers and are not positional constructor parameters. Private trait state remains lexical to the defining trait; host classes cannot reach it directly. A trait method may depend on another host method only when that dependency is declared as an abstract trait method requirement.

Traits must declare their dependencies. If a trait method needs host behavior, it declares an abstract method requirement instead of silently reaching into the host class:

```ores
define trait Loads as
  pub abstract load() => int;

  pub read() => int {
    return self.load();
  }
end

define class Store with Loads as
  pub load() => int {
    return 42;
  }
end
```

Trait composition is intentionally strict:

- two traits contributing the same field are rejected;
- two concrete trait methods with the same name and arity are rejected unless the class declares a compatible resolving method;
- there is no declaration-order or “last wins” rule;
- concrete classes must implement abstract trait requirements;
- unused traits are still statically validated.

For v0, trait composition is module-local. This preserves lexical module lookup while flattening is the implementation strategy. Cross-module traits can be added later with an explicit lexical-environment/import contract.

Class interface satisfaction uses public members, including inherited and composed public members.

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

Built-in numeric families include integral, floating, decimal, and complex types. Imaginary literals use `i`:

```ores
const complex z = 3 + 4i;
```

Numeric widening is loss-aware; real values can widen toward complex values, but silent lossy narrowing is not performed.

## Lambdas

Lambdas use `->`:

```ores
val Fnc<int, int> inc = (int x) -> x + 1;
```

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

`async` and `await` are reserved and parsed. `await` unwraps future-like runtime values. The scheduler is intentionally separate from the language surface so actor isolation does not depend on a specific OS-thread implementation.

## Actors

Actors own their mutable heaps. Cross-actor communication occurs through mailboxes, and message values are frozen/copied/serialized at the runtime boundary. Arbitrary mutable host objects are rejected as messages. Deeply immutable values may use read-only sharing.

A `singleton module` is a language-level process service built on the same ownership rule: one actor owns the module bindings for the entire OS process, while all other actors/isolates communicate with it through generated/runtime proxies. It is not one singleton per isolate or per Graal context.

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
define module x as
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

`STDIN`, `STDOUT`, `PROCESS_INFO`, `ACTOR_SHARE_READONLY`, `NETWORK`, `FILESYSTEM_READ`, `FILESYSTEM_WRITE`, `ENVIRONMENT`, `HOT_CODE_LOAD`, `FFI`, `NATIVE`, `REFLECTION`, `CHILD_PROCESS`, `THREAD_CREATE`, and `POLYGLOT`.

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

### Ownership intrinsics without pointer syntax

Ownership operations use contextual compiler intrinsics with ordinary function-call spelling:

```ores
borrow(value)
copy(value)
take(value)
share(value)
```

These are **not ordinary global functions and are not lexer keywords**. The parser keeps them as ordinary call-shaped syntax and semantic analysis recognizes them contextually, avoiding four more globally reserved lexical names.

- `borrow(x)` creates an immutable borrow. It is the pointer-free spelling of the existing `&x` compatibility form.
- `take(x)` explicitly transfers ownership and invalidates the old owned binding for move-only values.
- `copy(x)` preserves `x` and creates a distinct owned value.
- `share(x)` creates an independent deeply read-only snapshot and does not borrow the original owner.

`copy` deliberately differs by type category:

- **struct** — copy semantics are compiler-derived recursively from stored fields, including trait-composed state.
- **class** — never implicitly copyable. A human must provide a concrete, synchronous, non-generic, public instance `copy() => Self` implementation with an immutable receiver.
- **trait** — has no independent runtime/value identity and therefore cannot itself be copied.
- **interface** — is a storage-free contract, so an interface-typed value is not considered provably copyable without a concrete type.

For classes, the runtime verifies that `copy()` returns a fresh instance of the same concrete class and that the result does not retain mutable object/list/map/array storage from the source graph. Returning `self` or reusing a mutable nested container violates the Copy contract and is rejected.

```ores
define class Buffer as
  pub let int size = 0;

  pub copy() => Buffer {
    return new Buffer(self.size);
  }
end

let Buffer original = new Buffer(8);
let Buffer duplicate = copy(original);
```

The legacy `&T` / `&mut T` spellings remain accepted during migration. New code can use `borrow(x)` immediately; broader Java-like default parameter borrowing can evolve separately without weakening the ownership invariants.

## Zero-copy and zero-allocation contracts

Oreslang treats copying and allocation as compiler-visible semantic effects. The first hard contracts are:

```ores
@NoAlloc
@NoCopy
fnc checksum(int a, int b) => int {
  return a + b;
}
```

These annotations are compile-time contracts, not optimizer hints:

- `@NoAlloc` forbids guest-language storage allocation/materialization that cannot be represented as an inline/fixed value.
- `@NoCopy` forbids explicit or transitive ownership-copy effects.
- contracts propagate transitively through statically resolved Oreslang functions, exact struct/private/static method calls, and other non-virtual calls;
- unresolved/dynamic/foreign calls are conservative: a constrained caller must have a provable effect contract rather than silently escaping analysis;
- diagnostics walk the call graph to the first concrete effect source, including through recursive call graphs.

The ownership intrinsics have type-aware effect semantics:

| operation | allocation effect | copy effect |
| --- | --- | --- |
| `borrow(x)` | no | no |
| `take(x)` | no | no |
| `copy(x)` | only when the copied type needs materialized storage | yes |
| `share(x)` | only when the snapshot type needs materialized storage | only when sharing requires a snapshot copy |

For example, copying an `int` or a primitive-only struct is a copy effect but is not an allocation effect. Copying a class, array/list, map/object, closure, future, mutex state, or a struct containing such storage is potentially allocating. `share(x)` is genuinely zero-copy for already-immutable scalar/string values; composite values still require snapshot-copy semantics.

```ores
struct Point {
  x: int;
  y: int;
}

@NoAlloc
fnc duplicate(Point value) => Point {
  return copy(value); // legal: fixed value copy, no heap/storage materialization
}

@NoCopy
fnc forbidden(Point value) => Point {
  return copy(value); // compile error: copy effect
}
```

Ownership-transfer pipelines remain both zero-copy and zero-allocation:

```ores
@NoAlloc
@NoCopy
fnc inspect(&Buffer value) => int {
  return value.size;
}

@NoAlloc
@NoCopy
fnc pipeline(Buffer value) => int {
  return inspect(borrow(value));
}
```

### Allocation sources

The checker currently treats these as allocation-producing semantic operations:

- `new T(...)` for reference/identity classes;
- array/list and object/map literals;
- capturing closure environments;
- asynchronous callables, because invoking one creates/schedules asynchronous result/task state;
- `Mutex.new(...)` and `SharedMutex.new(...)`;
- `copy(...)` / composite `share(...)` when the operand's recursively known representation needs materialized storage;
- string `+` concatenation;
- opaque calls without a provable `@NoAlloc` guarantee.

Fixed-value forms do not intrinsically allocate:

- named struct construction;
- tuples;
- `Some(value)` / `Option<T>` tag construction;
- scalar arithmetic;
- non-capturing lambdas, which may lower to static call targets;
- `borrow(...)` and `take(...)`.

Their nested expressions are still checked normally, so an inline value containing an allocating expression does not hide that effect.

### Dynamic dispatch contracts

A class instance method body being allocation-free is not enough to make an unconstrained virtual call allocation-free: a subclass may override it. Public class methods therefore need an explicit effect contract before constrained callers can rely on the guarantee.

```ores
define class Source as
  @NoAlloc
  @NoCopy
  pub read() => int {
    return 1;
  }
end

@NoAlloc
@NoCopy
fnc consume(Source source) => int {
  return source.read();
}
```

Effect contracts are inherited behavioral contracts. An overriding method must satisfy the inherited `@NoAlloc` / `@NoCopy` guarantees even when it does not repeat the annotation. Private methods, static methods, and struct methods have exact dispatch and can propagate their inferred effects directly.

### Async and callbacks

An `async` callable has an allocation effect because its invocation creates/schedules asynchronous state. `@NoAlloc async fnc ...` is therefore rejected.

Creating a lambda does not execute its body. The creator is charged for a closure allocation only when the lambda captures outer state. Effects inside a callback body are accounted for when that callback is invoked; calls through unverified dynamic callback values remain conservative.

### Backend contract

`@NoAlloc` is a source-language/guest allocation guarantee. Fixed value forms are deliberately specified so JVM/native backends can lower them to registers, stack slots, inline fields, or caller-provided storage. A backend must not introduce a guest-visible heap-allocation semantic requirement for those forms.

A host VM or interpreter may still create implementation bookkeeping objects internally. Machine-level host-allocation verification is a separate backend/profiling concern and must not be claimed solely from the source effect pass.

`@NoAlloc` and `@NoCopy` take no arguments. Budgeted effects and explicit arena/heap-only contracts can build on this strict zero baseline later.

## Multi-threaded targets

Actors/isolate message passing remains the primary concurrency model, but the ownership contract is backend-independent.

The same compiled program can target a secondary multi-threaded runtime because:

- mutable state has one owner unless temporarily accessed through an exclusive `&mut` borrow;
- shared aliases are immutable;
- move-only values cannot remain accessible from both sides of an ownership transfer;
- closures cannot smuggle an outstanding stack borrow into a longer-lived task;
- actor messages continue to cross actor boundaries only through the existing frozen/sendable contract.

When explicit thread/task spawning is added, cross-thread transfer will require move semantics and a `Send`-equivalent capability; shared cross-thread references will additionally require a `Sync`-equivalent guarantee. Those marker traits are intentionally a future surface feature—the current source language has no ambient raw-thread API, so there is no unchecked escape hatch to bypass ownership.
