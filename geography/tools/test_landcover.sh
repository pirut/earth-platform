#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
java_home="${JAVA_HOME:-$PWD/.toolchain/jdk21}"
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
python3 geography/tools/test_worldcover_import.py
"$java_home/bin/javac" --release 21 -Xlint:all -Werror -d "$classes" \
  geography/src/main/java/io/earthplatform/geography/GeoPoint.java \
  geography/src/main/java/io/earthplatform/geography/landcover/*.java \
  geography/src/test/java/io/earthplatform/geography/landcover/*.java
"$java_home/bin/java" -ea -cp "$classes" io.earthplatform.geography.landcover.LandCoverTest
(cd geography/data/worldcover-2021-proof && sha256sum -c SHA256SUMS)
