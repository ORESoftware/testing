
# Initial fleet source audit

The first extraction should preserve the strongest behavior rather than copying the smallest implementation.

## Canonical

- `src/main.rs`: strict config before telemetry, stderr telemetry, a structured `mcp.server` span, stdio service lifecycle, and error propagation.
- `src/telemetry.rs`: optional OTLP traces/metrics, five-second exporter timeout, secret-safe resource attributes, explicit per-tool calls/duration/error instrumentation, and provider shutdown.
- `src/flags.rs`: `flags2env` config audit, strict unknown/parse/positional rejection, explicit config discovery, and log-filter validation.

## Fiducia

- structurally equivalent bootstrap and telemetry core, but on OpenTelemetry 0.27 rather than 0.32;
- hardened diagnostic HTTP behavior: redirects disabled, connect/total timeouts, bounded declared and chunked bodies, credential-free URL validation, metadata-host rejection, and UTF-8-safe error truncation.

## Extraction consequence

Do not embed OpenTelemetry concrete types in stable public APIs. Extract policy and tests first, then add narrow SDK adapters. Product-specific headers, endpoints, auth modes, and tool schemas remain local.
