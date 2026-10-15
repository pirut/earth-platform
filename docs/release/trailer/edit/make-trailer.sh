#!/bin/sh
# Cuts the trailer from per-shot clips. usage: make-trailer.sh SHOTS_DIR OUT_DIR
# Needs ffmpeg and python3 with Pillow (a venv in OUT_DIR is made if Pillow is missing).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SHOTS=$1; OUT=$2
mkdir -p "$OUT/stills"
PY=python3
if ! $PY -c "import PIL" 2>/dev/null; then
  [ -x "$OUT/venv/bin/python" ] || { python3 -m venv "$OUT/venv"; "$OUT/venv/bin/pip" install -q pillow; }
  PY="$OUT/venv/bin/python"
fi
$PY "$HERE/edit.py" "$SHOTS" "$HERE/heroic-age.mp3" "$HERE/logo-stacked-white.png" "$OUT/earth-platform-trailer.mp4"
$PY "$HERE/edit.py" "$SHOTS" "$HERE/heroic-age.mp3" "$HERE/logo-stacked-white.png" "$OUT/earth-platform-trailer-silent.mp4" --silent
# Smaller copy for sharing (under 95 MB), and stills for the thumbnail and banners.
ffmpeg -y -v error -i "$OUT/earth-platform-trailer.mp4" -c:v libx264 -preset slow -crf 20 -maxrate 7M -bufsize 14M -c:a copy -movflags +faststart "$OUT/earth-platform-trailer-share.mp4"
for f in "$SHOTS"/*.mp4; do
  n=$(basename "$f" .mp4)
  for t in 1 3 5 7; do
    ffmpeg -y -v error -ss $t -i "$f" -frames:v 1 -q:v 2 "$OUT/stills/$n-$t.jpg" 2>/dev/null || true
  done
done
ls -la "$OUT" "$OUT/stills" | head -100
