#!/usr/bin/env bash
# Builds both debug APKs and copies them into dist/.
set -euo pipefail
source "$(dirname "$0")/common.sh"

"$GRADLE" --console=plain :aquarium-tv:assembleDebug :fish-scanner:assembleDebug
copy_apks
ls -lh "$ROOT/dist"
