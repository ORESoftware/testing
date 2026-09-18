# Meta-Agent Control Plane

A single-process Rust daemon and Leptos SSR dashboard for observing AI-agent work, coordinating meta-tasks, and retaining bounded, reusable lessons.

The server is provider-neutral. OpenAI/ChatGPT-backed agents, Anthropic/Claude-backed agents, Google/Gemini-backed agents, local models, and custom runtimes all emit the same structured event protocol over HTTP, WebSocket, TCP, or UDP. The human operator opens one web UI to see agents, goals, tasks, progress, blockers, explicit reflections, lessons, transport activity, and memory pressure.

> This project records observable summaries, claims, evidence references, confidence, assumptions, risks, decisions, and next actions. It does not request, store, or expose hidden chain-of-thought or private model reasoning.

## One binary, four ingress paths

- **HTTP JSON** — reliable event ingestion at `POST /api/v1/events`.
- **WebSocket** — reliable interactive ingestion at `/ws/agent`.
- **TCP NDJSON** — reliable local-daemon or sidecar ingestion.
- **UDP JSON** — best-effort, low-authority telemetry only.

The same Tokio process serves Axum APIs, the Leptos SSR dashboard, WebSockets, TCP, UDP, health/readiness, Prometheus metrics, and OpenAPI.

## Quick start

Rust 1.97.1 is pinned in `rust-toolchain.toml`.

```bash
cargo run -- \
  --auth-token 'replace-with-at-least-16-bytes' \
  --protect-read-api
```

Open `http://127.0.0.1:8787`. The default listeners are:

| Transport | Address |
| --- | --- |
| HTTP / WebSocket / UI | `127.0.0.1:8787` |
| TCP NDJSON | `127.0.0.1:8788` |
| UDP JSON | `127.0.0.1:8789` |

The server refuses non-loopback bindings unless authentication and read protection are enabled, unless the operator explicitly selects the unsafe isolated-network override.

## Emit an event over HTTP

```bash
curl --fail-with-body \
  -H 'authorization: Bearer replace-with-at-least-16-bytes' \
  -H 'content-type: application/json' \
  --data @fixtures/progress-updated.json \
  http://127.0.0.1:8787/api/v1/events
```

Provider-neutral Rust examples are included:

```bash
META_AGENT_TOKEN='replace-with-at-least-16-bytes' cargo run --example tcp_progress
META_AGENT_TOKEN='replace-with-at-least-16-bytes' cargo run --example udp_heartbeat
```

## Event model

Every message uses a versioned envelope:

```json
{
  "protocol_version": "v1",
  "event_id": "018f5c8a-d5a7-7f7c-8d61-84f55a35fe91",
  "occurred_at": "2026-07-30T20:00:00Z",
  "agent": {
    "agent_id": "review-agent",
    "provider": "anthropic",
    "model": "claude",
    "instance_id": "local-daemon-1"
  },
  "session_id": "run-42",
  "correlation_id": "goal-17",
  "sequence": 8,
  "kind": "progress_updated",
  "data": {
    "task_id": "task-semantic-merge",
    "progress": 0.65,
    "summary": "Reconciled protocol and state semantics.",
    "next_action": "Run the transport conformance suite."
  }
}
```

See [`docs/protocol.md`](docs/protocol.md) for event shapes, reliability semantics, UDP policy, idempotency, ordering, and privacy boundaries.

## Bounded in-memory state

The MVP deliberately has no database. `lru::LruCache` instances bound agents, goals, tasks, lessons, recent events, and the independent event-ID idempotency window. Capacities are configurable, pressure and evictions are exposed in the snapshot/UI/metrics, and domain storage is isolated behind the `Store` API so a durable backend can follow without changing transports.

The reducer resolves out-of-order and conflicting observations semantically:

- an inferred task can later receive an authoritative definition without losing progress, attempts, blockers, reflections, or outcome state;
- an older task definition cannot overwrite a newer authoritative definition;
- task/goal/lesson identifiers are scoped by agent;
- repeated completion claims do not inflate counters;
- corrected outcomes move counters between success/failure buckets;
- a newer progress or start event can reopen a completed task cleanly;
- event deduplication has its own bounded LRU window and is not coupled to the shorter UI timeline.

## UDP safety boundary

UDP accepts only these low-authority telemetry events:

- `heartbeat`
- `progress_updated`
- `reflection_recorded`
- `error_observed`
- `agent_status_changed`

Agent registration, goal/task definitions, task start/completion, and learned lessons require HTTP, WebSocket, or TCP. UDP delivery, ordering, and acknowledgements are never treated as reliable. Use an authenticated private network or tunnel for remote UDP; the token does not provide encryption.

## Operator endpoints

| Route | Purpose |
| --- | --- |
| `/` | Leptos SSR operator dashboard |
| `/healthz` | Liveness and current revision |
| `/readyz` | Readiness |
| `/metrics` | Prometheus text exposition |
| `/openapi.json` | Runtime OpenAPI document |
| `/api/v1/snapshot` | Current bounded projection |
| `/api/v1/events` | HTTP event ingestion |
| `/ws/agent` | Agent ingestion socket |
| `/ws/ui` | Live UI invalidation stream |

## Configuration

All flags have `META_AGENT_*` environment-variable equivalents. Run `cargo run -- --help` for the complete list.

Important controls include listener addresses, auth/read protection, payload/datagram limits, maximum TCP connections, cache capacities, update-channel capacity, CORS policy, and structured logging.

## Validation

```bash
cargo fmt --check
cargo clippy --all-targets --all-features -- -D warnings
cargo test --all-targets --all-features
node --check scripts/dashboard.js
python scripts/verify_contract.py
```

The inline dashboard script is mirrored in `scripts/dashboard.js` so its JavaScript syntax can be checked independently. CI also builds the OCI image.

## Deployment

```bash
docker build -t meta-agent-control-plane:local .
docker run --rm \
  -p 8787:8787 -p 8788:8788 -p 8789:8789/udp \
  -e META_AGENT_AUTH_TOKEN='replace-with-at-least-16-bytes' \
  -e META_AGENT_PROTECT_READ_API=true \
  meta-agent-control-plane:local \
  --http-addr 0.0.0.0:8787 \
  --tcp-addr 0.0.0.0:8788 \
  --udp-addr 0.0.0.0:8789
```

Terminate TLS at a trusted reverse proxy for remote HTTP/WebSocket deployments. TCP and UDP need a private network, VPN, or transport-level security wrapper for confidentiality.

## Repository layout

```text
src/model.rs      versioned provider-neutral protocol
src/store.rs      bounded projections and semantic reducer
src/http.rs       Axum API, WebSockets, metrics, security headers
src/tcp.rs        bounded NDJSON TCP listener
src/udp.rs        telemetry-only UDP listener
src/ui.rs         Leptos SSR dashboard
src/daemon.rs     one-process lifecycle and listener binding
src/auth.rs       constant-time shared-token policy
src/config.rs     validated CLI/environment configuration
docs/             architecture, protocol, and checked-in OpenAPI
fixtures/         valid and invalid protocol fixtures
```

## Project tracking

The canonical Linear implementation issue is `DEN-1057`. GitHub publication is blocked until the GitHub app is installed for the `meta-agents-demo` organization and the canonical repository `meta-agent-control-plane.rs` is created or exposed.
