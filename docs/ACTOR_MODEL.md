# Oreslang actor-model hardening contract

Oreslang keeps Erlang/BEAM's useful actor properties—cheap isolated workers,
message passing, monitors, crash containment, and supervisor logic—without
copying historical failure modes into the language contract.

## Required invariants

1. Mutable guest state has one actor owner.
2. Ordinary messages are copied/deep-frozen; shared regions are read-only.
3. Mailboxes are bounded by message count and estimated bytes.
4. Actor, monitor, and shared-region handles are runtime/isolate-scoped
   capabilities. A handle minted by one tenant runtime is rejected by another.
5. Source actors lower their message type to typed `Protocol<M>` admission metadata. Primitive, string, list/array, `Option`, actor-handle, and nominal class messages are checked again at runtime; richer structural/generic forms remain governed by the static checker rather than inventing stricter runtime rules. Dynamic interoperability may explicitly opt into a dynamic protocol.
6. FIFO dequeue is the mailbox primitive. Oreslang does not implement
   Erlang-style selective-receive scanning over old unmatched messages.
7. Backpressure is explicit: `trySend` reports admission failure and ordinary
   `send` fails fast rather than growing an unbounded queue.
8. Request/reply is message passing. `ask` creates a least-privilege,
   one-message reply actor rather than sharing a mutable promise with the target.
9. Monitors are one-way capability-scoped relationships. Restart strategies
   remain supervisor logic (or future language sugar lowered to that logic),
   rather than hidden mutable runtime state.
10. Actor fault isolation does not imply tenant security isolation. Graal
    isolates and `IsolatePolicy` remain the security/resource boundary.

## Capability transfer

`ActorRef`, `MonitorRef`, and `Shared` are not plain data. They are
authority-bearing handles tied to the runtime that minted them.

`ActorRuntime.freeze(value)` is therefore a pure-data operation and rejects
those handles. The actor-send boundary uses a separate runtime-aware freeze path
that permits handles only when their runtime id matches the destination runtime.

Cross-isolate/distributed messaging must serialize data and mint an explicit
remote capability. It must never pass the local Java handle through unchanged.

## Mailbox behavior and overload

User mailboxes have finite count and byte budgets. Message graphs are size
preflighted before copying, cyclic/deep graphs are rejected, and graph traversal
has a node budget to prevent stack/memory denial of service.

A graceful stop permanently closes admission before requesting termination. If
the mailbox is already full, the actor drains only already-admitted messages and
then exits; a full queue cannot cancel the stop request.

`SENT` means admitted to the mailbox, not processed. Actor execution may race with the sender after admission. `join` after stop/termination, or an explicit request/reply/ack protocol, is the synchronization point; source code and tests must never infer receiver execution order from `send` alone.

## Freezing rules

The freeze boundary:

- rejects unknown mutable host objects;
- rejects cyclic mutable graphs;
- rejects graphs deeper than the configured runtime maximum;
- rejects excessively large graphs during preflight;
- recursively validates nested values supplied through the trusted/generated `Sendable.freezeForSend()` freezer; generated transport values remain a trusted runtime ABI;
- rejects map/set collisions introduced by transport projection;
- rejects foreign-runtime capability handles.

Large immutable data can use `process.share_readonly`, but the shared handle is
still runtime-scoped. Sharing read-only bytes/data inside one tenant is not a
shortcut for cross-tenant memory sharing.

## Supervision

Monitors produce immutable `DOWN` messages. Runtime cleanup must continue even
if a watcher is gone or overloaded. Because `DOWN` is currently a real actor
message rather than a separate unbounded control mailbox, a typed watcher must
admit the `DOWN` shape in its protocol. Monitor registration fails immediately
when that contract is impossible. If a registered `DOWN` cannot be admitted
because the bounded watcher mailbox is full (or delivery otherwise fails), the
watcher itself fails closed with an observable actor failure; supervision loss
is never silently ignored.

The runtime does not hard-code one-for-one/one-for-all policies. Supervisor
actors can implement restart intensity, backoff, escalation, and child ordering
as normal typed actor logic. Future `supervisor { ... }` syntax should lower to
these primitives rather than inventing a second scheduler.

## Time and cancellation

Each actor policy supplies a wall-time budget. Compiler-inserted scheduler
safepoints enforce that budget cooperatively for actor message execution.
Native/FFI calls that cannot cooperate remain subject to the stronger isolate
sandbox and capability rules.

## Deliberately absent Erlang warts

- no global, non-GC user atom table;
- no process dictionary / hidden actor-local globals;
- no unbounded mailbox;
- no selective receive scan semantics;
- no ETS-style ambient shared mutable table primitive;
- no assumption that a local actor ref is a remote actor ref;
- no treating fault isolation as a hostile-code sandbox;
- no unrestricted native extension escape hatch;
- no charlist-as-string model.

The separate source type-system work provides `Option`, exhaustive matching,
ownership/borrows, explicit `any`/`unknown`, and formal-contract syntax. Those
features complement this runtime contract rather than being reimplemented here.

## Copy versus shared-readonly delivery

Oreslang does not conflate message isolation with immutability. A normal send of a mutable aggregate creates a deep actor-local copy, so the receiver may mutate its copy without creating an alias back into the sender. `Shared<T>` is the separate zero-copy/read-only mechanism: its backing graph is deeply frozen and mutation is rejected. This distinction avoids both Java/Go shared-memory races and the opposite wart where a safe copied value unexpectedly becomes immutable merely because the JVM transport used an unmodifiable collection.
