#!/usr/bin/env bash
# Shared helpers for the build/test scripts.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -n "${GRADLE_BIN:-}" ]]; then
    GRADLE="$GRADLE_BIN"
elif [[ -x "$ROOT/gradlew" ]]; then
    GRADLE="$ROOT/gradlew"
elif command -v gradle >/dev/null 2>&1; then
    GRADLE="gradle"
else
    # Fallback for this development VM, where Gradle 8.9 is in the wrapper cache.
    CACHED_GRADLE="$HOME/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle"
    if [[ -x "$CACHED_GRADLE" ]]; then
        GRADLE="$CACHED_GRADLE"
    else
        echo "No Gradle found. Open the project in Android Studio or set GRADLE_BIN." >&2
        exit 1
    fi
fi

ADB="${ADB:-adb}"

copy_apks() {
    mkdir -p "$ROOT/dist"
    cp "$ROOT/aquarium-tv/build/outputs/apk/debug/aquarium-tv-debug.apk" "$ROOT/dist/" 2>/dev/null || true
    cp "$ROOT/fish-scanner/build/outputs/apk/debug/fish-scanner-debug.apk" "$ROOT/dist/" 2>/dev/null || true
}
