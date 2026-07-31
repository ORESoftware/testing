//! Feature-gated umbrella exports for ORESoftware's shared MCP infrastructure.
//!
//! Product MCP servers may depend on this crate for a coordinated feature set,
//! or depend directly on the narrower crates when they need tighter dependency
//! control. Product tools, authorization, credentials, mutations, and business
//! policy remain in their owning repositories.

#![forbid(unsafe_code)]

#[cfg(feature = "config")]
pub use ore_mcp_config as config;
#[cfg(feature = "contracts")]
pub use ore_mcp_contracts as contracts;
#[cfg(feature = "http")]
pub use ore_mcp_http as http;
#[cfg(feature = "runtime")]
pub use ore_mcp_runtime as runtime;
#[cfg(feature = "safety")]
pub use ore_mcp_safety as safety;
#[cfg(feature = "telemetry")]
pub use ore_mcp_telemetry as telemetry;
#[cfg(feature = "testkit")]
pub use ore_mcp_testkit as testkit;
