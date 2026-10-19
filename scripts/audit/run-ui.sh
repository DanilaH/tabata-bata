#!/usr/bin/env bash
set -euo pipefail
adb shell 'screenrecord --time-limit 180 /sdcard/audit.mp4 >/dev/null 2>&1 & echo $!' > /tmp/audit-record.pid
cleanup() {
  adb shell kill -2 "$(tr -d '\r\n' < /tmp/audit-record.pid)" || true
  sleep 2
  adb pull /sdcard/audit.mp4 app/build/audit.mp4 || true
}
trap cleanup EXIT
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.tabatabata.AuditStoreProbe,ru.tabatabata.AuditUiProbe
