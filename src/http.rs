use std::{fmt::Write as _, sync::Arc, time::Duration};

use axum::{
    Json, Router,
    extract::{
        DefaultBodyLimit, Query, Request, State, WebSocketUpgrade, rejection::JsonRejection, ws,
    },
    http::{
        HeaderMap, HeaderName, HeaderValue, StatusCode,
        header::{CONTENT_TYPE, HOST, ORIGIN},
    },
    middleware::{self, Next},
    response::{Html, IntoResponse, Response},
    routing::{get, post},
};
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use serde_json::json;
use tokio::net::TcpListener;
use tokio::sync::broadcast;
use tokio_util::sync::CancellationToken;
use tower_http::{cors::CorsLayer, trace::TraceLayer};
use tracing::{debug, warn};

use crate::{
    auth::{AuthPolicy, bearer_token, preferred_token},
    config::Config,
    daemon::BoundAddresses,
    model::{EventEnvelope, Transport, TransportPayload},
    openapi,
    store::{Store, StoreError, StoreUpdate},
    ui,
};

#[derive(Clone, Debug)]
pub struct AppState {
    pub store: Store,
    pub auth: AuthPolicy,
    pub config: Arc<Config>,
    pub addresses: BoundAddresses,
}

#[derive(Debug, Deserialize)]
struct AuthQuery {
    token: Option<String>,
}

#[derive(Debug, Deserialize)]
struct UiAuthFrame {
    token: String,
}

#[derive(Debug, Serialize)]
struct HealthResponse {
    status: &'static str,
    version: &'static str,
    revision: u64,
}

#[derive(Debug)]
enum ApiError {
    Unauthorized,
    Forbidden,
    BadRequest(String),
}

impl From<StoreError> for ApiError {
    fn from(error: StoreError) -> Self {
        Self::BadRequest(error.to_string())
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, code, message) = match self {
            Self::Unauthorized => (
                StatusCode::UNAUTHORIZED,
                "unauthorized",
                "Authentication failed".to_owned(),
            ),
            Self::Forbidden => (
                StatusCode::FORBIDDEN,
                "forbidden",
                "Cross-origin WebSocket upgrade rejected".to_owned(),
            ),
            Self::BadRequest(message) => {
                (StatusCode::BAD_REQUEST, "invalid_request", message)
            }
        };
        (status, Json(json!({ "error": code, "message": message }))).into_response()
    }
}

pub fn router(state: AppState) -> Router {
    let max_payload_bytes = state.config.max_payload_bytes;
    let cors_any = state.config.cors_any;

    let trace_layer = TraceLayer::new_for_http().make_span_with(|request: &Request| {
        tracing::info_span!(
            "http_request",
            method = %request.method(),
            path = %request.uri().path()
        )
    });

    let mut router = Router::new()
        .route("/", get(index))
        .route("/healthz", get(health))
        .route("/readyz", get(health))
        .route("/metrics", get(metrics))
        .route("/openapi.json", get(openapi_document))
        .route("/api/v1/snapshot", get(snapshot))
        .route("/api/v1/events", post(ingest_event))
        .route("/ws/agent", get(agent_websocket))
        .route("/ws/ui", get(ui_websocket))
        .with_state(state)
        .layer(DefaultBodyLimit::max(max_payload_bytes))
        .layer(middleware::from_fn(security_headers))
        .layer(trace_layer);

    if cors_any {
        router = router.layer(CorsLayer::permissive());
    }
    router
}

pub async fn serve(
    listener: TcpListener,
    state: AppState,
    cancellation: CancellationToken,
) -> std::io::Result<()> {
    axum::serve(listener, router(state))
        .with_graceful_shutdown(cancellation.cancelled_owned())
        .await
}

async fn security_headers(request: Request, next: Next) -> Response {
    let mut response = next.run(request).await;
    let headers = response.headers_mut();
    for (name, value) in [
        (
            HeaderName::from_static("content-security-policy"),
            HeaderValue::from_static(
                "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self' ws: wss:; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
            ),
        ),
        (
            HeaderName::from_static("x-content-type-options"),
            HeaderValue::from_static("nosniff"),
        ),
        (
            HeaderName::from_static("referrer-policy"),
            HeaderValue::from_static("no-referrer"),
        ),
        (
            HeaderName::from_static("permissions-policy"),
            HeaderValue::from_static("camera=(), microphone=(), geolocation=()"),
        ),
        (
            HeaderName::from_static("cross-origin-opener-policy"),
            HeaderValue::from_static("same-origin"),
        ),
    ] {
        headers.insert(name, value);
    }
    response
}

async fn index(State(state): State<AppState>) -> Html<String> {
    Html(ui::dashboard(
        state.addresses,
        state.auth.reads_are_protected(),
    ))
}

async fn health(State(state): State<AppState>) -> Json<HealthResponse> {
    Json(HealthResponse {
        status: "ok",
        version: env!("CARGO_PKG_VERSION"),
        revision: state.store.revision().await,
    })
}

async fn openapi_document(State(state): State<AppState>) -> Json<serde_json::Value> {
    Json(openapi::document(
        state.addresses,
        state.auth.ingestion_is_protected(),
        state.auth.reads_are_protected(),
    ))
}

async fn snapshot(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<crate::store::Snapshot>, ApiError> {
    state
        .auth
        .authorize_read(bearer_token(&headers))
        .map_err(|_| ApiError::Unauthorized)?;
    Ok(Json(state.store.snapshot().await))
}

async fn metrics(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<impl IntoResponse, ApiError> {
    state
        .auth
        .authorize_read(bearer_token(&headers))
        .map_err(|_| ApiError::Unauthorized)?;
    let snapshot = state.store.metrics_snapshot().await;
    let mut body = String::with_capacity(2_048);

    writeln!(body, "# TYPE meta_agent_state_revision gauge").unwrap();
    writeln!(body, "meta_agent_state_revision {}", snapshot.revision).unwrap();
    writeln!(body, "# TYPE meta_agent_events_accepted_total counter").unwrap();
    writeln!(
        body,
        "meta_agent_events_accepted_total {}",
        snapshot.counters.accepted
    )
    .unwrap();
    writeln!(body, "# TYPE meta_agent_events_duplicate_total counter").unwrap();
    writeln!(
        body,
        "meta_agent_events_duplicate_total {}",
        snapshot.counters.duplicate
    )
    .unwrap();
    writeln!(body, "# TYPE meta_agent_events_rejected_total counter").unwrap();
    writeln!(
        body,
        "meta_agent_events_rejected_total {}",
        snapshot.counters.rejected
    )
    .unwrap();
    writeln!(body, "# TYPE meta_agent_cache_items gauge").unwrap();
    writeln!(body, "# TYPE meta_agent_cache_capacity gauge").unwrap();
    writeln!(body, "# TYPE meta_agent_cache_evictions_total counter").unwrap();

    for (name, cache) in [
        ("agents", snapshot.caches.agents),
        ("goals", snapshot.caches.goals),
        ("tasks", snapshot.caches.tasks),
        ("lessons", snapshot.caches.lessons),
        ("events", snapshot.caches.events),
        ("idempotency", snapshot.caches.idempotency),
    ] {
        writeln!(
            body,
            "meta_agent_cache_items{{cache=\"{name}\"}} {}",
            cache.length
        )
        .unwrap();
        writeln!(
            body,
            "meta_agent_cache_capacity{{cache=\"{name}\"}} {}",
            cache.capacity
        )
        .unwrap();
        writeln!(
            body,
            "meta_agent_cache_evictions_total{{cache=\"{name}\"}} {}",
            cache.evictions
        )
        .unwrap();
    }

    writeln!(
        body,
        "# TYPE meta_agent_events_accepted_by_transport_total counter"
    )
    .unwrap();
    for (transport, count) in &snapshot.counters.accepted_by_transport {
        writeln!(
            body,
            "meta_agent_events_accepted_by_transport_total{{transport=\"{transport}\"}} {count}"
        )
        .unwrap();
    }
    writeln!(
        body,
        "# TYPE meta_agent_events_rejected_by_transport_total counter"
    )
    .unwrap();
    for (transport, count) in &snapshot.counters.rejected_by_transport {
        writeln!(
            body,
            "meta_agent_events_rejected_by_transport_total{{transport=\"{transport}\"}} {count}"
        )
        .unwrap();
    }
    writeln!(body, "# TYPE meta_agent_projection_items gauge").unwrap();
    for (kind, count) in [
        ("agents", snapshot.projection.agents),
        ("goals", snapshot.projection.goals),
        ("tasks", snapshot.projection.tasks),
        ("lessons", snapshot.projection.lessons),
    ] {
        writeln!(
            body,
            "meta_agent_projection_items{{kind=\"{kind}\"}} {count}"
        )
        .unwrap();
    }

    Ok((
        [(CONTENT_TYPE, "text/plain; version=0.0.4; charset=utf-8")],
        body,
    ))
}

async fn ingest_event(
    State(state): State<AppState>,
    headers: HeaderMap,
    payload: Result<Json<EventEnvelope>, JsonRejection>,
) -> Result<impl IntoResponse, ApiError> {
    if state
        .auth
        .authorize_ingest(bearer_token(&headers))
        .is_err()
    {
        state.store.record_rejection(Transport::Http).await;
        return Err(ApiError::Unauthorized);
    }
    let Json(event) = match payload {
        Ok(payload) => payload,
        Err(error) => {
            state.store.record_rejection(Transport::Http).await;
            return Err(ApiError::BadRequest(error.body_text()));
        }
    };
    let ack = state.store.ingest(event, Transport::Http).await?;
    let status = if ack.duplicate {
        StatusCode::OK
    } else {
        StatusCode::ACCEPTED
    };
    Ok((status, Json(ack)))
}

async fn agent_websocket(
    websocket: WebSocketUpgrade,
    State(state): State<AppState>,
    Query(query): Query<AuthQuery>,
    headers: HeaderMap,
) -> Response {
    if !websocket_origin_allowed(&headers, state.config.cors_any) {
        state.store.record_rejection(Transport::WebSocket).await;
        return ApiError::Forbidden.into_response();
    }
    let provided = preferred_token(&headers, query.token.as_deref());
    if state.auth.authorize_ingest(provided).is_err() {
        state.store.record_rejection(Transport::WebSocket).await;
        return ApiError::Unauthorized.into_response();
    }

    websocket
        .max_message_size(state.config.max_payload_bytes)
        .max_frame_size(state.config.max_payload_bytes)
        .on_upgrade(move |socket| handle_agent_socket(socket, state))
        .into_response()
}

async fn handle_agent_socket(mut socket: ws::WebSocket, state: AppState) {
    while let Some(message) = socket.recv().await {
        let message = match message {
            Ok(message) => message,
            Err(error) => {
                debug!(%error, "agent WebSocket closed with a protocol error");
                return;
            }
        };

        let payload = match message {
            ws::Message::Text(text) => serde_json::from_str::<TransportPayload>(text.as_str()),
            ws::Message::Binary(bytes) => serde_json::from_slice::<TransportPayload>(&bytes),
            ws::Message::Ping(bytes) => {
                if socket.send(ws::Message::Pong(bytes)).await.is_err() {
                    return;
                }
                continue;
            }
            ws::Message::Pong(_) => continue,
            ws::Message::Close(_) => return,
        };

        let response = match payload {
            Ok(payload) => {
                let (_frame_token, event) = payload.into_parts();
                match state.store.ingest(event, Transport::WebSocket).await {
                    Ok(ack) => serde_json::to_value(ack).unwrap_or_else(|error| {
                        json!({ "error": "serialization_failed", "message": error.to_string() })
                    }),
                    Err(error) => {
                        json!({ "error": "invalid_event", "message": error.to_string() })
                    }
                }
            }
            Err(error) => {
                state.store.record_rejection(Transport::WebSocket).await;
                json!({ "error": "invalid_json", "message": error.to_string() })
            }
        };

        if socket
            .send(ws::Message::Text(response.to_string().into()))
            .await
            .is_err()
        {
            return;
        }
    }
}

async fn ui_websocket(
    websocket: WebSocketUpgrade,
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Response {
    if !websocket_origin_allowed(&headers, state.config.cors_any) {
        return ApiError::Forbidden.into_response();
    }

    websocket
        .max_message_size(16 * 1024)
        .max_frame_size(16 * 1024)
        .on_upgrade(move |socket| handle_ui_socket(socket, state.store, state.auth))
        .into_response()
}

fn websocket_origin_allowed(headers: &HeaderMap, cors_any: bool) -> bool {
    if cors_any {
        return true;
    }

    let Some(origin) = headers.get(ORIGIN) else {
        return true;
    };
    let Some(host) = headers.get(HOST).and_then(|value| value.to_str().ok()) else {
        return false;
    };
    let Ok(origin) = origin.to_str() else {
        return false;
    };

    origin
        .strip_prefix("http://")
        .or_else(|| origin.strip_prefix("https://"))
        .is_some_and(|origin_host| origin_host.eq_ignore_ascii_case(host))
}

async fn handle_ui_socket(mut socket: ws::WebSocket, store: Store, auth: AuthPolicy) {
    if auth.reads_are_protected() && !authenticate_ui_socket(&mut socket, &auth).await {
        let error = json!({ "error": "unauthorized", "message": "Authentication failed" });
        if let Err(send_error) = socket
            .send(ws::Message::Text(error.to_string().into()))
            .await
        {
            debug!(error = %send_error, "failed to send UI authentication error");
        }
        return;
    }

    let authenticated = json!({ "kind": "authenticated" });
    if socket
        .send(ws::Message::Text(authenticated.to_string().into()))
        .await
        .is_err()
    {
        return;
    }

    let mut updates: broadcast::Receiver<StoreUpdate> = store.subscribe();
    let (mut sender, mut receiver) = socket.split();

    loop {
        tokio::select! {
            update = updates.recv() => {
                let payload = match update {
                    Ok(update) => serde_json::to_string(&update),
                    Err(broadcast::error::RecvError::Lagged(skipped)) => {
                        Ok(json!({ "kind": "resync_required", "skipped": skipped }).to_string())
                    }
                    Err(broadcast::error::RecvError::Closed) => return,
                };
                match payload {
                    Ok(payload) => {
                        if sender.send(ws::Message::Text(payload.into())).await.is_err() {
                            return;
                        }
                    }
                    Err(error) => warn!(%error, "failed to serialize UI update"),
                }
            }
            incoming = receiver.next() => {
                match incoming {
                    Some(Ok(ws::Message::Ping(bytes))) => {
                        if sender.send(ws::Message::Pong(bytes)).await.is_err() {
                            return;
                        }
                    }
                    Some(Ok(ws::Message::Close(_))) | None | Some(Err(_)) => return,
                    Some(Ok(_)) => {}
                }
            }
        }
    }
}

async fn authenticate_ui_socket(socket: &mut ws::WebSocket, auth: &AuthPolicy) -> bool {
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let Some(message) = socket.recv().await else {
                return false;
            };
            let Ok(message) = message else {
                return false;
            };

            let frame = match message {
                ws::Message::Text(text) => serde_json::from_str::<UiAuthFrame>(text.as_str()).ok(),
                ws::Message::Binary(bytes) => serde_json::from_slice::<UiAuthFrame>(&bytes).ok(),
                ws::Message::Ping(bytes) => {
                    if socket.send(ws::Message::Pong(bytes)).await.is_err() {
                        return false;
                    }
                    continue;
                }
                ws::Message::Pong(_) => continue,
                ws::Message::Close(_) => return false,
            };

            return frame.is_some_and(|frame| auth.authorize_read(Some(&frame.token)).is_ok());
        }
    })
    .await
    .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use axum::{
        body::Body,
        http::{Request, header},
    };
    use tower::ServiceExt;

    use crate::{
        auth::AuthPolicy,
        config::Config,
        daemon::BoundAddresses,
        store::Store,
    };

    use super::*;

    fn test_state() -> AppState {
        let config = Arc::new(Config::local_test());
        let addresses = BoundAddresses {
            http: config.http_addr,
            tcp: config.tcp_addr,
            udp: config.udp_addr,
        };
        AppState {
            store: Store::new(config.cache_config(), config.update_channel_capacity),
            auth: AuthPolicy::from_config(&config),
            config,
            addresses,
        }
    }

    #[tokio::test]
    async fn health_and_dashboard_are_served_from_one_router() {
        let app = router(test_state());

        let health = app
            .clone()
            .oneshot(Request::builder().uri("/healthz").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(health.status(), StatusCode::OK);

        let dashboard = app
            .oneshot(Request::builder().uri("/").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(dashboard.status(), StatusCode::OK);
        assert_eq!(
            dashboard
                .headers()
                .get("x-content-type-options")
                .and_then(|value| value.to_str().ok()),
            Some("nosniff")
        );
    }

    #[tokio::test]
    async fn protected_snapshot_rejects_missing_credentials() {
        let response = router(test_state())
            .oneshot(
                Request::builder()
                    .uri("/api/v1/snapshot")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }

    #[test]
    fn websocket_origins_are_same_origin_by_default() {
        let mut headers = HeaderMap::new();
        headers.insert(header::HOST, "127.0.0.1:8787".parse().unwrap());
        headers.insert(
            header::ORIGIN,
            "http://127.0.0.1:8787".parse().unwrap(),
        );
        assert!(websocket_origin_allowed(&headers, false));

        headers.insert(header::ORIGIN, "https://attacker.example".parse().unwrap());
        assert!(!websocket_origin_allowed(&headers, false));
        assert!(websocket_origin_allowed(&headers, true));
    }
}
