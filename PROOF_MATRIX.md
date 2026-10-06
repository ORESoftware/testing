# Concurrency proof matrix

Pinned source snapshot:

`ores-truffle-oreslang/oreslang-source.java@e1e84d071beebb59671eeae28e2344f9a6bc3940`

This is the head of the ActorGroup event-bus stack and includes the channel/select/mailbox work from PR #252.

## Executable proofs

| Area | Proof | Status |
|---|---|---|
| Future + scheduler | Awaiting a channel Future unwinds and resumes through a fresh owning-scheduler dispatch | executable |
| Producer isolation | Channel/Future completion thread never executes guest continuation code | executable |
| Mailbox/channel unification | ActorCell mailbox transport is `ChannelRuntime.Channel<MessageEnvelope>` | executable |
| `nb readch` lowering substrate | Pending channel Future can re-enter the actor through a runtime continuation envelope | executable |
| `nb writech` Future surface | `writeAsync` returns a pending `OresFuture<void>` until a reader commits | executable |
| `nb writech` callback substrate | The same write registration can enqueue a callback back through the actor mailbox/scheduler lease | executable runtime substrate |
| select / nb select | `ChannelRuntime.SelectSet.selectAsync` is the one registration substrate; source parser supports static/dynamic nb select | covered by pinned runtime + syntax proof |
| ActorGroup event bus | Subscription is channel-backed; event Future completion re-enters subscriber actor through mailbox continuation | executable |
| Shared actors | Shared actor progresses on SHARED dispatcher | executable |
| Iso/private actors | Private actor progresses on separate PRIVATE dispatcher | executable |
| Hungry actors | Dedicated native carrier does not consume/starve ordinary actor dispatcher | executable |
| Cancellation | Actor cancellation detaches outstanding channel continuation and late completion cannot resurrect actor | executable |
| Event publisher isolation | Event publisher never runs subscriber guest callback inline | executable |

## Contract gaps this snapshot must not pretend are solved

### 1. Pending blocking source waits inside actors

PR #252 explicitly says arbitrary pending blocking actor `await`, `readch`, `writech`, and `select` still need source-frame lowering onto the resumable `OresScheduler.Task` ABI.

Already-ready operations work. A genuinely pending blocking operation currently fails closed rather than parking an actor carrier. This is safe, but it is not the final semantics.

**Required proof before merge:** a source-level actor suspends on each pending operation, another actor continues on the same bounded dispatcher, and the suspended actor later resumes on a fresh dispatch without retaining a Java interpreter stack.

### 2. `nb cb writech` syntax

Required distinction:

```ores
val Future<void> f = nb writech output, value;

nb cb writech output, value || -> {
  // callback body
};
```

The first form returns a Future. The `cb` form is a void callback surface. Its completion callback must be enqueued back to the owning actor/root scheduler domain; it may never run inline on the producer/channel-completion thread.

The runtime substrate is proven by `ConcurrencyIntegrationProofTest`. Parser/type/lowering support is not yet present; the contract test is deliberately disabled until implemented.

### 3. Untrusted actors

The pinned #255/#252 runtime has only `PRIVATE` and `SHARED` `ActorKind` values. The separate actor-class hardening stack discusses `UNTRUSTED`, but it is not converged into this source snapshot.

**Required proof:** untrusted actors execute on an independently bulkheaded pool/isolation domain and host force-cancel revokes that isolation boundary before logical teardown.

### 4. ActorMailman

The pinned runtime has ActorGroups and the event bus, but no `ActorMailman` implementation/class or group outbox/mailman event loop in this stack.

Do not claim Mailman correctness from ActorGroup/event-bus tests. Converge the Mailman branch first, then prove:

- one logical serialized mailman per group;
- bounded MPSC group outbox;
- mailman runs on CONTROL pool, not one scheduler per actor;
- mailman never scans/selects every actor mailbox;
- mailbox writes enqueue runnable actors;
- cancellation/teardown cannot strand outbox entries.

### 5. Persistent `actor` classes / full spawn syntax convergence

The active actor-class hardening work is on a separate stack. This harness currently proves the runtime actor substrate (`spawnShared`, `spawnPrivate`) and source actor-callable/channel syntax, not the final persistent `define actor Worker as ... end` object-model integration.

## Acceptance rule

A feature moves from **contract gap** to **proven** only when:

1. there is an executable test here;
2. it uses the real pinned runtime/compiler implementation, not a mock;
3. it proves scheduler/carrier ownership, not only output values;
4. cancellation and late-completion behavior are covered where applicable;
5. bounded-resource behavior is covered (mailbox/channel/outbox/backpressure);
6. the proof runs in CI on a clean checkout.


## Current CPS gate
Pending source-level `await`, `readch`, `writech`, and `select` execute through the heap-owned `SourceTask` / `OresScheduler.Task` state machine. Actor callables use the same mailbox-backed scheduler lane. The proof suite includes end-to-end pending rendezvous channel and static-select cases.
