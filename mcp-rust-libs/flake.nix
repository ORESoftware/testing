{
  description = "Polyglot shared infrastructure and contracts for ORESoftware MCP servers";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f (import nixpkgs { inherit system; }));
    in {
      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = with pkgs; [
            cargo
            clippy
            rustc
            rustfmt
            cargo-audit
            cargo-deny
            nodejs_22
            dart
            gleam
            erlang
            python3
            gnumake
            git
          ];
          RUST_BACKTRACE = "1";
        };
      });
    };
}
