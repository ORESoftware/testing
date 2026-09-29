//! Consumer-shaped regression for the ores-compose -> ores-proc-isolation policy handoff.

#![allow(clippy::needless_return)]

use sha2::{Digest, Sha256};
use std::error::Error;
use std::fs;
use std::process::Command;
use tempfile::tempdir;

#[test]
fn compose_argument_order_reaches_exact_policy_digest_admission() -> Result<(), Box<dyn Error>> {
    let dir = tempdir()?;
    let path = dir.path().join("policy.yaml");
    let policy = r#"version: 1
defaults:
  group: strict
groups:
  strict: {}
processes:
  target:
    command: [/usr/bin/true]
"#;
    fs::write(&path, policy)?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&path, fs::Permissions::from_mode(0o600))?;
    }

    let actual = format!("{:x}", Sha256::digest(policy.as_bytes()));
    let replacement = if actual.starts_with('0') { '1' } else { '0' };
    let wrong_digest = format!("{replacement}{}", &actual[1..]);

    let output = Command::new(env!("CARGO_BIN_EXE_ores-proc-isolation"))
        .arg("--target-env-stdin")
        .arg("--config-sha256")
        .arg(&wrong_digest)
        .arg("run")
        .arg("target")
        .arg("--config")
        .arg(&path)
        .output()?;

    assert!(!output.status.success());
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(
        stderr.contains("policy SHA-256 does not match the expected handoff digest"),
        "exact compose-shaped argv must reach digest admission before sandbox launch; stderr={stderr:?}"
    );
    assert!(
        !stderr.contains("unknown option") && !stderr.contains("invalid arguments were rejected"),
        "compose-shaped argv must remain accepted by the canonical flags contract; stderr={stderr:?}"
    );

    return Ok(());
}
