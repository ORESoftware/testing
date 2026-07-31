use std::io;

use serde::Serialize;
use serde_json::json;
use tokio::net::UdpSocket;
use tokio_util::sync::CancellationToken;
use tracing::{debug, warn};

use crate::{
    auth::AuthPolicy,
    model::{Transport, TransportPayload},
    store::Store,
};

pub async fn serve(
    socket: UdpSocket,
    store: Store,
    auth: AuthPolicy,
    max_payload_bytes: usize,
    cancellation: CancellationToken,
) -> io::Result<()> {
    let mut buffer = vec![0_u8; max_payload_bytes.saturating_add(1)];

    loop {
        let (length, peer) = tokio::select! {
            () = cancellation.cancelled() => return Ok(()),
            received = socket.recv_from(&mut buffer) => received?,
        };

        if length > max_payload_bytes {
            store.record_rejection(Transport::Udp).await;
            warn!(%peer, length, max_payload_bytes, "discarding oversized UDP agent datagram");
            continue;
        }

        let payload = match serde_json::from_slice::<TransportPayload>(&buffer[..length]) {
            Ok(payload) => payload,
            Err(error) => {
                store.record_rejection(Transport::Udp).await;
                debug!(%peer, %error, "discarding malformed UDP agent datagram");
                continue;
            }
        };
        let (token, event) = payload.into_parts();

        if auth.authorize_ingest(token.as_deref()).is_err() {
            store.record_rejection(Transport::Udp).await;
            debug!(%peer, "discarding unauthorized UDP agent datagram");
            continue;
        }

        if !event.event.allowed_over_udp() {
            store.record_rejection(Transport::Udp).await;
            let response = json!({
                "error": "transport_policy",
                "message": "this event kind requires HTTP, WebSocket, or TCP"
            });
            if let Err(error) = send_json(&socket, peer, &response).await {
                debug!(%peer, %error, "failed to send UDP policy acknowledgement");
            }
            continue;
        }

        let response = match store.ingest(event, Transport::Udp).await {
            Ok(ack) => serde_json::to_value(ack).unwrap_or_else(|error| {
                json!({ "error": "serialization_failed", "message": error.to_string() })
            }),
            Err(error) => json!({ "error": "invalid_event", "message": error.to_string() }),
        };
        if let Err(error) = send_json(&socket, peer, &response).await {
            debug!(%peer, %error, "failed to send UDP ingestion acknowledgement");
        }
    }
}

async fn send_json<T: Serialize>(
    socket: &UdpSocket,
    peer: std::net::SocketAddr,
    value: &T,
) -> io::Result<()> {
    let bytes = serde_json::to_vec(value).map_err(io::Error::other)?;
    socket.send_to(&bytes, peer).await?;
    Ok(())
}
