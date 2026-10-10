#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$(cd ../.toolchain/jdk21 && pwd)}"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.gradle-home}"
# Java does not honor HTTPS_PROXY. Preserve the environment's managed proxy and CA trust.
if [[ -n "${https_proxy:-${HTTPS_PROXY:-}}" ]]; then
  read -r proxy_host proxy_port < <(python3 -c 'import os,urllib.parse; p=urllib.parse.urlparse(os.environ.get("https_proxy",os.environ.get("HTTPS_PROXY",""))); print(p.hostname,p.port or 80)')
  export GRADLE_OPTS="-Dhttp.proxyHost=$proxy_host -Dhttp.proxyPort=$proxy_port -Dhttps.proxyHost=$proxy_host -Dhttps.proxyPort=$proxy_port -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
fi
exec ./gradlew --no-daemon --console=plain "$@"
