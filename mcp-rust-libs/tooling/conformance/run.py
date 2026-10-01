#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from typing import Any

sys.dont_write_bytecode = True

HERE = Path(__file__).resolve()
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE.parent))
from portable_validator import validate  # noqa: E402


def canonical(value: object) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def load_expectations() -> dict[str, dict[str, Any]]:
    path = ROOT / "contracts/examples/expectations.json"
    document = json.loads(path.read_text(encoding="utf-8"))
    if document.get("format") != "ore-mcp-fixture-expectations/v1":
        raise SystemExit("unsupported fixture-expectations format")
    fixtures = document.get("fixtures")
    if not isinstance(fixtures, dict):
        raise SystemExit("fixture expectations must contain an object named fixtures")
    return fixtures


def main() -> int:
    lock = json.loads((ROOT / "contracts/schema-lock.json").read_text(encoding="utf-8"))
    schema_entry = lock["schemas"][0]
    schema_path = ROOT / schema_entry["path"]
    schema_bytes = schema_path.read_bytes()
    digest = hashlib.sha256(schema_bytes).hexdigest()
    if digest != schema_entry["sha256"]:
        raise SystemExit("schema digest does not match schema-lock.json")
    schema = json.loads(schema_bytes)
    expectations = load_expectations()

    records: list[dict[str, object]] = []
    failures: list[str] = []
    seen: set[str] = set()
    for directory in ("valid", "invalid"):
        root = ROOT / "contracts/examples" / directory / "result-envelope"
        for fixture in sorted(root.glob("*.json")):
            fixture_id = f"{directory}/result-envelope/{fixture.name}"
            seen.add(fixture_id)
            expected = expectations.get(fixture_id)
            if expected is None:
                failures.append(f"{fixture_id}: missing fixture expectation")
                continue

            value = json.loads(fixture.read_text(encoding="utf-8"))
            errors = validate(value, schema)
            accepted = not errors
            actual_error_code = None if accepted else errors[0].code
            actual_error_path = None if accepted else errors[0].path
            expected_accept = expected.get("accepted")
            expected_error_code = expected.get("error_code")
            expected_error_path = expected.get("error_path")

            records.append(
                {
                    "fixture": fixture_id,
                    "expected_accept": expected_accept,
                    "accepted": accepted,
                    "expected_error_code": expected_error_code,
                    "error_code": actual_error_code,
                    "expected_error_path": expected_error_path,
                    "error_path": actual_error_path,
                    "canonical_sha256": hashlib.sha256(canonical(value).encode("utf-8")).hexdigest(),
                }
            )
            if not isinstance(expected_accept, bool):
                failures.append(f"{fixture_id}: expected accepted must be a boolean")
            elif accepted != expected_accept:
                failures.append(
                    f"{fixture_id}: expected accepted={expected_accept}, got {accepted}"
                )
            elif not accepted:
                if actual_error_code != expected_error_code:
                    failures.append(
                        f"{fixture_id}: expected error_code={expected_error_code!r}, "
                        f"got {actual_error_code!r}"
                    )
                if actual_error_path != expected_error_path:
                    failures.append(
                        f"{fixture_id}: expected error_path={expected_error_path!r}, "
                        f"got {actual_error_path!r}"
                    )

    stale = sorted(set(expectations) - seen)
    failures.extend(f"{fixture_id}: expectation has no fixture" for fixture_id in stale)

    generated = [
        "packages/rust/crates/ore-mcp-contracts/src/generated/result_envelope.rs",
        "packages/typescript/src/generated/result-envelope.ts",
        "packages/dart/lib/src/generated/result_envelope.dart",
        "packages/gleam/src/ore_mcp_contracts/generated/result_envelope.gleam",
    ]
    for relative in generated:
        text = (ROOT / relative).read_text(encoding="utf-8")
        if digest not in text:
            failures.append(f"{relative}: missing schema digest")

    report = {
        "format": "ore-mcp-contract-parity/v1",
        "scope": "scaffold-portable-validator",
        "schema_id": schema["$id"],
        "schema_version": schema["x-ore-contract-version"],
        "schema_sha256": digest,
        "generator_version": lock["generator"]["version"],
        "fixtures": records,
        "native_validators_executed": [],
        "native_validation_status": "not-run-in-this-environment",
        "failures": failures,
    }
    report_path = ROOT / "reports/scaffold-conformance.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"checked {len(records)} fixtures; report={report_path}")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
