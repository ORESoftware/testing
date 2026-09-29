//! Exact-source compile and unit-test carrier for proc-isolation lifecycle v1.

#![forbid(unsafe_code)]
#![allow(clippy::needless_return)]

pub mod error;
pub mod flags;
pub mod lifecycle_host_guard;
pub mod linux_lifecycle;
pub mod linux_process_identity;
