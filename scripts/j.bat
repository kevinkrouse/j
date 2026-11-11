@echo off
rem Launch j from an unpacked binary distribution.
rem
rem The extension jars sit one level down, in lib\extensions\<name>\*.jar, so
rem they are collected explicitly onto the classpath.
setlocal EnableDelayedExpansion
set "HERE=%~dp0"
set "CP=%HERE%j.jar"
for /D %%d in ("%HERE%lib\extensions\*") do (
    for %%f in ("%%d\*.jar") do set "CP=!CP!;%%f"
)

java ^
    -cp "%CP%" ^
    Main %*
