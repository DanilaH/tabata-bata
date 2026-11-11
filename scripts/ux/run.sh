#!/usr/bin/env bash
set -euo pipefail
mkdir -p ui-audit-results
adb shell wm size "$UX_SIZE"
adb shell wm density "$UX_DENSITY"
adb shell settings put system font_scale "$UX_FONT"
adb install -r audit-apks/debug/app-debug.apk
adb install -r audit-apks/androidTest/debug/app-debug-androidTest.apk
collect() {
  adb pull /sdcard/Android/data/ru.tabatabata/files/ux-audit ui-audit-results/ || true
  adb pull /sdcard/ui-audit-feel.mp4 ui-audit-results/feel.mp4 || true
  adb logcat -d -s AndroidRuntime:E TabataEvents:D > ui-audit-results/logcat.txt || true
}
trap collect EXIT
adb shell am instrument -w -r \
  -e class ru.tabatabata.UxAuditProbe \
  -e additionalTestOutputDir /sdcard/Android/data/ru.tabatabata/files/ux-audit \
  -e uxLocale "$UX_LOCALE" -e uxOrientation "$UX_ORIENTATION" \
  -e uxTheme "$UX_THEME" -e uxFeel "$UX_FEEL" \
  ru.tabatabata.test/androidx.test.runner.AndroidJUnitRunner | tee ui-audit-results/instrumentation.txt
rg '^OK \([0-9]+ tests?\)' ui-audit-results/instrumentation.txt
