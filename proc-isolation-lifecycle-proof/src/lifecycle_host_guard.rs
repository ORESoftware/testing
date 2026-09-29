//! Host-context guard for privileged Linux lifecycle commands.
//!
//! Namespace uid 0 is not host-root authority. A hostile tenant sandbox may map
//! its own user namespace uid/gid 0 while retaining zero host capabilities. The
//! lifecycle CLI therefore requires the initial Linux user namespace in addition
//! to effective uid 0 before it will touch host lifecycle authority paths.

#![forbid(unsafe_code)]
#![allow(clippy::needless_return)]

use crate::error::{Error, Result};

const INITIAL_ID_MAP_COUNT: u64 = 4_294_967_295;

/// Require host-root credentials in the initial Linux user namespace.
pub fn require_lifecycle_host_context() -> Result<()> {
    #[cfg(target_os = "linux")]
    {
        return linux::require_host_context();
    }

    #[cfg(not(target_os = "linux"))]
    {
        return Err(Error::SandboxUnavailable(
            "managed-scope lifecycle operations require the Linux host user namespace".to_owned(),
        ));
    }
}

fn is_initial_id_map(value: &str) -> bool {
    let mut non_empty = value.lines().filter(|line| !line.trim().is_empty());
    let Some(line) = non_empty.next() else {
        return false;
    };
    if non_empty.next().is_some() {
        return false;
    }

    let mut fields = line.split_ascii_whitespace();
    let parsed = (
        fields.next().and_then(|field| field.parse::<u64>().ok()),
        fields.next().and_then(|field| field.parse::<u64>().ok()),
        fields.next().and_then(|field| field.parse::<u64>().ok()),
    );
    if fields.next().is_some() {
        return false;
    }

    return parsed == (Some(0), Some(0), Some(INITIAL_ID_MAP_COUNT));
}

#[cfg(target_os = "linux")]
mod linux {
    use std::fs;

    use super::*;

    pub(super) fn require_host_context() -> Result<()> {
        let status = fs::read_to_string("/proc/self/status").map_err(|error| {
            Error::SandboxUnavailable(format!(
                "cannot read lifecycle helper credentials from /proc/self/status: {error}"
            ))
        })?;
        let effective_uid = parse_effective_uid(&status).ok_or_else(|| {
            Error::SandboxUnavailable(
                "cannot parse lifecycle helper effective uid from /proc/self/status".to_owned(),
            )
        })?;
        if effective_uid != 0 {
            return Err(Error::SandboxUnavailable(format!(
                "lifecycle commands require host effective uid 0, got {effective_uid}"
            )));
        }

        require_initial_map("/proc/self/uid_map", "uid")?;
        require_initial_map("/proc/self/gid_map", "gid")?;
        return Ok(());
    }

    fn require_initial_map(path: &str, kind: &str) -> Result<()> {
        let value = fs::read_to_string(path).map_err(|error| {
            Error::SandboxUnavailable(format!(
                "cannot read lifecycle helper {kind} namespace map {path}: {error}"
            ))
        })?;
        if !is_initial_id_map(&value) {
            return Err(Error::SandboxUnavailable(format!(
                "lifecycle commands require the initial host user namespace; {kind} map is not the initial namespace mapping"
            )));
        }
        return Ok(());
    }

    fn parse_effective_uid(status: &str) -> Option<u32> {
        let value = status.lines().find_map(|line| {
            let (name, value) = line.split_once(':')?;
            if name == "Uid" {
                return Some(value.trim());
            }
            return None;
        })?;
        return value.split_whitespace().nth(1)?.parse().ok();
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn effective_uid_parser_uses_effective_column() {
            assert_eq!(parse_effective_uid("Uid:\t1000\t0\t1000\t1000\n"), Some(0));
            assert_eq!(parse_effective_uid("Uid:\t0\t1000\t0\t0\n"), Some(1000));
            assert_eq!(parse_effective_uid("Name:\thelper\n"), None);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn initial_namespace_map_is_exact_and_single_range() {
        assert!(is_initial_id_map("         0          0 4294967295\n"));
        assert!(is_initial_id_map("0 0 4294967295\n"));

        for value in [
            "0 1000 1\n",
            "0 0 1\n",
            "0 0 4294967294\n",
            "0 0 4294967295\n1 1 1\n",
            "1 0 4294967295\n",
            "0 1 4294967295\n",
            "",
            "garbage\n",
        ] {
            assert!(!is_initial_id_map(value), "{value:?}");
        }
    }
}
