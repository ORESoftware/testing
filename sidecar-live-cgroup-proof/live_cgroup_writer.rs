//! Stateful writer fixture used to prove a real process stops and resumes.

#![forbid(unsafe_code)]

use std::env;
use std::fs::OpenOptions;
use std::io::Write;
use std::path::PathBuf;
use std::thread;
use std::time::Duration;

fn main() -> Result<(), String> {
    let path = env::args_os()
        .nth(1)
        .map(PathBuf::from)
        .ok_or_else(|| "usage: live_cgroup_writer <counter-path>".to_owned())?;
    let mut file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .map_err(|error| format!("open counter failed: {error}"))?;

    loop {
        file.write_all(b"x\n")
            .map_err(|error| format!("write counter failed: {error}"))?;
        file.flush()
            .map_err(|error| format!("flush counter failed: {error}"))?;
        thread::sleep(Duration::from_millis(20));
    }
}
