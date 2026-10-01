
.PHONY: check conformance generated native-toolchains

check:
	PYTHONDONTWRITEBYTECODE=1 python3 scripts/regenerate-generated.py --check
	PYTHONDONTWRITEBYTECODE=1 python3 scripts/static-source-checks.py
	PYTHONDONTWRITEBYTECODE=1 python3 scripts/check-scaffold.py
	PYTHONDONTWRITEBYTECODE=1 python3 tooling/conformance/run.py

generated:
	PYTHONDONTWRITEBYTECODE=1 python3 scripts/regenerate-generated.py --write

conformance:
	PYTHONDONTWRITEBYTECODE=1 python3 tooling/conformance/run.py

native-toolchains:
	sh scripts/check-native-toolchains.sh
