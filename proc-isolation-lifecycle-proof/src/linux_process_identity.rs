//! Narrow Linux process identity attestation for host lifecycle operations.
//!
//! This module deliberately does not verify the hostile-tenant sandbox boundary.
//! It proves only the process identity properties lifecycle mutation needs:
//! host PID, stable `/proc/<pid>/stat` start ticks, and exact unified cgroup-v2
//! membership. Full namespace/capability/NoNewPrivs admission remains in
//! `linux_attestation` and is a separate trust decision.

#![allow(clippy::needless_return)]

use std::fs;
use std::path::{Path, PathBuf};

use serde::Serialize;

use crate::error::{Error, Result};

/// Narrow identity evidence for one Linux process.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct LinuxProcessIdentity {
    /// Host PID inspected through procfs.
    pub pid: u32,
    /// `/proc/<pid>/stat` field 22.
    pub process_start_ticks: u64,
    /// Exact unified cgroup-v2 path from `/proc/<pid>/cgroup`.
    pub cgroup_v2_path: String,
}

/// Read a stable lifecycle identity for one Linux process.
pub fn attest_linux_process_identity(pid: u32) -> Result<LinuxProcessIdentity> {
    if !cfg!(target_os = "linux") {
        return Err(Error::SandboxUnavailable(
            "Linux process identity attestation is only available on Linux".to_owned(),
        ));
    }
    if pid == 0 {
        return Err(Error::SandboxUnavailable(
            "refusing to attest lifecycle identity for PID 0".to_owned(),
        ));
    }

    let proc_dir = PathBuf::from(format!("/proc/{pid}"));
    let start_before = read_process_start_ticks(&proc_dir)?;
    let cgroup_v2_path = read_cgroup_v2_path(&proc_dir, pid)?;
    let start_after = read_process_start_ticks(&proc_dir)?;
    if start_before != start_after {
        return Err(Error::SandboxUnavailable(format!(
            "PID {pid} changed identity during lifecycle attestation"
        )));
    }

    return Ok(LinuxProcessIdentity {
        pid,
        process_start_ticks: start_before,
        cgroup_v2_path,
    });
}

fn read_cgroup_v2_path(proc_dir: &Path, pid: u32) -> Result<String> {
    let path = proc_dir.join("cgroup");
    let text = fs::read_to_string(&path).map_err(|error| {
        Error::SandboxUnavailable(format!(
            "cannot read cgroup membership for PID {pid}: {error}"
        ))
    })?;
    return parse_unified_cgroup_path(&text).ok_or_else(|| {
        Error::SandboxUnavailable(format!(
            "PID {pid} has missing or ambiguous cgroup-v2 membership"
        ))
    });
}

fn read_process_start_ticks(proc_dir: &Path) -> Result<u64> {
    let stat_path = proc_dir.join("stat");
    let text = fs::read_to_string(&stat_path).map_err(|error| {
        Error::SandboxUnavailable(format!(
            "cannot read process identity {}: {error}",
            stat_path.display()
        ))
    })?;
    return parse_process_start_ticks(&text).ok_or_else(|| {
        Error::SandboxUnavailable(format!(
            "cannot parse process start identity {}",
            stat_path.display()
        ))
    });
}

fn parse_unified_cgroup_path(text: &str) -> Option<String> {
    let mut matches = text.lines().filter_map(|line| line.strip_prefix("0::"));
    let path = matches.next()?;
    if matches.next().is_some() || !path.starts_with('/') {
        return None;
    }
    return Some(path.to_owned());
}

fn parse_process_start_ticks(stat: &str) -> Option<u64> {
    let command_end = stat.rfind(')')?;
    let remainder = stat.get(command_end + 1..)?.trim();
    // The first token after `comm` is field 3 (`state`). Field 22 (`starttime`)
    // is therefore token index 19 in this remainder.
    return remainder.split_whitespace().nth(19)?.parse().ok();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_start_ticks_with_spaces_and_parentheses_in_comm() {
        let stat =
            "4242 (tenant (beam) vm) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 987654 20";
        assert_eq!(parse_process_start_ticks(stat), Some(987654));
    }

    #[test]
    fn unified_cgroup_membership_must_be_single_and_absolute() {
        assert_eq!(
            parse_unified_cgroup_path("0::/system.slice/runtime.scope\n"),
            Some("/system.slice/runtime.scope".to_owned())
        );
        assert_eq!(parse_unified_cgroup_path(""), None);
        assert_eq!(parse_unified_cgroup_path("0::relative\n"), None);
        assert_eq!(parse_unified_cgroup_path("0::/one\n0::/two\n"), None);
    }
}
