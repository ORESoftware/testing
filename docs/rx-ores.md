# rx-ores: native reactive streams for Oreslang

Status: first runtime substrate, stacked on the Ores-owned Future/suspension work.

## Why rx-ores exists

rx-ores is intended to be the Oreslang-native peer of RxDart, RxRust, RxJava,
and RxJS. It is also a forcing function for Oreslang's async model: reactive
streams, ordinary Futures, actor suspension, timers, sockets, and async I/O must
all agree on one scheduling contract.

The first rule is therefore:

> rx-ores does not invent a second callback scheduler.

Every blocking-looking reactive wait crosses the same runtime-owned
`OresFuture<T>` boundary used by language `await`.

## Initial model: pull first

The v0 substrate is intentionally pull-oriented:

```text
Observable<T>
    |
    +-- subscribe() -> Subscription<T>
                         |
                         +-- next() -> Future<Notification<T>>
```

A `next()` call admits at most one item. This gives us real backpressure before
we add a larger demand protocol.

There may be only one outstanding `next()` per subscription.

A pull settles with either:

- `NEXT(value)`;
- `COMPLETE`; or
- a failed Future for the stream error path.

Errors are not encoded as ordinary values.

## Scheduler invariant

A timer, socket reactor, JNI worker, Java virtual thread, or arbitrary producer
thread may settle an Ores Future. It may not execute an Oreslang observer,
operator lambda, actor callback, or continuation directly.

The allowed path is:

```text
producer
  -> settle OresFuture
  -> enqueue owning Ores continuation
  -> resume on OresVM scheduler domain
  -> execute rx-ores guest operator
```

This is the same invariant already used by actor `await`.

## Why callback-style subscribe is not in the first patch

The familiar surface eventually wants forms such as:

```ores
val doubled = values.map(|int value| -> int {
  return value * 2;
});

val sub = doubled.subscribe(|int value| -> void {
  consume(value);
});
```

But exposing that before scheduler-bound guest callbacks exist would be wrong:
a Future completion callback could accidentally execute guest code on an I/O or
JNI completion thread.

So the first runtime API deliberately exposes no public Java
`Consumer`/`Function` observer surface. Native higher-order operators come
after the compiler/runtime can bind the operator lambda to an Ores task or actor
continuation.

## Core library, explicit linking, and executable size

rx-ores is a **core Oreslang library**, but it is not an implicit prelude
dependency and must not be retained by applications that do not use it.

The canonical source-level dependency is an explicit Unix-style core import:

```ores
import module rx from "std/rx";
```

The build/link contract is:

1. there is no automatic `std/rx` import;
2. resolving `std/rx` creates the RX dependency edge;
3. no RX import means no RX source/operator unit enters the application link
   graph;
4. Oreslang's language-level reachability/tree-shaking pass runs over the
   linked graph before per-application JVM/native emission;
5. GraalVM Native Image may then perform its own closed-world reachability, so
   the Java runtime substrate below has no retention edge when RX is absent;
6. a minimized JVM application bundle should likewise include the optional RX
   runtime/support payload only when the linked graph requires it.

This is deliberately **not** reflective runtime plugin loading. Runtime dynamic
loading would make AOT reasoning worse. The preferred model is explicit import
plus static/lazy linking and reachability elimination.

The Oreslang toolchain distribution may ship rx-ores so `std/rx` is always
available to import. Shipping it with the compiler is separate from bundling it
into a user's application executable.

The native substrate in this draft is intentionally not registered eagerly by
an OresVM/global runtime singleton. Source lowering and the standard-library
linker should be the only path that makes RX runtime support reachable.

Operators should also remain granular. `std/rx` is the small pull/Future
nucleus; operators and adapters should live in separate units such as
`std/rx/operators/map`, `std/rx/operators/filter`, and
`std/rx/async`. A convenience `std/rx/all` may intentionally aggregate them
for users who want a batteries-included dependency.

## Intended Oreslang surface

The source-level shape should stay library-first rather than requiring special
reactive syntax.

Using the current callable direction, the target shape is approximately:

```ores
pub interface Subscription<T> {
  fnc next(): Future<Notification<T>>;
  fnc cancel(): void;
}

pub interface Observable<T> {
  fnc subscribe(): Subscription<T>;
}

pub async fnc first_two(Observable<int> values): [int, int] {
  val sub = values.subscribe();

  val first = await sub.next();
  val second = await sub.next();

  rt defer || -> {
    sub.cancel();
  };

  return [first.value, second.value];
}
```

The exact source facade should be added only on top of the current callable,
`rt` builtin, local-struct/interface, and AOT-safe declaration branches so this
library does not freeze stale syntax from an older stack.

## Future / Observable bridge

The first native bridge includes:

- `Observable.fromValues(...)`: cold replayable finite source;
- `Observable.fromFuture(...)`: adapt one shared `OresFuture<T>`;
- `take(n)`: bounded upstream consumption;
- `first()`: adapt the first stream item back into `OresFuture<T>`.

`fromFuture` treats the supplied Future as shared. Cancelling one subscription
does not cancel that producer, because another subscriber may be observing it.

Per-subscription owned producers will be added with a deferred-source primitive.

## Operator roadmap

The next layers should add, in roughly this order:

1. scheduler-bound guest operator execution;
2. `map`, `filter`, `scan`, `take_while`;
3. `flat_map` / `switch_map` with structured child cancellation;
4. `merge`, `concat`, `zip`, `combine_latest`;
5. timer operators such as `delay`, `debounce`, `throttle`;
6. hot subjects/signals with explicit bounded buffering;
7. actor/mailbox and socket adapters;
8. untrusted-actor quotas for item count, bytes, lifetime, and downstream writes.

## Backpressure and memory

A reactive source must never imply unbounded buffering.

The pull substrate naturally limits in-flight demand to one item. Future push
sources must declare one of these policies explicitly:

- rendezvous / no buffer;
- bounded buffer with capacity;
- drop newest;
- drop oldest;
- coalesce/latest;
- fail on overflow.

There is no implicit unbounded queue.

## Cancellation

Cancellation is structured:

```text
downstream subscription cancel
  -> cancel pending pull
  -> cancel owned child work
  -> release buffers/resources
  -> stop future production
```

As with ordinary Ores Futures, cancellation is a request to underlying host work,
not proof that an uncooperative host call has stopped.

## AOT / JIT constraint

rx-ores must remain valid under the AOT semantic floor:

- no runtime class generation;
- no dynamic module/namespace creation;
- no reflective operator discovery;
- operator graphs are ordinary statically known Ores values/types;
- JIT may optimize a known operator pipeline, but it may not change language
  semantics or require dynamic type creation.

That makes reactive pipelines useful as an AOT/JIT conformance workload as the
language matures.
