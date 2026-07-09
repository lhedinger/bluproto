#!/usr/bin/env bash
# Compile all sources into bin/ (gitignored). Used by the run/test/capture
# skills and safe to run standalone.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p bin
javac -d bin $(find src -name '*.java')
echo "compiled to bin/"
