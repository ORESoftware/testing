# Protocol v1

## Privacy boundary

The protocol carries observable artifacts that help a human or another agent evaluate work: goals, task state, concise summaries, confidence, assumptions, evidence references, alternatives, risks, blockers, next actions, outcomes, and reusable lessons.

Clients must not send hidden chain-of-thought, private scratchpads, raw credentials, authorization headers, or secret-bearing prompts. A concise rationale or reflection should state the conclusion and evidence without exposing private model reasoning.

## Envelope

All transports deserialize the same `EventEnvelope` JSON object. Unknown fields inside typed event data are rejected. The current protocol version is `v1`.

Required envelope fields:

- `protocol_version`
- `event_id` — non-nil UUID and bounded idempotency key
- `occurred_at` — client event time
- `agent.agent_id`, `agent.provider`, `agent.model`
- `kind`
- `data`

Optional correlation fields:

- `agent.instance_id`
- `session_id`
- `correlation_id`
- `sequence`

`sequence` is advisory in the MVP. Event-time conflict rules and event-ID deduplication are authoritative. A future protocol revision may negotiate strict per-session sequence enforcement.

## Event kinds

| Kind | Purpose | UDP |
| --- | --- | --- |
| `agent_registered` | Display metadata and capabilities | No |
| `heartbeat` | Presence, status, active task, load | Yes |
| `goal_declared` | Goal and success criteria | No |
| `task_created` | Task definition and dependencies | No |
| `task_started` | Attempt and concise plan summary | No |
| `progress_updated` | Self-reported progress, summary, blocker, next action | Yes |
| `reflection_recorded` | Confidence, assumptions, evidence, alternatives, risks | Yes |
| `lesson_learned` | Reusable evidence-bearing heuristic | No |
| `error_observed` | Structured failure and proposed recovery | Yes |
| `task_completed` | Outcome, summary, artifacts, actual result | No |
| `agent_status_changed` | Agent lifecycle status | Yes |

UDP is best effort. It can lose, duplicate, truncate, or reorder datagrams. The server therefore rejects high-authority events over UDP even when the JSON is otherwise valid.

## Reliable transport framing

### HTTP

Send one `EventEnvelope` to `POST /api/v1/events`. Authenticate with `Authorization: Bearer <token>` when configured. A new event returns `202`; a duplicate within the idempotency window returns `200` with `duplicate: true`.

### WebSocket

Connect to `/ws/agent`. Authenticate during upgrade with a Bearer header or, only when header injection is unavailable, the `token` query parameter. Send either an `EventEnvelope` or a `TransportFrame` JSON message. The server replies once per message with an acknowledgement or structured error.

### TCP

Send UTF-8 JSON followed by `\n`. Each line is either an `EventEnvelope` or:

```json
{
  "token": "shared token when configured",
  "event": { "protocol_version": "v1" }
}
```

The server replies with one newline-delimited acknowledgement/error per input frame. Lines over the configured maximum are rejected.

### UDP

Send one complete `EventEnvelope` or `TransportFrame` per datagram. Keep payloads below the configured datagram ceiling. A response is advisory only and must not be treated as durable acknowledgement.

## Idempotency and ordering

`event_id` identifies one semantic observation across retries and transports. The server retains event IDs in an independent bounded LRU window. A replay inside that window does not mutate projections or advance the revision.

For task execution fields, older events do not overwrite newer projected state. An authoritative task definition may enrich a task inferred from an out-of-order execution event, but an older definition cannot replace a newer authoritative definition.

The MVP does not promise exactly-once processing after idempotency eviction or process restart. Durable uniqueness and replay require a persistent backend.

## Acknowledgement

```json
{
  "accepted": true,
  "duplicate": false,
  "event_id": "018f5c8a-d5a7-7f7c-8d61-84f55a35fe91",
  "revision": 42,
  "transport": "tcp",
  "received_at": "2026-07-30T20:00:00.100Z"
}
```

The server revision is a coherent projection watermark, not the client sequence.

## Limits

Text, list, metadata, payload, frame, datagram, connection, cache, and broadcast capacities are bounded. Validation failures and transport-policy failures increment rejection counters. Tokens are redacted from `Debug` output, but clients must still avoid embedding secrets in event payloads.
