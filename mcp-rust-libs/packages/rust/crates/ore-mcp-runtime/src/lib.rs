//! Thin, explicit stdio lifecycle helpers for Rust MCP servers.
//!
//! The crate deliberately does not construct product handlers, authorize tool
//! calls, or hide `rmcp` protocol concepts. Consumers parse configuration and
//! initialize telemetry first, construct their own server, and then hand it to
//! [`serve_stdio`]. MCP owns stdout; all lifecycle diagnostics are emitted
//! through `tracing` and therefore belong on stderr.

#![forbid(unsafe_code)]

use std::{error::Error, fmt};

use ore_mcp_safety::valid_service_identity_value;
#[cfg(feature = "rmcp-stdio")]
use rmcp::{ServerHandler, ServiceExt, transport::stdio};
#[cfg(feature = "rmcp-stdio")]
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
#[cfg(feature = "rmcp-stdio")]
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

/// Ordered phases for a safe MCP server bootstrap.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BootstrapPhase {
    /// Parse and validate non-secret operational configuration.
    ParseOperationalConfig,
    /// Install stderr-only and optional OTLP telemetry.
    InitializeTelemetry,
    /// Construct the product-owned handler and authorization policy.
    ConstructServer,
    /// Expose the product-owned tool router.
    ExposeToolRouter,
    /// Start the selected MCP transport.
    ServeTransport,
    /// Wait for protocol or process shutdown.
    WaitForShutdown,
    /// Flush telemetry providers without writing to protocol stdout.
    FlushTelemetry,
}

/// Canonical bootstrap ordering used by architecture tests and templates.
pub const REQUIRED_BOOTSTRAP_ORDER: &[BootstrapPhase] = &[
    BootstrapPhase::ParseOperationalConfig,
    BootstrapPhase::InitializeTelemetry,
    BootstrapPhase::ConstructServer,
    BootstrapPhase::ExposeToolRouter,
    BootstrapPhase::ServeTransport,
    BootstrapPhase::WaitForShutdown,
    BootstrapPhase::FlushTelemetry,
];

/// Validated, low-cardinality service identity shared with telemetry adapters.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ServerIdentity {
    service_name: String,
    service_namespace: String,
    version: String,
}

impl ServerIdentity {
    /// Creates a validated identity that excludes whitespace, controls, and
    /// free-form user data.
    pub fn new(
        service_name: impl Into<String>,
        service_namespace: impl Into<String>,
        version: impl Into<String>,
    ) -> Result<Self, IdentityError> {
        let identity = Self {
            service_name: service_name.into(),
            service_namespace: service_namespace.into(),
            version: version.into(),
        };
        if [
            identity.service_name.as_str(),
            identity.service_namespace.as_str(),
            identity.version.as_str(),
        ]
        .into_iter()
        .all(valid_service_identity_value)
        {
            Ok(identity)
        } else {
            Err(IdentityError)
        }
    }

    /// Returns the validated service name.
    pub fn service_name(&self) -> &str {
        &self.service_name
    }

    /// Returns the validated owning namespace.
    pub fn service_namespace(&self) -> &str {
        &self.service_namespace
    }

    /// Returns the validated package/server version.
    pub fn version(&self) -> &str {
        &self.version
    }
}

/// A value-free server identity validation failure.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct IdentityError;

impl fmt::Display for IdentityError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("invalid server identity")
    }
}

impl Error for IdentityError {}

/// Builds the standard stdio lifecycle span with a validated version field.
#[cfg(feature = "rmcp-stdio")]
pub fn server_span(identity: &ServerIdentity, access_mode: AccessMode) -> tracing::Span {
    tracing::info_span!(
        "mcp.server",
        rpc.system = "mcp",
        transport = "stdio",
        service.name = %identity.service_name(),
        service.namespace = %identity.service_namespace(),
        service.version = %identity.version(),
        access.mode = access_mode.as_str(),
    )
}

/// Serve an already-constructed `rmcp` server over stdio using a caller-owned
/// span. The macro keeps unstable SDK generic bounds out of downstream APIs.
#[cfg(feature = "rmcp-stdio")]
#[macro_export]
macro_rules! serve_stdio_with_span {
    ($server:expr, $span:expr) => {{
        use $crate::__private::rmcp::{ServiceExt as _, transport::stdio};
        use $crate::__private::tracing::Instrument as _;
        let __ore_span = $span;
        let __ore_service = ($server)
            .serve(stdio())
            .instrument(__ore_span.clone())
            .await?;
        __ore_service.waiting().instrument(__ore_span).await
    }};
}

#[cfg(feature = "rmcp-stdio")]
#[doc(hidden)]
pub mod __private {
    pub use rmcp;
    pub use tracing;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn access_mode_labels_are_stable() {
        assert_eq!(AccessMode::ReadOnly.as_str(), "read_only");
        assert_eq!(AccessMode::MutationCapable.as_str(), "mutation_capable");
    }

    #[test]
    fn runtime_spec_preserves_declared_identity() {
        let spec = RuntimeSpec::new("example-mcp-server", "example", AccessMode::ReadOnly);
        assert_eq!(spec.service_name(), "example-mcp-server");
        assert_eq!(spec.service_namespace(), "example");
        assert_eq!(spec.access_mode(), AccessMode::ReadOnly);
    }
    #[test]
    fn bootstrap_order_keeps_config_before_telemetry() {
        assert_eq!(
            REQUIRED_BOOTSTRAP_ORDER[0],
            BootstrapPhase::ParseOperationalConfig
        );
        assert_eq!(
            REQUIRED_BOOTSTRAP_ORDER[1],
            BootstrapPhase::InitializeTelemetry
        );
    }

    #[test]
    fn identity_rejects_free_form_values() {
        assert!(ServerIdentity::new("good", "org", "1.0.0+build").is_ok());
        assert!(ServerIdentity::new("bad\nname", "org", "1").is_err());
        assert!(ServerIdentity::new("user supplied", "org", "1").is_err());
    }
}
