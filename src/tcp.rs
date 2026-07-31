use std::{io, sync::Arc};

use futures_util::StreamExt;
use serde::Serialize;
use serde_json::json;
use tokio::{
    io::AsyncWriteExt,
    net::{TcpListener, TcpStream, tcp::OwnedWriteHalf},
    sync::Semaphore,
    task::JoinSet,
};
use tokio_util::{
    codec::{FramedRead, LinesCodec},
    sync::CancellationToken,
};
use tracing::{debug, warn};

use crate::{
    auth::AuthPolicy,
    model::{Transport, TransportPayload},
    store::Store,
};

pub async fn serve(
    listener: TcpListener,
    store: Store,
    auth: AuthPolicy,
    max_payload_bytes: usize,
    max_connections: usize,
    cancellation: CancellationToken,
) -> io::Result<()> {
    let limiter = Arc::new(Semaphore::new(max_connections));
    let mut connections = JoinSet::new();

    loop {
        tokio::select! {
            () = cancellation.cancelled() => break,
            accepted = listener.accept() => {
                let (stream, peer) = accepted?;
                let Ok(permit) = Arc::clone(&limiter).try_acquire_owned() else {
                    warn!(%peer, "rejecting TCP agent connection because the connection limit is full");
                    drop(stream);
                    continue;
                };
                let connection_store = store.clone();
                let connection_auth = auth.clone();
                let connection_cancellation = cancellation.child_token();
                connections.spawn(async move {
                    let _permit = permit;
                    if let Err(error) = handle_connection(
                        stream,
                        connection_store,
                        connection_auth,
                        max_payload_bytes,
                        connection_cancellation,
                    )
                    .await
                    {
                        debug!(%peer, %error, "TCP agent connection ended");
                    }
                });
            }
            result = connections.join_next(), if !connections.is_empty() => {
                if let Some(Err(error)) = result {
                    warn!(%error, "TCP agent connection task failed");
                }
            }
        }
    }

    while let Some(result) = connections.join_next().await {
        if let Err(error) = result {
            warn!(%error, "TCP agent connection task failed during shutdown");
        }
    }
    Ok(())
}

async fn handle_connection(
    stream: TcpStream,
    store: Store,
    auth: AuthPolicy,
    max_payload_bytes: usize,
    cancellation: CancellationToken,
) -> io::Result<()> {
    stream.set_nodelay(true)?;
    let (read_half, mut write_half) = stream.into_split();
    let mut frames = FramedRead::new(
        read_half,
        LinesCodec::new_with_max_length(max_payload_bytes),
    );

    loop {
        let frame = tokio::select! {
            () = cancellation.cancelled() => return Ok(()),
            frame = frames.next() => frame,
        };
        let Some(frame) = frame else {
            return Ok(());
        };

        let line = match frame {
            Ok(line) => line,
            Err(error) => {
                store.record_rejection(Transport::Tcp).await;
                write_json_line(
                    &mut write_half,
                    &json!({ "error": "invalid_frame", "message": error.to_string() }),
                )
                .await?;
                continue;
            }
        };

        let payload = match serde_json::from_str::<TransportPayload>(&line) {
            Ok(payload) => payload,
            Err(error) => {
                store.record_rejection(Transport::Tcp).await;
                write_json_line(
                    &mut write_half,
                    &json!({ "error": "invalid_json", "message": error.to_string() }),
                )
                .await?;
                continue;
            }
        };
        let (token, event) = payload.into_parts();

        if let Err(error) = auth.authorize_ingest(token.as_deref()) {
            store.record_rejection(Transport::Tcp).await;
            write_json_line(
                &mut write_half,
                &json!({ "error": "unauthorized", "message": error.to_string() }),
            )
            .await?;
            continue;
        }

        match store.ingest(event, Transport::Tcp).await {
            Ok(ack) => write_json_line(&mut write_half, &ack).await?,
            Err(error) => {
                write_json_line(
                    &mut write_half,
                    &json!({ "error": "invalid_event", "message": error.to_string() }),
                )
                .await?;
            }
        }
    }
}

async fn write_json_line<T: Serialize>(writer: &mut OwnedWriteHalf, value: &T) -> io::Result<()> {
    let mut bytes = serde_json::to_vec(value).map_err(io::Error::other)?;
    bytes.push(b'\n');
    writer.write_all(&bytes).await
}
