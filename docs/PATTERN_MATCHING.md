# Pattern matching, refinement, and casting

Oreslang deliberately separates type refinement, full pattern matching, and
value-case dispatch.

## Surface syntax

```ores
if value is Dog dog then
  dog.bark();
fi

if result matches Some(value) then
  use(value);
fi

val dog = animal as Dog;
val maybeDog = animal as? Dog;

match result
  Some(value) -> { use(value); }
  None -> { recover(); }
end

switch status
  case 200, 201 -> { success(); }
  default -> { failure(); }
end
```

Every `if` closes with `fi`, including brace-bodied forms. Executable arms
use the slim arrow `->`. The fat arrow `=>` remains type-level syntax for
function types and interface callable signatures.

## Semantic separation

- `x is T` is a nominal type predicate and flow refinement.
- `x is T name` additionally introduces a lexical refinement alias.
- `x matches P` tests a complete pattern and exposes its bindings only on the
  successful control-flow edge.
- `x as T` is a checked cast. A failed runtime cast raises `CastError`.
- `x as? T` is a non-panicking cast with type `Option<T>`.
- `match x` is proof-checked pattern partitioning.
- `match first x` is the explicit ordered/priority escape hatch.
- `switch x` is constant/equality dispatch and never performs destructuring.

A refinement binding is not a copy. If `dog` is introduced by
`animal is Dog dog`, both names identify the same ownership place. Moving
through either name consumes the same move-only value.

## Exclusive match proof obligation

For a scrutinee domain `D`, each explicit arm is normalized to a predicate
`P_i(x)`. A normal match is admitted only when the checker establishes:

```text
for every i != j:  D(x) && P_i(x) && P_j(x) is UNSAT
exhaustiveness:    D(x) && !(P_1(x) || ... || P_n(x)) is UNSAT
```

An unguarded final `else`, `_`, or catch-all binding is not treated as an
ordinary universal arm. It denotes the complement of all preceding explicit
arms.

The first proof engine is intentionally conservative. It has exact reasoning
for:

- booleans and literal equality;
- `Option` constructors `Some` / `None`;
- `Result` constructors `Ok` / `Err`;
- nominal subtype/interface relations when a relation is provable;
- simple numeric guard intervals using `< <= == >= >`;
- wildcard/catch-all coverage.

If disjointness cannot be proved, ordinary `match` is rejected rather than
silently using source order. Code that deliberately needs priority semantics
must say `match first`.

This is the important semantic distinction:

```ores
match value
  is Dog dog -> { dog.bark(); }
  is Animal animal -> { handle(animal); }
end
```

is rejected because a `Dog` witnesses both predicates. The ordered form is
explicit:

```ores
match first value
  is Dog dog -> { dog.bark(); }
  is Animal animal -> { handle(animal); }
end
```

Arbitrary calls inside guards are opaque to the proof engine unless a future
verified predicate/contract system gives the compiler a sound logical summary.
The checker must never infer exclusivity from undocumented function behavior.

## Lowering contract

Patterns are represented by dedicated Oreslang AST nodes rather than generic
binary operators. The compiler can therefore normalize them into a decision
DAG and constraint representation before backend lowering.

The language contract is independent of Java object identity. A backend must
implement Oreslang type tests through Oreslang type/constructor metadata:

- stable type identity;
- superclass/subtype edges;
- implemented interface/trait edges;
- constructor tags for sum types;
- reified generic information only where the language declares it reifiable.

The Java/Truffle evaluator is a reference/bootstrap backend. Its internal Java
objects may represent Oreslang values, but Java `Class.isInstance`,
reflection, or arbitrary host `instanceof` relationships are not Oreslang
type semantics.

See `NATIVE_RUNTIME_ABI.md` for the native/JNI boundary.
