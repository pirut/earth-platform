#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE/minecraft"
export JAVA_HOME="$ROOT/.toolchain/jdk21"
export GRADLE_USER_HOME="$ROOT/minecraft/.gradle-home"
# This shader candidate is explicitly selected by the operator; it never silently
# downgrades to the isolated diagnostic renderer when an installed input is absent.
if [[ "${EARTH_ENABLE_INSTALLED_SHADERS:-}" != "true" ]]; then
  printf '%s\n' "Explicit shader opt-in required: EARTH_ENABLE_INSTALLED_SHADERS=true ./launch-client.sh" >&2
  exit 2
fi
if [[ "$#" != "0" ]]; then
  printf '%s\n' "This launcher takes no arguments; set EARTH_ENABLE_INSTALLED_SHADERS=true explicitly." >&2
  exit 2
fi
JVM_INPUTS="${JAVA_TOOL_OPTIONS:-} ${JDK_JAVA_OPTIONS:-} ${_JAVA_OPTIONS:-} ${GRADLE_OPTS:-}"
for FORBIDDEN_OPTION in \
  earthplatform.test.isolatedDhRuntime=true \
  earthplatform.experimental.installedDhBackend=false \
  earthplatform.experimental.installedDhTempFileShim=false \
  earthplatform.experimental.installedShaderBackend=false \
  earthplatform.experimental.renderSubmissionProbe=true \
  earthplatform.experimental.nativeSubmissionPhases=true \
  earthplatform.experimental.sparseTerrainView=true \
  earthplatform.experimental.longDistanceDepthProbe=true; do
  if [[ "$JVM_INPUTS" == *"$FORBIDDEN_OPTION"* ]]; then
    printf '%s\n' "Refusing conflicting or diagnostic JVM option: $FORBIDDEN_OPTION" >&2
    exit 2
  fi
done
# Players install these unchanged files separately. No downloader, extraction,
# copying, runtime-classpath injection or installation fallback is permitted here.
INSTALLED_DH_JAR="$PWD/run/mods/DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar"
INSTALLED_IRIS_JAR="$PWD/run/mods/iris-neoforge-1.8.14-beta.1+mc1.21.1.jar"
INSTALLED_SODIUM_JAR="$PWD/run/mods/sodium-neoforge-0.8.12-beta.1+mc1.21.1.jar"
INSTALLED_SHADER_PACK="$PWD/run/shaderpacks/photon_v1.3b.zip"
verify_installed_input() {
  local LABEL="$1" INPUT="$2" EXPECTED_SHA256="$3"
  if [[ ! -f "$INPUT" ]]; then
    printf '%s\n' "Install official $LABEL separately at $INPUT before launching this candidate." >&2
    exit 2
  fi
  local ACTUAL_SHA256
  ACTUAL_SHA256="$(sha256sum "$INPUT" | cut -d ' ' -f 1)"
  if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
    printf '%s\n' "Refusing unsupported or changed installed $LABEL artifact: $INPUT" >&2
    exit 2
  fi
}
verify_installed_input "DH 3.3.3" "$INSTALLED_DH_JAR" "864e70def5b0d54d619b940b66bb2784e91d3f3fc253064fdc1b3fc5786cbfab"
verify_installed_input "Iris 1.8.14-beta.1" "$INSTALLED_IRIS_JAR" "60d5f8bf52f25e9986440f4a4270a6bd986d9cff994510e1108ac1547063b314"
verify_installed_input "Sodium 0.8.12-beta.1" "$INSTALLED_SODIUM_JAR" "dc2838d5538db39414171fe86586ab89d1a30d2fd97f59229cc44cf3777f569d"
verify_installed_input "Photon v1.3b" "$INSTALLED_SHADER_PACK" "120897768eaa8cb1f14a3e98f88dff009ae3204ee3b96904a4fe8586edfc1ead"
# Only compile-time references are supplied to Gradle. NeoForge discovers installed
# mods in run/mods; Iris discovers Photon in run/shaderpacks and validates settings.
export EARTH_INSTALLED_DH_JAR="$INSTALLED_DH_JAR"
export EARTH_INSTALLED_IRIS_JAR="$INSTALLED_IRIS_JAR"
export EARTH_INSTALLED_SODIUM_JAR="$INSTALLED_SODIUM_JAR"
export EARTH_INSTALLED_SHADER_PACK="$INSTALLED_SHADER_PACK"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dearthplatform.experimental.authoritativeCoreGate=true -Dearthplatform.experimental.coreCollisionGate=true -Dearthplatform.experimental.fullHeightPlayerGate=true -Dearthplatform.experimental.rollingCubeGate=true -Dearthplatform.experimental.authoritativeReplacement=true -Dearthplatform.experimental.originalCubeBoundaryEdits=true -Dearthplatform.experimental.twoWorkspaceGate=true -Dearthplatform.experimental.adjacentWorkspaceGate=true -Dearthplatform.experimental.indexedWorkspaceGate=true -Dearthplatform.experimental.indexedScenario=true -Dearthplatform.experimental.renderSubmissionProbe=false -Dearthplatform.experimental.nativeSubmissionPhases=false -Dearthplatform.experimental.earthPlayerGate=true -Dearthplatform.experimental.sparseTerrainView=false -Dearthplatform.experimental.longDistanceDepthProbe=false -Dearthplatform.experimental.installedDhTempFileShim=true -Dearthplatform.experimental.installedDhBackend=true -Dearthplatform.experimental.installedShaderBackend=true"
# This is a distinct 4 GiB client-heap baseline, not a performance comparison.
# Refuse before launching if there is not room for client plus the 2 GiB Gradle cap.
AVAILABLE_KIB="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)"
if [[ ! "$AVAILABLE_KIB" =~ ^[0-9]+$ ]] || (( AVAILABLE_KIB < 6291456 )); then
  printf '%s\n' "Shader launch requires at least 6 GiB currently available RAM; observed ${AVAILABLE_KIB:-unknown} KiB. Stop competing builds/clients before retrying." >&2
  exit 2
fi
printf '%s\n' "Shader client maximum heap 4 GiB; MemAvailable=${AVAILABLE_KIB} KiB; no concurrent build/client is authorized."
exec scripts/gradle.sh --offline runClient -x downloadAssets -x createMinecraftArtifacts
