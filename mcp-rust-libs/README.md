# mcp-rust-libs

Canonical shared infrastructure for ORESoftware MCP servers. Rust is the primary server-runtime implementation; JSON Schema Draft 2020-12 contracts, generated validators/types, fixtures, and conformance tooling are shared across Rust, TypeScript, Dart, and Gleam.

This scaffold implements the repository boundary agreed in Linear `DEN-957`/`DEN-959`:

- shared runtime, strict startup configuration, telemetry, diagnostic HTTP, safety, contracts, and testkit policy live here;
- product tools, authorization, mutations, upstream clients, and business policy stay in their owning repositories;
- official MCP protocol models remain upstream-owned and pinned rather than copied;
- `contracts/` is the source of truth for ORESoftware-owned transport-neutral DTOs;
- generated code is committed, carries its schema digest, and is checked for drift.

## Implemented first slice

The Rust workspace contains eight narrow crates. The six mature Rust implementations from the validated incubator are the behavioral base; the polyglot scaffold contributes contracts and additive policy rather than replacing stronger code:

- `ore-mcp-contracts` — canonical generated DTOs plus stable value-level validation errors;
- `ore-mcp-safety` — atomic byte budgets, borrowed and owned UTF-8-safe truncation, item/depth/timeout limits, label validation, redaction policy, and the canonical generated `SafeError`;
- `ore-mcp-http` — credential-incapable public diagnostics with strict URL and metadata-endpoint policy, no ambient proxies, no redirects, no implicit retries, bounded timeouts, streamed body limits, and JSON helpers;
- `ore-mcp-config` — runtime-independent operational configuration plus the previously validated, optional pinned `flags2env` adapter and explicit output allowlist;
- `ore-mcp-runtime` — access posture, validated server identity, required bootstrap ordering, and optional `rmcp` stdio function/macro adapters;
- `ore-mcp-telemetry` — stderr-only JSON logging, secret-safe OpenTelemetry resources, deterministic shutdown, unified runtime identity, and optional `rmcp` tool instrumentation;
- `ore-mcp-testkit` — the mature bounded real-process stdio harness plus portable fixture and stdout-purity helpers;
- `ore-mcp` — a feature-gated umbrella crate.

The TypeScript package provides Zod schemas plus stable validation codes and JSON-pointer paths. Dart provides closed-object decoding and valid-by-construction models. Gleam provides generated typed models, validation, and deterministic encoding; strict raw-JSON closed-object decoding remains a follow-on generator slice.

## Initial contract

`result-envelope` demonstrates a closed, discriminated success/error contract. The canonical corpus currently contains 38 focused valid and invalid fixtures covering required fields, unknown properties, bounds, defaults, explicit `null`, integer semantics, control characters, path indexing, and Unicode code-point limits.

Canonical schema SHA-256:

```text
2f28d646fa7ada0b3d2edd3c5ee19d663543e11117bbb37f4609307c6a9ff61e
```

## Deterministic generation

`tooling/schema-codegen/generator-map.json` registers six generated outputs. The initial generator renders locked templates with schema identity, generator version, and digest provenance; `scripts/regenerate-generated.py --check` fails on any drift and `--write` restores the registered outputs. General schema-AST emission for additional contracts is the next `DEN-967` slice, not something this initial template-backed generator pretends to complete.

## Repository map

- `contracts/` — versioned schemas, lockfile, exact fixture expectations, and valid/invalid fixtures.
- `packages/rust/` — narrow crates plus the `ore-mcp` umbrella crate.
- `packages/typescript/` — generated Zod contracts and small runtime helpers.
- `packages/dart/` — generated immutable DTOs and strict runtime decoding.
- `packages/gleam/` — generated public types, validation, and deterministic JSON encoding.
- `tooling/schema-codegen/` — Rust code-generation CLI and registered templates.
- `tooling/conformance/` — portable fixture parity runner and report format.
- `scripts/` — deterministic generation, layout, source, secret, and Zed-package checks.
- `reports/` — machine-readable conformance and environment validation results.

## Local verification

```sh
make check
sh scripts/verify-zed-package.sh "$PWD"
sh scripts/check-native-toolchains.sh
```

The portable gate checks generation drift, source invariants, manifest/layout rules, schema locks, fixture expectations, and all 38 canonical fixtures. Native package builds require their ordinary Rust, Node/npm, Dart, and Gleam toolchains. The checked-in CI pins those toolchains and the GitHub Actions by immutable commit SHA.

## Extraction order

1. Freeze the portable schema profile, stable error vocabulary, fixture corpus, and generator contract.
2. Extract safety, strict configuration, public diagnostic HTTP, runtime bootstrap, and telemetry policy with parity tests.
3. Keep stable policy independent of SDK versions; expose current OpenTelemetry 0.32 and `rmcp` 2.2 adapters behind features while retaining a migration path for the fleet’s OpenTelemetry 0.27 servers.
4. Pilot Canonical, Messaging Intel, Sonus Auris, Shared Auth, Fiducia, and Akrion without changing product tool schemas or authorization.
5. Migrate the remaining fleet only against immutable reviewed releases.

## Status and limits

The authoritative `ORESoftware/mcp-rust-libs` repository still does not exist and remains blocked by Linear `DEN-319`. The exact workspace is carried in draft `ORESoftware/testing#2` only for GitHub Actions validation and later subtree transfer; that PR is deliberately **not mergeable into the legacy `testing` product branch**. Fleet inventory documentation was separately merged through `ORESoftware/ai-agent-coordinator.rs#14`.

The earlier six-crate Rust carrier passed formatting, strict Clippy, tests, docs, release build, MSRV, RustSec, cargo-deny, and Zed-layout checks at commit `f951d9f559ccbbdc8539eccf6f9ba24a32f3d95d`. This semantic merge preserves that code and layers the schema/polyglot workspace around it. The expanded native matrix must still run on the new carrier head; no package publication or authoritative repository acceptance is claimed until those jobs and the target-repository transfer complete.
