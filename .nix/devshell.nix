{ pkgs }:
pkgs.mkShell {
  packages = with pkgs; [
    cargo
    clippy
    git
    jq
    nodejs_22
    python313
    rust-analyzer
    rustc
    rustfmt
  ];

  RUST_SRC_PATH = "${pkgs.rustPlatform.rustLibSrc}";
}
