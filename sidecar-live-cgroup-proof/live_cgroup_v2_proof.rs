#![forbid(unsafe_code)]

use std::env;
use std::fs;
use std::path::PathBuf;
use std::thread;
use std::time::Duration;

use ores_otel_sidecar::adapters::linux_process_lifecycle::CgroupV2Controller;
use ores_otel_sidecar::adapters::process_lifecycle_freeze::FreezeOnlyCgroupEffects;
use ores_otel_sidecar::process_lifecycle_agent::LifecycleEffects;

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
    let controller = CgroupV2Controller::new(&cgroup);
    let before = controller.status().map_err(|error| error.to_string())?;
    require(before.populated, "live proof target cgroup is not populated")?;
    require(!before.frozen, "live proof target unexpectedly starts frozen")?;

    let mut effects = FreezeOnlyCgroupEffects::new(controller.clone());
    LifecycleEffects::freeze(&mut effects)?;
    let frozen = controller.status().map_err(|error| error.to_string())?;
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
    let thawed = controller.status().map_err(|error| error.to_string())?;
    require(thawed.populated, "target became unpopulated while thawing")?;
    require(!thawed.frozen, "kernel did not confirm frozen=0")?;

    thread::sleep(THAW_OBSERVATION);
    let thawed_counter = counter_size(&counter)?;
    require(
        thawed_counter > frozen_counter_after,
        "target process did not resume observable work after thaw",
    )?;

    println!(
        "live_cgroup_v2_proof=passed before_frozen={} frozen={} thawed={} frozen_counter={} thawed_counter={}",
        before.frozen,
        frozen.frozen,
        thawed.frozen,
        frozen_counter_after,
        thawed_counter,
    );
    return Ok(());
}
