use std::{net::SocketAddr, sync::Arc};

use serde::{Deserialize, Serialize};
use thiserror::Error;
use tokio::net::{TcpListener, UdpSocket};
use tokio_util::sync::CancellationToken;

use crate::{
    auth::AuthPolicy,
    config::{Config, ConfigError},
    http::{self, AppState},
    store::Store,
    tcp, udp,
};

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct BoundAddresses {
    pub http: SocketAddr,
    pub tcp: SocketAddr,
    pub udp: SocketAddr,
}

#[derive(Debug)]
pub struct Daemon {
    config: Arc<Config>,
    state: AppState,
    http_listener: TcpListener,
    tcp_listener: TcpListener,
    udp_socket: UdpSocket,
    addresses: BoundAddresses,
}

#[derive(Debug, Error)]
pub enum DaemonError {
    #[error(transparent)]
    Config(#[from] ConfigError),
    #[error("failed to bind {transport} listener at {address}: {source}")]
    Bind {
        transport: &'static str,
        address: SocketAddr,
        #[source]
        source: std::io::Error,
    },
    #[error("daemon listener failed: {0}")]
    Serve(#[from] std::io::Error),
}

impl Daemon {
    pub async fn bind(config: Config) -> Result<Self, DaemonError> {
        config.validate()?;

        let http_listener = TcpListener::bind(config.http_addr)
            .await
            .map_err(|source| DaemonError::Bind {
                transport: "HTTP",
                address: config.http_addr,
                source,
            })?;
        let tcp_listener = TcpListener::bind(config.tcp_addr)
            .await
            .map_err(|source| DaemonError::Bind {
                transport: "TCP",
                address: config.tcp_addr,
                source,
            })?;
        let udp_socket = UdpSocket::bind(config.udp_addr)
            .await
            .map_err(|source| DaemonError::Bind {
                transport: "UDP",
                address: config.udp_addr,
                source,
            })?;

        let addresses = BoundAddresses {
            http: http_listener.local_addr()?,
            tcp: tcp_listener.local_addr()?,
            udp: udp_socket.local_addr()?,
        };
        let config = Arc::new(config);
        let store = Store::new(config.cache_config(), config.update_channel_capacity);
        let state = AppState {
            store,
            auth: AuthPolicy::from_config(&config),
            config: Arc::clone(&config),
            addresses,
        };

        Ok(Self {
            config,
            state,
            http_listener,
            tcp_listener,
            udp_socket,
            addresses,
        })
    }

    pub const fn addresses(&self) -> BoundAddresses {
        self.addresses
    }

    pub fn store(&self) -> Store {
        self.state.store.clone()
    }

    pub async fn serve(self, cancellation: CancellationToken) -> Result<(), DaemonError> {
        let Self {
            config,
            state,
            http_listener,
            tcp_listener,
            udp_socket,
            addresses: _,
        } = self;

        let http = http::serve(http_listener, state.clone(), cancellation.child_token());
        let tcp = tcp::serve(
            tcp_listener,
            state.store.clone(),
            state.auth.clone(),
            config.max_payload_bytes,
            config.max_tcp_connections,
            cancellation.child_token(),
        );
        let udp = udp::serve(
            udp_socket,
            state.store,
            state.auth,
            config.max_udp_payload_bytes,
            cancellation.child_token(),
        );

        tokio::try_join!(http, tcp, udp)?;
        Ok(())
    }
}
