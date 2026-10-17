#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# Use MANIM_PYTHON=/path/to/venv/bin/python when not installed globally.
PYTHON="${MANIM_PYTHON:-python3}"
LAYOUT="${1:-landscape}"
MODE="${2:-panel}"
export EARTH_LAYOUT="$LAYOUT"
SCENES=(DataToBlocks TrueHeight FarTerrainLOD SharedServer FairComparison HonestLimits)
ARGS=(--renderer=cairo --media_dir "media/$LAYOUT/$MODE")
if [[ "$MODE" == "overlay" ]]; then
  export EARTH_OVERLAY=1
  ARGS+=(-t --format=mov)
fi
"$PYTHON" -m manim "${ARGS[@]}" earth_scenes.py "${SCENES[@]}"
