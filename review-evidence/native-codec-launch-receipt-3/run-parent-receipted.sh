#!/bin/bash
set -euo pipefail
ROOT=/workspace/scratch/67f3a8ccac99/earth-platform/native-survival-039
T=/workspace/scratch/67f3a8ccac99/earth-platform/toolchain-recovery
export JAVA_HOME="$T/jdk21" PATH="$T/jdk21/bin:$PATH" GRADLE_USER_HOME="$T/gradle-home"
export EARTH_INSTALLED_DH_JAR="$T/downloads/DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar" EARTH_INSTALLED_IRIS_JAR="$T/downloads/iris-neoforge-1.8.14-beta.1+mc1.21.1.jar" EARTH_INSTALLED_SODIUM_JAR="$T/downloads/sodium-neoforge-0.8.12-beta.1+mc1.21.1.jar" EARTH_INSTALLED_SHADER_PACK="$T/downloads/photon_v1.3b.zip"
read -r ph pp < <(python3 -c 'import os,urllib.parse;p=urllib.parse.urlparse(os.environ.get("https_proxy",os.environ.get("HTTPS_PROXY","")));print(p.hostname or "",p.port or 80)')
export GRADLE_OPTS="-Dhttp.proxyHost=$ph -Dhttp.proxyPort=$pp -Dhttps.proxyHost=$ph -Dhttps.proxyPort=$pp -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
DRAFT=/workspace/shared/native-network-flush-owner-draft/local-scope-codec3-route-v2
EVIDENCE="$ROOT/review-evidence/native-codec-boundary-batch-3"
test "${EARTH_PARENT_NATIVE_CODEC_LEASE:-}" = "serial-after-gui-close" || { echo "Parent serial runtime lease required"; exit 2; }
test ! -e "$ROOT/minecraft/run-native-codec-boundary-003"
test ! -e "$EVIDENCE"
mkdir -p "$EVIDENCE"
python3 "$DRAFT/source-lock.py" "$ROOT" "$EVIDENCE/source-inputs.json" --create
cp "$DRAFT/codec3-seam.init.gradle" "$EVIDENCE/codec-seam.init.gradle"
cd "$ROOT/minecraft"
set +e
"$T/gradle-9.2.1/bin/gradle" --offline --no-daemon --console=plain --no-build-cache --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1G -PearthBuildDirectory=/tmp/earthplatform-foundation69-build --init-script "$EVIDENCE/codec-seam.init.gradle" --init-script "$ROOT/review-evidence/native-codec-launch-receipt-3/pre-java-receipt.init.gradle" runSurvivalGameTest 2>&1 | tee "$EVIDENCE/run.log"
rc=${PIPESTATUS[0]}
set -e
printf "%s\n" "$rc" > "$EVIDENCE/exit-code.txt"
python3 "$DRAFT/source-lock.py" "$ROOT" "$EVIDENCE/source-inputs.json"
exit "$rc"
