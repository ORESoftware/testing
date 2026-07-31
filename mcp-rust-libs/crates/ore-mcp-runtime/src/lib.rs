//! Thin, explicit stdio lifecycle helpers for Rust MCP servers.
//!
//! The crate deliberately does not construct product handlers, authorize tool
//! calls, or hide `rmcp` protocol concepts. Consumers parse configuration and
//! initialize telemetry first, construct their own server, and then hand it to
//! [`serve_stdio`]. MCP owns stdout; all lifecycle diagnostics are emitted
//! through `tracing` and therefore belong on stderr.

use std::{error::Error, fmt};

use rmcp::{ServerHandler, ServiceExt, transport::stdio};
use tracing::Instrument;

/// A boxed runtime error returned by [`serve_stdio`].
pub type RuntimeError = Box<dyn Error + Send + Sync + 'static>;

/// The server's externally visible authorization posture.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AccessMode {
    /// The server exposes observation and diagnostics only.
    ReadOnly,
    /// The server exposes one or more state-changing operations.
    MutationCapable,
}

impl AccessMode {
    /// Returns the stable low-cardinality telemetry label for this mode.
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::ReadOnly => "read_only",
            Self::MutationCapable => "mutation_capable",
        }
    }
}

impl fmt::Display for AccessMode {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.as_str())
    }
}

/// Stable metadata attached to the server lifecycle span.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RuntimeSpec {
    service_name: &'static str,
    service_namespace: &'static str,
    access_mode: AccessMode,
}

impl RuntimeSpec {
    /// Creates a stdio runtime specification.
    pub const fn new(
        service_name: &'static str,
        service_namespace: &'static str,
        access_mode: AccessMode,
    ) -> Self {
        Self {
            service_name,
            service_namespace,
            access_mode,
        }
    }

    /// Returns the service name.
    pub const fn service_name(self) -> &'static str {
        self.service_name
    }

    /// Returns the owning organization or service namespace.
    pub const fn service_namespace(self) -> &'static str {
        self.service_namespace
    }

    /// Returns the declared authorization posture.
    pub const fn access_mode(self) -> AccessMode {
        self.access_mode
    }
}

/// Serves a fully constructed MCP handler over stdin and stdout until shutdown.
///
/// Configuration and telemetry must be initialized before calling this
/// function. The helper emits only low-cardinality lifecycle metadata and never
/// records tool arguments, result bodies, credentials, or identity data.
///
/// # Errors
///
/// Returns an error when MCP initialization fails, the stdio transport fails,
/// or the running service exits with an error.
pub async fn serve_stdio<S>(server: S, spec: RuntimeSpec) -> Result<(), RuntimeError>
where
    S: ServerHandler,
{
    tracing::info!(
        service.name = spec.service_name,
        service.namespace = spec.service_namespace,
        transport = "stdio",
        access.mode = spec.access_mode.as_str(),
        "starting MCP server"
    );
    let server_span = tracing::info_span!(
        "mcp.server",
        rpc.system = "mcp",
        transport = "stdio",
        service.name = spec.service_name,
        service.namespace = spec.service_namespace,
        access.mode = spec.access_mode.as_str(),
    );
    let service = server
        .serve(stdio())
        .instrument(server_span.clone())
        .await?;
    service.waiting().instrument(server_span).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn access_mode_labels_are_stable() {
        assert_eq!(AccessMode::ReadOnly.as_str(), "read_only");
        assert_eq!(
            AccessMode::MutationCapable.as_str(),
            "mutation_capable"
        );
    }

    #[test]
    fn runtime_spec_preserves_declared_identity() {
        let spec = RuntimeSpec::new("example-mcp-server", "example", AccessMode::ReadOnly);
        assert_eq!(spec.service_name(), "example-mcp-server");
        assert_eq!(spec.service_namespace(), "example");
        assert_eq!(spec.access_mode(), AccessMode::ReadOnly);
    }
}
