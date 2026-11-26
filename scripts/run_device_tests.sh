#!/usr/bin/env bash
# Runs the instrumented suites on a connected Android device/emulator.
#
#   TV_IP=192.0.2.10 ./scripts/run_device_tests.sh
#   ANDROID_SERIAL=emulator-5554 ./scripts/run_device_tests.sh
#
# With more than one device attached, set ANDROID_SERIAL explicitly.
set -euo pipefail
source "$(dirname "$0")/common.sh"

if [[ -n "${TV_IP:-}" ]]; then
    echo "==> Trying legacy ADB on TV at $TV_IP:5555"
    "$ADB" connect "$TV_IP:5555" || true
    echo "   (Modern Google TV firmware uses Wireless debugging pairing;"
    echo "    use 'adb mdns services' + 'adb pair' and set ANDROID_SERIAL.)"
fi

"$ADB" start-server >/dev/null 2>&1 || true
echo "==> Attached devices"
"$ADB" devices -l

mapfile -t DEVICES < <("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
if (( ${#DEVICES[@]} == 0 )); then
    echo "No authorised Android device found. Enable USB/network debugging and retry." >&2
    exit 1
fi
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    if (( ${#DEVICES[@]} > 1 )); then
        echo "Multiple devices attached: ${DEVICES[*]}" >&2
        echo "Re-run with ANDROID_SERIAL=<serial>." >&2
        exit 1
    fi
    ANDROID_SERIAL="${DEVICES[0]}"
fi
export ANDROID_SERIAL
echo "==> Running on $ANDROID_SERIAL"

mkdir -p "$ROOT/build/device-artifacts"

cat <<'WARN'
==> WARNING: connectedAndroidTest uninstalls the app after the run on this setup.
    For the aquarium that deletes its private files/fish storage (all scanned fish).
    Back up first, or use a spare device/profile:
      adb exec-out run-as com.aquabricks.aquarium tar -C files -cf - fish > fish-backup.tar
WARN

echo "==> Aquarium TV instrumented tests"
"$GRADLE" --console=plain :aquarium-tv:connectedDebugAndroidTest

echo "==> Fish scanner instrumented tests"
"$GRADLE" --console=plain :fish-scanner:connectedDebugAndroidTest

echo "==> Pulling smoke-test screenshots"
"$ADB" pull /sdcard/Android/data/com.aquabricks.aquarium/files/screenshots \
    "$ROOT/build/device-artifacts/" 2>/dev/null || true

echo
echo "Instrumented suites passed on $ANDROID_SERIAL."
echo "Screenshots (if written): build/device-artifacts/"
