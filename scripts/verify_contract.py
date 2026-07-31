#!/usr/bin/env python3
"""Fast offline checks for protocol/documentation drift.

This complements Rust tests and deliberately uses only the Python standard library.
"""

from __future__ import annotations

import json
import re
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODEL = (ROOT / "src/model.rs").read_text()
OPENAPI = json.loads((ROOT / "docs/openapi.json").read_text())
CARGO = tomllib.loads((ROOT / "Cargo.toml").read_text())

match = re.search(r"pub const EVENT_KINDS: &\[&str\] = &\[(.*?)\];", MODEL, re.S)
if not match:
    raise SystemExit("could not locate EVENT_KINDS in src/model.rs")
RUST_KINDS = re.findall(r'"([a-z_]+)"', match.group(1))
OPENAPI_KINDS = OPENAPI["x-meta-agent-event-kinds"]
udp_match = re.search(r"pub const UDP_EVENT_KINDS: &\[&str\] = &\[(.*?)\];", MODEL, re.S)
if not udp_match:
    raise SystemExit("could not locate UDP_EVENT_KINDS in src/model.rs")
RUST_UDP_KINDS = re.findall(r'"([a-z_]+)"', udp_match.group(1))
OPENAPI_UDP_KINDS = OPENAPI["x-meta-agent-udp-event-kinds"]

errors: list[str] = []
if RUST_KINDS != OPENAPI_KINDS:
    errors.append(f"event-kind drift: Rust={RUST_KINDS!r}, OpenAPI={OPENAPI_KINDS!r}")
if RUST_UDP_KINDS != OPENAPI_UDP_KINDS:
    errors.append(
        f"UDP policy drift: Rust={RUST_UDP_KINDS!r}, OpenAPI={OPENAPI_UDP_KINDS!r}"
    )

required_paths = {
    "/api/v1/events",
    "/api/v1/snapshot",
    "/healthz",
    "/readyz",
    "/metrics",
    "/ws/agent",
    "/ws/ui",
}
missing_paths = required_paths - set(OPENAPI["paths"])
if missing_paths:
    errors.append(f"OpenAPI is missing paths: {sorted(missing_paths)!r}")

if CARGO["package"]["name"] != "meta-agent-control-plane":
    errors.append("Cargo package name changed without updating contract tooling")

for fixture in sorted((ROOT / "fixtures").glob("*.json")):
    try:
        json.loads(fixture.read_text())
    except json.JSONDecodeError as error:
        errors.append(f"{fixture.relative_to(ROOT)} is not valid JSON: {error}")

script = (ROOT / "scripts/dashboard.js").read_text().strip()
ui = (ROOT / "src/ui.rs").read_text()
if script not in ui:
    errors.append("scripts/dashboard.js drifted from the inline Leptos dashboard script")

conflict_pattern = re.compile(r"^(<<<<<<<|=======|>>>>>>>)", re.M)
for path in ROOT.rglob("*"):
    if path.is_file() and ".git" not in path.parts and path.suffix not in {".png", ".jpg", ".jpeg"}:
        try:
            text = path.read_text()
        except UnicodeDecodeError:
            continue
        if conflict_pattern.search(text):
            errors.append(f"unresolved conflict marker in {path.relative_to(ROOT)}")

if errors:
    for error in errors:
        print(f"error: {error}", file=sys.stderr)
    raise SystemExit(1)

print(
    f"contract OK: {len(RUST_KINDS)} event kinds, "
    f"{len(RUST_UDP_KINDS)} UDP kinds, {len(required_paths)} required paths"
)
