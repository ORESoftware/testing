//! Exact-parser proof for the ores-compose process-isolation handoff.

#![allow(clippy::needless_return)]

use proc_isolation_lifecycle_proof::flags::{CliCommand, parse_from};
use std::error::Error;
use std::path::PathBuf;

#[test]
fn compose_shaped_argv_binds_digest_config_and_target_env_stdin() -> Result<(), Box<dyn Error>> {
    let config_path = PathBuf::from("/tmp/ores-compose-isolation-proof.yaml");
    let digest = "a".repeat(64);
    let cli = parse_from(vec![
        "ores-proc-isolation".to_owned(),
        "--target-env-stdin".to_owned(),
        "--config-sha256".to_owned(),
        digest.clone(),
        "run".to_owned(),
        "target".to_owned(),
        "--config".to_owned(),
        config_path.to_string_lossy().into_owned(),
    ])?;

    assert!(cli.target_env_stdin);
    assert_eq!(cli.config_sha256.as_deref(), Some(digest.as_str()));
    assert_eq!(cli.config_path, config_path);
    assert!(!cli.dry_run);
    assert!(!cli.json);

    match cli.command {
        CliCommand::Run {
            process,
            extra_args,
        } => {
            assert_eq!(process, "target");
            assert!(extra_args.is_empty());
        }
        command => {
            panic!("compose-shaped argv resolved to the wrong command: {command:?}");
        }
    }

    return Ok(());
}
