#![forbid(unsafe_code)]

use std::env;
use std::fs;
use std::os::unix::net::UnixListener;
use std::path::Path;

fn main() {
    if let Err(error) = run() {
        eprintln!("lifecycle DAC proof failed: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let arguments = env::args().collect::<Vec<_>>();
    if arguments.len() != 3 {
        return Err("usage: lifecycle-dac <bind|remove> <path>".to_owned());
    }

    let operation = arguments[1].as_str();
    let path = Path::new(&arguments[2]);
    match operation {
        "bind" => {
            let listener = UnixListener::bind(path)
                .map_err(|error| format!("bind {}: {error}", path.display()))?;
            drop(listener);
            return Ok(());
        }
        "remove" => {
            fs::remove_file(path)
                .map_err(|error| format!("remove {}: {error}", path.display()))?;
            return Ok(());
        }
        _ => {
            return Err(format!("unsupported operation: {operation}"));
        }
    }
}
