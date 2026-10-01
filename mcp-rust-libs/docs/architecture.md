# Architecture

## Dependency direction

Product MCP servers depend on narrow packages from this repository. Shared packages never depend on product repositories.

```text
contracts/*.schema.json
  -> deterministic generator
  -> generated Rust / TypeScript / Dart / Gleam contracts
  -> one golden fixture corpus

ore-mcp-contracts
  -> ore-mcp-safety
       -> ore-mcp-config (optional flags2env adapter)
       -> ore-mcp-http
       -> ore-mcp-runtime (optional rmcp stdio adapter)
       -> ore-mcp-testkit
       -> ore-mcp-telemetry <- ore-mcp-runtime identity

ore-mcp is a feature-gated umbrella; production servers may depend on narrow crates directly.
```

The graph is intentionally shallow. No crate may depend on a product repository, and no product-specific API belongs in this workspace. `ore-mcp-testkit` is test support and is not required by production servers.

## Semantic-merge rule

The Rust-only incubator was validated before the polyglot workspace existed. Its mature implementations remain the behavioral base for configuration, HTTP, runtime, safety, telemetry, and process conformance. The newer schema, identity, allowlist, metadata-endpoint, fixture, and polyglot capabilities are additive modules. A merge must not replace a stronger bounded or redacting implementation merely because a newer scaffold has a similarly named, smaller implementation.

## Stable policy versus version adapters

The fleet contains equivalent telemetry and MCP behavior on different SDK versions. Stable policy—redaction, resource-attribute rules, metric names, cardinality constraints, stdout purity, bootstrap ordering, and shutdown expectations—must be version-neutral. SDK-specific wiring belongs behind narrow adapter features or compatibility crates. This avoids coupling the extraction to a fleet-wide dependency upgrade.

## Extraction threshold

Code moves here only when all of the following are true:

- at least two MCP servers implement materially the same contract;
- the contract can be stated without product knowledge;
- parity tests capture the behavior before migration;
- the shared API reduces total maintenance cost;
- repository-local extension or exception remains possible.

Similar-looking functions are not sufficient evidence by themselves.

## Security boundaries

- MCP owns stdout; runtime and telemetry helpers write diagnostics only to stderr.
- Public diagnostic HTTP clients expose no credential API, ignore ambient proxies, disable redirects and implicit retries, reject metadata/link-local targets, and stream bodies into checked budgets.
- Operational command-line flags are allowlisted; secret-bearing values remain environment- or secret-store-only and are never echoed in errors.
- Telemetry attributes are bounded, low-cardinality, and filtered for credentials, identity-like keys, and service-identity overrides.
- Generated validators fail closed on unsupported schema behavior, unknown object fields, explicit-null drift, and boundary mismatches.

## Non-goals

This repository does not own product tool catalogs, permissions, mutation gates, private schemas, service credentials, captured request/response bodies, domain-specific defaults, or upstream MCP protocol models.
