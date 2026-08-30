use std::net::{Ipv4Addr, SocketAddr};

use rust_ssr_coexistence_proof::{router, AppState};
use tokio::net::TcpListener;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let address = SocketAddr::from((Ipv4Addr::LOCALHOST, 3000));
    let listener = TcpListener::bind(address).await?;
    axum::serve(listener, router(AppState::new())).await?;
    Ok(())
}
