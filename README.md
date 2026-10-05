# Oreslang Async / Actor / Channel Proof Harness

This repository is an executable integration harness for the Oreslang concurrency stack.

It is intentionally designed to prove cross-layer invariants involving:

- OresFuture / Awaitable / async-await scheduler resumption
- actor spawn and actor execution domains
- shared, private/iso, untrusted, and hungry actors
- ActorGroup / ActorMailman routing
- mailbox = policy around Channel<Envelope>
- readch / writech / nb readch / nb writech
- static and dynamic select / nb select
- ActorGroup event bus delivery and backpressure
- cancellation, fairness, progress, and carrier starvation safety

The first proof branch is pinned to the active Oreslang source stack that includes
PR #252 (channels/select/mailbox/cancellation) and PR #255 (ActorGroup event bus).

Source snapshot: `ores-truffle-oreslang/oreslang-source.java@e1e84d071beebb59671eeae28e2344f9a6bc3940`.

See `PROOF_MATRIX.md` on the proof branch for the required invariants and known gaps.
