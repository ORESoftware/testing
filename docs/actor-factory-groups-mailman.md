# Persistent actor classes, generated factories, groups, and mailmen

Status: hardened design contract for the current actor-class/runtime model.

This document distinguishes three concepts that must not collapse into one
another:

1. **persistent actor classes** — long-lived typed mailbox objects;
2. **actor fnc/routine callables** — one-shot scheduled actor-domain jobs;
3. **runtime actor factories** — compiler/host construction descriptors used by
   supervisors, groups, and generated catalogs.

A source `actor fnc` is **not** the persistent actor-class factory ABI.

## 1. Persistent source actors are actor classes

A persistent actor is a class with an `ActorKind`:

- `actor` / `shared actor` / `extends Actor` -> SHARED
- `isoactor` / `extends IsoActor` -> PRIVATE
- `untrusted actor` / `extends UntrustedActor` -> UNTRUSTED

The intrinsic bases define execution/isolation domain only. They do not carry a
second `Actor<Message, Reply, Error>` protocol.

```ores
define actor Counter as
  let int value = 0;

  constructor(initial: int) {
    self.value = initial;
  }

  private normalized(delta: int): int {
    return delta;
  }

  pub add(delta: int): void {
    self.value = self.value + self.normalized(delta);
    return;
  }

  pub current(): int {
    return self.value;
  }
end
```

Public instance methods form the typed source protocol. Private methods are
direct `self` calls inside the active actor turn.

There is still exactly one runtime mailbox. The compiler lowers the complete
public method set into one hidden tagged request/reply dispatcher. A source
method named `receive` has no special privilege.

Conceptually:

```text
Counter.add(int): void
Counter.current(): int

      lowers to

hidden CounterProtocol =
    Add(int, Reply<void>)
  | Current(Reply<int>)
```

## 2. ActorRef is typed protocol authority

External code receives `ActorRef<Counter>` or an interface-narrowed
`ActorRef<CounterAPI>`.

```ores
val counter = spawn Counter(40);

await counter.add(2);
val value = await counter.current();
```

The source projection is:

```text
Counter.add(int): void
ActorRef<Counter>.add(int): Future<void>

Counter.current(): int
ActorRef<Counter>.current(): Future<int>
```

There is no ambient raw source-level `send`/`receive` operation for actor
classes. If the actor declares a public method named `send` or `receive`, it
is an ordinary typed protocol endpoint. The mutable mailbox itself remains
runtime-private, while `id`, `is_alive`, and `mailbox` are reserved
ActorRef control-member names.

Protocol methods are not first-class bound callback objects. Use an explicit
closure when callback behavior is desired so capture/lifetime/suspension remain
visible to the compiler.

## 3. Inheritance and protocol interfaces

Actor inheritance preserves execution/isolation domain and protocol visibility.
A child may inherit its entire public protocol and may add endpoints, but it
cannot narrow an inherited public endpoint to private.

For abstraction and hot loading, an actor may implement a multi-method
interface:

```ores
define interface CounterAPI
  fnc add(delta: int): void;
  fnc current(): int;
end
```

`ActorRef<Counter>` may narrow to a compatible `ActorRef<CounterAPI>`. The
interface is an ABI boundary, not a way to expose the mutable actor object.

## 4. Boundary rules

Every public actor endpoint is an actor boundary.

The compiler must reject:

- method-level generic protocol endpoints;
- `mut` protocol parameters;
- borrows/references crossing the mailbox;
- `Future`, mutex guards, actor spawn tickets, or mutable host objects crossing
  the boundary;
- actor instances transported by value;
- writable external shared state;
- public actor fields;
- actor constructors that are public, generic, static, or suspending;
- raw imported/effect-unknown helper calls from actor code.

`ActorRef<Protocol>` is an explicit capability and may cross a boundary when
its protocol type is valid.

A SHARED actor may receive an explicit `RwLock<T>` read capability when `T`
is shared-safe, but actor code cannot acquire a write guard. PRIVATE and
UNTRUSTED actors cannot use shared external memory.

Actor restrictions propagate transitively through ordinary local functions and
methods called by actor code. A helper does not become an effect escape hatch
merely because it lacks an `actor` keyword.

## 5. One-shot actor callables are separate

`actor fnc` / `actor routine` remain one-shot scheduled jobs in an actor
execution domain.

They are invoked with `spawn`, not by ordinary call syntax, and produce an
`ActorSpawn<R>` control handle:

```text
ActorSpawn<R>
  id
  ready : Future<ActorRef<?>>
  done  : Future<bool>
  result: Future<R>     // only when R != void
```

This is intentionally different from a persistent actor class. Do not use
`actor fnc` as a hidden constructor for a long-lived class protocol.

## 6. Runtime generated factories

The Java/runtime `BehaviorFactory` abstraction is a kernel construction
mechanism. It is not the source `actor fnc` ABI.

For source actor classes, the compiler/linker may generate a construction
descriptor/factory that:

1. reserves the ActorId/domain/group quotas;
2. validates and transports constructor arguments;
3. initializes actor-owned state under the target actor context;
4. installs the hidden typed protocol dispatcher;
5. publishes READY only after initialization succeeds.

The SHARED reference evaluator uses the privileged
`spawnSourceSharedProtocolActor` lowering path. PRIVATE and UNTRUSTED source
actor classes require their isolation-aware OresVM lowering and must not be
silently routed through the shared evaluator.

## 7. ActorFactoryCatalog meaning

`ActorFactoryCatalog` is immutable, generated, and generation-scoped. It is
link metadata, not a self-registering runtime service.

A descriptor key is path-oriented:

```text
workers/worker.ores::Worker
```

The existing descriptor `inputType` should be read as the compiler-generated
hidden protocol-envelope ABI for a persistent actor class, not as evidence that
source actors have one public `receive(In)` method. `outputType` describes
the group's typed emitted-output contract where applicable.

The descriptor's ABI digest must cover the full public protocol shape,
constructor contract, actor kind, relevant capability contract, and generated
wire/message tags. Reordering private helpers must not perturb it; changing a
public endpoint must.

Catalogs are pinned to a code generation. Existing actors retain the generation
lease from which they were constructed.

## 8. ActorGroup and mailman

An ActorGroup owns runtime policy and routing for a set of actors:

```text
ActorGroup<Out>
  actor registry
  bounded inbox policy
  bounded MPSC outbox<ActorMail<Out>>
  one logical serialized ActorMailman<Out>
  supervisor/restart policy
  quotas
```

No scheduler scans/selects across all actor inboxes. A mailbox transition makes
one ActorRef runnable in its execution-domain queue.

The mailman is one logical serialized consumer scheduled on the CONTROL pool,
not a permanently dedicated OS thread and not an actor-domain carrier.

Actors may emit bounded group output:

```text
actor turn -> emit Out -> group outbox -> ActorMailman
```

The mailman/supervisor may route a reply/event by invoking the target actor's
typed protocol, which re-enters that actor's one mailbox.

## 9. Group capabilities

Supervisor/root code may hold `ActorGroupRef<Out>`, which is control-plane
authority.

Trusted actor code may receive only an opaque `ActorGroupHandle<Out>`. The
handle contains authenticated identity/domain/generation capability data and no
pointer to the runtime, scheduler, registry, inbox, outbox, or mailman state.

UNTRUSTED actors never receive a spawn-capable group handle.

Group ownership therefore does not transfer merely because an actor can request
a child spawn in that group.

## 10. Scheduling

All actor kinds preserve:

- one active execution lease per actor;
- bounded carrier pools by actor domain;
- local/global ready queues with work stealing only within the domain;
- no mailbox scanning;
- bounded turn/reduction/fuel budgets;
- `await` as a hard scheduler boundary;
- completion threads enqueueing wakeups only;
- continuation state rooted until the logical turn finishes.

A protocol request that suspends remains the same serialized logical mailbox
turn until its continuation completes.

## 11. Group configuration

`.ores-actors.toml` is deployment authority. Limits are ceilings, not hints.

Current runtime policy uses a canonical `factory = "<path>::<symbol>"` key
when a supervisor needs to recreate actors automatically. For persistent source
actors, that key names a **generated actor-class constructor descriptor**, not a
source `actor fnc` return value.

Example:

```toml
version = 1

[[group]]
name = "workers"
kind = "shared"
min_actors = 8
max_actors = 128
factory = "workers/worker.ores::Worker"
inbox_capacity = 1024
outbox_capacity = 4096
restart_strategy = "one_for_one"
max_restarts = 5
restart_window = "10s"

[[group_template]]
name = "tenant-workers"
kind = "shared"
min_actors = 0
max_actors = 32
max_instances = 1024
factory = "workers/tenant_worker.ores::TenantWorker"
inbox_capacity = 512
outbox_capacity = 2048
restart_strategy = "one_for_one"
max_restarts = 3
restart_window = "5s"
```

A positive `min_actors` requires a factory key because the supervisor needs a
concrete generation-pinned actor constructor to restore the floor.

Dynamic overrides are narrowing-only. They may not widen capacities,
capabilities, restart intensity, or change actor execution domain.

## 12. Supervision

Use OTP-style restart vocabulary:

- `one_for_one`
- `one_for_all`
- `rest_for_one`

Dynamic groups currently use `one_for_one` only.

Restart intensity is bounded by `max_restarts` within `restart_window`.
Exceeding the window escalates rather than spinning forever.

Restart policy remains distinct:

- `permanent`
- `transient`
- `temporary`

An unrecovered actor failure goes to supervision. It is not ordinary outgoing
mail.

## 13. Tree shaking and hot reload

Actor declarations do not self-register or run at import time.

Build roots include:

1. ordinary executable roots such as `main`;
2. actor classes referenced by static group factory keys;
3. actor classes referenced by dynamic group templates;
4. deliberately exported/hot-load entry actors;
5. ordinary statically reachable actor classes/callables.

The linker emits only required generation-scoped descriptors. No reflection,
classpath scan, static initializer, or mutable global registry is required.

A new hot-load generation gets a new immutable catalog. Existing actors remain
pinned to their birth generation until drained/terminated.

## 14. Enforcement checklist

Compiler/type system:

- actor classes are persistent; actor fnc/routine callables remain one-shot;
- at least one effective public actor protocol endpoint exists;
- inherited endpoints count and cannot be narrowed to private;
- actors may implement multi-method protocol interfaces;
- `ActorRef<Concrete>` may narrow to a compatible `ActorRef<Interface>`;
- direct ActorRef protocol calls return `Future<T>`;
- public protocol methods are monomorphic at method level and non-reifiable;
- undeclared send/receive calls fail as unknown protocol methods; mailbox access remains runtime-private;
- constructor and every protocol parameter/return pass boundary sendability;
- actor effect restrictions propagate through helper call graphs;
- imported effect-unknown calls fail closed in actor context.

Runtime:

- one mailbox and one execution lease exist per actor;
- typed actor protocol calls use runtime-private request/reply metadata;
- only user arguments/replies pass transport validation and memory accounting;
- suspended protocol calls retain their request envelope until final continuation settlement;
- stop/failure settles pending protocol Futures and releases reservations exactly once;
- raw mailbox messages cannot enter a typed source-protocol dispatcher;
- await/timer/next-tick continuations resume only through the owning scheduler;
- queue/memory/fuel/lifetime limits are enforced before admission;
- PRIVATE/UNTRUSTED actor memory is reclaimable at actor termination;
- group/generation/quota reservations release exactly once;
- hot-reload code generations remain pinned for actor lifetime.
