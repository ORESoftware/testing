#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "reports/source-files.sha256"
IGNORED_PARTS = {
    ".git",
    ".dart_tool",
    ".gleam",
    ".gradle",
    ".npm",
    ".pnpm-store",
    "__pycache__",
    "_build",
    "build",
    "coverage",
    "deps",
    "node_modules",
    "target",
}
IGNORED_FILES = {MANIFEST.resolve()}


def included(path: Path) -> bool:
    if not path.is_file() or path.resolve() in IGNORED_FILES:
        return False
    relative = path.relative_to(ROOT)
    if any(part in IGNORED_PARTS for part in relative.parts):
        return False
    return not relative.name.endswith((".pyc", ".pyo"))


def render() -> str:
    lines: list[str] = []
    for path in sorted((path for path in ROOT.rglob("*") if included(path)), key=lambda item: item.as_posix()):
        relative = path.relative_to(ROOT).as_posix()
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        lines.append(f"{digest}  {relative}")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    args = parser.parse_args()

    expected = render()
    if args.write:
        MANIFEST.write_text(expected, encoding="utf-8")
        print(f"wrote {MANIFEST.relative_to(ROOT)} with {expected.count(chr(10))} entries")
        return 0

    actual = MANIFEST.read_text(encoding="utf-8") if MANIFEST.is_file() else ""
    if actual != expected:
        print("error: source manifest is stale; run scripts/update-source-manifest.py --write", file=sys.stderr)
        return 1
    print(f"source manifest verified with {expected.count(chr(10))} entries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
