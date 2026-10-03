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
        in
        {
          default = pkgs.mkShell {
            name = "j";

            buildInputs = [
              jdk
              babashka
              pkgs.gcc          # jpty
            ];

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
