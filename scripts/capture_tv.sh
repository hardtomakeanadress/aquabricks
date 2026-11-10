#!/usr/bin/env bash
# Grabs a PNG of whatever is on the TV right now.
set -euo pipefail
source "$(dirname "$0")/common.sh"

mkdir -p "$ROOT/build/device-artifacts"
OUT="$ROOT/build/device-artifacts/tv-frame-$(date +%Y%m%d-%H%M%S).png"
"$ADB" exec-out screencap -p > "$OUT"
echo "Saved $OUT"
