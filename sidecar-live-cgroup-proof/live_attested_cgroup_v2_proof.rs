//! Live kernel proof for the identity-attested freeze-only lifecycle effect.

#![forbid(unsafe_code)]

use std::env;
use std::fs;
use std::path::PathBuf;
use std::thread;
use std::time::Duration;

use ores_otel_sidecar::adapters::process_lifecycle_attested_freeze::AttestedFreezeOnlyCgroupEffects;
use ores_otel_sidecar::adapters::process_lifecycle_freeze::{
    FreezeTransitionEffects, FreezeTransitionStatus,
};
use ores_otel_sidecar::adapters::process_lifecycle_identity::ExpectedLinuxProcessIdentity;
use ores_otel_sidecar::process_lifecycle_agent::LifecycleEffects;
use ores_otel_sidecar::process_lifecycle_record::LifecycleCheckpoint;

const FREEZE_OBSERVATION: Duration = Duration::from_millis(400);
const THAW_OBSERVATION: Duration = Duration::from_millis(250);

fn required_path(name: &str) -> Result<PathBuf, String> {
    let value = env::var_os(name).ok_or_else(|| format!("missing {name}"))?;
    let path = PathBuf::from(value);
    if !path.is_absolute() {
        return Err(format!("{name} must be absolute"));
    }
    return Ok(path);
}

fn required_u32(name: &str) -> Result<u32, String> {
    return env::var(name)
        .map_err(|_| format!("missing {name}"))?
        .parse::<u32>()
        .map_err(|_| format!("invalid {name}"));
}

fn required_u64(name: &str) -> Result<u64, String> {
    return env::var(name)
        .map_err(|_| format!("missing {name}"))?
        .parse::<u64>()
        .map_err(|_| format!("invalid {name}"));
}

fn counter_size(path: &PathBuf) -> Result<u64, String> {
    return fs::metadata(path)
        .map(|metadata| metadata.len())
        .map_err(|error| format!("counter metadata failed: {error}"));
}

fn require(condition: bool, message: &str) -> Result<(), String> {
    if !condition {
        return Err(message.to_owned());
    }
    return Ok(());
}

fn main() -> Result<(), String> {
    let cgroup = required_path("ORES_LIVE_CGROUP")?;
    let counter = required_path("ORES_LIVE_COUNTER")?;
    let identity = ExpectedLinuxProcessIdentity {
        pid: required_u32("ORES_LIVE_PID")?,
        process_start_ticks: required_u64("ORES_LIVE_START_TICKS")?,
        managed_cgroup: cgroup,
    };
    let mut effects = AttestedFreezeOnlyCgroupEffects::new(identity);

    let before = FreezeTransitionEffects::freeze_status(&mut effects)?;
    require(
        before == FreezeTransitionStatus {
            populated: true,
            frozen: false,
        },
        "live proof target did not start populated and thawed",
    )?;

    let checkpoint = LifecycleCheckpoint {
        artifact_ref: "test://forbidden".to_owned(),
        digest: "sha256:forbidden".to_owned(),
        format: "forbidden".to_owned(),
    };
    require(
        LifecycleEffects::checkpoint_and_terminate(&mut effects).is_err(),
        "freeze-only adapter unexpectedly exposed checkpoint authority",
    )?;
    require(
        LifecycleEffects::restore(&mut effects, &checkpoint).is_err(),
        "freeze-only adapter unexpectedly exposed restore authority",
    )?;

    LifecycleEffects::freeze(&mut effects)?;
    let frozen = FreezeTransitionEffects::freeze_status(&mut effects)?;
    require(frozen.populated, "target became unpopulated while freezing")?;
    require(frozen.frozen, "kernel did not confirm frozen=1")?;

    let frozen_counter_before = counter_size(&counter)?;
    thread::sleep(FREEZE_OBSERVATION);
    let frozen_counter_after = counter_size(&counter)?;
    require(
        frozen_counter_after == frozen_counter_before,
        "target process counter advanced after kernel-confirmed freeze",
    )?;

    LifecycleEffects::thaw(&mut effects)?;
    let thawed = FreezeTransitionEffects::freeze_status(&mut effects)?;
    require(thawed.populated, "target became unpopulated while thawing")?;
    require(!thawed.frozen, "kernel did not confirm frozen=0")?;

    thread::sleep(THAW_OBSERVATION);
    let thawed_counter = counter_size(&counter)?;
    require(
        thawed_counter > frozen_counter_after,
        "target process did not resume observable work after thaw",
    )?;

    println!(
        "live_attested_cgroup_v2_proof=passed pid={} frozen_counter={} thawed_counter={}",
        required_u32("ORES_LIVE_PID")?,
        frozen_counter_after,
        thawed_counter,
    );
    return Ok(());
}
