//! Strict, stdio-safe startup configuration for MCP servers.
//!
//! The adapter audits a pinned `flags2env` configuration, rejects unknown
//! options and positional arguments, and deliberately avoids echoing values in
//! startup errors. Secret-bearing options should not be declared in a server's
//! `.cli-flags.toml`; they remain environment- or secret-store-only.

use std::{
    error::Error,
    fmt, io,
    path::{Path, PathBuf},
};

use flags2env::BundledFlags2Env;
use ore_mcp_safety::sanitized_cli_token;
use tracing_subscriber::EnvFilter;

/// Deterministic search rules for a server's `.cli-flags.toml` file.
#[derive(Clone, Debug)]
pub struct ConfigSearch {
    environment_override: &'static str,
    installed_share_path: Option<&'static str>,
}

impl ConfigSearch {
    /// Creates search rules with a required environment-variable override.
    pub const fn new(environment_override: &'static str) -> Self {
        Self {
            environment_override,
            installed_share_path: None,
        }
    }

    /// Adds a path relative to the executable directory, such as
    /// `../share/example-mcp-server/.cli-flags.toml`.
    #[must_use]
    pub const fn with_installed_share_path(mut self, path: &'static str) -> Self {
        self.installed_share_path = Some(path);
        self
    }

    /// Returns the environment variable used for an explicit path override.
    pub const fn environment_override(&self) -> &'static str {
        self.environment_override
    }
}

/// A startup configuration error whose display representation omits supplied
/// command-line values.
#[derive(Debug)]
pub enum ConfigError {
    /// The explicit config-path environment variable was set but invalid.
    InvalidOverride(&'static str),
    /// No configuration file was found in any allowed location.
    ConfigNotFound(&'static str),
    /// The configuration file path was not valid UTF-8.
    NonUtf8Path,
    /// The `flags2env` audit failed.
    Audit(String),
    /// `flags2env` could not parse the supplied argument structure.
    Parse,
    /// One or more undeclared option names were supplied.
    UnknownOptions(Vec<String>),
    /// One or more declared options had invalid values.
    InvalidValues(usize),
    /// Positional arguments were supplied to a server that accepts none.
    UnexpectedPositionals(usize),
    /// The resulting tracing filter was invalid.
    InvalidLogFilter,
    /// Filesystem discovery failed.
    Io(io::Error),
}

impl fmt::Display for ConfigError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidOverride(name) => {
                write!(formatter, "{name} does not point to a readable file")
            }
            Self::ConfigNotFound(name) => write!(
                formatter,
                "cannot locate .cli-flags.toml; set {name} to its path"
            ),
            Self::NonUtf8Path => formatter.write_str(".cli-flags.toml path is not valid UTF-8"),
            Self::Audit(message) => {
                write!(formatter, "flags2env configuration audit failed: {message}")
            }
            Self::Parse => formatter.write_str("flags2env could not parse command-line arguments"),
            Self::UnknownOptions(options) => {
                write!(
                    formatter,
                    "unknown command-line option(s): {}",
                    options.join(", ")
                )
            }
            Self::InvalidValues(count) => {
                write!(formatter, "{count} command-line value(s) were invalid")
            }
            Self::UnexpectedPositionals(count) => {
                write!(formatter, "{count} unexpected positional argument(s)")
            }
            Self::InvalidLogFilter => formatter.write_str("invalid --log-filter value"),
            Self::Io(error) => write!(formatter, "configuration discovery failed: {error}"),
        }
    }
}

impl Error for ConfigError {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        match self {
            Self::Io(error) => Some(error),
            _ => None,
        }
    }
}

impl From<io::Error> for ConfigError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

/// Resolves a configuration file using an explicit override first, followed by
/// the current directory, executable directory, and optional installed share
/// path.
pub fn resolve_config_path(search: &ConfigSearch) -> Result<PathBuf, ConfigError> {
    if let Some(path) = std::env::var_os(search.environment_override)
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
    {
        if path.is_file() {
            return Ok(path);
        }
        return Err(ConfigError::InvalidOverride(search.environment_override));
    }

    let mut candidates = Vec::new();
    if let Ok(current) = std::env::current_dir() {
        candidates.push(current.join(".cli-flags.toml"));
    }
    if let Ok(executable) = std::env::current_exe()
        && let Some(parent) = executable.parent()
    {
        candidates.push(parent.join(".cli-flags.toml"));
        if let Some(path) = search.installed_share_path {
            candidates.push(parent.join(path));
        }
    }

    candidates
        .into_iter()
        .find(|candidate| candidate.is_file())
        .ok_or(ConfigError::ConfigNotFound(search.environment_override))
}

/// Audits and parses `argv`, returning a validated tracing filter.
///
/// Unknown option diagnostics retain names but strip attached values. Invalid
/// declared values and positionals are reported by count only.
pub fn parse_log_filter(
    argv: &[String],
    config_path: &Path,
    default_filter: &str,
) -> Result<EnvFilter, ConfigError> {
    let config_path = config_path.to_str().ok_or(ConfigError::NonUtf8Path)?;
    let parser = BundledFlags2Env::new();
    parser
        .audit_config(Some(config_path))
        .map_err(|error| ConfigError::Audit(error.to_string()))?;
    let parsed = parser
        .parse_structured(argv, Some(config_path))
        .map_err(|_| ConfigError::Parse)?;

    if !parsed.unknown_options.is_empty() {
        let options = parsed
            .unknown_options
            .iter()
            .map(|value| sanitized_cli_token(value))
            .collect();
        return Err(ConfigError::UnknownOptions(options));
    }
    if !parsed.errors.is_empty() {
        return Err(ConfigError::InvalidValues(parsed.errors.len()));
    }
    if !parsed.extras.is_empty() {
        return Err(ConfigError::UnexpectedPositionals(parsed.extras.len()));
    }

    let filter = parsed
        .flags
        .get("RUST_LOG")
        .map_or(default_filter, String::as_str);
    EnvFilter::try_new(filter).map_err(|_| ConfigError::InvalidLogFilter)
}

/// Resolves configuration and parses the current process arguments.
pub fn process_log_filter(
    search: &ConfigSearch,
    default_filter: &str,
) -> Result<EnvFilter, ConfigError> {
    let argv = std::env::args().collect::<Vec<_>>();
    let config_path = resolve_config_path(search)?;
    parse_log_filter(&argv, &config_path, default_filter)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config_path() -> PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR")).join(".cli-flags.toml")
    }

    #[test]
    fn accepts_declared_operational_filter() {
        let argv = vec![
            "example-mcp-server".to_owned(),
            "--log-filter=debug,hyper=warn".to_owned(),
        ];
        let filter = parse_log_filter(&argv, &config_path(), "info")
            .expect("declared operational option must parse");
        assert!(filter.to_string().contains("debug"));
    }

    #[test]
    fn rejects_secret_bearing_unknown_option_without_echoing_value() {
        let argv = vec![
            "example-mcp-server".to_owned(),
            "--api-token=must-never-appear".to_owned(),
        ];
        let message = parse_log_filter(&argv, &config_path(), "info")
            .expect_err("undeclared secret option must fail")
            .to_string();
        assert!(message.contains("--api-token"));
        assert!(!message.contains("must-never-appear"));
    }

    #[test]
    fn positional_diagnostics_report_count_only() {
        let argv = vec![
            "example-mcp-server".to_owned(),
            "possibly-sensitive-value".to_owned(),
        ];
        let message = parse_log_filter(&argv, &config_path(), "info")
            .expect_err("positionals must fail")
            .to_string();
        assert!(message.contains("1 unexpected positional"));
        assert!(!message.contains("possibly-sensitive-value"));
    }

    #[test]
    fn rejects_invalid_log_filter_without_echoing_value() {
        let argv = vec![
            "example-mcp-server".to_owned(),
            "--log-filter=[invalid".to_owned(),
        ];
        let message = parse_log_filter(&argv, &config_path(), "info")
            .expect_err("invalid filter must fail")
            .to_string();
        assert_eq!(message, "invalid --log-filter value");
    }
}
