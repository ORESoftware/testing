# Security model

- stdout belongs exclusively to MCP protocol frames; logs and diagnostics use stderr.
- secrets remain environment-only and are never accepted as ordinary CLI flags.
- telemetry never records tool arguments, result bodies, credentials, identity payloads, or unbounded values.
- public diagnostic HTTP clients expose no credential API, ignore ambient proxy variables, disable redirects and implicit retries, and require HTTPS except explicit loopback HTTP.
- authenticated clients require a separate typed product adapter; credential forwarding, redirect following, proxy use, retries, and TLS exceptions are never inherited silently.
- response bodies are streamed into checked budgets rather than trusting `Content-Length`; surfaced errors preserve category/status without exposing upstream bodies or secret-bearing URLs.
- OTLP is opt-in, uses a bounded exporter timeout, has explicit TLS roots for HTTPS gRPC endpoints, and shuts providers down deterministically.
- generated files are confined to declared roots and carry schema/generator provenance.
- fixtures contain synthetic data only.
- CI actions are pinned by immutable commit SHA and run with read-only repository permissions.
- release jobs require reviewed immutable tags, native package inspection, coordinated cross-language parity, checksums, and provenance.
