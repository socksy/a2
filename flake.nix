{
  description = "a2 — Architecture Animator";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            babashka
            d2
            clojure
            jdk21
          ];
        };

        packages.default = pkgs.writeShellScriptBin "a2" ''
          export PATH="${pkgs.lib.makeBinPath [ pkgs.d2 ]}:$PATH"
          exec ${pkgs.babashka}/bin/bb -cp ${self}/src -m a2.core "$@"
        '';
      });
}
