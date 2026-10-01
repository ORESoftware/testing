# Migration guide

## Existing module replacement

| Existing local code | Shared destination | Remains local |
| --- | --- | --- |
| stdio bootstrap/server span | `ore-mcp-runtime` | concrete server construction and instructions |
| strict `flags2env` parsing | `ore-mcp-config` | product-specific operational flag declarations |
| stderr/OTLP telemetry core | `ore-mcp-telemetry` | optional product metric extensions |
| URL/body/time-limit helpers | `ore-mcp-http` + `ore-mcp-safety` | endpoint/auth policy and response schemas |
| reusable ORE-owned DTOs | `ore-mcp-contracts` | product tool input/output schemas |
| process/fixture harness | `ore-mcp-testkit` | product-specific snapshots |

## Consumer migration contract

For every MCP server migration:

1. Record the exact reviewed consumer commit.
2. Capture server information, instructions, tool list, descriptions, schemas, transport behavior, and security assertions.
3. Pin a reviewed `mcp-rust-libs` tag or immutable revision.
4. Add the shared package without deleting the repository-local implementation.
5. Switch only modules whose behavior is covered by parity fixtures.
6. Keep tools, schemas, authorization, endpoint policy, mutations, upstream clients, and product metadata transformations local.
7. Run the existing consumer tests plus `ore-mcp-testkit` real-process stdout-purity and protocol tests.
8. Compare pre/post fixtures and document every approved difference.
9. Delete duplicated local code only after parity passes.
10. Record production-code deletion, dependency changes, and any local exception.

The initial canaries are Canonical, Messaging Intel, Sonus Auris, and Shared Auth, followed by Fiducia and Akrion. They deliberately exercise strict configuration, telemetry, tool metadata hooks, hardened public diagnostics, and different authorization postures.

## Conflict-resolution policy

When a consumer branch and the shared-library migration both modify the same behavior, do not choose one side wholesale. Reconstruct the intended contract from tests, security invariants, schemas, and repository-specific policy; preserve both compatible intentions; add a focused regression fixture for any reconciled ambiguity.
