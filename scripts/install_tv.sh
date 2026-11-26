#!/usr/bin/env bash
# Installs the aquarium on a Chromecast with Google TV.
#
# Enable on the TV first:
#   Settings -> System -> About -> "Android TV OS build" (select 7 times)
#   Settings -> System -> Developer options -> Wireless debugging
#   Pair: adb pair 192.0.2.10:<pairPort> <six-digit code>
#   Then: adb mdns services, adb connect 192.0.2.10:<debugPort>
#
# Then:
#   TV_SERIAL=<full serial or host:port> ./scripts/install_tv.sh
# For legacy ADB, supply the TV's IP address in TV_IP.
set -euo pipefail
source "$(dirname "$0")/common.sh"

TV_IP="${TV_IP:-}"
TV_SERIAL="${TV_SERIAL:-${ANDROID_SERIAL:-}}"
APK="$ROOT/aquarium-tv/build/outputs/apk/debug/aquarium-tv-debug.apk"

if [[ -z "$TV_SERIAL" && -n "$TV_IP" ]]; then
    echo "==> Trying legacy ADB on $TV_IP:5555"
    if "$ADB" connect "$TV_IP:5555" && [[ "$("$ADB" -s "$TV_IP:5555" get-state)" == device ]]; then
        TV_SERIAL="$TV_IP:5555"
    fi
fi
if [[ -z "$TV_SERIAL" ]]; then
    echo "No TV found. On current Chromecast HD firmware enable Wireless debugging," >&2
    echo "pair with 'adb pair', then re-run with TV_SERIAL=<serial from adb devices>." >&2
    exit 1
fi

if [[ ! -f "$APK" ]]; then
    echo "APK not built yet. Building..."
    "$GRADLE" --console=plain :aquarium-tv:assembleDebug
fi

echo "==> Installing on $TV_SERIAL"
"$ADB" -s "$TV_SERIAL" install -r "$APK"

echo "==> Launching on the TV"
"$ADB" -s "$TV_SERIAL" shell monkey -p com.aquabricks.aquarium -c android.intent.category.LEANBACK_LAUNCHER 1

echo "Done. The tank should be on screen; its IP and port are shown bottom-right."
