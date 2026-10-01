import ore_mcp_contracts/generated/result_envelope as generated

pub fn validate(
  value: generated.ResultEnvelope(data),
) -> Result(generated.ResultEnvelope(data), generated.ContractError) {
  generated.validate(value)
}
