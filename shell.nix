{ pkgs ? import <nixpkgs> {} }:

let
  inherit (pkgs) lib;

  # Prebuilt babashka binaries, tracking the latest upstream release.
  # https://github.com/cormacc/nix-babashka  (refreshed every 6h)
  nix-babashka = builtins.fetchTarball {
    url = "https://github.com/cormacc/nix-babashka/archive/main.tar.gz";
  };
  bbPins = builtins.fromJSON (builtins.readFile "${nix-babashka}/nix/pins.json");
  babashka = pkgs.callPackage "${nix-babashka}/nix/bin.nix" {
    pname = "babashka";
    version = bbPins.release.version;
    repo = "babashka";
    pin = bbPins.release;
  };

  jdk = pkgs.jdk25;

in

pkgs.mkShell {
  name = "j";

  buildInputs = [
    jdk
    babashka
    pkgs.gcc          # jpty
    pkgs.ant          # legacy build, kept for parity checking
  ];

  shellHook = ''
    export JAVA_HOME=${jdk}
    alias b="bb build"
  ''
  #+ lib.optionalString pkgs.stdenv.isDarwin ''
  #  export JAVA_HOME=$JAVA_HOME/Contents/Home
  #''
  ;
}
