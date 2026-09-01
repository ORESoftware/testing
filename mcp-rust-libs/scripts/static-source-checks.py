#!/usr/bin/env python3
from __future__ import annotations

import ast
import json
import re
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PAIRS = {"(": ")", "[": "]", "{": "}"}
CLOSING = {value: key for key, value in PAIRS.items()}


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def raw_string_start(text: str, index: int) -> tuple[int, str] | None:
    for prefix in ("br", "rb", "r"):
        if not text.startswith(prefix, index):
            continue
        cursor = index + len(prefix)
        hashes = 0
        while cursor < len(text) and text[cursor] == "#":
            hashes += 1
            cursor += 1
        if cursor < len(text) and text[cursor] == '"':
            return cursor + 1, '"' + ("#" * hashes)
    return None


def rust_delimiters(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    stack: list[tuple[str, int]] = []
    index = 0
    block_depth = 0
    while index < len(text):
        if block_depth:
            if text.startswith("/*", index):
                block_depth += 1
                index += 2
            elif text.startswith("*/", index):
                block_depth -= 1
                index += 2
            else:
                index += 1
            continue

        if text.startswith("//", index):
            newline = text.find("\n", index + 2)
            index = len(text) if newline < 0 else newline + 1
            continue
        if text.startswith("/*", index):
            block_depth = 1
            index += 2
            continue

        raw = raw_string_start(text, index)
        if raw is not None:
            content_start, terminator = raw
            end = text.find(terminator, content_start)
            if end < 0:
                fail(f"unterminated raw string in {path.relative_to(ROOT)}")
            index = end + len(terminator)
            continue

        character = text[index]
        if character == '"':
            index += 1
            while index < len(text):
                if text[index] == "\\":
                    index += 2
                elif text[index] == '"':
                    index += 1
                    break
                else:
                    index += 1
            else:
                fail(f"unterminated string in {path.relative_to(ROOT)}")
            continue

        if character == "'":
            # A Rust lifetime has no nearby closing quote. Only skip a compact
            # char literal; delimiters inside lifetimes are not possible.
            cursor = index + 1
            if cursor < len(text) and text[cursor] == "\\":
                cursor += 2
            else:
                cursor += 1
            if cursor < len(text) and text[cursor] == "'":
                index = cursor + 1
                continue

        if character in PAIRS:
            stack.append((character, index))
        elif character in CLOSING:
            if not stack or stack[-1][0] != CLOSING[character]:
                fail(f"unmatched {character!r} in {path.relative_to(ROOT)} at byte {index}")
            stack.pop()
        index += 1

    if block_depth:
        fail(f"unterminated block comment in {path.relative_to(ROOT)}")
    if stack:
        opening, position = stack[-1]
        fail(f"unclosed {opening!r} in {path.relative_to(ROOT)} at byte {position}")


def main() -> int:
    for path in ROOT.rglob("*.py"):
        if any(part == "__pycache__" for part in path.parts):
            continue
        ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    for path in ROOT.rglob("*.json"):
        json.loads(path.read_text(encoding="utf-8"))
    for path in ROOT.rglob("*.toml"):
        tomllib.loads(path.read_text(encoding="utf-8"))
    for path in ROOT.rglob("*.rs"):
        rust_delimiters(path)

    rust_sources = "\n".join(
        path.read_text(encoding="utf-8")
        for path in ROOT.glob("packages/rust/crates/*/src/**/*.rs")
    )
    if len(re.findall(r"\bpub struct SafeError\b", rust_sources)) != 1:
        fail("Rust must expose exactly one canonical SafeError definition")
    if "service_version" in rust_sources:
        fail("stale service_version field remains after identity unification")
    if "return Err(ContractValidationError::new(\n            return Err" in rust_sources:
        fail("duplicated Rust return expression detected")

    print("static source checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
