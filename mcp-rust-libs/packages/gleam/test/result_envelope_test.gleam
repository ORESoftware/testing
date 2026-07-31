import gleam/json
import gleam/string
import gleeunit
import gleeunit/should
import ore_mcp_contracts/generated/result_envelope

pub fn main() {
  gleeunit.main()
}

pub fn valid_failure_test() {
  result_envelope.Failure(
    result_envelope.FailureEnvelope(
      result_envelope.SafeError("upstream.timeout", "timed out", [], True),
      None,
    ),
  )
  |> result_envelope.validate
  |> should.be_ok
}

pub fn unicode_code_point_boundary_test() {
  let accepted = result_envelope.Failure(
    result_envelope.FailureEnvelope(
      result_envelope.SafeError(
        "unicode.boundary",
        string.repeat("🙂", times: 2048),
        [string.repeat("🙂", times: 128)],
        False,
      ),
      None,
    ),
  )
  accepted
  |> result_envelope.validate
  |> should.be_ok

  result_envelope.Failure(
    result_envelope.FailureEnvelope(
      result_envelope.SafeError(
        "unicode.overflow",
        string.repeat("🙂", times: 2049),
        [],
        False,
      ),
      None,
    ),
  )
  |> result_envelope.validate
  |> should.equal(Error(result_envelope.ContractError(
    "max_length",
    "$/error/message",
  )))
}

pub fn indexed_path_error_test() {
  result_envelope.Failure(
    result_envelope.FailureEnvelope(
      result_envelope.SafeError("bad.path", "empty component", [""], False),
      None,
    ),
  )
  |> result_envelope.validate
  |> should.equal(Error(result_envelope.ContractError(
    "min_length",
    "$/error/path/0",
  )))
}

pub fn metadata_bound_error_test() {
  result_envelope.Success(
    result_envelope.SuccessEnvelope(
      Nil,
      Some(result_envelope.Metadata(None, False, 2_147_483_648)),
    ),
  )
  |> result_envelope.validate
  |> should.equal(Error(result_envelope.ContractError(
    "maximum",
    "$/meta/omitted_bytes",
  )))
}

pub fn encodes_failure_test() {
  result_envelope.Failure(
    result_envelope.FailureEnvelope(
      result_envelope.SafeError("upstream.timeout", "timed out", [], True),
      None,
    ),
  )
  |> result_envelope.to_json(fn(_) { json.null() })
  |> json.to_string
  |> should.equal("{\"ok\":false,\"error\":{\"code\":\"upstream.timeout\",\"message\":\"timed out\",\"path\":[],\"retryable\":true}}")
}
