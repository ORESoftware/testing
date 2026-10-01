use clap::Parser;
use meta_agent_control_plane::{Config, Daemon};
use tokio::signal;
use tokio_util::sync::CancellationToken;
use tracing::{error, info};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() {
    let config = Config::parse();
    init_tracing(&config);

    if let Err(error) = run(config).await {
        error!(%error, "meta-agent control plane stopped with an error");
        std::process::exit(1);
    }
}

async fn run(config: Config) -> Result<(), Box<dyn std::error::Error>> {
    config.validate()?;
    let daemon = Daemon::bind(config).await?;
    let addresses = daemon.addresses();

    info!(
        http = %addresses.http,
        tcp = %addresses.tcp,
        udp = %addresses.udp,
        "meta-agent control plane is listening"
    );

    let cancellation = CancellationToken::new();
    let signal_cancellation = cancellation.clone();
    drop(tokio::spawn(async move {
        shutdown_signal().await;
        signal_cancellation.cancel();
    }));

    daemon.serve(cancellation).await?;
    info!("meta-agent control plane shut down cleanly");
    Ok(())
}

fn init_tracing(config: &Config) {
    let filter = EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new(config.log_filter.clone()));

    if config.log_json {
        tracing_subscriber::fmt()
            .with_env_filter(filter)
            .json()
            .init();
    } else {
        tracing_subscriber::fmt()
            .with_env_filter(filter)
            .compact()
            .init();
    }
}

async fn shutdown_signal() {
    let ctrl_c = async {
        if let Err(error) = signal::ctrl_c().await {
            error!(%error, "failed to install Ctrl-C handler");
        }
    };

    #[cfg(unix)]
    let terminate = async {
        match signal::unix::signal(signal::unix::SignalKind::terminate()) {
            Ok(mut stream) => {
                stream.recv().await;
            }
            Err(error) => error!(%error, "failed to install SIGTERM handler"),
        }
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        () = ctrl_c => {},
        () = terminate => {},
    }
}
