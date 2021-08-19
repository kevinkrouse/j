{ pkgs ? import <nixpkgs> {} }:

with pkgs;

let
  inherit (lib) optional optionals;

  jdk = adoptopenjdk-hotspot-bin-16;

in

mkShell {
  name = "j";

  buildInputs = [
    jdk
    ant
  ];

  shellHook = ''
    alias b="ant build"
  ''
  #+ lib.optionalString stdenv.isDarwin ''
  #  export JAVA_HOME=$JAVA_HOME/Contents/Home
  #''
  ;
}
