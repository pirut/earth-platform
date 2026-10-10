#!/bin/sh
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export JAVA_HOME="$ROOT/.toolchain/jdk21"
export PATH="$JAVA_HOME/bin:$PATH"
exec "$@"
