#!/usr/bin/env bash
set -euo pipefail
/tmp/ad-cdp/bin/python /tmp/observe.py > diagnostic-evidence/webview-network.jsonl 2>&1 &
observer_pid=$!
trap 'kill "$observer_pid" 2>/dev/null || true' EXIT
set +e
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.tabatabata.BannerDiagnosticTest
probe_status=$?
set -e
exit "$probe_status"
