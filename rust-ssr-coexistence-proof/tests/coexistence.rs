use std::net::Ipv4Addr;

use reqwest::StatusCode;
use rust_ssr_coexistence_proof::{router, AppState, SERVER_INSTANCE_HEADER};
use serde_json::json;
use tokio::net::TcpListener;
use tokio::sync::oneshot;

struct Observation {
    path: &'static str,
    renderer: &'static str,
    marker: &'static str,
    status: StatusCode,
    server_instance: String,
    body: String,
}

async fn observe(
    client: &reqwest::Client,
    origin: &str,
    path: &'static str,
    renderer: &'static str,
    marker: &'static str,
) -> Observation {
    let response = client
        .get(format!("{origin}{path}"))
        .send()
        .await
        .unwrap_or_else(|error| panic!("{renderer} request failed: {error}"));
    let status = response.status();
    let server_instance = response
        .headers()
        .get(SERVER_INSTANCE_HEADER)
        .unwrap_or_else(|| panic!("{renderer} omitted {SERVER_INSTANCE_HEADER}"))
        .to_str()
        .unwrap_or_else(|error| panic!("{renderer} instance was not text: {error}"))
        .to_owned();
    let body = response
        .text()
        .await
        .unwrap_or_else(|error| panic!("{renderer} body failed: {error}"));
    Observation {
        path,
        renderer,
        marker,
        status,
        server_instance,
        body,
    }
}

#[tokio::test]
async fn one_listener_concurrently_serves_all_three_renderers() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .await
        .expect("loopback listener should bind");
    let address = listener
        .local_addr()
        .expect("loopback listener should expose its address");
    let origin = format!("http://{address}");
    let (shutdown_tx, shutdown_rx) = oneshot::channel::<()>();
    let server = tokio::spawn(async move {
        axum::serve(listener, router(AppState::new()))
            .with_graceful_shutdown(async {
                let _ = shutdown_rx.await;
            })
            .await
    });
    let client = reqwest::Client::new();

    let (maud, leptos, dioxus) = tokio::join!(
        observe(
            &client,
            &origin,
            "/maud",
            "Maud + htmx",
            "data-renderer=\"maud\""
        ),
        observe(
            &client,
            &origin,
            "/leptos",
            "Leptos",
            "data-renderer=\"leptos\""
        ),
        observe(
            &client,
            &origin,
            "/dioxus",
            "Dioxus",
            "data-renderer=\"dioxus\""
        ),
    );
    let observations = [maud, leptos, dioxus];
    let expected_instance = observations[0].server_instance.clone();

    for observation in &observations {
        assert_eq!(
            observation.status,
            StatusCode::OK,
            "{}",
            observation.renderer
        );
        assert_eq!(
            observation.server_instance, expected_instance,
            "{} came from a different server instance",
            observation.renderer
        );
        assert!(
            observation.body.contains(observation.marker),
            "{} omitted {}",
            observation.renderer,
            observation.marker
        );
    }

    println!(
        "{}",
        json!({
            "schema": "rust-ssr-public-coexistence-proof.v1",
            "claim": "one-listener-three-real-ssr-renderers",
            "origin": origin,
            "serverInstance": expected_instance,
            "concurrent": true,
            "externalServicesUsed": false,
            "observations": observations.map(|observation| json!({
                "path": observation.path,
                "renderer": observation.renderer,
                "status": observation.status.as_u16(),
                "provenanceMarker": observation.marker,
            })),
            "decisionEligible": false,
            "warning": "Independent generic coexistence proof; not private-app, browser, database, deployment, hydration, or performance evidence."
        })
    );

    let _ = shutdown_tx.send(());
    server
        .await
        .expect("server task should join")
        .expect("server should shut down cleanly");
}
