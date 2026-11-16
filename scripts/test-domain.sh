#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
if command -v javac >/dev/null 2>&1; then
  javac -source 21 -target 21 -d "$out" src/main/java/app/tablesignal/domain/*.java src/test/java/app/tablesignal/domain/DomainChecks.java
else
  java -m jdk.compiler/com.sun.tools.javac.Main -source 21 -target 21 -d "$out" src/main/java/app/tablesignal/domain/*.java src/test/java/app/tablesignal/domain/DomainChecks.java
fi
java -cp "$out" app.tablesignal.domain.DomainChecks
