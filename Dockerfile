FROM rust:1.97.1-bookworm AS builder
WORKDIR /workspace
COPY . .
RUN cargo build --release --bin meta-agent-control-plane

FROM debian:bookworm-slim AS runtime
RUN useradd --system --uid 10001 --create-home meta-agent
COPY --from=builder /workspace/target/release/meta-agent-control-plane /usr/local/bin/meta-agent-control-plane
USER 10001:10001
EXPOSE 8787/tcp 8788/tcp 8789/udp
ENTRYPOINT ["/usr/local/bin/meta-agent-control-plane"]
CMD ["--http-addr", "0.0.0.0:8787", "--tcp-addr", "0.0.0.0:8788", "--udp-addr", "0.0.0.0:8789"]
