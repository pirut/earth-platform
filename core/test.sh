#!/usr/bin/env bash
set -euo pipefail
CORE=$(cd "$(dirname "$0")" && pwd)
PROJECT=$(dirname "$CORE")
if [[ -z ${JAVA_HOME:-} && -x "$PROJECT/.toolchain/jdk21/bin/javac" ]]; then
  export JAVA_HOME="$PROJECT/.toolchain/jdk21"
fi
JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
mkdir -p "$CORE/build/classes"
find "$CORE/src/main/java" "$CORE/src/test/java" -name '*.java' | sort > "$CORE/build/sources.txt"
"$JAVAC" --release 21 -Xlint:all -Werror -d "$CORE/build/classes" @"$CORE/build/sources.txt"
"$JAVA" -cp "$CORE/build/classes" io.earthplatform.core.CoreTests
if [[ ${1:-} == --benchmark ]]; then
  "$JAVA" -cp "$CORE/build/classes" io.earthplatform.core.CoreBenchmark
fi
