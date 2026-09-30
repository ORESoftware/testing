//! Fail-closed startup-environment validator for the BeamScale runtime host.
//!
//! This binary is intentionally side-effect free. Deployment should execute it
//! immediately before `bmscl-runtime-host` so malformed explicit configuration
//! cannot silently fall back to a different execution backend or resource limit.

use std::collections::BTreeMap;
use std::env;
use std::net::SocketAddr;

const DEFAULT_GUEST_MAX_FRAME_BYTES: usize = 16 * 1024 * 1024;
const DEFAULT_GUEST_MAX_ARTIFACT_CHUNK_BYTES: usize = 1024 * 1024;

const POSITIVE_U32_VARS: &[&str] = &[
    "BMSCL_JAILER_UID",
    "BMSCL_JAILER_GID",
    "BMSCL_VMM_OVERHEAD_MIB",
    "BMSCL_GUEST_VSOCK_PORT",
];

const POSITIVE_U64_VARS: &[&str] = &[
    "BMSCL_FIRECRACKER_API_TIMEOUT_MS",
    "BMSCL_MAX_ARTIFACT_BYTES",
];

const ABSOLUTE_PATH_VARS: &[&str] = &[
    "BMSCL_FIRECRACKER_BIN",
    "BMSCL_JAILER_BIN",
    "BMSCL_JAILER_CHROOT_BASE",
    "BMSCL_IP_BIN",
    "BMSCL_NETNS_ROOT",
    "BMSCL_GUEST_KERNEL_IMAGE",
    "BMSCL_GUEST_ROOTFS_IMAGE",
    "BMSCL_RUNTIME_DIR",
    "BMSCL_CGROUP_ROOT",
    "BMSCL_RUNTIME_IDENTITY_ROOT",
    "BMSCL_RUNTIME_IDENTITY_WRITER",
    "BMSCL_RUNTIME_ARTIFACT_ROOT",
];

fn main() {
    let values = env::vars().collect::<BTreeMap<_, _>>();
    if let Err(error) = validate(&values) {
        eprintln!("bmscl-runtime-host-env-check: {error}");
        std::process::exit(2);
    }
}

fn validate(values: &BTreeMap<String, String>) -> Result<(), String> {
    validate_backend(values)?;
    validate_positive_numeric_vars(values)?;
    validate_frame_sizes(values)?;
    validate_paths(values)?;
    validate_bind(values)?;
    return Ok(());
}

fn validate_backend(values: &BTreeMap<String, String>) -> Result<(), String> {
    let allow_mock = match values.get("BMSCL_ALLOW_MOCK_BACKEND").map(String::as_str) {
        None | Some("0") => false,
        Some("1") => true,
        Some(value) => {
            return Err(format!(
                "BMSCL_ALLOW_MOCK_BACKEND must be unset, '0', or '1'; got {value:?}"
            ));
        }
    };

    match values
        .get("BMSCL_RUNTIME_BACKEND")
        .map(String::as_str)
        .unwrap_or("firecracker")
    {
        "firecracker" => return Ok(()),
        "mock" if allow_mock => return Ok(()),
        "mock" => {
            return Err(
                "BMSCL_RUNTIME_BACKEND=mock requires BMSCL_ALLOW_MOCK_BACKEND=1".to_owned(),
            );
        }
        value => {
            return Err(format!(
                "unsupported BMSCL_RUNTIME_BACKEND {value:?}; expected 'firecracker' or explicitly authorized 'mock'"
            ));
        }
    }
}

fn validate_positive_numeric_vars(values: &BTreeMap<String, String>) -> Result<(), String> {
    for name in POSITIVE_U32_VARS {
        if let Some(value) = values.get(*name) {
            let parsed = value.parse::<u32>().map_err(|error| {
                format!("{name} must be a positive u32, got {value:?}: {error}")
            })?;
            if parsed == 0 {
                return Err(format!("{name} must be greater than zero"));
            }
        }
    }

    for name in POSITIVE_U64_VARS {
        if let Some(value) = values.get(*name) {
            let parsed = value.parse::<u64>().map_err(|error| {
                format!("{name} must be a positive u64, got {value:?}: {error}")
            })?;
            if parsed == 0 {
                return Err(format!("{name} must be greater than zero"));
            }
        }
    }
    return Ok(());
}

fn validate_frame_sizes(values: &BTreeMap<String, String>) -> Result<(), String> {
    let frame = parse_positive_usize(
        values,
        "BMSCL_GUEST_MAX_FRAME_BYTES",
        DEFAULT_GUEST_MAX_FRAME_BYTES,
    )?;
    let chunk = parse_positive_usize(
        values,
        "BMSCL_GUEST_MAX_ARTIFACT_CHUNK_BYTES",
        DEFAULT_GUEST_MAX_ARTIFACT_CHUNK_BYTES,
    )?;
    if chunk > frame {
        return Err(format!(
            "BMSCL_GUEST_MAX_ARTIFACT_CHUNK_BYTES ({chunk}) must not exceed BMSCL_GUEST_MAX_FRAME_BYTES ({frame})"
        ));
    }
    return Ok(());
}

fn parse_positive_usize(
    values: &BTreeMap<String, String>,
    name: &str,
    default: usize,
) -> Result<usize, String> {
    let Some(value) = values.get(name) else {
        return Ok(default);
    };
    let parsed = value
        .parse::<usize>()
        .map_err(|error| format!("{name} must be a positive usize, got {value:?}: {error}"))?;
    if parsed == 0 {
        return Err(format!("{name} must be greater than zero"));
    }
    return Ok(parsed);
}

fn validate_paths(values: &BTreeMap<String, String>) -> Result<(), String> {
    for name in ABSOLUTE_PATH_VARS {
        let Some(value) = values.get(*name) else {
            continue;
        };
        validate_absolute_path(name, value)?;
    }
    return Ok(());
}

fn validate_absolute_path(name: &str, value: &str) -> Result<(), String> {
    if !value.starts_with('/') || value.len() < 2 {
        return Err(format!("{name} must be an absolute non-root path, got {value:?}"));
    }
    if value.ends_with('/') {
        return Err(format!("{name} must not end with '/', got {value:?}"));
    }
    for segment in value[1..].split('/') {
        if segment.is_empty() || matches!(segment, "." | "..") {
            return Err(format!(
                "{name} must not contain empty, '.' or '..' path components, got {value:?}"
            ));
        }
    }
    return Ok(());
}

fn validate_bind(values: &BTreeMap<String, String>) -> Result<(), String> {
    let Some(value) = values.get("BMSCL_RUNTIME_HOST_BIND") else {
        return Ok(());
    };
    value.parse::<SocketAddr>().map_err(|error| {
        format!("BMSCL_RUNTIME_HOST_BIND must be a numeric socket address, got {value:?}: {error}")
    })?;
    return Ok(());
}

#[cfg(test)]
mod tests {
    use super::*;

    fn values(entries: &[(&str, &str)]) -> BTreeMap<String, String> {
        return entries
            .iter()
            .map(|(name, value)| ((*name).to_owned(), (*value).to_owned()))
            .collect();
    }

    #[test]
    fn defaults_are_firecracker_and_valid() {
        assert!(validate(&BTreeMap::new()).is_ok());
    }

    #[test]
    fn unknown_backend_does_not_fall_back_to_firecracker() {
        let error = validate(&values(&[("BMSCL_RUNTIME_BACKEND", "firecraker")]))
            .expect_err("misspelled backend must fail closed");
        assert!(error.contains("unsupported BMSCL_RUNTIME_BACKEND"));
    }

    #[test]
    fn mock_backend_requires_explicit_authorization() {
        assert!(validate(&values(&[("BMSCL_RUNTIME_BACKEND", "mock")])).is_err());
        assert!(
            validate(&values(&[
                ("BMSCL_RUNTIME_BACKEND", "mock"),
                ("BMSCL_ALLOW_MOCK_BACKEND", "1"),
            ]))
            .is_ok()
        );
        assert!(validate(&values(&[("BMSCL_ALLOW_MOCK_BACKEND", "yes")])).is_err());
    }

    #[test]
    fn explicitly_malformed_numeric_values_never_default() {
        for name in POSITIVE_U32_VARS.iter().chain(POSITIVE_U64_VARS.iter()) {
            assert!(validate(&values(&[(name, "nope")])).is_err(), "{name}");
            assert!(validate(&values(&[(name, "0")])).is_err(), "{name}");
        }
        assert!(
            validate(&values(&[("BMSCL_GUEST_MAX_FRAME_BYTES", "invalid")])).is_err()
        );
        assert!(
            validate(&values(&[("BMSCL_GUEST_MAX_ARTIFACT_CHUNK_BYTES", "0")])).is_err()
        );
    }

    #[test]
    fn artifact_chunk_must_fit_inside_guest_frame() {
        assert!(
            validate(&values(&[
                ("BMSCL_GUEST_MAX_FRAME_BYTES", "1024"),
                ("BMSCL_GUEST_MAX_ARTIFACT_CHUNK_BYTES", "2048"),
            ]))
            .is_err()
        );
    }

    #[test]
    fn security_sensitive_paths_are_absolute_and_unaliased() {
        for name in ABSOLUTE_PATH_VARS {
            for rejected in [
                "relative/path",
                "/",
                "/safe/../escape",
                "/safe/./alias",
                "/safe//duplicate",
                "/safe/trailing/",
            ] {
                assert!(validate(&values(&[(name, rejected)])).is_err(), "{name}: {rejected}");
            }
            assert!(validate(&values(&[(name, "/safe/path")])).is_ok(), "{name}");
        }
    }

    #[test]
    fn bind_must_be_an_unambiguous_numeric_socket_address() {
        assert!(
            validate(&values(&[("BMSCL_RUNTIME_HOST_BIND", "127.0.0.1:9090")])).is_ok()
        );
        assert!(validate(&values(&[("BMSCL_RUNTIME_HOST_BIND", "localhost:9090")])).is_err());
        assert!(validate(&values(&[("BMSCL_RUNTIME_HOST_BIND", "not-an-address")])).is_err());
    }
}
