#!/usr/bin/env bash
# Installs the scanner on the one connected phone.
set -euo pipefail
source "$(dirname "$0")/common.sh"

APK="$ROOT/fish-scanner/build/outputs/apk/debug/fish-scanner-debug.apk"
if [[ ! -f "$APK" ]]; then
    echo "APK not built yet. Building..."
    "$GRADLE" --console=plain :fish-scanner:assembleDebug
fi

"$ADB" install -r "$APK"
echo "Open AquaBricks Scanner on the phone; choose SCAN A FISH and allow the camera."
