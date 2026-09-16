{ pkgs ? import <nixpkgs> {} }:

with pkgs;

let
  inherit (lib) optional optionals;

  jdk = jdk25;

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
