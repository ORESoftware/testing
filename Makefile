.PHONY: check run test image

check:
	cargo fmt --check
	cargo clippy --all-targets --all-features -- -D warnings
	cargo test --all-targets --all-features
	python scripts/verify_contract.py
	node --check scripts/dashboard.js

run:
	cargo run -- --auth-token "$${META_AGENT_TOKEN}" --protect-read-api

test:
	cargo test --all-targets --all-features

image:
	docker build -t meta-agent-control-plane:local .
