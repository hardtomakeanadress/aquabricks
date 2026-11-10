#!/usr/bin/env bash
# Installs both debug APKs on a connected phone, launches the tank, uploads
# scripts/demo-fish.png over HTTP, screenshots it, force-stops the app, relaunches
# it and verifies that the fish is still there.
#
#   ANDROID_SERIAL=<serial> ./scripts/phone_e2e.sh
#
# Requires adb and curl on the machine running the script.
set -euo pipefail
source "$(dirname "$0")/common.sh"

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    mapfile -t DEVICES < <("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
    if (( ${#DEVICES[@]} != 1 )); then
        echo "Set ANDROID_SERIAL (found ${#DEVICES[@]} devices)." >&2
        "$ADB" devices -l >&2
        exit 1
    fi
    ANDROID_SERIAL="${DEVICES[0]}"
fi
export ANDROID_SERIAL

HOST_PORT="${HOST_PORT:-18765}"
FISH_NAME="${FISH_NAME:-Luna}"
FISH_CREATOR="${FISH_CREATOR:-Mia}"
ARTIFACTS="$ROOT/build/device-artifacts"
mkdir -p "$ARTIFACTS"

cleanup() {
    "$ADB" -s "$ANDROID_SERIAL" forward --remove "tcp:$HOST_PORT" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> Installing APKs"
"$ADB" -s "$ANDROID_SERIAL" install -r "$ROOT/dist/aquarium-tv-debug.apk"
"$ADB" -s "$ANDROID_SERIAL" install -r "$ROOT/dist/fish-scanner-debug.apk"
"$ADB" -s "$ANDROID_SERIAL" shell pm grant com.aquabricks.scanner android.permission.CAMERA 2>/dev/null || true

echo "==> Launching the tank"
"$ADB" -s "$ANDROID_SERIAL" shell am force-stop com.aquabricks.aquarium || true
"$ADB" -s "$ANDROID_SERIAL" shell monkey \
    -p com.aquabricks.aquarium -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 3
"$ADB" -s "$ANDROID_SERIAL" forward "tcp:$HOST_PORT" tcp:8765 >/dev/null

wait_for_tank() {
    local health=""
    for _ in $(seq 1 20); do
        health=$(curl -fsS --max-time 2 "http://127.0.0.1:$HOST_PORT/health" 2>/dev/null || true)
        if [[ -n "$health" ]]; then
            echo "$health"
            return 0
        fi
        sleep 0.5
    done
    return 1
}

echo "==> Health before"
before=$(wait_for_tank) || { echo "Tank server did not answer." >&2; exit 1; }
echo "$before"

echo "==> Uploading $FISH_NAME"
curl -fsS --max-time 5 -X POST "http://127.0.0.1:$HOST_PORT/fish" \
    -H "Content-Type: image/png" \
    -H "X-Fish-Name: $FISH_NAME" \
    -H "X-Fish-Creator: $FISH_CREATOR" \
    -H "X-Fish-Facing: right" \
    --data-binary "@$ROOT/scripts/demo-fish.png"
echo
sleep 2
"$ADB" -s "$ANDROID_SERIAL" exec-out screencap -p > "$ARTIFACTS/phone-${FISH_NAME}-arrival.png"

after=$(wait_for_tank) || true
echo "==> Health after upload: $after"

echo "==> Force-stop and relaunch"
"$ADB" -s "$ANDROID_SERIAL" shell am force-stop com.aquabricks.aquarium || true
sleep 1
"$ADB" -s "$ANDROID_SERIAL" shell monkey \
    -p com.aquabricks.aquarium -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 3
relaunched=$(wait_for_tank) || { echo "Tank did not come back after relaunch." >&2; exit 1; }
echo "==> Health after relaunch: $relaunched"
if ! grep -q '"fish":[1-9]' <<<"$relaunched"; then
    echo "Fish did not persist across relaunch." >&2
    exit 1
fi
"$ADB" -s "$ANDROID_SERIAL" exec-out screencap -p > "$ARTIFACTS/phone-${FISH_NAME}-relaunch.png"

echo "==> Passed: fish persisted across a full app restart."
echo "Screenshots: $ARTIFACTS"
