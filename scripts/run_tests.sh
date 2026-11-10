#!/usr/bin/env bash
# Runs everything that does not need an Android device:
#   unit tests -> JVM/Robolectric suite
#   lint
#   debug APKs + instrumentation APK compilation
set -euo pipefail
source "$(dirname "$0")/common.sh"

echo "==> JVM + Robolectric tests"
"$GRADLE" --console=plain \
    :aquarium-tv:testDebugUnitTest \
    :fish-scanner:testDebugUnitTest

echo "==> Lint"
"$GRADLE" --console=plain \
    :aquarium-tv:lintDebug \
    :fish-scanner:lintDebug

echo "==> Debug APKs"
"$GRADLE" --console=plain \
    :aquarium-tv:assembleDebug \
    :fish-scanner:assembleDebug
copy_apks

echo "==> Instrumentation APK compilation"
"$GRADLE" --console=plain \
    :aquarium-tv:assembleDebugAndroidTest \
    :fish-scanner:assembleDebugAndroidTest

echo
echo "All host-side checks passed."
echo "Test reports: aquarium-tv/build/reports/tests/testDebugUnitTest/index.html"
echo "             fish-scanner/build/reports/tests/testDebugUnitTest/index.html"
echo "Visual artefacts: aquarium-tv/build/test-artifacts, fish-scanner/build/test-artifacts"
echo
echo "On-device tests (TV/phone connected over adb):"
echo "  ./scripts/run_device_tests.sh"
