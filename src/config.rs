use std::{
    fmt,
    net::{IpAddr, Ipv4Addr, SocketAddr},
};

use clap::Parser;
use serde::Serialize;
use thiserror::Error;

const DEFAULT_MAX_PAYLOAD_BYTES: usize = 256 * 1024;
const DEFAULT_MAX_UDP_PAYLOAD_BYTES: usize = 60 * 1024;
const MAX_HTTP_STREAM_PAYLOAD_BYTES: usize = 16 * 1024 * 1024;
const MAX_UDP_DATAGRAM_BYTES: usize = 65_507;

#[derive(Clone, Parser)]
#[command(
    name = "meta-agent-control-plane",
    about = "Observable, reflective AI-agent daemon and operator UI",
    version
)]
pub struct Config {
    /// HTTP, WebSocket, dashboard, health, metrics, and OpenAPI listener.
    #[arg(long, env = "META_AGENT_HTTP_ADDR", default_value = "127.0.0.1:8787")]
    pub http_addr: SocketAddr,

    /// Newline-delimited JSON TCP ingestion listener.
    #[arg(long, env = "META_AGENT_TCP_ADDR", default_value = "127.0.0.1:8788")]
    pub tcp_addr: SocketAddr,

    /// JSON datagram UDP ingestion listener.
    #[arg(long, env = "META_AGENT_UDP_ADDR", default_value = "127.0.0.1:8789")]
    pub udp_addr: SocketAddr,

    /// Bearer/per-frame token required for ingestion when configured.
    #[arg(long, env = "META_AGENT_AUTH_TOKEN")]
    pub auth_token: Option<String>,

    /// Permit non-loopback listeners without authentication. Unsafe outside isolation.
    #[arg(
        long,
        env = "META_AGENT_ALLOW_UNAUTHENTICATED_REMOTE",
        default_value_t = false
    )]
    pub allow_unauthenticated_remote: bool,

    /// Protect snapshots, metrics, and the browser live-data socket with the same token.
    #[arg(long, env = "META_AGENT_PROTECT_READ_API", default_value_t = false)]
    pub protect_read_api: bool,

    /// Permit cross-origin browser requests and WebSocket origins.
    #[arg(long, env = "META_AGENT_CORS_ANY", default_value_t = false)]
    pub cors_any: bool,

    #[arg(
        long,
        env = "META_AGENT_MAX_PAYLOAD_BYTES",
        default_value_t = DEFAULT_MAX_PAYLOAD_BYTES
    )]
    pub max_payload_bytes: usize,

    /// UDP has a smaller protocol-level datagram ceiling than stream transports.
    #[arg(
        long,
        env = "META_AGENT_MAX_UDP_PAYLOAD_BYTES",
        default_value_t = DEFAULT_MAX_UDP_PAYLOAD_BYTES
    )]
    pub max_udp_payload_bytes: usize,

    #[arg(long, env = "META_AGENT_MAX_TCP_CONNECTIONS", default_value_t = 256)]
    pub max_tcp_connections: usize,

    #[arg(long, env = "META_AGENT_AGENT_CAPACITY", default_value_t = 1_024)]
    pub agent_capacity: usize,

    #[arg(long, env = "META_AGENT_GOAL_CAPACITY", default_value_t = 4_096)]
    pub goal_capacity: usize,

    #[arg(long, env = "META_AGENT_TASK_CAPACITY", default_value_t = 16_384)]
    pub task_capacity: usize,

    #[arg(long, env = "META_AGENT_LESSON_CAPACITY", default_value_t = 8_192)]
    pub lesson_capacity: usize,

    #[arg(long, env = "META_AGENT_EVENT_CAPACITY", default_value_t = 65_536)]
    pub event_capacity: usize,

    /// Event IDs retained for bounded idempotency independently of timeline retention.
    #[arg(
        long,
        env = "META_AGENT_IDEMPOTENCY_CAPACITY",
        default_value_t = 131_072
    )]
    pub idempotency_capacity: usize,

    #[arg(long, env = "META_AGENT_UPDATE_CHANNEL_CAPACITY", default_value_t = 2_048)]
    pub update_channel_capacity: usize,

    #[arg(long, env = "META_AGENT_LOG", default_value = "info,meta_agent_control_plane=debug")]
    pub log_filter: String,

    #[arg(long, env = "META_AGENT_LOG_JSON", default_value_t = false)]
    pub log_json: bool,
}

impl fmt::Debug for Config {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("Config")
            .field("http_addr", &self.http_addr)
            .field("tcp_addr", &self.tcp_addr)
            .field("udp_addr", &self.udp_addr)
            .field("auth_token_configured", &self.auth_token.is_some())
            .field(
                "allow_unauthenticated_remote",
                &self.allow_unauthenticated_remote,
            )
            .field("protect_read_api", &self.protect_read_api)
            .field("cors_any", &self.cors_any)
            .field("max_payload_bytes", &self.max_payload_bytes)
            .field("max_udp_payload_bytes", &self.max_udp_payload_bytes)
            .field("max_tcp_connections", &self.max_tcp_connections)
            .field("agent_capacity", &self.agent_capacity)
            .field("goal_capacity", &self.goal_capacity)
            .field("task_capacity", &self.task_capacity)
            .field("lesson_capacity", &self.lesson_capacity)
            .field("event_capacity", &self.event_capacity)
            .field("idempotency_capacity", &self.idempotency_capacity)
            .field("update_channel_capacity", &self.update_channel_capacity)
            .field("log_filter", &self.log_filter)
            .field("log_json", &self.log_json)
            .finish()
    }
}

#[derive(Clone, Copy, Debug, Serialize)]
pub struct CacheConfig {
    pub agents: usize,
    pub goals: usize,
    pub tasks: usize,
    pub lessons: usize,
    pub events: usize,
    pub idempotency: usize,
}

#[derive(Debug, Error)]
pub enum ConfigError {
    #[error("{name} must be greater than zero")]
    ZeroCapacity { name: &'static str },
    #[error("stream payload limit must be between 1 and {maximum} bytes")]
    InvalidPayloadLimit { maximum: usize },
    #[error("UDP payload limit must be between 1 and {maximum} bytes")]
    InvalidUdpPayloadLimit { maximum: usize },
    #[error("authentication tokens must contain at least 16 bytes")]
    WeakAuthenticationToken,
    #[error("read protection requires an authentication token")]
    ReadProtectionRequiresAuthentication,
    #[error(
        "non-loopback listeners require an authentication token; use --allow-unauthenticated-remote only inside an isolated network"
    )]
    RemoteBindingRequiresAuthentication,
    #[error(
        "non-loopback listeners require --protect-read-api; use --allow-unauthenticated-remote only inside an isolated network"
    )]
    RemoteBindingRequiresReadProtection,
}

impl Config {
    pub fn validate(&self) -> Result<(), ConfigError> {
        for (name, value) in [
            ("agent capacity", self.agent_capacity),
            ("goal capacity", self.goal_capacity),
            ("task capacity", self.task_capacity),
            ("lesson capacity", self.lesson_capacity),
            ("event capacity", self.event_capacity),
            ("idempotency capacity", self.idempotency_capacity),
            ("update channel capacity", self.update_channel_capacity),
            ("maximum TCP connections", self.max_tcp_connections),
        ] {
            if value == 0 {
                return Err(ConfigError::ZeroCapacity { name });
            }
        }

        if self.max_payload_bytes == 0
            || self.max_payload_bytes > MAX_HTTP_STREAM_PAYLOAD_BYTES
        {
            return Err(ConfigError::InvalidPayloadLimit {
                maximum: MAX_HTTP_STREAM_PAYLOAD_BYTES,
            });
        }

        if self.max_udp_payload_bytes == 0
            || self.max_udp_payload_bytes > MAX_UDP_DATAGRAM_BYTES
        {
            return Err(ConfigError::InvalidUdpPayloadLimit {
                maximum: MAX_UDP_DATAGRAM_BYTES,
            });
        }

        if self
            .auth_token
            .as_deref()
            .is_some_and(|token| token.len() < 16)
        {
            return Err(ConfigError::WeakAuthenticationToken);
        }

        if self.protect_read_api && self.auth_token.is_none() {
            return Err(ConfigError::ReadProtectionRequiresAuthentication);
        }

        let has_non_loopback_listener = [self.http_addr, self.tcp_addr, self.udp_addr]
            .iter()
            .any(|address| !address.ip().is_loopback());
        if has_non_loopback_listener
            && self.auth_token.is_none()
            && !self.allow_unauthenticated_remote
        {
            return Err(ConfigError::RemoteBindingRequiresAuthentication);
        }
        if has_non_loopback_listener
            && !self.protect_read_api
            && !self.allow_unauthenticated_remote
        {
            return Err(ConfigError::RemoteBindingRequiresReadProtection);
        }

        Ok(())
    }

    pub const fn cache_config(&self) -> CacheConfig {
        CacheConfig {
            agents: self.agent_capacity,
            goals: self.goal_capacity,
            tasks: self.task_capacity,
            lessons: self.lesson_capacity,
            events: self.event_capacity,
            idempotency: self.idempotency_capacity,
        }
    }

    pub fn local_test() -> Self {
        let localhost = IpAddr::V4(Ipv4Addr::LOCALHOST);
        Self {
            http_addr: SocketAddr::new(localhost, 0),
            tcp_addr: SocketAddr::new(localhost, 0),
            udp_addr: SocketAddr::new(localhost, 0),
            auth_token: Some("test-token-at-least-16-bytes".to_owned()),
            allow_unauthenticated_remote: false,
            protect_read_api: true,
            cors_any: false,
            max_payload_bytes: 64 * 1024,
            max_udp_payload_bytes: 60 * 1024,
            max_tcp_connections: 16,
            agent_capacity: 32,
            goal_capacity: 64,
            task_capacity: 128,
            lesson_capacity: 128,
            event_capacity: 512,
            idempotency_capacity: 1_024,
            update_channel_capacity: 64,
            log_filter: "warn".to_owned(),
            log_json: false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_unauthenticated_non_loopback_bindings() {
        let mut config = Config::local_test();
        config.http_addr = "0.0.0.0:8787".parse().unwrap();
        config.auth_token = None;
        config.protect_read_api = false;

        assert!(matches!(
            config.validate(),
            Err(ConfigError::RemoteBindingRequiresAuthentication)
        ));
    }

    #[test]
    fn read_protection_requires_a_token() {
        let mut config = Config::local_test();
        config.auth_token = None;

        assert!(matches!(
            config.validate(),
            Err(ConfigError::ReadProtectionRequiresAuthentication)
        ));
    }

    #[test]
    fn non_loopback_bindings_require_read_protection() {
        let mut config = Config::local_test();
        config.http_addr = "0.0.0.0:8787".parse().unwrap();
        config.protect_read_api = false;

        assert!(matches!(
            config.validate(),
            Err(ConfigError::RemoteBindingRequiresReadProtection)
        ));
    }

    #[test]
    fn debug_output_redacts_authentication_tokens() {
        let config = Config::local_test();
        let debug = format!("{config:?}");

        assert!(debug.contains("auth_token_configured: true"));
        assert!(!debug.contains("test-token-at-least-16-bytes"));
    }
}
