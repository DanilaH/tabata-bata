#!/usr/bin/env bash
set -euo pipefail
mkdir -p ui-audit-results
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell wm size "$UX_SIZE"
adb shell wm density "$UX_DENSITY"
adb shell settings put system font_scale "$UX_FONT"
adb install -r audit-apks/debug/app-debug.apk
adb install -r audit-apks/androidTest/debug/app-debug-androidTest.apk
collect() {
  if [ "$UX_FEEL" = true ]; then
    adb shell pkill -2 screenrecord || true
    sleep 2
  fi
  adb exec-out run-as ru.tabatabata tar -cf - -C files ux-audit > ui-audit-results/screens.tar || true
  tar -xf ui-audit-results/screens.tar -C ui-audit-results/ || true
  adb pull /sdcard/ui-audit-feel.mp4 ui-audit-results/feel.mp4 || true
  adb logcat -d -s AndroidRuntime:E TabataEvents:D > ui-audit-results/logcat.txt || true
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
