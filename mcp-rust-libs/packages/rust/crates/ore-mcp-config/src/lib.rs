//! Strict, secret-safe startup configuration shared by MCP servers.
//!
//! The core module validates non-secret operational settings without requiring
//! `flags2env`. The optional `flags2env-adapter` feature exposes the previously
//! validated parser and preserves its value-redacting diagnostics.

#![forbid(unsafe_code)]

use std::{collections::BTreeSet, path::PathBuf};
use thiserror::Error;
use tracing_subscriber::EnvFilter;

/// Conservative default for stderr logging when no operational override exists.
pub const DEFAULT_LOG_FILTER: &str = "info,hyper=warn";

/// Errors from the runtime-independent operational configuration layer.
#[derive(Debug, Error, Clone, Copy, Eq, PartialEq)]
pub enum OperationalConfigError {
    /// No allowed configuration file could be found.
    #[error("configuration file was not found")]
    NotFound,
    /// An explicit path override did not point to a regular file.
    #[error("configuration override does not point to a file")]
    InvalidOverride,
    /// A tracing filter was syntactically invalid.
    #[error("invalid log filter")]
    InvalidLogFilter,
}

/// Runtime-independent configuration discovery with an explicit environment
/// override and deterministic relative candidates.
#[derive(Debug, Clone, Eq, PartialEq)]
pub struct PortableConfigSearch {
    override_env: String,
    relative_candidates: Vec<PathBuf>,
}

impl PortableConfigSearch {
    /// Builds the standard source-tree and installed-share search policy.
    pub fn standard(override_env: impl Into<String>, package_name: &str) -> Self {
        Self {
            override_env: override_env.into(),
            relative_candidates: vec![
                PathBuf::from(".cli-flags.toml"),
                PathBuf::from(format!("../share/{package_name}/.cli-flags.toml")),
            ],
        }
    }

    /// Adds an additional deterministic relative candidate.
    #[must_use]
    pub fn with_candidate(mut self, candidate: impl Into<PathBuf>) -> Self {
        self.relative_candidates.push(candidate.into());
        self
    }

    /// Resolves the first regular file from the explicit override, current
    /// directory, and executable directory. Empty overrides are ignored.
    pub fn resolve(&self) -> Result<PathBuf, OperationalConfigError> {
        if let Some(value) = std::env::var_os(&self.override_env).filter(|value| !value.is_empty()) {
            let path = PathBuf::from(value);
            return if path.is_file() {
                Ok(path)
            } else {
                Err(OperationalConfigError::InvalidOverride)
            };
        }

        let mut candidates = Vec::new();
        if let Ok(current) = std::env::current_dir() {
            candidates.extend(self.relative_candidates.iter().map(|path| current.join(path)));
        }
        if let Ok(executable) = std::env::current_exe()
            && let Some(parent) = executable.parent()
        {
            candidates.extend(self.relative_candidates.iter().map(|path| parent.join(path)));
        }
        candidates
            .into_iter()
            .find(|path| path.is_file())
            .ok_or(OperationalConfigError::NotFound)
    }
}

/// Validates a tracing filter while deliberately omitting the supplied value
/// from any displayable error.
pub fn validate_log_filter(raw: Option<&str>) -> Result<EnvFilter, OperationalConfigError> {
    EnvFilter::try_new(raw.unwrap_or(DEFAULT_LOG_FILTER))
        .map_err(|_| OperationalConfigError::InvalidLogFilter)
}

/// Explicit allowlist for non-secret environment variables that may be
/// materialized from command-line flags.
#[derive(Debug, Clone, Default, Eq, PartialEq)]
pub struct OperationalAllowlist {
    env_names: BTreeSet<String>,
}

impl OperationalAllowlist {
    /// Creates an allowlist from exact environment-variable names.
    pub fn new(names: impl IntoIterator<Item = impl Into<String>>) -> Self {
        Self {
            env_names: names.into_iter().map(Into::into).collect(),
        }
    }

    /// Returns whether the exact name is allowed.
    pub fn contains(&self, name: &str) -> bool {
        self.env_names.contains(name)
    }

    /// Verifies that every parsed output name is operationally allowlisted.
    pub fn validate_names<'a>(
        &self,
        names: impl IntoIterator<Item = &'a str>,
    ) -> Result<(), OperationalAllowlistError> {
        if names.into_iter().all(|name| self.contains(name)) {
            Ok(())
        } else {
            Err(OperationalAllowlistError)
        }
    }
}

/// A deliberately value-free allowlist failure.
#[derive(Debug, Error, Clone, Copy, Eq, PartialEq)]
#[error("a parsed flag was not in the operational allowlist")]
pub struct OperationalAllowlistError;

#[cfg(feature = "flags2env-adapter")]
mod flags2env_adapter;

#[cfg(feature = "flags2env-adapter")]
pub use flags2env_adapter::{
    ConfigError, ConfigSearch, parse_log_filter, process_log_filter, resolve_config_path,
};

/// Parses the pinned `flags2env` structured result and returns only explicitly
/// allowlisted operational values. Secret-bearing flags must never be declared
/// in `.cli-flags.toml`.
#[cfg(feature = "flags2env-adapter")]
pub fn parse_operational_flags(
    argv: &[String],
    config_path: &std::path::Path,
    allowlist: &OperationalAllowlist,
) -> Result<std::collections::BTreeMap<String, String>, ConfigError> {
    use flags2env::BundledFlags2Env;

    let config_path = config_path.to_str().ok_or(ConfigError::NonUtf8Path)?;
    let parser = BundledFlags2Env::new();
    parser
        .audit_config(Some(config_path))
        .map_err(|error| ConfigError::Audit(error.to_string()))?;
    let parsed = parser
        .parse_structured(argv, Some(config_path))
        .map_err(|_| ConfigError::Parse)?;

    if !parsed.unknown_options.is_empty() {
        let names = parsed
            .unknown_options
            .iter()
            .map(|value| ore_mcp_safety::sanitized_cli_token(value))
            .collect();
        return Err(ConfigError::UnknownOptions(names));
    }
    if !parsed.errors.is_empty() {
        return Err(ConfigError::InvalidValues(parsed.errors.len()));
    }
    if !parsed.extras.is_empty() {
        return Err(ConfigError::UnexpectedPositionals(parsed.extras.len()));
    }
    if allowlist
        .validate_names(parsed.flags.keys().map(String::as_str))
        .is_err()
    {
        // Reuse the intentionally value-free parse error. The richer old error
        // enum has no allowlist variant because the mature implementation
        // predates this policy layer.
        return Err(ConfigError::NotAllowlisted);
    }
    Ok(parsed.flags.into_iter().collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_default_filter() {
        assert!(validate_log_filter(None).is_ok());
    }

    #[test]
    fn rejects_invalid_filter_without_echoing_it() {
        assert_eq!(
            validate_log_filter(Some("[invalid"))
                .expect_err("invalid filter must fail")
                .to_string(),
            "invalid log filter"
        );
    }

    #[test]
    fn allowlist_is_exact_and_secret_excluding() {
        let allowlist = OperationalAllowlist::new(["RUST_LOG", "MCP_MAX_OUTPUT_BYTES"]);
        assert!(allowlist.contains("RUST_LOG"));
        assert!(!allowlist.contains("API_TOKEN"));
        assert!(allowlist.validate_names(["RUST_LOG"]).is_ok());
        assert!(allowlist.validate_names(["API_TOKEN"]).is_err());
    }
}
