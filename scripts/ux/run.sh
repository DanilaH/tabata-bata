#!/usr/bin/env bash
set -euo pipefail
mkdir -p ui-audit-results
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell settings put system font_scale "$UX_FONT"
adb install -r audit-apks/debug/app-debug.apk
adb install -r audit-apks/androidTest/debug/app-debug-androidTest.apk
collect_screens() {
  local pending
  pending=$(mktemp ui-audit-results/screens.XXXXXX)
  if timeout 15s adb exec-out run-as ru.tabatabata tar -cf - -C files ux-audit > "$pending" 2>> ui-audit-results/collection.log &&
      tar -tf "$pending" > /dev/null 2>&1; then
    mv "$pending" ui-audit-results/screens.tar
  else
    rm -f "$pending"
  fi
}
collect_video() {
  if [ "$UX_FEEL" = true ] &&
      timeout 15s adb pull /sdcard/ui-audit-feel.mp4 ui-audit-results/feel.pending.mp4 >> ui-audit-results/collection.log 2>&1; then
    mv ui-audit-results/feel.pending.mp4 ui-audit-results/feel.mp4
  fi
}
# Keep the last valid capture if the emulator disappears before the final trap.
(
  while sleep 20; do
    date -u
    free -m
    ps -eo pid,rss,comm --sort=-rss | head -12
    collect_screens
  done
) > ui-audit-results/host-memory.txt 2>&1 &
monitor_pid=$!
adb logcat -v threadtime -s AndroidRuntime:E UxAudit:E TabataEvents:D > ui-audit-results/logcat.txt 2>&1 &
logcat_pid=$!
collect() {
  kill "$monitor_pid" "$logcat_pid" 2>/dev/null || true
  wait "$monitor_pid" "$logcat_pid" 2>/dev/null || true
  if [ "$UX_FEEL" = true ]; then
    timeout 10s adb shell pkill -2 screenrecord || true
    sleep 2
  fi
  collect_screens
  tar -xf ui-audit-results/screens.tar -C ui-audit-results/ || true
  if [ "$UX_FEEL" = true ]; then
    collect_video
  fi
}
trap collect EXIT
if [ "$UX_FEEL" = true ]; then
  timeout 110s adb shell screenrecord --size 720x1280 --bit-rate 2000000 --time-limit 90 /sdcard/ui-audit-feel.mp4 > ui-audit-results/record.log 2>&1 &
fi
adb shell am instrument -w -r \
  -e class "${UX_CLASS:-ru.tabatabata.UxAuditProbe}" \
  -e additionalTestOutputDir /sdcard/Android/data/ru.tabatabata/files/ux-audit \
  -e uxLocale "$UX_LOCALE" -e uxOrientation "$UX_ORIENTATION" \
  -e uxTheme "$UX_THEME" -e uxFeel "$UX_FEEL" \
  ru.tabatabata.test/androidx.test.runner.AndroidJUnitRunner | tee ui-audit-results/instrumentation.txt
python3 - <<'PY'
from pathlib import Path
import re,sys
text=Path('ui-audit-results/instrumentation.txt').read_text()
match=re.search(r'^OK \([0-9]+ tests?\)\r?$',text,re.M)
if match is None: sys.exit('Instrumented audit contains failures; see evidence')
print(match.group(0))
PY
collect_screens
collect_video
# Distinguish actual SystemUI appearance from instrumentation-owned window focus.
if [ "$UX_FEEL" = true ] || [ "${UX_NATIVE:-false}" = true ]; then
  python3 scripts/ux/native-navigation.py
fi
