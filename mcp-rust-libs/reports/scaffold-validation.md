# Semantic-merge validation report

Date: 2026-07-30

## Reconciled inputs

- validated Rust-only carrier head: `f951d9f559ccbbdc8539eccf6f9ba24a32f3d95d`;
- successful Rust carrier workflow: `30595725450`;
- polyglot schema scaffold: Rust, TypeScript/Zod, Dart, Gleam, JSON Schema 2020-12, deterministic generation, and 38 canonical fixtures;
- fleet inventory/documentation PR: `ORESoftware/ai-agent-coordinator.rs#14`, already merged.

The merge did not select one scaffold wholesale. Mature Rust configuration, HTTP, runtime, safety, telemetry, and real-process testkit code remain the behavioral base. New schema contracts, validated identity, operational allowlists, metadata/link-local rejection, no-proxy/no-retry policy, portable fixture helpers, polyglot packages, and coordinated Zed layout are additive.

## Passed in this construction environment

- deterministic generator drift check for six registered outputs;
- Python, JSON, TOML, and Rust delimiter/static invariant checks;
- Zed manifest target/layout, schema-lock, provenance, fixture-set, secret-marker, cache-path, and symlink checks;
- portable conformance over 38 canonical fixtures with exact acceptance, stable error code, and JSON-pointer path assertions;
- source-manifest regeneration and verification;
- JavaScript syntax checks for committed `dist/*.js` outputs;
- full Zed smoke-test entrypoint after semantic reconciliation.

Schema SHA-256: `2f28d646fa7ada0b3d2edd3c5ee19d663543e11117bbb37f4609307c6a9ff61e`

## Previously validated and preserved

The Rust-only carrier previously passed actionlint, lockfile integrity, rustfmt, strict Clippy across all targets/features, all-feature and no-default-feature tests, rustdoc with warnings denied, optimized release build, Rust 1.95 MSRV, RustSec audit, cargo-deny, and Zed package smoke checks. Its exact lockfile is retained under `reports/rust-validated-baseline/` as evidence, not reused as the active lock for a different eight-crate graph.

## Native gates on the semantic-merge head

The local environment does not contain Cargo/Rust, Dart, or Gleam. Its npm registry mirror also lacks the requested current `@types/node` package, so the real Zod/Vitest install could not be repeated locally. The carrier workflow resolves fresh Rust/npm lockfiles and runs the complete Rust 1.95/1.97, Node 22/24, Dart 3.8/3.12, Gleam 1.17/OTP 29, audit, package, and conformance matrix.

## Publication status

The authoritative target repository still returns 404. Draft `ORESoftware/testing#2` is a validation and transfer carrier only and must not be merged into `testing`. Package publication, target branch protection, and production consumer migration remain blocked by `DEN-319` and the expanded native CI results.
