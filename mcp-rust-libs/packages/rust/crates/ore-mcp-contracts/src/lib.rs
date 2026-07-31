#![forbid(unsafe_code)]
#![doc = "Generated and hand-written contract glue. Do not hand-edit generated modules."]

pub mod generated;

pub use generated::result_envelope::{
    ContractValidationError, Failure, Metadata, ResultEnvelope, SafeError, Success,
    parse_result_envelope, validate_result_envelope_value,
};

pub const SCHEMA_DIGEST: &str = "2f28d646fa7ada0b3d2edd3c5ee19d663543e11117bbb37f4609307c6a9ff61e";
