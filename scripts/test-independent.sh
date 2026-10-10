#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$PWD/.toolchain/jdk21}"
if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
  echo "Set JAVA_HOME to a Java21 JDK before running." >&2
  exit 2
fi
export PATH="$JAVA_HOME/bin:$PATH"
./core/test.sh
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
find core/src/main/java geography/src/main/java geography/src/test/java -name '*.java' -print | sort > "$classes/sources.txt"
"$JAVA_HOME/bin/javac" --release 21 -Xlint:all -Werror -d "$classes" @"$classes/sources.txt"
"$JAVA_HOME/bin/java" -ea -cp "$classes" io.earthplatform.geography.GeographyTest
if [[ -f geography/src/test/java/io/earthplatform/geography/composition/SourceCompositionTest.java ]]; then
  "$JAVA_HOME/bin/java" -ea -cp "$classes" io.earthplatform.geography.composition.SourceCompositionTest geography
fi
python3 geography/tools/test_noaa_import.py
if [[ -f geography/tools/test_copernicus_import.py ]]; then
  python3 geography/tools/test_copernicus_import.py
  "$JAVA_HOME/bin/java" -ea -cp "$classes" io.earthplatform.geography.RegionalDataPackTest geography/data/copernicus-everest-proof/world.properties 27.9881 86.925 8720 8721 1
fi
if [[ -f geography/src/test/java/io/earthplatform/geography/variety/TerrainVarietyTest.java ]]; then
  "$JAVA_HOME/bin/java" -ea -cp "$classes" io.earthplatform.geography.variety.TerrainVarietyTest geography
fi
./streaming/test.sh
bash geography/tools/test_landcover.sh
printf '\nIndependent core, geography, composition, variety, streaming and land cover tests passed. This does not establish Minecraft gameplay.\n' 
