//! Generic single-listener proof for three Rust server-side renderers.

use std::sync::Arc;

use axum::extract::{Request, State};
use axum::http::{HeaderName, HeaderValue};
use axum::middleware::Next;
use axum::response::{Html, Response};
use axum::routing::get;
use axum::{middleware, Router};
use dioxus::prelude::*;
use leptos::prelude::*;
use maud::html;
use uuid::Uuid;

pub const SERVER_INSTANCE_HEADER: &str = "x-rust-ssr-server-instance";

#[derive(Clone)]
pub struct AppState(Arc<Inner>);

struct Inner {
    server_instance: HeaderValue,
}

impl AppState {
    #[must_use]
    pub fn new() -> Self {
        let server_instance = HeaderValue::from_str(&Uuid::new_v4().to_string())
            .expect("a UUID is always a valid HTTP header value");
        Self(Arc::new(Inner { server_instance }))
    }
}

impl Default for AppState {
    fn default() -> Self {
        Self::new()
    }
}

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/maud", get(maud_page))
        .route("/leptos", get(leptos_page))
        .route("/dioxus", get(dioxus_page))
        .layer(middleware::from_fn_with_state(
            state.clone(),
            stamp_server_instance,
        ))
        .with_state(state)
}

async fn stamp_server_instance(
    State(state): State<AppState>,
    request: Request,
    next: Next,
) -> Response {
    let mut response = next.run(request).await;
    response.headers_mut().insert(
        HeaderName::from_static(SERVER_INSTANCE_HEADER),
        state.0.server_instance.clone(),
    );
    response
}

async fn maud_page() -> Html<String> {
    Html(
        html! {
            main data-renderer="maud" {
                h1 { "Maud + htmx" }
                p { "Rendered by Maud inside the shared Axum server." }
            }
        }
        .into_string(),
    )
}

async fn leptos_page() -> Html<String> {
    let owner = Owner::new();
    Html(owner.with(|| {
        view! {
            <main data-renderer="leptos">
                <h1>"Leptos SSR"</h1>
                <p>"Rendered by Leptos inside the shared Axum server."</p>
            </main>
        }
        .to_html()
    }))
}

async fn dioxus_page() -> Html<String> {
    let mut vdom = VirtualDom::new(dioxus_view);
    vdom.rebuild_in_place();
    Html(dioxus_ssr::render(&vdom))
}

fn dioxus_view() -> Element {
    rsx! {
        main { "data-renderer": "dioxus",
            h1 { "Dioxus SSR" }
            p { "Rendered by Dioxus inside the shared Axum server." }
        }
    }
}
