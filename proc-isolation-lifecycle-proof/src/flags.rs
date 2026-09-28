//! `flags-2-env` adapter. `.cli-flags.toml` is the only public argv authority.

use std::collections::HashMap;
use std::env;
use std::io::Write;
use std::path::PathBuf;

use flags2env::BundledFlags2Env;
use serde::Deserialize;
use tempfile::NamedTempFile;

use crate::error::{Error, Result};
use crate::linux_lifecycle::validate_lifecycle_runtime_id;

const BUNDLED_CONTRACT: &str = include_str!("../.cli-flags.toml");

/// Typed command selected by the canonical flag contract.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CliCommand {
    /// Validate configuration.
    Check,
    /// Validate configuration and host dependencies.
    Doctor,
    /// Explain one process.
    Explain {
        /// Configured process name.
        process: String,
    },
    /// Run one process with optional appended arguments.
    Run {
        /// Configured process name.
        process: String,
        /// Arguments appended after the configured fixed arguments.
        extra_args: Vec<String>,
    },
    /// Observe one exact trusted Linux managed runtime scope.
    LifecycleStatus {
        /// Host-owned logical runtime identity.
        runtime_id: String,
    },
    /// Freeze one exact trusted Linux managed runtime scope.
    LifecycleFreeze {
        /// Host-owned logical runtime identity.
        runtime_id: String,
    },
    /// Thaw one exact trusted Linux managed runtime scope.
    LifecycleThaw {
        /// Host-owned logical runtime identity.
        runtime_id: String,
    },
    /// Best-effort reclaim one exact frozen Linux managed runtime scope.
    LifecycleReclaim {
        /// Host-owned logical runtime identity.
        runtime_id: String,
    },
}

impl CliCommand {
    /// Whether this command belongs to the host-only lifecycle surface.
    pub fn is_lifecycle(&self) -> bool {
        return matches!(
            self,
            Self::LifecycleStatus { .. }
                | Self::LifecycleFreeze { .. }
                | Self::LifecycleThaw { .. }
                | Self::LifecycleReclaim { .. }
        );
    }
}

/// Fully resolved CLI options.
#[derive(Debug, Clone)]
pub struct Cli {
    /// Policy path.
    pub config_path: PathBuf,
    /// Optional expected lowercase SHA-256 digest for the policy bytes.
    pub config_sha256: Option<String>,
    /// Do not launch; report the plan.
    pub dry_run: bool,
    /// Emit JSON output.
    pub json: bool,
    /// Enable the fixed BeamScale tripwire mount/PATH contract on Linux.
    pub beamscale_honeypot: bool,
    /// Read a bounded target-environment JSON object from stdin for `run`.
    pub target_env_stdin: bool,
    /// Selected command.
    pub command: CliCommand,
}

#[derive(Debug, Default, Deserialize)]
struct ResolvedFlags {
    #[serde(rename = "ORES_PI_CONFIG", default = "default_policy_path")]
    config: String,
    #[serde(rename = "ORES_PI_CONFIG_SHA256", default)]
    config_sha256: String,
    #[serde(rename = "ORES_PI_DRY_RUN", default)]
    dry_run: bool,
    #[serde(rename = "ORES_PI_JSON", default)]
    json: bool,
    #[serde(rename = "ORES_PI_BEAMSCALE_HONEYPOT", default)]
    beamscale_honeypot: bool,
    #[serde(rename = "ORES_PI_TARGET_ENV_STDIN", default)]
    target_env_stdin: bool,
    #[serde(rename = "ORES_PI_LIFECYCLE_ID", default)]
    lifecycle_id: String,
}

fn default_policy_path() -> String {
    return ".ores-proc-isolation.yaml".to_owned();
}

/// Parse the process argv using the audited, bundled `flags-2-env` contract.
pub fn parse() -> Result<Cli> {
    return parse_from(env::args().collect());
}

/// Parse a supplied argv vector. Exposed for tests and embedding.
pub fn parse_from(argv: Vec<String>) -> Result<Cli> {
    let parser = BundledFlags2Env::new();
    let mut contract_file = NamedTempFile::new().map_err(Error::HelperIo)?;
    contract_file
        .write_all(BUNDLED_CONTRACT.as_bytes())
        .map_err(Error::HelperIo)?;
    let contract = contract_file.path().to_string_lossy().into_owned();

    parser
        .audit_config(Some(&contract))
        .map_err(|error| Error::Cli(format!("flag contract audit failed: {error}")))?;
    let structured = parser
        .parse_structured(&argv, Some(&contract))
        .map_err(|error| Error::Cli(format!("flag parsing failed: {error}")))?;

    if !structured.unknown_options.is_empty() {
        return Err(Error::Cli(format!(
            "unknown options were rejected: {:?}",
            structured.unknown_options
        )));
    }
    if !structured.errors.is_empty() {
        return Err(Error::Cli(format!(
            "invalid arguments were rejected: {:?}",
            structured.errors
        )));
    }

    let resolved_commands = parser
        .resolve_commands(&argv, Some(&contract))
        .map_err(|error| Error::Cli(format!("command resolution failed: {error}")))?;
    let values = coerce_values(
        &parser,
        &structured.dotenv,
        &structured.dotenv_overrides,
        &structured.provided_flags,
        &contract,
    )?;
    let path = if resolved_commands.path.is_empty() {
        let mut fallback = Vec::new();
        if !structured.command.trim().is_empty() {
            fallback.push(structured.command.trim().to_owned());
        }
        fallback.extend(
            structured
                .subcommands
                .iter()
                .map(|value| value.trim())
                .filter(|value| !value.is_empty())
                .map(ToOwned::to_owned),
        );
        fallback
    } else {
        resolved_commands.path
    };

    let mut extras = structured.extras;
    if extras.first().is_some_and(|value| value == "--") {
        extras.remove(0);
    }

    let command = match path.as_slice() {
        [name] if name == "check" => {
            reject_extras("check", &extras)?;
            CliCommand::Check
        }
        [name] if name == "doctor" => {
            reject_extras("doctor", &extras)?;
            CliCommand::Doctor
        }
        [name] if name == "explain" => {
            if extras.len() != 1 {
                return Err(Error::Cli(
                    "usage: ores-proc-isolation explain <process>".to_owned(),
                ));
            }
            CliCommand::Explain {
                process: extras.remove(0),
            }
        }
        [name] if name == "run" => {
            if extras.is_empty() {
                return Err(Error::Cli(
                    "usage: ores-proc-isolation run <process> -- [extra args...]".to_owned(),
                ));
            }
            let process = extras.remove(0);
            if extras.first().is_some_and(|value| value == "--") {
                extras.remove(0);
            }
            CliCommand::Run {
                process,
                extra_args: extras,
            }
        }
        [name] if name == "lifecycle-status" => {
            reject_extras("lifecycle-status", &extras)?;
            CliCommand::LifecycleStatus {
                runtime_id: require_lifecycle_id(&values.lifecycle_id)?,
            }
        }
        [name] if name == "lifecycle-freeze" => {
            reject_extras("lifecycle-freeze", &extras)?;
            CliCommand::LifecycleFreeze {
                runtime_id: require_lifecycle_id(&values.lifecycle_id)?,
            }
        }
        [name] if name == "lifecycle-thaw" => {
            reject_extras("lifecycle-thaw", &extras)?;
            CliCommand::LifecycleThaw {
                runtime_id: require_lifecycle_id(&values.lifecycle_id)?,
            }
        }
        [name] if name == "lifecycle-reclaim" => {
            reject_extras("lifecycle-reclaim", &extras)?;
            CliCommand::LifecycleReclaim {
                runtime_id: require_lifecycle_id(&values.lifecycle_id)?,
            }
        }
        [] => {
            return Err(Error::Cli(
                "missing command: expected check, doctor, explain, run, lifecycle-status, lifecycle-freeze, lifecycle-thaw, or lifecycle-reclaim"
                    .to_owned(),
            ));
        }
        _ => {
            return Err(Error::Cli(format!(
                "unknown command path: {}",
                path.join(" ")
            )));
        }
    };

    if values.target_env_stdin && !matches!(&command, CliCommand::Run { .. }) {
        return Err(Error::Cli(
            "--target-env-stdin is valid only with the run command".to_owned(),
        ));
    }
    if !values.lifecycle_id.is_empty() && !command.is_lifecycle() {
        return Err(Error::Cli(
            "--lifecycle-id is valid only with lifecycle-status, lifecycle-freeze, lifecycle-thaw, or lifecycle-reclaim"
                .to_owned(),
        ));
    }

    let config_sha256 = if values.config_sha256.is_empty() {
        None
    } else if values.config_sha256.len() == 64
        && values
            .config_sha256
            .bytes()
            .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
    {
        Some(values.config_sha256)
    } else {
        return Err(Error::Cli(
            "--config-sha256 must be exactly 64 lowercase hexadecimal characters".to_owned(),
        ));
    };

    if command.is_lifecycle() {
        if values.dry_run {
            return Err(Error::Cli(
                "--dry-run is not valid for lifecycle commands; lifecycle-status is the read-only operation"
                    .to_owned(),
            ));
        }
        if values.beamscale_honeypot {
            return Err(Error::Cli(
                "--beamscale-honeypot is not valid for lifecycle commands".to_owned(),
            ));
        }
        if values.target_env_stdin {
            return Err(Error::Cli(
                "--target-env-stdin is not valid for lifecycle commands".to_owned(),
            ));
        }
        if config_sha256.is_some() {
            return Err(Error::Cli(
                "--config-sha256 is not valid for lifecycle commands; lifecycle authority never comes from tenant policy"
                    .to_owned(),
            ));
        }
    }

    return Ok(Cli {
        config_path: PathBuf::from(values.config),
        config_sha256,
        dry_run: values.dry_run,
        json: values.json,
        beamscale_honeypot: values.beamscale_honeypot,
        target_env_stdin: values.target_env_stdin,
        command,
    });
}

fn require_lifecycle_id(value: &str) -> Result<String> {
    if value.is_empty() {
        return Err(Error::Cli(
            "lifecycle commands require --lifecycle-id <trusted-runtime-id>".to_owned(),
        ));
    }
    validate_lifecycle_runtime_id(value)?;
    return Ok(value.to_owned());
}

fn reject_extras(command: &str, extras: &[String]) -> Result<()> {
    if extras.is_empty() {
        return Ok(());
    }
    return Err(Error::Cli(format!(
        "{command} does not accept positional arguments: {extras:?}"
    )));
}

fn coerce_values(
    parser: &BundledFlags2Env,
    dotenv: &HashMap<String, String>,
    dotenv_overrides: &HashMap<String, String>,
    provided_flags: &HashMap<String, String>,
    contract: &str,
) -> Result<ResolvedFlags> {
    let mut values = dotenv.clone();
    values.extend(env::vars());
    values.extend(dotenv_overrides.clone());
    values.extend(provided_flags.clone());
    return parser
        .coerce(&values, Some(contract))
        .map_err(|error| Error::Cli(format!("invalid typed flag/environment value: {error}")));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn configured_group_cannot_be_overridden_from_cli() {
        let result = parse_from(vec![
            "ores-proc-isolation".to_owned(),
            "check".to_owned(),
            "--group".to_owned(),
            "weaker-policy".to_owned(),
        ]);
        assert!(result.is_err());
    }

    #[test]
    fn bundled_contract_has_no_group_override_flag() {
        assert!(!BUNDLED_CONTRACT.contains("[flags.group]"));
        assert!(!BUNDLED_CONTRACT.contains("ORES_PI_GROUP"));
    }

    #[test]
    fn beamscale_honeypot_flag_is_explicit_and_typed() {
        let cli = parse_from(vec![
            "ores-proc-isolation".to_owned(),
            "--beamscale-honeypot".to_owned(),
            "check".to_owned(),
        ])
        .expect("honeypot flag should parse");
        assert!(cli.beamscale_honeypot);
    }

    #[test]
    fn config_sha256_is_strict_lowercase_hex() {
        let good = parse_from(vec![
            "ores-proc-isolation".to_owned(),
            "--config-sha256".to_owned(),
            "a".repeat(64),
            "check".to_owned(),
        ])
        .expect("valid digest should parse");
        assert_eq!(
            good.config_sha256.as_deref(),
            Some("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        );

        for bad in ["abc".to_owned(), "A".repeat(64), "g".repeat(64)] {
            assert!(
                parse_from(vec![
                    "ores-proc-isolation".to_owned(),
                    "--config-sha256".to_owned(),
                    bad,
                    "check".to_owned(),
                ])
                .is_err()
            );
        }
    }

    #[test]
    fn target_env_stdin_is_run_only_and_typed() {
        let cli = parse_from(vec![
            "ores-proc-isolation".to_owned(),
            "--target-env-stdin".to_owned(),
            "run".to_owned(),
            "worker".to_owned(),
        ])
        .expect("run stdin environment flag should parse");
        assert!(cli.target_env_stdin);

        let rejected = parse_from(vec![
            "ores-proc-isolation".to_owned(),
            "--target-env-stdin".to_owned(),
            "check".to_owned(),
        ]);
        assert!(rejected.is_err());
    }

    #[test]
    fn lifecycle_commands_require_safe_explicit_runtime_id() {
        let cli = parse_from(vec![
            "ores-proc-isolation".to_owned(),
            "--lifecycle-id".to_owned(),
            "tenant-a:runtime-7".to_owned(),
            "lifecycle-freeze".to_owned(),
        ])
        .expect("safe lifecycle id should parse");
        assert_eq!(
            cli.command,
            CliCommand::LifecycleFreeze {
                runtime_id: "tenant-a:runtime-7".to_owned()
            }
        );

        assert!(
            parse_from(vec![
                "ores-proc-isolation".to_owned(),
                "lifecycle-status".to_owned(),
            ])
            .is_err()
        );
        assert!(
            parse_from(vec![
                "ores-proc-isolation".to_owned(),
                "--lifecycle-id".to_owned(),
                "../escape".to_owned(),
                "lifecycle-status".to_owned(),
            ])
            .is_err()
        );
    }

    #[test]
    fn lifecycle_selector_is_rejected_outside_lifecycle_surface() {
        let rejected = parse_from(vec![
            "ores-proc-isolation".to_owned(),
            "--lifecycle-id".to_owned(),
            "runtime-7".to_owned(),
            "check".to_owned(),
        ]);
        assert!(rejected.is_err());
    }

    #[test]
    fn lifecycle_commands_reject_sandbox_mode_flags() {
        for flag in ["--dry-run", "--beamscale-honeypot", "--target-env-stdin"] {
            let rejected = parse_from(vec![
                "ores-proc-isolation".to_owned(),
                flag.to_owned(),
                "--lifecycle-id".to_owned(),
                "runtime-7".to_owned(),
                "lifecycle-status".to_owned(),
            ]);
            assert!(rejected.is_err(), "{flag}");
        }
    }
}
