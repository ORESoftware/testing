# BeamScale host process lifecycle

BeamScale extends its existing runtime lifecycle with an external per-host
lifecycle agent that can reclaim resources from warm-idle runtimes while keeping
scheduler placement, runtime epochs, and durable application state authoritative.

The provider-neutral lease/fencing semantics and cross-runtime lifecycle corpus
live in `ORESoftware/ores-locks-and-leases` (DEN-1154; merged PR #127).
BeamScale supplies queue/admission, placement, runtime-health and effect adapters.
The host must pass the same stale-resume/token-reuse rules before lifecycle state
can become routable.

## Relationship to the existing runtime lifecycle

Do not build a second scheduler. Existing operations remain authoritative:

```text
ensure_runtime
start_runtime
mark_warm_idle
drain_runtime
terminate_runtime
get_runtime_health
get_runtime_metering
```

Host suspension adds resource states around an already placed runtime:

```text
hot -> warm_idle -> frozen -> hot
hot -> warm_idle -> hibernating -> hibernated -> resuming -> hot
```

`runtime_epoch` / deployment generation still identifies the BeamScale runtime.
The lifecycle record adds a separate monotonic `placement_epoch`, record revision,
and distributed fencing token. A controller must satisfy both generations before
performing a local effect.

## Host topology

```text
system.slice
  bmscl-lifecycle-agent.service       # trusted host authority; never suspendable
  BeamScale host scheduler/router     # never suspendable

beamscale-workloads.slice             # suspendable runtime/process scopes only
  <logical-workload>.scope
```

Tenant-influenced code continues to launch through
`ORESoftware/ores-proc-isolation-cli`. Suspension does not weaken that isolation
boundary and must never provide tenant code with cgroup/checkpoint authority.

## Unix socket trust boundary

The cooperative supervisor socket and trusted host-control socket must not share a
writable parent directory.

```text
/run/beamscale-lifecycle/              root:root 0755
  product/                             <supervisor-user>:beamscale-lifecycle-control 2770
    control.sock                       cooperative quiesce/resume only
  host/                                root:root 0700
    control.sock                       trusted scheduler/process attestation
```

The exact paths are:

```text
/run/beamscale-lifecycle/product/control.sock
/run/beamscale-lifecycle/host/control.sock
```

This split is required because write permission on a Unix-socket parent directory
is enough to unlink or replace a socket entry even when the socket inode itself is
root-owned. The supervisor therefore receives write access only to `product/` and
its systemd sandbox marks `host/` inaccessible. The trusted host/control-plane
service owns `host/`; tenant/runtime processes never receive directory write,
lease, fence, scheduler-admission, or process-attestation authority there.

The product socket remains cooperative evidence. Trusted lifecycle snapshots and
post-wake admission must be derived from host/scheduler/process state through the
v2 host-control protocol and re-attested after wake before routing is reopened.

## Locking without a circular dependency

Initial locking uses the Cloudflare Durable Object backend exposed by
`ORESoftware/ores-locks-and-leases`. BeamScale does not depend on a BeamScale
native lock service to manage BeamScale processes.

The lock key is independent of physical host placement and is derived by the
shared lifecycle lease adapter, not reimplemented by BeamScale:

```text
process-lifecycle/beamscale/<cluster>/<workload>
```

`<cluster>` is BeamScale's stable environment/region/cell identity and `<workload>`
is the logical shard/runtime identity. Node identity is deliberately excluded so
old and new hosts still contend on one lock during reassignment.

When a shard moves from node A to node B, both controllers contend on the same
lease. A new placement increments `placement_epoch` and persists the new fencing
token; node A is then unable to publish or resume the replacement runtime.

Fiducia may replace Cloudflare through the same generic `Lease` interface.
Do not use BeamScale's future durable-lock service for this host lifecycle path:
that would make BeamScale availability depend circularly on BeamScale itself.

## Suspend eligibility

A runtime is eligible only when all are true:

- scheduler placement still assigns the logical workload to this node and runtime
  epoch/generation;
- queue depth is zero;
- in-flight invocation count is zero;
- the zero-work condition has held for the configured idle grace interval;
- no drain, deployment switch, migration, or termination is concurrently active;
- the chosen execution profile explicitly allows the requested suspend strategy.

After eligibility is observed, acquire the fenced lease, stop new admissions,
re-read placement, and re-check queue/in-flight state before any process effect.
If demand appeared, cancel quiescing and keep the runtime hot.

## Resource policies

### Freeze

Use cgroup-v2 freeze/thaw for the first rollout. It stops CPU scheduling for the
managed hierarchy while retaining the process memory image. It is useful for warm
capacity that should wake quickly but does not reclaim RSS.

### Hibernate

Hibernate means verified checkpoint/snapshot plus process/VM termination, which
can actually release RSS. It is **not** an authority granted to the generic
freeze agent.

- standard bare-process FaaS: terminate/reconstruct is the default until a
  separately reviewed checkpoint helper proves a process tree compatible;
- Phoenix / durable-actor Firecracker profiles use their explicit VM
  snapshot/restore adapter rather than silently applying CRIU;
- the privileged helper surface is typed to a runtime identity and fixed managed
  scope; never expose arbitrary PID, path, signal, command, or cgroup mutation;
- unsupported, ambiguous, corrupt, or fence-lost state fails closed and never
  becomes `hibernated` or routable.

Checkpoint/snapshot artifacts are runtime optimizations, never application
durability or distributed lifecycle authority. Application state remains external
and replicated according to the workload contract.

## Demand-triggered wake

Polling/cron is only a repair loop. Normal dispatch must wake first:

1. queued demand targets a logical workload;
2. scheduler admission calls lifecycle `ensure_running` before dispatch;
3. acquire a fresh logical request on the same workload lease and re-read
   runtime/placement generations;
4. prove its fencing token is newer than the stored lifecycle watermark;
5. thaw or restore through the admitted effect adapter;
6. wait for BeamScale readiness and generation checks;
7. atomically advance the lifecycle watermark under that fence;
8. reopen admission and dispatch.

This keeps wake latency tied to demand rather than a reconciliation interval.

## Reassignment and scale-down

Before removing a host:

1. mark it unavailable for new placement;
2. acquire per-workload lifecycle leases as workloads migrate;
3. increment placement epoch for each new owner;
4. persist replacement placement under the newer fence;
5. restore/start on the destination and pass readiness;
6. drain/terminate or leave safely frozen on the source;
7. remove the source only after no durable record assigns it active authority.

A stale source host may still perform a harmless local cleanup, but it cannot
publish state or admit traffic for the replacement placement.

## Observability

Record logical workload id, runtime epoch/generation, placement epoch, fencing
token, source/destination node, suspend strategy, queue/in-flight observations,
action duration, checkpoint size/digest, CPU/RSS before/after, restore latency,
and failure reason. Export through ORES telemetry without logging credentials or
tenant secret material.
