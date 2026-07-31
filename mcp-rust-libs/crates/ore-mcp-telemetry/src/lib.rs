//! Explicit, stdio-safe OpenTelemetry for MCP servers.
//!
//! MCP owns stdout, so the fallback tracing layer always writes structured
//! logs to stderr. OTLP traces and metrics are enabled only when
//! `OTEL_EXPORTER_OTLP_ENDPOINT` is configured. Tool arguments, result bodies,
//! credentials, identity fields, and unbounded values are never recorded.

use std::{fmt, time::Duration};

use opentelemetry::{KeyValue, global, trace::TracerProvider as _};
use opentelemetry_otlp::WithExportConfig;
use opentelemetry_sdk::{
    Resource,
    metrics::{PeriodicReader, SdkMeterProvider},
    trace::{SdkTracerProvider, Tracer},
};
use ore_mcp_safety::{is_sensitive_key, valid_attribute_key, valid_attribute_value};
use tracing_subscriber::{EnvFilter, layer::SubscriberExt, util::SubscriberInitExt};

const EXPORT_TIMEOUT: Duration = Duration::from_secs(5);

/// Static identity metadata for one MCP server process.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct TelemetrySpec {
    name: &'static str,
    namespace: &'static str,
    version: &'static str,
}

impl TelemetrySpec {
    /// Creates a telemetry identity.
    pub const fn new(name: &'static str, namespace: &'static str, version: &'static str) -> Self {
        Self {
            name,
            namespace,
            version,
        }
    }

    /// Returns the service name.
    pub const fn service_name(self) -> &'static str {
        self.name
    }

    /// Returns the service namespace.
    pub const fn service_namespace(self) -> &'static str {
        self.namespace
    }

    /// Returns the service version.
    pub const fn service_version(self) -> &'static str {
        self.version
    }
}

/// Owns SDK providers so their final batches can be flushed on shutdown.
pub struct TelemetryGuard {
    tracer_provider: Option<SdkTracerProvider>,
    meter_provider: Option<SdkMeterProvider>,
}

impl fmt::Debug for TelemetryGuard {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("TelemetryGuard")
            .field("trace_exporter", &self.tracer_provider.is_some())
            .field("metric_exporter", &self.meter_provider.is_some())
            .finish()
    }
}

impl Drop for TelemetryGuard {
    fn drop(&mut self) {
        let tracer_provider = self.tracer_provider.take();
        let meter_provider = self.meter_provider.take();
        if tracer_provider.is_none() && meter_provider.is_none() {
            return;
        }

        let result = std::thread::spawn(move || {
            if let Some(provider) = meter_provider {
                let _ = provider.shutdown();
            }
            if let Some(provider) = tracer_provider {
                let _ = provider.shutdown();
            }
        })
        .join();
        if result.is_err() {
            eprintln!("telemetry: shutdown flush panicked; final batches may be incomplete");
        }
    }
}

/// Installs structured stderr logs and optional OTLP trace and metric exporters.
///
/// Exporter construction fails open to stderr-only telemetry. Error details are
/// not printed because an OTLP endpoint or header may contain credentials.
pub fn init(spec: TelemetrySpec, filter: EnvFilter) -> TelemetryGuard {
    let resource = resource(spec);
    let endpoint = std::env::var("OTEL_EXPORTER_OTLP_ENDPOINT")
        .ok()
        .filter(|value| !value.trim().is_empty());

    let (tracer_provider, tracer) = endpoint
        .as_deref()
        .and_then(|value| build_tracer_provider(value, resource.clone()).ok())
        .map_or((None, None), |(provider, tracer)| {
            global::set_tracer_provider(provider.clone());
            (Some(provider), Some(tracer))
        });

    let meter_provider = endpoint
        .as_deref()
        .and_then(|value| build_meter_provider(value, resource).ok());
    if let Some(provider) = meter_provider.as_ref() {
        global::set_meter_provider(provider.clone());
    }

    install_subscriber(filter, tracer);
    tracing::info!(
        service.name = spec.name,
        service.namespace = spec.namespace,
        service.version = spec.version,
        otel.trace_exporter = tracer_provider.is_some(),
        otel.metric_exporter = meter_provider.is_some(),
        log.stream = "stderr",
        "MCP telemetry initialized"
    );

    TelemetryGuard {
        tracer_provider,
        meter_provider,
    }
}

fn build_tracer_provider(
    endpoint: &str,
    resource: Resource,
) -> Result<(SdkTracerProvider, Tracer), ()> {
    let exporter = opentelemetry_otlp::SpanExporter::builder()
        .with_tonic()
        .with_endpoint(endpoint)
        .with_timeout(EXPORT_TIMEOUT)
        .build()
        .map_err(|_| ())?;
    let provider = SdkTracerProvider::builder()
        .with_batch_exporter(exporter)
        .with_resource(resource)
        .build();
    let tracer = provider.tracer("mcp-server");
    Ok((provider, tracer))
}

fn build_meter_provider(endpoint: &str, resource: Resource) -> Result<SdkMeterProvider, ()> {
    let exporter = opentelemetry_otlp::MetricExporter::builder()
        .with_tonic()
        .with_endpoint(endpoint)
        .with_timeout(EXPORT_TIMEOUT)
        .build()
        .map_err(|_| ())?;
    let reader = PeriodicReader::builder(exporter).build();
    Ok(SdkMeterProvider::builder()
        .with_reader(reader)
        .with_resource(resource)
        .build())
}

fn install_subscriber(filter: EnvFilter, tracer: Option<Tracer>) {
    let result = match tracer {
        Some(tracer) => tracing_subscriber::registry()
            .with(filter)
            .with(stderr_json_layer())
            .with(tracing_opentelemetry::layer().with_tracer(tracer))
            .try_init(),
        None => tracing_subscriber::registry()
            .with(filter)
            .with(stderr_json_layer())
            .try_init(),
    };
    if result.is_err() {
        eprintln!("telemetry: subscriber already initialized; keeping existing subscriber");
    }
}

fn stderr_json_layer<S>() -> impl tracing_subscriber::Layer<S>
where
    S: tracing::Subscriber + for<'lookup> tracing_subscriber::registry::LookupSpan<'lookup>,
{
    tracing_subscriber::fmt::layer()
        .json()
        .flatten_event(true)
        .with_ansi(false)
        .with_current_span(true)
        .with_span_list(true)
        .with_target(true)
        .with_writer(std::io::stderr)
}

fn resource(spec: TelemetrySpec) -> Resource {
    let mut attributes = vec![
        KeyValue::new("service.name", spec.name),
        KeyValue::new("service.namespace", spec.namespace),
        KeyValue::new("service.version", spec.version),
    ];
    push_env_attribute(&mut attributes, "DEPLOYMENT_ENV", "deployment.environment");
    push_env_attribute(&mut attributes, "POD_NAMESPACE", "k8s.namespace.name");
    push_env_attribute(&mut attributes, "POD_NAME", "k8s.pod.name");
    push_env_attribute(&mut attributes, "NODE_NAME", "k8s.node.name");
    push_env_attribute(&mut attributes, "HOSTNAME", "host.name");

    if let Ok(raw) = std::env::var("OTEL_RESOURCE_ATTRIBUTES") {
        attributes.extend(
            parse_resource_attributes(&raw)
                .into_iter()
                .map(|(key, value)| KeyValue::new(key, value)),
        );
    }
    Resource::builder_empty()
        .with_attributes(attributes)
        .build()
}

fn push_env_attribute(attributes: &mut Vec<KeyValue>, env_name: &str, key: &'static str) {
    if let Ok(value) = std::env::var(env_name) {
        let value = value.trim();
        if valid_attribute_value(value) {
            attributes.push(KeyValue::new(key, value.to_owned()));
        }
    }
}

/// Parses bounded, non-sensitive resource attributes.
///
/// Service identity keys are reserved for [`TelemetrySpec`] and cannot be
/// overridden by `OTEL_RESOURCE_ATTRIBUTES`.
#[must_use]
pub fn parse_resource_attributes(raw: &str) -> Vec<(String, String)> {
    raw.split(',')
        .filter_map(|pair| {
            let (key, value) = pair.split_once('=')?;
            let key = key.trim();
            let value = value.trim();
            let reserved = matches!(
                key,
                "service.name" | "service.namespace" | "service.version"
            );
            if valid_attribute_key(key)
                && valid_attribute_value(value)
                && !is_sensitive_key(key)
                && !reserved
            {
                Some((key.to_owned(), value.to_owned()))
            } else {
                None
            }
        })
        .collect()
}

/// Adds a span and low-cardinality call and duration metrics around every tool.
///
/// Tool arguments and result bodies are deliberately never recorded.
#[cfg(feature = "rmcp-router")]
pub fn instrument_tool_router<S>(
    mut router: rmcp::handler::server::tool::ToolRouter<S>,
) -> rmcp::handler::server::tool::ToolRouter<S>
where
    S: rmcp::service::MaybeSend + 'static,
{
    use std::{sync::Arc, time::Instant};

    use tracing::{Instrument, field};

    let meter = global::meter("mcp-server");
    let calls = meter
        .u64_counter("mcp.server.tool.calls")
        .with_description("Number of MCP tool calls completed")
        .with_unit("{call}")
        .build();
    let duration = meter
        .f64_histogram("mcp.server.tool.duration")
        .with_description("MCP tool call duration")
        .with_unit("ms")
        .build();

    for route in router.map.values_mut() {
        let original = Arc::clone(&route.call);
        let tool_name = route.attr.name.clone();
        let calls = calls.clone();
        let duration = duration.clone();
        route.call = Arc::new(move |context| {
            let original = Arc::clone(&original);
            let tool_name = tool_name.clone();
            let calls = calls.clone();
            let duration = duration.clone();
            Box::pin(async move {
                let started = Instant::now();
                let span = tracing::info_span!(
                    "mcp.tool.call",
                    rpc.system = "mcp",
                    rpc.method = "tools/call",
                    mcp.tool.name = %tool_name,
                    otel.status_code = field::Empty,
                    mcp.tool.error = field::Empty,
                );
                async move {
                    let result = (original)(context).await;
                    let is_error = match result.as_ref() {
                        Ok(output) => output.is_error.unwrap_or(false),
                        Err(_) => true,
                    };
                    let elapsed_ms = started.elapsed().as_secs_f64() * 1_000.0;
                    let attributes = [
                        KeyValue::new("mcp.tool.name", tool_name.to_string()),
                        KeyValue::new("mcp.tool.error", is_error),
                    ];
                    calls.add(1, &attributes);
                    duration.record(elapsed_ms, &attributes);
                    tracing::Span::current()
                        .record("otel.status_code", if is_error { "ERROR" } else { "OK" });
                    tracing::Span::current().record("mcp.tool.error", is_error);
                    result
                }
                .instrument(span)
                .await
            })
        });
    }
    router
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resource_attributes_reject_secrets_and_identity_overrides() {
        let attributes = parse_resource_attributes(
            "team=simulation,api.token=nope,service.name=spoof,cloud.region=us-east-1,user.id=42",
        );
        assert_eq!(
            attributes,
            vec![
                ("team".to_owned(), "simulation".to_owned()),
                ("cloud.region".to_owned(), "us-east-1".to_owned()),
            ]
        );
    }

    #[test]
    fn resource_attributes_reject_controls_and_oversized_values() {
        let long = "x".repeat(257);
        let raw = format!("good=value,bad=line\nfeed,long={long}");
        assert_eq!(
            parse_resource_attributes(&raw),
            vec![("good".to_owned(), "value".to_owned())]
        );
    }
}
