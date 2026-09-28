//! Error types for configuration, CLI parsing, and sandbox launch failures.

use std::path::PathBuf;

use thiserror::Error;

/// Result alias used throughout the crate.
pub type Result<T> = std::result::Result<T, Error>;

/// Fail-closed errors surfaced by the launcher.
#[derive(Debug, Error)]
pub enum Error {
    /// The public CLI contract could not be audited or parsed.
    #[error("CLI contract error: {0}")]
    Cli(String),

    /// The policy file could not be read.
    #[error("failed to read policy {path}: {source}")]
    PolicyRead {
        /// Policy path.
        path: PathBuf,
        /// I/O failure.
        source: std::io::Error,
    },

    /// The policy YAML is malformed or violates the schema.
    #[error("invalid policy {path}: {message}")]
    PolicyInvalid {
        /// Policy path.
        path: PathBuf,
        /// Validation detail.
        message: String,
    },

    /// A configured process or group could not be resolved.
    #[error("policy resolution error: {0}")]
    Resolution(String),

    /// A configured executable is missing or unsafe to launch.
    #[error("executable error: {0}")]
    Executable(String),

    /// The host cannot provide the requested sandbox boundary.
    #[error("sandbox unavailable: {0}")]
    SandboxUnavailable(String),

    /// A trusted helper script could not be materialized.
    #[error("failed to materialize sandbox helper: {0}")]
    HelperIo(#[from] std::io::Error),

    /// The sandbox helper exited without launching or completing correctly.
    #[error("sandbox launch failed: {0}")]
    Launch(String),

    /// JSON serialization failed.
    #[error("failed to serialize output: {0}")]
    Json(#[from] serde_json::Error),
}
