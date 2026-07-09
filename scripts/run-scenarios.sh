#!/usr/bin/env bash
# Run the behavior scenario tests and encode one MP4 per scenario.
#
#   scripts/run-scenarios.sh [outBaseDir] [fps]
#
# Each scenario stages a specific interaction, asserts an outcome (PASS/FAIL),
# and writes PNG frames; this script encodes them to <scenario>/sim.mp4. Exits
# non-zero if any scenario fails its assertion.
set -euo pipefail
cd "$(dirname "$0")/.."

BASE="${1:-capture/scenarios}"
FPS="${2:-14}"

./build.sh

set +e
java -Djava.awt.headless=true -cp bin net.hedinger.prototype.main.ScenarioRunner "$BASE"
STATUS=$?
set -e

echo
echo "encoding videos..."
for d in "$BASE"/*/; do
  name="$(basename "$d")"
  if [ -d "$d/frames" ] && [ -n "$(ls -A "$d/frames" 2>/dev/null)" ]; then
    ./scripts/encode-mp4.sh "$d" "$FPS" >/dev/null && echo "  $name -> ${d}sim.mp4"
  fi
done

exit $STATUS
