#![forbid(unsafe_code)]
//! Isolated proof copy of `pmap-api-server.rs/src/four_transports.rs` from the
//! DEN-4134 rescue. This scratch branch is never merged; it exists only to
//! compile/test the adapter against an immutable `ores-transport` revision.

use ores_transport::{
    Envelope, NatsSubjects, OperationHandler, Reply, ServeError, serve_envelope,
};
use serde::{Serialize, de::DeserializeOwned};
use std::sync::Arc;

pub const SERVICE_SLUG: &str = "pmap";
pub const ENV_PREFIX: &str = "PMAP";
pub const ENVELOPE_PATH: &str = "/v1/operations";

#[must_use]
pub fn subjects() -> NatsSubjects {
    NatsSubjects::for_service(SERVICE_SLUG)
}

pub async fn serve_http_envelope<O, T>(
    handler: &dyn OperationHandler<O, T>,
    envelope: &Envelope<O>,
) -> (Reply<T>, Result<(), ServeError>)
where
    O: Send + Sync,
    T: Send,
{
    serve_envelope(handler, envelope).await
}

pub async fn serve_stateful<O, T>(
    listener: tokio::net::TcpListener,
    handler: Arc<dyn OperationHandler<O, T>>,
) where
    O: DeserializeOwned + Send + Sync + 'static,
    T: Serialize + Send + 'static,
{
    ores_transport::serve_tcp(listener, handler).await;
}

pub async fn serve_asynchronous<O, T>(
    context: ores_transport::async_nats::jetstream::Context,
    handler: Arc<dyn OperationHandler<O, T>>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>>
where
    O: DeserializeOwned + Send + Sync,
    T: Serialize + Send,
{
    ores_transport::serve_jetstream(context, subjects(), handler).await
}

pub async fn jetstream_from_env()
-> Result<Option<ores_transport::async_nats::jetstream::Context>, Box<dyn std::error::Error + Send + Sync>>
{
    let config = ores_transport::TransportConfig::from_env(ENV_PREFIX)?;
    match config.nats_url.as_deref() {
        None => Ok(None),
        Some(url) => Ok(Some(ores_transport::connect_nats(url).await?)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_service_slug_matches_the_environment_prefix() {
        assert_eq!(SERVICE_SLUG.replace('-', "_").to_uppercase(), ENV_PREFIX);
    }

    #[test]
    fn the_subjects_are_namespaced_to_this_service() {
        let subjects = subjects();
        assert!(subjects.request_subject.starts_with(SERVICE_SLUG));
        assert!(subjects.result_subject.starts_with(SERVICE_SLUG));
        assert!(!subjects.request_stream.contains(['.', '-']));
        assert!(!subjects.result_stream.contains(['.', '-']));
    }

    #[test]
    fn ingress_path_is_stable() {
        assert_eq!(ENVELOPE_PATH, "/v1/operations");
    }
}
