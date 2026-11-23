#!/usr/bin/env bash
# U06-T04C-02B process-boundary test; invoked inside the API 35 emulator job.
# Uses two separate AndroidJUnitRunner processes, no APK reinstall between them.
set -euo pipefail
APP_ID=org.jeresaf.datacollector.android
CLASS=org.odk.collect.android.recordwork.ConnectedRestartDraftBoundaryTest

./gradlew :collect_app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.odk.collect.android.recordwork.ConnectedBackendTlsSmokeTest,org.odk.collect.android.recordwork.ConnectedRecordDiscoveryTest,org.odk.collect.android.recordwork.ConnectedStaleWorkRecoveryTest \
  --no-daemon --stacktrace

# Gradle's first invocation installed both test and target APKs. Discover the
# installed instrumentation component; never guess a launcher/package path.
instrumentation="$(adb shell pm list instrumentation | tr -d '\r' |
  sed -n "s/^instrumentation:\([^ ]*\) (target=${APP_ID})$/\1/p" | head -n1)"
if [[ -z "$instrumentation" ]]; then
  echo "::error::Expected app instrumentation was not installed"
  exit 1
fi

run_one() {
  local method="$1"
  local output
  output="$(adb shell am instrument -w -e class "${CLASS}#${method}" "$instrumentation")"
  # Never dump test instrumentation output: it may contain private diagnostics.
  if ! grep -Eq '^OK \(1 test\)' <<<"$output" || grep -Eq '^FAILURES|^INSTRUMENTATION_FAILED' <<<"$output"; then
    echo "::error::Android process-boundary phase ${method} did not pass"
    exit 1
  fi
  echo "Android instrumentation phase ${method}: PASS"
}
run_one phaseOneWriteOnlyEncryptedNoBackupCiphertext
# An actual process stop, not reconstructing an object in the same JVM.
adb shell am force-stop "$APP_ID"
run_one phaseTwoAfterForceStopPreservesAnswersOnLiveConflict
echo "U06-T04C-02B: separate instrumentation processes, private ciphertext preserved; server 409 enforced."
