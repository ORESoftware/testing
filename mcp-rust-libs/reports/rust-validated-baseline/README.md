# Validated Rust baseline

This directory preserves the exact dependency lockfile from the Rust-only incubator head
`f951d9f559ccbbdc8539eccf6f9ba24a32f3d95d`, validated by GitHub Actions run
`30595725450`.

It is evidence for the semantic merge, **not** the active lockfile for the expanded
polyglot workspace. The active `packages/rust/Cargo.lock` must be generated and reviewed
against the eight-crate workspace because blindly copying the six-crate lock would hide
new package and dependency resolution.
