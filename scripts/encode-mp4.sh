#!/usr/bin/env bash
# Encode the PNG frames written by SimCapture into a mobile-friendly MP4.
#
#   scripts/encode-mp4.sh [outDir] [fps]
#
# Looks for ffmpeg on PATH, then falls back to the static binary shipped by the
# Python package imageio-ffmpeg (pip install imageio-ffmpeg). Produces
# <outDir>/sim.mp4 with H.264 + yuv420p + faststart, which plays inline on
# iOS/Android and in chat clients (unlike an animated GIF).
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${1:-capture}"
FPS="${2:-16}"
FRAMES="$OUT/frames"

if [ ! -d "$FRAMES" ] || [ -z "$(ls -A "$FRAMES" 2>/dev/null)" ]; then
  echo "no frames in $FRAMES — run SimCapture with frames>1 first" >&2
  exit 1
fi

FFMPEG="$(command -v ffmpeg || true)"
if [ -z "$FFMPEG" ]; then
  FFMPEG="$(python3 -c 'import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())' 2>/dev/null || true)"
fi
if [ -z "$FFMPEG" ]; then
  echo "ffmpeg not found. Install it with: pip install imageio-ffmpeg" >&2
  exit 1
fi

"$FFMPEG" -y -framerate "$FPS" -i "$FRAMES/frame_%04d.png" \
  -c:v libx264 -pix_fmt yuv420p -movflags +faststart \
  "$OUT/sim.mp4"

echo "wrote $OUT/sim.mp4"
