# Architecture

## Dependency direction

```text
ore-mcp-safety
   ^       ^
   |       |
ore-mcp-http   ore-mcp-config
   ^                ^
   |                |
   +------- ore-mcp-runtime
                    ^
                    |
             ore-mcp-telemetry

ore-mcp-testkit is test support and is not required by production servers.
```

The graph is intentionally shallow. No crate may depend on a product repository, and no product-specific API belongs in this workspace.

## Extraction threshold

Code moves here only when all of the following are true:

- at least two MCP servers implement materially the same contract;
- the contract can be stated without product knowledge;
- parity tests can capture the behavior before migration;
- the shared API reduces total maintenance cost;
- repository-local extension or exception remains possible.

Similar-looking functions are not sufficient evidence by themselves.

## Security boundaries

- MCP owns stdout; runtime and telemetry helpers write diagnostics only to stderr.
- Public diagnostic HTTP clients cannot attach credentials.
- Redirects are disabled, response bodies are streamed into checked budgets, and errors do not contain upstream bodies or secret URLs.
- Operational command-line flags are allowlisted by each server configuration; secret-bearing values stay in environment or secret stores.
- Telemetry attributes are bounded, low-cardinality, and filtered for credentials and identity-like keys.
