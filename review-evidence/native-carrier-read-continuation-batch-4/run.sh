#!/bin/bash
set -euo pipefail
ROOT=/workspace/scratch/67f3a8ccac99/earth-platform/native-survival-039
T=/workspace/scratch/67f3a8ccac99/earth-platform/toolchain-recovery
export JAVA_HOME="$T/jdk21" PATH="$T/jdk21/bin:$PATH" GRADLE_USER_HOME="$T/gradle-home"
export EARTH_INSTALLED_DH_JAR="$T/downloads/DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar" EARTH_INSTALLED_IRIS_JAR="$T/downloads/iris-neoforge-1.8.14-beta.1+mc1.21.1.jar" EARTH_INSTALLED_SODIUM_JAR="$T/downloads/sodium-neoforge-0.8.12-beta.1+mc1.21.1.jar" EARTH_INSTALLED_SHADER_PACK="$T/downloads/photon_v1.3b.zip"
read -r ph pp < <(python3 -c 'import os,urllib.parse;p=urllib.parse.urlparse(os.environ.get("https_proxy",os.environ.get("HTTPS_PROXY","")));print(p.hostname or "",p.port or 80)')
export GRADLE_OPTS="-Dhttp.proxyHost=$ph -Dhttp.proxyPort=$pp -Dhttps.proxyHost=$ph -Dhttps.proxyPort=$pp -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
cd "$ROOT/minecraft"
"$T/gradle-9.2.1/bin/gradle" --offline --no-daemon --console=plain --no-build-cache --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1G -PearthBuildDirectory=/workspace/scratch/67f3a8ccac99/earth-platform/native-test-builds/native-carrier-read-build-004 -PearthRegisteredFixtureDraft=/workspace/shared/native-carrier-read-continuation-v6 -PearthRegisteredFixtureGameDir=/workspace/scratch/67f3a8ccac99/earth-platform/native-survival-039/minecraft/run-native-carrier-read-continuation-004 -I /workspace/shared/native-carrier-read-continuation-v6/scripts/registered-held-session.init.gradle -I /workspace/scratch/67f3a8ccac99/earth-platform/native-survival-039/review-evidence/native-carrier-read-continuation-batch-4/pre-java-receipt.init.gradle runSurvivalGameTest
