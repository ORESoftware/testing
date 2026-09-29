fn main() {
    match proc_isolation_lifecycle_proof::lifecycle_host_guard::require_lifecycle_host_context() {
        Ok(()) => {
            println!("host lifecycle context accepted");
        }
        Err(error) => {
            eprintln!("host lifecycle context rejected: {error}");
            std::process::exit(125);
        }
    }
}
