# Native runtime ABI direction

Oreslang source semantics must not depend on Java runtime classes. The current
Truffle implementation is a bootstrap/reference backend; native backends should
preserve the same contracts with Ores-owned metadata and native runtime
services.

## Boundary rule

Prefer this direction:

```text
Oreslang source
  -> typed Ores AST / IR
  -> Ores runtime intrinsic
  -> native runtime implementation
  -> OS primitive when necessary
```

Do not define a language feature as:

```text
Oreslang source
  -> Java library semantic
  -> Java reflection/class identity
```

JNI (or Panama/FFM in trusted hosts) may be used as a narrow transport boundary
while the Java-hosted compiler exists, but the native function implements an
Oreslang runtime contract. JNI is not the type system.

## Pattern/type intrinsics

The pattern implementation should eventually lower to a small native ABI
conceptually equivalent to:

```text
ores_type_id(value) -> TypeId
ores_type_is(value, target: TypeId) -> bool
ores_type_is_subtype(actual: TypeId, target: TypeId) -> bool
ores_constructor_tag(value) -> ConstructorTag
ores_constructor_field(value, index) -> ValueRef
```

Names here describe semantics, not a frozen C ABI. Before stabilization the
native runtime may change calling convention, handles, and layout.

Required invariants:

1. Type IDs belong to Oreslang, not JVM `Class` objects.
2. Constructor tags are Oreslang sum/enum metadata.
3. A failed checked cast becomes an Oreslang `CastError`; optional casts
   produce `Option<T>`.
4. Pattern binding does not copy or clone ownership. Bindings refer to the same
   Ores ownership place or to a proven projection of it.
5. Exclusive-match proof happens before code generation. The runtime retains a
   defensive invariant check in debug/reference builds, but source order is not
   the semantics of ordinary `match`.
6. Host Java objects never become nominal Oreslang values merely because a JVM
   `instanceof` relationship succeeds. Host interop remains capability-gated.

## Decision DAG lowering

After static overlap/exhaustiveness proofs, a normal `match` may be reordered
and lowered to an optimized decision DAG because its explicit predicates are
proven disjoint. Backends may choose type-tag switches, constructor-tag
switches, interval branches, or another equivalent implementation.

`match first` is different: source order is semantic and must be preserved.

## Native-first migration

For runtime facilities that Oreslang owns (type metadata, collections, files,
networking, scheduling, futures, actors, mutexes, GC/arenas), new work should
prefer Ores-native/runtime-native implementations. Java facades should become
compiler/bootstrap adapters around those facilities rather than the canonical
implementation.

A practical migration path is:

1. keep parser/type checker/IR validation host-side while the compiler is Java;
2. define narrow Ores runtime intrinsics with backend-independent semantics;
3. implement those intrinsics in the native runtime;
4. have the Truffle backend call the intrinsic layer (JNI only where required);
5. progressively move library/runtime behavior out of Java;
6. keep Java interop explicitly optional and capability-gated.

This lets the same Ores program retain its meaning under Truffle/JIT,
Native-Image/AOT, a JNI-backed native runtime, or a future non-JVM compiler.
