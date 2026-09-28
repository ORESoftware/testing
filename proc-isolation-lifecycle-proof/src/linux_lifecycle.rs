//! Fail-closed Linux managed-scope lifecycle primitives.
//!
//! These helpers are intentionally narrower than a fleet lifecycle controller.
//! A trusted host process records the exact runtime identity under a fixed
//! root-owned directory. The CLI re-attests PID, process start identity, and
//! cgroup-v2 membership before and after any mutation. Distributed leases,
//! placement policy, queue-idle decisions, and checkpoint/restore belong to the
//! caller and are deliberately absent here.

#![allow(clippy::needless_return)]

use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};

/// Fixed host-owned directory containing lifecycle runtime identity records.
pub const LIFECYCLE_IDENTITY_ROOT: &str = "/run/ores-proc-isolation/lifecycle-identities";

/// Fixed unified cgroup-v2 mount used for lifecycle effects.
pub const CGROUP_V2_ROOT: &str = "/sys/fs/cgroup";

const IDENTITY_SCHEMA: &str = "ores.proc-isolation.linux-managed-scope-identity/v1";
const LIFECYCLE_RECEIPT_SCHEMA: &str = "ores.proc-isolation.linux-lifecycle-receipt/v1";
const MAX_RUNTIME_ID_BYTES: usize = 128;
const MAX_SCOPE_IDENTITY_BYTES: usize = 256;
const MAX_CGROUP_PATH_BYTES: usize = 4096;
const MAX_PROCESS_COUNT: usize = 1_000_000;
const MAX_IDENTITY_RECORD_BYTES: u64 = 64 * 1024;
const FREEZER_POLL_ATTEMPTS: usize = 100;
const FREEZER_POLL_INTERVAL_MS: u64 = 10;
const DEFAULT_RECLAIM_BYTES: u64 = 16 * 1024 * 1024;

/// Host-created identity for one managed Linux runtime scope.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct LinuxManagedScopeIdentity {
    /// Contract discriminator for the host-created identity record.
    pub schema: String,
    /// Stable logical runtime identity chosen by the trusted host controller.
    pub runtime_id: String,
    /// Host-managed systemd unit/scope identity used for receipts and audit.
    pub scope_identity: String,
    /// Host PID captured when the managed runtime was admitted.
    pub pid: u32,
    /// `/proc/<pid>/stat` field 22 captured at admission.
    pub process_start_ticks: u64,
    /// Exact unified cgroup-v2 path for the managed scope.
    pub cgroup_v2_path: String,
}

/// One narrow lifecycle operation.
#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum LinuxLifecycleOperation {
    /// Observe the exact managed scope without mutating it.
    Status,
    /// Freeze the complete managed cgroup-v2 hierarchy.
    Freeze,
    /// Thaw the complete managed cgroup-v2 hierarchy.
    Thaw,
    /// Request bounded best-effort reclaim while the scope is frozen.
    Reclaim,
}

/// Result classification for a reclaim request.
#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ReclaimOutcome {
    /// No reclaim was requested for this operation.
    NotRequested,
    /// The exact managed cgroup does not expose `memory.reclaim`.
    Unsupported,
    /// The kernel accepted the bounded best-effort reclaim request.
    BestEffortCompleted,
}

/// Machine-readable evidence produced by each lifecycle operation.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
pub struct LinuxLifecycleReceipt {
    /// Contract discriminator for the receipt format.
    pub schema: String,
    /// Lifecycle operation that produced this receipt.
    pub operation: LinuxLifecycleOperation,
    /// Host-managed unit/scope identity from the trusted record.
    pub scope_identity: String,
    /// SHA-256 digest of the exact cgroup-v2 identity path.
    pub cgroup_identity_sha256: String,
    /// Stable process-start identity used to reject PID reuse.
    pub process_start_identity: String,
    /// Freezer state observed before the operation.
    pub before_frozen: bool,
    /// Freezer state observed after the operation.
    pub after_frozen: bool,
    /// `memory.current` before the operation when available.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub memory_current_before: Option<u64>,
    /// `memory.current` after the operation when available.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub memory_current_after: Option<u64>,
    /// `memory.swap.current` before the operation when available.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub memory_swap_current_before: Option<u64>,
    /// `memory.swap.current` after the operation when available.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub memory_swap_current_after: Option<u64>,
    /// Number of PIDs observed in the exact managed cgroup after the operation.
    pub process_count: usize,
    /// Whether the exact managed cgroup exposes cgroup-v2 freezer control.
    pub freezer_supported: bool,
    /// Whether the exact managed cgroup exposes `memory.reclaim`.
    pub reclaim_supported: bool,
    /// Reclaim result classification.
    pub reclaim_outcome: ReclaimOutcome,
    /// Always false; freeze/reclaim is never durable RAM-release evidence.
    pub ram_released_claimed: bool,
}

/// Execute one managed-scope lifecycle operation using the fixed host authority roots.
pub fn execute_linux_lifecycle(
    runtime_id: &str,
    operation: LinuxLifecycleOperation,
) -> Result<LinuxLifecycleReceipt> {
    return execute_linux_lifecycle_with_reclaim(runtime_id, operation, DEFAULT_RECLAIM_BYTES);
}

/// Execute one lifecycle operation with an explicit bounded reclaim byte request.
pub fn execute_linux_lifecycle_with_reclaim(
    runtime_id: &str,
    operation: LinuxLifecycleOperation,
    reclaim_bytes: u64,
) -> Result<LinuxLifecycleReceipt> {
    #[cfg(target_os = "linux")]
    {
        return linux::execute(runtime_id, operation, reclaim_bytes);
    }

    #[cfg(not(target_os = "linux"))]
    {
        let _ = (runtime_id, operation, reclaim_bytes);
        return Err(Error::SandboxUnavailable(
            "managed-scope lifecycle operations are Linux-only".to_owned(),
        ));
    }
}

/// Validate a logical runtime id accepted by the fixed identity-record lookup.
pub fn validate_lifecycle_runtime_id(runtime_id: &str) -> Result<()> {
    if runtime_id.is_empty() || runtime_id.len() > MAX_RUNTIME_ID_BYTES {
        return Err(Error::Cli(format!(
            "lifecycle runtime id must contain 1..={MAX_RUNTIME_ID_BYTES} bytes"
        )));
    }
    if !runtime_id.bytes().all(is_runtime_id_byte) {
        return Err(Error::Cli(
            "lifecycle runtime id may contain only ASCII alphanumeric, '.', '_', ':', or '-'"
                .to_owned(),
        ));
    }
    return Ok(());
}

fn is_runtime_id_byte(byte: u8) -> bool {
    return byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b':' | b'-');
}

fn is_scope_first_byte(byte: u8) -> bool {
    return byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b':' | b'@' | b'-');
}

fn is_scope_byte(byte: u8) -> bool {
    return is_scope_first_byte(byte) || byte == b'.';
}

fn validate_scope_identity(value: &str) -> Result<()> {
    if value.is_empty() || value.len() > MAX_SCOPE_IDENTITY_BYTES {
        return Err(Error::SandboxUnavailable(format!(
            "managed scope identity must contain 1..={MAX_SCOPE_IDENTITY_BYTES} bytes"
        )));
    }
    let bytes = value.as_bytes();
    if !is_scope_first_byte(bytes[0]) || !bytes.iter().copied().all(is_scope_byte) {
        return Err(Error::SandboxUnavailable(format!(
            "managed scope identity {value:?} violates the safe ASCII grammar"
        )));
    }
    return Ok(());
}

fn validate_cgroup_identity(cgroup_v2_path: &str) -> Result<()> {
    if cgroup_v2_path.len() < 2
        || cgroup_v2_path.len() > MAX_CGROUP_PATH_BYTES
        || !cgroup_v2_path.starts_with('/')
        || cgroup_v2_path.ends_with('/')
    {
        return Err(Error::SandboxUnavailable(format!(
            "unsafe managed cgroup-v2 path {cgroup_v2_path:?}"
        )));
    }

    for segment in cgroup_v2_path[1..].split('/') {
        let bytes = segment.as_bytes();
        if bytes.is_empty()
            || !is_scope_first_byte(bytes[0])
            || !bytes.iter().copied().all(is_scope_byte)
        {
            return Err(Error::SandboxUnavailable(format!(
                "managed cgroup-v2 path {cgroup_v2_path:?} violates the safe ASCII grammar"
            )));
        }
    }
    return Ok(());
}

#[cfg(target_os = "linux")]
mod linux {
    use std::fs::{self, Metadata, OpenOptions};
    use std::io::{Read, Write};
    use std::os::unix::fs::{MetadataExt, PermissionsExt};
    use std::path::{Path, PathBuf};
    use std::thread;
    use std::time::Duration;

    use sha2::{Digest, Sha256};

    use super::*;
    use crate::linux_process_identity::{LinuxProcessIdentity, attest_linux_process_identity};

    #[derive(Debug, Clone)]
    struct Snapshot {
        frozen: bool,
        memory_current: Option<u64>,
        memory_swap_current: Option<u64>,
        process_count: usize,
        freezer_supported: bool,
        reclaim_supported: bool,
    }

    pub(super) fn execute(
        runtime_id: &str,
        operation: LinuxLifecycleOperation,
        reclaim_bytes: u64,
    ) -> Result<LinuxLifecycleReceipt> {
        validate_lifecycle_runtime_id(runtime_id)?;
        require_root_helper()?;
        if reclaim_bytes == 0 {
            return Err(Error::Cli(
                "lifecycle reclaim byte request must be greater than zero".to_owned(),
            ));
        }

        let identity = load_trusted_identity(runtime_id)?;
        validate_identity(&identity, runtime_id)?;
        let before_identity = attest_linux_process_identity(identity.pid)?;
        verify_process_identity(&identity, &before_identity)?;

        let helper_identity = attest_linux_process_identity(std::process::id())?;
        if helper_identity.cgroup_v2_path == identity.cgroup_v2_path {
            return Err(Error::SandboxUnavailable(format!(
                "refusing lifecycle operation for runtime {:?}: trusted helper shares target cgroup {:?}",
                identity.runtime_id, identity.cgroup_v2_path
            )));
        }

        let cgroup_root = canonical_cgroup_root()?;
        let cgroup_path = resolve_managed_cgroup(&cgroup_root, &identity.cgroup_v2_path)?;
        let before = snapshot(&cgroup_path)?;

        let reclaim_outcome = match operation {
            LinuxLifecycleOperation::Status => ReclaimOutcome::NotRequested,
            LinuxLifecycleOperation::Freeze => {
                require_freezer(&before, &identity)?;
                set_frozen(&cgroup_path, true, before.frozen)?;
                ReclaimOutcome::NotRequested
            }
            LinuxLifecycleOperation::Thaw => {
                require_freezer(&before, &identity)?;
                set_frozen(&cgroup_path, false, before.frozen)?;
                ReclaimOutcome::NotRequested
            }
            LinuxLifecycleOperation::Reclaim => {
                require_freezer(&before, &identity)?;
                if !before.frozen {
                    return Err(Error::SandboxUnavailable(format!(
                        "refusing memory reclaim for runtime {:?}: managed scope is not frozen",
                        identity.runtime_id
                    )));
                }
                reclaim(&cgroup_path, reclaim_bytes, before.reclaim_supported)?
            }
        };

        let after = snapshot(&cgroup_path)?;
        validate_postcondition(operation, &identity, &after)?;

        let after_identity = attest_linux_process_identity(identity.pid)?;
        verify_process_identity(&identity, &after_identity)?;

        return Ok(build_receipt(
            operation,
            &identity,
            &before,
            &after,
            reclaim_outcome,
        ));
    }

    fn require_root_helper() -> Result<()> {
        let status = fs::read_to_string("/proc/self/status").map_err(|error| {
            Error::SandboxUnavailable(format!(
                "cannot read trusted lifecycle helper credentials: {error}"
            ))
        })?;
        let effective_uid = parse_effective_uid(&status).ok_or_else(|| {
            Error::SandboxUnavailable(
                "cannot parse trusted lifecycle helper effective uid".to_owned(),
            )
        })?;
        if effective_uid != 0 {
            return Err(Error::SandboxUnavailable(format!(
                "lifecycle operations require effective uid 0, got {effective_uid}"
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

    fn identity_path(runtime_id: &str) -> PathBuf {
        return Path::new(LIFECYCLE_IDENTITY_ROOT).join(format!("{runtime_id}.json"));
    }

    fn load_trusted_identity(runtime_id: &str) -> Result<LinuxManagedScopeIdentity> {
        let root = Path::new(LIFECYCLE_IDENTITY_ROOT);
        let root_metadata = fs::symlink_metadata(root).map_err(|error| {
            Error::SandboxUnavailable(format!(
                "trusted lifecycle identity root {} is unavailable: {error}",
                root.display()
            ))
        })?;
        validate_trusted_metadata(root, &root_metadata, true)?;

        let path = identity_path(runtime_id);
        let path_metadata = fs::symlink_metadata(&path).map_err(|error| {
            Error::SandboxUnavailable(format!(
                "trusted lifecycle identity record {} is unavailable: {error}",
                path.display()
            ))
        })?;
        validate_trusted_metadata(&path, &path_metadata, false)?;

        let mut file = OpenOptions::new().read(true).open(&path).map_err(|error| {
            Error::SandboxUnavailable(format!(
                "failed to open trusted lifecycle identity record {}: {error}",
                path.display()
            ))
        })?;
        let opened_metadata = file.metadata().map_err(|error| {
            Error::SandboxUnavailable(format!(
                "failed to stat opened lifecycle identity record {}: {error}",
                path.display()
            ))
        })?;
        validate_same_inode(&path, &path_metadata, &opened_metadata)?;
        validate_trusted_metadata(&path, &opened_metadata, false)?;

        let mut bytes = Vec::new();
        file.by_ref()
            .take(MAX_IDENTITY_RECORD_BYTES + 1)
            .read_to_end(&mut bytes)
            .map_err(|error| {
                Error::SandboxUnavailable(format!(
                    "failed to read trusted lifecycle identity record {}: {error}",
                    path.display()
                ))
            })?;
        if bytes.len() as u64 > MAX_IDENTITY_RECORD_BYTES {
            return Err(Error::SandboxUnavailable(format!(
                "trusted lifecycle identity record {} exceeds {MAX_IDENTITY_RECORD_BYTES} bytes",
                path.display()
            )));
        }

        let identity = serde_json::from_slice::<LinuxManagedScopeIdentity>(&bytes).map_err(|error| {
            Error::SandboxUnavailable(format!(
                "trusted lifecycle identity record {} is invalid JSON: {error}",
                path.display()
            ))
        })?;
        return Ok(identity);
    }

    fn validate_trusted_metadata(path: &Path, metadata: &Metadata, directory: bool) -> Result<()> {
        let expected_type = if directory {
            metadata.is_dir()
        } else {
            metadata.is_file()
        };
        if !expected_type {
            return Err(Error::SandboxUnavailable(format!(
                "trusted lifecycle path {} has an unsafe filesystem type",
                path.display()
            )));
        }
        if metadata.uid() != 0 {
            return Err(Error::SandboxUnavailable(format!(
                "trusted lifecycle path {} must be owned by uid 0",
                path.display()
            )));
        }
        let mode = metadata.permissions().mode();
        if mode & 0o022 != 0 {
            return Err(Error::SandboxUnavailable(format!(
                "trusted lifecycle path {} must not be group/world writable (mode {:o})",
                path.display(),
                mode & 0o7777
            )));
        }
        return Ok(());
    }

    fn validate_same_inode(path: &Path, before: &Metadata, opened: &Metadata) -> Result<()> {
        if before.dev() != opened.dev() || before.ino() != opened.ino() {
            return Err(Error::SandboxUnavailable(format!(
                "trusted lifecycle path {} changed identity while opening",
                path.display()
            )));
        }
        return Ok(());
    }

    fn validate_identity(
        identity: &LinuxManagedScopeIdentity,
        requested_runtime_id: &str,
    ) -> Result<()> {
        if identity.schema != IDENTITY_SCHEMA {
            return Err(Error::SandboxUnavailable(format!(
                "runtime {requested_runtime_id:?} identity schema {:?} is unsupported",
                identity.schema
            )));
        }
        if identity.runtime_id != requested_runtime_id {
            return Err(Error::SandboxUnavailable(format!(
                "runtime identity record mismatch: requested {requested_runtime_id:?}, record contains {:?}",
                identity.runtime_id
            )));
        }
        validate_lifecycle_runtime_id(&identity.runtime_id).map_err(|_error| {
            Error::SandboxUnavailable(format!(
                "runtime {:?} contains an invalid logical identity",
                identity.runtime_id
            ))
        })?;
        validate_scope_identity(&identity.scope_identity)?;
        if identity.pid == 0 || identity.process_start_ticks == 0 {
            return Err(Error::SandboxUnavailable(format!(
                "runtime {requested_runtime_id:?} has invalid process identity"
            )));
        }
        validate_cgroup_identity(&identity.cgroup_v2_path)?;
        return Ok(());
    }

    fn verify_process_identity(
        expected: &LinuxManagedScopeIdentity,
        actual: &LinuxProcessIdentity,
    ) -> Result<()> {
        if actual.pid != expected.pid
            || actual.process_start_ticks != expected.process_start_ticks
            || actual.cgroup_v2_path != expected.cgroup_v2_path
        {
            return Err(Error::SandboxUnavailable(format!(
                "managed runtime {:?} process identity changed or does not match trusted host record",
                expected.runtime_id
            )));
        }
        return Ok(());
    }

    fn canonical_cgroup_root() -> Result<PathBuf> {
        let root = fs::canonicalize(CGROUP_V2_ROOT).map_err(|error| {
            Error::SandboxUnavailable(format!(
                "cgroup-v2 root {CGROUP_V2_ROOT} is unavailable: {error}"
            ))
        })?;
        let controllers = root.join("cgroup.controllers");
        if !controllers.is_file() {
            return Err(Error::SandboxUnavailable(format!(
                "{} is not a unified cgroup-v2 hierarchy",
                root.display()
            )));
        }
        return Ok(root);
    }

    fn resolve_managed_cgroup(root: &Path, cgroup_v2_path: &str) -> Result<PathBuf> {
        validate_cgroup_identity(cgroup_v2_path)?;
        let candidate = root.join(cgroup_v2_path.trim_start_matches('/'));
        let canonical = fs::canonicalize(&candidate).map_err(|error| {
            Error::SandboxUnavailable(format!(
                "managed cgroup {} is unavailable: {error}",
                candidate.display()
            ))
        })?;
        if !canonical.starts_with(root) || canonical == root || canonical != candidate {
            return Err(Error::SandboxUnavailable(format!(
                "managed cgroup {cgroup_v2_path:?} escapes or aliases the cgroup-v2 root"
            )));
        }
        return Ok(canonical);
    }

    fn snapshot(cgroup_path: &Path) -> Result<Snapshot> {
        let freezer_supported = cgroup_path.join("cgroup.freeze").is_file();
        let reclaim_supported = cgroup_path.join("memory.reclaim").is_file();
        let frozen = if freezer_supported {
            read_frozen(cgroup_path)?
        } else {
            false
        };
        let process_count = count_processes(&cgroup_path.join("cgroup.procs"))?;
        return Ok(Snapshot {
            frozen,
            memory_current: read_optional_u64(&cgroup_path.join("memory.current"))?,
            memory_swap_current: read_optional_u64(&cgroup_path.join("memory.swap.current"))?,
            process_count,
            freezer_supported,
            reclaim_supported,
        });
    }

    fn require_freezer(snapshot: &Snapshot, identity: &LinuxManagedScopeIdentity) -> Result<()> {
        if !snapshot.freezer_supported {
            return Err(Error::SandboxUnavailable(format!(
                "runtime {:?} managed cgroup does not support cgroup-v2 freezer",
                identity.runtime_id
            )));
        }
        return Ok(());
    }

    fn set_frozen(cgroup_path: &Path, desired: bool, current: bool) -> Result<()> {
        if current == desired {
            return Ok(());
        }
        write_control(
            &cgroup_path.join("cgroup.freeze"),
            if desired { b"1" } else { b"0" },
        )?;
        for _attempt in 0..FREEZER_POLL_ATTEMPTS {
            if read_frozen(cgroup_path)? == desired {
                return Ok(());
            }
            thread::sleep(Duration::from_millis(FREEZER_POLL_INTERVAL_MS));
        }
        return Err(Error::SandboxUnavailable(format!(
            "cgroup freezer did not reach desired frozen={desired} for {}",
            cgroup_path.display()
        )));
    }

    fn reclaim(cgroup_path: &Path, reclaim_bytes: u64, supported: bool) -> Result<ReclaimOutcome> {
        if !supported {
            return Ok(ReclaimOutcome::Unsupported);
        }
        write_control(
            &cgroup_path.join("memory.reclaim"),
            reclaim_bytes.to_string().as_bytes(),
        )?;
        return Ok(ReclaimOutcome::BestEffortCompleted);
    }

    fn validate_postcondition(
        operation: LinuxLifecycleOperation,
        identity: &LinuxManagedScopeIdentity,
        after: &Snapshot,
    ) -> Result<()> {
        match operation {
            LinuxLifecycleOperation::Freeze if !after.frozen => {
                return Err(Error::SandboxUnavailable(format!(
                    "runtime {:?} freeze postcondition failed",
                    identity.runtime_id
                )));
            }
            LinuxLifecycleOperation::Thaw if after.frozen => {
                return Err(Error::SandboxUnavailable(format!(
                    "runtime {:?} thaw postcondition failed",
                    identity.runtime_id
                )));
            }
            LinuxLifecycleOperation::Reclaim if !after.frozen => {
                return Err(Error::SandboxUnavailable(format!(
                    "runtime {:?} thawed unexpectedly during reclaim",
                    identity.runtime_id
                )));
            }
            _ => {}
        }
        return Ok(());
    }

    fn read_frozen(cgroup_path: &Path) -> Result<bool> {
        let events_path = cgroup_path.join("cgroup.events");
        if events_path.is_file() {
            let events = fs::read_to_string(&events_path).map_err(|error| {
                Error::SandboxUnavailable(format!(
                    "failed to read {}: {error}",
                    events_path.display()
                ))
            })?;
            for line in events.lines() {
                let mut fields = line.split_ascii_whitespace();
                if fields.next() == Some("frozen") {
                    return match fields.next() {
                        Some("0") => Ok(false),
                        Some("1") => Ok(true),
                        _ => Err(Error::SandboxUnavailable(format!(
                            "{} contains an invalid frozen field",
                            events_path.display()
                        ))),
                    };
                }
            }
        }

        let freeze_path = cgroup_path.join("cgroup.freeze");
        let value = fs::read_to_string(&freeze_path).map_err(|error| {
            Error::SandboxUnavailable(format!(
                "failed to read {}: {error}",
                freeze_path.display()
            ))
        })?;
        return match value.trim() {
            "0" => Ok(false),
            "1" => Ok(true),
            _ => Err(Error::SandboxUnavailable(format!(
                "{} contains an invalid freezer value {:?}",
                freeze_path.display(),
                value.trim()
            ))),
        };
    }

    fn count_processes(path: &Path) -> Result<usize> {
        let value = fs::read_to_string(path).map_err(|error| {
            Error::SandboxUnavailable(format!("failed to read {}: {error}", path.display()))
        })?;
        let count = value.lines().filter(|line| !line.trim().is_empty()).count();
        if count > MAX_PROCESS_COUNT {
            return Err(Error::SandboxUnavailable(format!(
                "{} contains more than {MAX_PROCESS_COUNT} process ids",
                path.display()
            )));
        }
        return Ok(count);
    }

    fn read_optional_u64(path: &Path) -> Result<Option<u64>> {
        if !path.is_file() {
            return Ok(None);
        }
        let value = fs::read_to_string(path).map_err(|error| {
            Error::SandboxUnavailable(format!("failed to read {}: {error}", path.display()))
        })?;
        let parsed = value.trim().parse::<u64>().map_err(|error| {
            Error::SandboxUnavailable(format!(
                "{} contains an invalid unsigned integer: {error}",
                path.display()
            ))
        })?;
        return Ok(Some(parsed));
    }

    fn write_control(path: &Path, value: &[u8]) -> Result<()> {
        let path_metadata = fs::symlink_metadata(path).map_err(|error| {
            Error::SandboxUnavailable(format!(
                "lifecycle control {} is unavailable: {error}",
                path.display()
            ))
        })?;
        if !path_metadata.is_file() {
            return Err(Error::SandboxUnavailable(format!(
                "lifecycle control {} has an unsafe filesystem type",
                path.display()
            )));
        }

        let mut file = OpenOptions::new().write(true).open(path).map_err(|error| {
            Error::SandboxUnavailable(format!(
                "failed to open lifecycle control {}: {error}",
                path.display()
            ))
        })?;
        let opened_metadata = file.metadata().map_err(|error| {
            Error::SandboxUnavailable(format!(
                "failed to stat opened lifecycle control {}: {error}",
                path.display()
            ))
        })?;
        validate_same_inode(path, &path_metadata, &opened_metadata)?;
        file.write_all(value).map_err(|error| {
            Error::SandboxUnavailable(format!(
                "failed to write lifecycle control {}: {error}",
                path.display()
            ))
        })?;
        return Ok(());
    }

    fn build_receipt(
        operation: LinuxLifecycleOperation,
        identity: &LinuxManagedScopeIdentity,
        before: &Snapshot,
        after: &Snapshot,
        reclaim_outcome: ReclaimOutcome,
    ) -> LinuxLifecycleReceipt {
        let mut hasher = Sha256::new();
        hasher.update(identity.cgroup_v2_path.as_bytes());
        let cgroup_identity_sha256 = format!("{:x}", hasher.finalize());
        return LinuxLifecycleReceipt {
            schema: LIFECYCLE_RECEIPT_SCHEMA.to_owned(),
            operation,
            scope_identity: identity.scope_identity.clone(),
            cgroup_identity_sha256,
            process_start_identity: identity.process_start_ticks.to_string(),
            before_frozen: before.frozen,
            after_frozen: after.frozen,
            memory_current_before: before.memory_current,
            memory_current_after: after.memory_current,
            memory_swap_current_before: before.memory_swap_current,
            memory_swap_current_after: after.memory_swap_current,
            process_count: after.process_count,
            freezer_supported: after.freezer_supported,
            reclaim_supported: after.reclaim_supported,
            reclaim_outcome,
            ram_released_claimed: false,
        };
    }

    #[cfg(test)]
    mod tests {
        use std::io;

        use super::*;
        use tempfile::tempdir;

        fn identity() -> LinuxManagedScopeIdentity {
            return LinuxManagedScopeIdentity {
                schema: IDENTITY_SCHEMA.to_owned(),
                runtime_id: "runtime-7".to_owned(),
                scope_identity: "runtime-7.scope".to_owned(),
                pid: 4242,
                process_start_ticks: 998877,
                cgroup_v2_path: "/beamscale-workloads.slice/runtime-7.scope".to_owned(),
            };
        }

        fn write(path: &Path, value: &str) -> io::Result<()> {
            return fs::write(path, value);
        }

        #[test]
        fn effective_uid_parser_reads_effective_not_real_uid() {
            assert_eq!(parse_effective_uid("Uid:\t1000\t0\t1000\t1000\n"), Some(0));
            assert_eq!(parse_effective_uid("Uid:\t0\t1000\t0\t0\n"), Some(1000));
            assert_eq!(parse_effective_uid("Name:\thelper\n"), None);
        }

        #[test]
        fn cgroup_path_grammar_rejects_traversal_aliases_and_non_ascii() {
            for value in [
                "/",
                "relative",
                "/safe/../escape",
                "/safe/./alias",
                "/safe/.hidden",
                "/safe//double",
                "/safe/trailing/",
                "/safe/µ",
            ] {
                assert!(validate_cgroup_identity(value).is_err(), "{value}");
            }
            assert!(validate_cgroup_identity("/safe.slice/runtime-7.scope").is_ok());
        }

        #[test]
        fn scope_identity_uses_the_same_safe_segment_grammar() {
            assert!(validate_scope_identity("runtime-7.scope").is_ok());
            for value in [".hidden", "../escape", "bad/scope", "bad scope", "µ.scope"] {
                assert!(validate_scope_identity(value).is_err(), "{value}");
            }
        }

        #[test]
        fn verifies_exact_pid_start_and_cgroup_tuple() {
            let expected = identity();
            let exact = LinuxProcessIdentity {
                pid: expected.pid,
                process_start_ticks: expected.process_start_ticks,
                cgroup_v2_path: expected.cgroup_v2_path.clone(),
            };
            assert!(verify_process_identity(&expected, &exact).is_ok());
            assert!(
                verify_process_identity(
                    &expected,
                    &LinuxProcessIdentity {
                        process_start_ticks: exact.process_start_ticks + 1,
                        ..exact.clone()
                    }
                )
                .is_err()
            );
            assert!(
                verify_process_identity(
                    &expected,
                    &LinuxProcessIdentity {
                        cgroup_v2_path: "/other.slice/runtime-7.scope".to_owned(),
                        ..exact
                    }
                )
                .is_err()
            );
        }

        #[test]
        fn snapshot_reports_freezer_memory_swap_and_process_count() -> io::Result<()> {
            let temp = tempdir()?;
            write(&temp.path().join("cgroup.freeze"), "1\n")?;
            write(&temp.path().join("cgroup.events"), "populated 1\nfrozen 1\n")?;
            write(&temp.path().join("cgroup.procs"), "10\n11\n")?;
            write(&temp.path().join("memory.current"), "4096\n")?;
            write(&temp.path().join("memory.swap.current"), "1024\n")?;
            write(&temp.path().join("memory.reclaim"), "")?;

            let observed = snapshot(temp.path()).expect("snapshot");
            assert!(observed.frozen);
            assert!(observed.freezer_supported);
            assert!(observed.reclaim_supported);
            assert_eq!(observed.process_count, 2);
            assert_eq!(observed.memory_current, Some(4096));
            assert_eq!(observed.memory_swap_current, Some(1024));
            return Ok(());
        }

        #[test]
        fn idempotent_freeze_and_thaw_do_not_require_state_transition() -> io::Result<()> {
            let frozen = tempdir()?;
            write(&frozen.path().join("cgroup.freeze"), "1\n")?;
            write(&frozen.path().join("cgroup.events"), "frozen 1\n")?;
            set_frozen(frozen.path(), true, true).expect("idempotent freeze");

            let thawed = tempdir()?;
            write(&thawed.path().join("cgroup.freeze"), "0\n")?;
            write(&thawed.path().join("cgroup.events"), "frozen 0\n")?;
            set_frozen(thawed.path(), false, false).expect("idempotent thaw");
            return Ok(());
        }

        #[test]
        fn reclaim_never_claims_durable_ram_release() -> io::Result<()> {
            let temp = tempdir()?;
            write(&temp.path().join("cgroup.freeze"), "1\n")?;
            write(&temp.path().join("cgroup.events"), "frozen 1\n")?;
            write(&temp.path().join("cgroup.procs"), "10\n")?;
            write(&temp.path().join("memory.current"), "8192\n")?;
            write(&temp.path().join("memory.swap.current"), "0\n")?;
            write(&temp.path().join("memory.reclaim"), "")?;

            let before = snapshot(temp.path()).expect("snapshot");
            let outcome = reclaim(temp.path(), 4096, before.reclaim_supported).expect("reclaim");
            let after = snapshot(temp.path()).expect("snapshot");
            let receipt = build_receipt(
                LinuxLifecycleOperation::Reclaim,
                &identity(),
                &before,
                &after,
                outcome,
            );
            assert_eq!(receipt.reclaim_outcome, ReclaimOutcome::BestEffortCompleted);
            assert!(!receipt.ram_released_claimed);
            assert_eq!(
                fs::read_to_string(temp.path().join("memory.reclaim"))?,
                "4096"
            );
            return Ok(());
        }

        #[test]
        fn unsupported_reclaim_is_distinct_from_success() {
            let outcome = reclaim(Path::new("/does/not/matter"), 4096, false)
                .expect("unsupported reclaim should not write");
            assert_eq!(outcome, ReclaimOutcome::Unsupported);
        }

        #[test]
        fn identity_mismatch_and_unsafe_scope_fail_closed() {
            let mut value = identity();
            assert!(validate_identity(&value, "runtime-7").is_ok());
            assert!(validate_identity(&value, "runtime-8").is_err());
            value.scope_identity = ".hidden".to_owned();
            assert!(validate_identity(&value, "runtime-7").is_err());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lifecycle_runtime_id_is_a_single_safe_segment() {
        for good in ["runtime-7", "tenant_a:runtime.9", "A1"] {
            assert!(validate_lifecycle_runtime_id(good).is_ok(), "{good}");
        }
        for bad in ["", "../escape", "a/b", "white space", "line\nbreak", "µ"] {
            assert!(validate_lifecycle_runtime_id(bad).is_err(), "{bad}");
        }
    }
}
