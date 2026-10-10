#!/usr/bin/env bash
set -euo pipefail
STREAMING=$(cd "$(dirname "$0")" && pwd)
PROJECT=$(dirname "$STREAMING")
JAVA_HOME=${JAVA_HOME:-"$PROJECT/.toolchain/jdk21"}
mkdir -p "$STREAMING/build/classes"
find "$PROJECT/core/src/main/java" "$STREAMING/src/main/java" "$STREAMING/src/test/java" -name '*.java' | sort > "$STREAMING/build/sources.txt"
"$JAVA_HOME/bin/javac" --release 21 -Xlint:all -Werror -d "$STREAMING/build/classes" @"$STREAMING/build/sources.txt"
"$JAVA_HOME/bin/java" -cp "$STREAMING/build/classes" io.earthplatform.streaming.StreamingTests
if [[ ${1:-} == --benchmark ]]; then
  "$JAVA_HOME/bin/java" -cp "$STREAMING/build/classes" io.earthplatform.streaming.StreamingBenchmark
fi
