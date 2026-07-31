#!/bin/sh
set -eu

root=${1:-.}
cd "$root"

test -f Cargo.toml
test -f Cargo.lock
test -f README.md
test -f crates/ore-mcp-runtime/Cargo.toml
test -f crates/ore-mcp-testkit/Cargo.toml
cargo test --locked --workspace --all-features
