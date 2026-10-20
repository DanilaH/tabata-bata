#!/usr/bin/env bash
set -euo pipefail
./gradlew assembleRelease writeDebugKeystorePath
"$ANDROID_HOME/build-tools/36.0.0/apksigner" sign --ks "$(cat app/build/debug-keystore-path.txt)" --ks-pass pass:android --key-pass pass:android --out /tmp/tabata-demo-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
# Demo IDs only; test the actual minified app without an available ad network.
trap 'adb shell svc wifi enable; adb shell svc data enable' EXIT
adb shell svc wifi disable
adb shell svc data disable
SMOKE_RELEASE=1 SMOKE_APK=/tmp/tabata-demo-release.apk SMOKE_OUT=app/build/reports/demo-release-offline python3 scripts/android-system-check.py
