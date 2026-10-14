# Project instructions

Read README.md, docs/ROADMAP.md and docs/RELEASE.md before changing scope.

This is a small native Kotlin/Compose Android interval timer for RuStore. Do not add accounts, a backend, courses, health promises, watches, calorie estimates or paid preset limits. Arbitrary exercise sequences are deferred.

TimerEngine uses injected monotonic time. Never derive elapsed time from UI tick counts or wall-clock time. TimerService owns the running session; activity lifecycle must not stop it. Pauses, completion and destruction must release wake locks and stale audio. Interrupted sessions resume only by user action.

No ads on editor/start/workout/pause, including preparation and rests. Never wait for an ad to load before starting or repeating. Never show real ads in development tests. Do not send timer names or other user-entered text to analytics. Keys are build properties; signing secrets do not belong in git.

Required checks for timing/service changes: `./gradlew testDebugUnitTest lintDebug assembleDebug`. Instrumented smoke: `./gradlew connectedDebugAndroidTest` on API 35 or newer. State precisely whether tests ran on an emulator or physical phone. Neither successful compilation nor a running service proves that audible signals work reliably on Xiaomi/HyperOS or Bluetooth devices.

Publishing requires a permanent user-owned signing key, actual SDK IDs and privacy/support details, plus physical-device verification. Keep known release gaps in docs/RELEASE.md current. Do not mark commercial viability or store distribution as proven from competitor download counters.
