#!/bin/sh
set -eu
root=${1:-$(pwd)}
cd "$root"
PYTHONDONTWRITEBYTECODE=1 python3 scripts/regenerate-generated.py --check
PYTHONDONTWRITEBYTECODE=1 python3 scripts/static-source-checks.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/check-scaffold.py
PYTHONDONTWRITEBYTECODE=1 python3 tooling/conformance/run.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/check-scaffold.py
