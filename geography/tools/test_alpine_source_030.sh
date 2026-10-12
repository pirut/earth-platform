#!/usr/bin/env bash
set -euo pipefail
geo="$(cd "$(dirname "$0")/.." && pwd)"
candidate="$(dirname "$geo")"
java_home="${JAVA_HOME:-$candidate/../../.toolchain/jdk21}"
if [[ ! -x "$java_home/bin/javac" ]]; then echo "A cached Java21 JDK is required; no download attempted" >&2; exit 1; fi
mkdir -p "$geo/earth-alpine-results"
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
find "$candidate/core/src/main/java" "$geo/src/main/java" -name '*.java' | sort > "$classes/sources.txt"
"$java_home/bin/javac" --release 21 -Xlint:all -Werror -d "$classes" @"$classes/sources.txt" "$geo/src/test/java/io/earthplatform/geography/earth/EarthAlpineDefinitionTest.java"
"$java_home/bin/java" -ea -cp "$classes" io.earthplatform.geography.earth.EarthAlpineDefinitionTest "$geo" | tee "$geo/earth-alpine-results/source-contract-test.txt"
(cd "$geo/data/copernicus-everest-expanded-030" && sha256sum -c SHA256SUMS)
