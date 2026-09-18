# Contributing

## Development

```bash
nix develop
# or install the toolchain pinned by rust-toolchain.toml

cargo fmt --check
cargo clippy --all-targets --all-features -- -D warnings
cargo test --all-targets --all-features
python scripts/verify_contract.py
node --check scripts/dashboard.js
```

## Protocol changes

A protocol change must update all of the following in one pull request:

1. Rust model and validation;
2. `EVENT_KINDS` when applicable;
3. reducer semantics and tests;
4. `docs/protocol.md`;
5. `docs/openapi.json` and runtime OpenAPI generation;
6. valid/invalid fixtures;
7. transport-policy tests, especially UDP.

Never add fields intended to capture hidden chain-of-thought. Prefer concise summaries, evidence references, confidence, assumptions, risks, decisions, and next actions.

## Conflict resolution

Do not resolve source conflicts with blanket `ours` or `theirs`. Identify the invariants each branch is preserving, construct the combined behavior, update generated/schema artifacts, and add a regression test for the conflict. Review the complete resulting diff against the Linear issue before merging.

## Pull requests

Use a focused `agent/<description>` branch, link `DEN-1057` or a child issue, explain the semantic impact, and report the exact validation commands and results. The merge must be guarded by the expected head SHA after required checks pass.
