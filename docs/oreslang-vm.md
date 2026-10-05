# Oreslang VM, hot loading, and scheduler domains

Status: runtime contract implemented as a stack on the ActorGroup/mailman design.

## Kernel/user boundary

Oreslang has an explicit **OresVM** runtime/kernel boundary.

`OresContext` is the Truffle/Graal integration object for one language context.
`OresVM` owns physical scheduler infrastructure, hot-code generations, and
isolate placement. Logical `ActorRuntime` instances attach to that VM and keep
per-context actor/group registries and policy ceilings.

Trusted Oreslang application code may execute in the same OS process and main
Graal isolate as OresVM. That does **not** make it kernel privileged.

The core invariant is:

> same process / same isolate does not imply same authority.

Ordinary actor/application code receives no `OresVM`, `OresContext`,
`ActorRuntime`, `ActorCell`, executor, scheduler, Truffle node, Polyglot
`Context`, Polyglot `Source`, raw fd, or native pointer.

Runtime interaction crosses narrow Oreslang capabilities/syscalls such as:

- `ActorRef` / inbox send;
- actor-local `spawnPrivate`, `spawnShared`, and `spawnInGroup`;
- immutable/read-only sharing capabilities;
- timers/futures/await;
- ActorGroup `emit`;
- bounded HTTP request/response capabilities.

`OresVM` is package-private. `dev.oreslang.runtime` is not exported by the
JPMS module descriptor. Raw generation `Context`/`Source` objects are
runtime-internal.

Host/embedder APIs remain an explicit privileged escape hatch, analogous to
Java JNI/FFM or Go unsafe; they are not part of the safe Oreslang guest model.

## Four scheduler domains

The VM owns exactly four guest/control execution pools:

1. **CONTROL** — main/root execution, supervisors, ActorGroup mailmen, and VM maintenance.
2. **SHARED_ACTOR** — shared-address-space actors.
3. **ISOACTOR** — private/memory-confined actors.
4. **UNTRUSTED_ACTOR** — sandboxed untrusted actors.

Timers, I/O reactors, watchdogs, GC threads, and Graal/JVM service threads are
runtime infrastructure. They are not additional actor scheduler domains and may
not execute actor guest code directly.

Each scheduler is M:N: actors/mailmen are logical tasks multiplexed over bounded
carrier threads. A logical actor or mailman does not own an OS thread.

The production default keeps the elastic carrier policy: a small floor with
bounded headroom. The control pool is physically distinct from all actor pools.

## Control plane authority

Supervision and ordinary actor execution do not share an actor scheduling queue.

The control plane runs:

- root/main Oreslang execution;
- supervisor lifecycle and failure policy;
- one logical `ActorMailman` per `ActorGroup`;
- bounded VM maintenance such as generation reclamation.

Running on a CONTROL carrier does not itself grant supervisor authority.

User mailman callbacks receive only `ActorGroupContext`, currently exposing
group identity/count and bounded `send`. They do not receive `ActorRuntime`
or OresVM.

Actor code receives `ActorContext`, not `ActorRuntime`. The actor context is
a narrow capability surface, so actors cannot close the runtime, install hooks,
resize schedulers, or reach VM implementation state.

## Actor scheduler isolation

Scheduler domains and Graal-isolate placement are deliberately different axes.
A trusted isoactor gets private/confined actor memory without moving into another
Graal isolate.

```text
OS process
└── PRIMARY_GRAAL_ISOLATE
    ├── OresVM / CONTROL
    │   ├── main/root
    │   ├── supervisors
    │   ├── ActorGroup mailmen
    │   └── generation reclamation
    ├── SHARED_ACTOR
    │   └── trusted shared actors
    ├── ISOACTOR
    │   └── trusted private/confined-memory actors
    └── spawned/subsequent Graal isolate(s)
        └── UNTRUSTED_ACTOR
            └── adversarial sandboxed actors
```

The hard invariant is:

> memory confinement does not imply a Graal-isolate boundary; only untrusted
> actors cross into a spawned/subsequent Graal isolate.

Actor invariants:

- one active execution lease per actor;
- runnable-actor queues rather than mailbox scans;
- bounded scheduler quanta;
- await/timer/I/O completion re-enqueues work instead of running actor code;
- isoactors do not gain shared-memory authority;
- untrusted actors cannot create threads or child actors;
- user actor code never receives the owning runtime object.

## Hot-code generations

OresVM owns versioned hot-code generations.

The lifecycle is:

```text
STAGED -> STARTED -> ACTIVE -> DRAINING -> CLOSED
                  \-> FAILED
```

Staging validates source/IR and creates the destination execution context without
publishing it. Starting evaluates the staged generation. Activation publishes a
successfully started generation atomically. A previously active generation then
enters DRAINING.

A failed staged/startup generation never replaces the currently healthy active
generation.

Generation IDs are process-monotonic. Activation is per code unit so independent
files/services do not unnecessarily replace one another.

## GenerationLease

Every actor born inside a hot-loaded generation automatically acquires an
internal `GenerationLease`.

The lease:

- is stored by `ActorCell`, never by guest code;
- pins the actor to its birth generation;
- is acquired before actor identity becomes externally usable;
- is released exactly once after actor finalization;
- prevents a draining generation from closing while old actors are alive.

Actor finalization first removes the actor from its runtime registry, then
releases its generation lease. If that is the final lease of a draining
generation, reclamation is submitted to the VM CONTROL plane.

Guest evaluation and Polyglot `Context.close()` never run while holding the
hot-loader monitor. Reclaimable generations remain in the VM ownership registry
until their actual context close completes so VM shutdown cannot race them out
of reach.

The same lease type is also available to pin request/stream work that must remain
on one code generation for its full lifetime.

## Trusted hot loading

Trusted application generations run in the main OS process/main Graal isolate.

`TRUSTED_JIT` and `TRUSTED_ISOACTOR_JIT` generations both remain in the
primary Graal isolate. The latter still strips host escape hatches and executes
on the ISOACTOR scheduler/memory domain; that confinement is semantic/runtime
memory isolation, not a second Graal isolate.

This is the normal Java/Go-style model: safe guest code and runtime
implementation are co-resident, while type safety, managed references,
capability APIs, package/module visibility, actor-memory ownership, and the
language verifier form the logical wall.

Trusted does **not** mean privileged. Trusted actor code still cannot obtain
OresVM or runtime implementation objects.

Supervisor/control-plane code may remain comparatively stable/AOT while trusted
application generations are JIT/hot-reloadable.

## Untrusted hot loading

Untrusted code is a different security domain.

An `UNTRUSTED_JIT` generation is normalized to an adversarial deny-by-default
policy and built with Graal:

- `SandboxPolicy.UNTRUSTED`;
- `spawnIsolate(true)`;
- bounded isolate/guest heap;
- bounded CPU time;
- bounded AST depth;
- one guest thread;
- bounded output;
- no HostAccess;
- no polyglot access;
- no native access;
- no guest-created threads;
- no filesystem/network host IO.

Generic ambient capabilities are stripped. HTTP request/response access is
provided through owner-bound object capabilities rather than raw sockets/fds.

Production `OresContext` actor runtimes record their physical placement as:

- `MAIN_GRAAL_ISOLATE`, or
- `SPAWNED_GRAAL_ISOLATE`.

Direct `UNTRUSTED` actor execution is rejected in `MAIN_GRAAL_ISOLATE`.
Therefore untrusted user code cannot silently fall back to the trusted/main
context.

Low-level direct `new ActorRuntime(...)` construction is marked
`HOST_EMBEDDER`; it exists for embedders and runtime tests and is outside the
safe guest security contract.

## Loader authority

Hot loading is control-plane authority.

The supervisor policy must contain `HOT_CODE_LOAD`. That capability is removed
from the guest policy before the generation starts.

Trusted isoactor and untrusted JIT generations strip host escape hatches such as:

- FFI;
- native access;
- reflection;
- thread creation;
- polyglot access.

A generation therefore cannot use the authority by which it was loaded to load
or replace arbitrary new code itself.

## Opaque context/generation binding

OresVM binds a Polyglot context back to its owning VM and generation with
unguessable runtime-managed binding tokens.

These tokens are host plumbing, not guest capabilities:

- user code never receives an OresVM reference;
- a generation binding resolves only inside its owning VM;
- invalid/retired bindings fail closed;
- bindings are revoked when generations close;
- dedicated VM shutdown revokes all remaining generation bindings.

This lets lazy Truffle context initialization still resolve the correct VM and
code generation without putting VM objects into guest values.

## VM ownership and lifecycle

`OresVM.process()` is host/process owned. Disposing one ordinary `OresContext`
closes that context's logical runtime but must not shut down the process VM's
physical pools because other contexts/generations may still use them.

Explicit host/test `new ActorRuntime(...)` construction owns a dedicated VM
scheduler set. Dedicated OresVM shutdown closes its hot-load managers,
generations, contexts, bindings, and scheduler resources.

```text
OresLanguage
    ↓
OresContext
    ↓
OresVM
    ├── CONTROL
    ├── SHARED_ACTOR
    ├── ISOACTOR
    ├── UNTRUSTED_ACTOR
    ├── generation registry / hot loader
    ├── Graal isolate placement
    └── timer/watchdog/reactor services
```

## Remaining work

The runtime now fails closed rather than executing an untrusted actor directly
inside the main Graal isolate. The next integration step is an OresVM broker that
takes a source-level untrusted spawn request from trusted code, selects/loads the
appropriate `UNTRUSTED_JIT` generation in a spawned Graal isolate, and exposes
only serialized inbox/outbox and explicit request/response capabilities across
that boundary.

CONTROL scheduling should also gain explicit priority/reserved supervisor
capacity so a mailman flood cannot starve lifecycle supervision.
