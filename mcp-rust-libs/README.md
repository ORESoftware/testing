# mcp-rust-libs

Small, security-hardened Rust crates shared by ORESoftware MCP servers.

This workspace extracts only behavior that is stable across multiple servers. Product tools, schemas, repository access, endpoint allowlists, authorization, confirmation rules, and mutation policy remain in their owning repositories.

## Crates

| Crate | Purpose |
| --- | --- |
| `ore-mcp-safety` | Checked byte budgets, UTF-8-safe truncation, redaction, and telemetry-label validation. |
| `ore-mcp-config` | Strict `flags2env` startup parsing and deterministic config discovery without leaking command-line secrets. |
| `ore-mcp-telemetry` | Stderr-only JSON logs, optional OTLP traces/metrics, safe resource attributes, and tool-router instrumentation. |
| `ore-mcp-http` | Credential-free diagnostic HTTP with HTTPS/loopback policy, disabled redirects, timeouts, and bounded streaming bodies. |
| `ore-mcp-runtime` | Thin stdio lifecycle helpers that preserve visible `rmcp` behavior and protocol-pure stdout. |
| `ore-mcp-testkit` | Bounded spawned-process JSON-RPC/MCP conformance support. |

## Design rules

1. No product-specific tools or business logic.
2. No implicit credential forwarding.
3. No logging of tool arguments, result bodies, user identity, credentials, or unbounded values.
4. No moving Git branch dependencies in consuming production repositories.
5. Every extraction must be backed by parity fixtures before a consumer deletes its local implementation.
6. Consumers retain explicit extension hooks and may document a local exception instead of forcing behavior into a shared crate.

## Development

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets --all-features
cargo test --workspace --no-default-features
cargo doc --workspace --all-features --no-deps
```

The workspace currently targets Rust 1.95 or newer and is validated with Rust 1.97.1. Dependencies are locked before the first release and consumers must pin a reviewed tag or immutable revision.

See [`docs/architecture.md`](docs/architecture.md) and [`docs/migration.md`](docs/migration.md).
