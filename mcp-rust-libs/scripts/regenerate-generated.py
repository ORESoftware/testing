#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def safe_path(relative: str) -> Path:
    path = Path(relative)
    if path.is_absolute() or ".." in path.parts:
        raise SystemExit(f"unsafe generator path: {relative}")
    resolved = (ROOT / path).resolve()
    if not resolved.is_relative_to(ROOT.resolve()):
        raise SystemExit(f"generator path escapes repository: {relative}")
    return resolved


def render() -> dict[Path, bytes]:
    lock = json.loads((ROOT / "contracts/schema-lock.json").read_text(encoding="utf-8"))
    plan = json.loads((ROOT / "tooling/schema-codegen/generator-map.json").read_text(encoding="utf-8"))
    if plan.get("format") != "ore-mcp-generator-map/v1":
        raise SystemExit("unsupported generator-map format")
    schemas = {entry["path"]: entry for entry in lock.get("schemas", [])}
    schema_path = plan.get("schema_path")
    if schema_path not in schemas:
        raise SystemExit("generator-map schema is not present in schema-lock.json")
    schema_entry = schemas[schema_path]
    schema_file = safe_path(schema_path)
    schema_bytes = schema_file.read_bytes()
    digest = hashlib.sha256(schema_bytes).hexdigest()
    if digest != schema_entry["sha256"]:
        raise SystemExit("schema digest does not match schema-lock.json")
    schema = json.loads(schema_bytes)
    replacements = {
        "{{GENERATOR_VERSION}}": lock["generator"]["version"],
        "{{SCHEMA_ID}}": schema["$id"],
        "{{SCHEMA_SHA256}}": digest,
    }
    rendered: dict[Path, bytes] = {}
    for item in plan.get("outputs", []):
        template_path = safe_path(item["template"])
        output_path = safe_path(item["output"])
        text = template_path.read_text(encoding="utf-8")
        for key, value in replacements.items():
            text = text.replace(key, value)
        if re.search(r"\{\{[A-Z][A-Z0-9_]*\}\}", text):
            raise SystemExit(f"unresolved template placeholder: {item['template']}")
        rendered[output_path] = text.encode("utf-8")
    return rendered


def write_atomically(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def main() -> int:
    parser = argparse.ArgumentParser(description="Deterministically render registered MCP contract targets")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true", help="replace generated outputs atomically")
    mode.add_argument("--check", action="store_true", help="fail if generated outputs differ (default)")
    args = parser.parse_args()

    rendered_outputs = render()
    failures: list[str] = []
    for path, expected in rendered_outputs.items():
        if args.write:
            write_atomically(path, expected)
        elif not path.is_file() or path.read_bytes() != expected:
            failures.append(str(path.relative_to(ROOT)))
    if failures:
        raise SystemExit("generated output drift: " + ", ".join(failures))
    print(f"generated outputs {'written' if args.write else 'match'}: {len(rendered_outputs)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
