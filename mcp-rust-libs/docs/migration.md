# Consumer migration contract

For every MCP server migration:

1. Record the exact reviewed consumer commit.
2. Capture server information, instructions, tool list, descriptions, schemas, transport behavior, and security assertions.
3. Pin a reviewed `mcp-rust-libs` tag or immutable revision.
4. Replace only modules whose contracts are covered by parity fixtures.
5. Keep tools, schemas, authorization, endpoint policy, and product-specific metadata transformations local.
6. Run the consumer's existing tests plus `ore-mcp-testkit` real-process conformance tests.
7. Compare pre/post fixtures and document every approved difference.
8. Record net production-code deletion, dependency changes, and any local exception.

The initial canaries are Canonical, Messaging Intel, Sonus Auris, and Shared Auth. They deliberately exercise strict configuration, telemetry, tool metadata hooks, and hardened public diagnostics.
