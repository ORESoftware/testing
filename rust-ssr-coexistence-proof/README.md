# Rust SSR single-server coexistence proof

This deliberately small public fixture proves one operational invariant: real
Maud, Leptos SSR, and Dioxus SSR renderers can remain live behind one Axum
`Router`, one boot-scoped state object, and one bound TCP listener.

The integration test starts that server on one ephemeral loopback socket and
sends the three renderer requests concurrently. It requires HTTP 200, one
shared `X-Rust-SSR-Server-Instance` UUID, and the expected renderer-specific
HTML provenance marker from every response.

```bash
cargo fmt --all -- --check
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked --all-targets
cargo build --locked --release
```

This is an independent generic reproduction for
[`rust-ssr-demos/axum-leptos-dioxus-unified-ssr#4`](https://github.com/rust-ssr-demos/axum-leptos-dioxus-unified-ssr/pull/4).
It contains no private application source, database schema, credential,
deployment configuration, or business data. It does not substitute for the
private repository's exact-head checks, which remain externally blocked by its
GitHub Actions billing boundary.

The proof does not cover hydration, browser behavior, authenticated mutations,
database integration, accessibility, load balancing, deployment, performance,
or framework selection.
