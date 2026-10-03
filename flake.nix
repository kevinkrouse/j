{
  description = "j";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

    # Prebuilt babashka binaries, tracking the latest upstream release.
    # https://github.com/cormacc/nix-babashka  (refreshed every 6h;
    # `nix flake update nix-babashka` to pick up a new release)
    nix-babashka = {
      url = "github:cormacc/nix-babashka";
      flake = false;
    };
  };

  outputs = { self, nixpkgs, nix-babashka }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forAllSystems (pkgs:
        let
          inherit (pkgs) lib;

          bbPins = builtins.fromJSON (builtins.readFile "${nix-babashka}/nix/pins.json");
          babashka = pkgs.callPackage "${nix-babashka}/nix/bin.nix" {
            pname = "babashka";
            version = bbPins.release.version;
            repo = "babashka";
            pin = bbPins.release;
          };

          jdk = pkgs.jdk25;

          # jfmt (https://github.com/bmarwell/jfmt) ships native binaries for
          # these systems only; `bb fmt-check` skips Java where it is missing.
          jfmtVersion = "0.2.0";
          jfmtDists = {
            x86_64-linux = { name = "linux-x86_64.tar.gz"; hash = "sha256-wqMpN8cUcgWC8pZcVSJ0LtsakgomliElmsYHAkxHmc0="; };
            aarch64-darwin = { name = "osx-aarch_64.zip"; hash = "sha256-7NCizuwDXt/ALB+O+nHg4JiUs9Tf0XOidFsnhadecv0="; };
          };
          jfmtDist = jfmtDists.${pkgs.stdenv.hostPlatform.system} or null;
          jfmt = pkgs.stdenvNoCC.mkDerivation {
            pname = "jfmt";
            version = jfmtVersion;
            src = pkgs.fetchurl {
              url = "https://github.com/bmarwell/jfmt/releases/download/v${jfmtVersion}/jfmt-${jfmtVersion}-${jfmtDist.name}";
              inherit (jfmtDist) hash;
            };
            nativeBuildInputs = [ pkgs.unzip ];
            installPhase = ''
              install -Dm755 bin/jfmt $out/bin/jfmt
            '';
          };
        in
        {
          default = pkgs.mkShell {
            name = "j";

            buildInputs = [
              jdk
              babashka
              pkgs.gcc          # jpty
            ] ++ lib.optional (jfmtDist != null) jfmt;

            shellHook = ''
              export JAVA_HOME=${jdk}
              alias b="bb build"
            ''
            #+ lib.optionalString pkgs.stdenv.isDarwin ''
            #  export JAVA_HOME=$JAVA_HOME/Contents/Home
            #''
            ;
          };
        });
    };
}
