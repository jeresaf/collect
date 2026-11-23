#!/usr/bin/env bash
# U06-T04C-02B process-boundary test; invoked inside the API 35 emulator job.
# Uses two separate AndroidJUnitRunner processes, no APK reinstall between them.
set -euo pipefail
APP_ID=org.jeresaf.datacollector.android
CLASS=org.odk.collect.android.recordwork.ConnectedRestartDraftBoundaryTest

./gradlew :collect_app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.odk.collect.android.recordwork.ConnectedBackendTlsSmokeTest,org.odk.collect.android.recordwork.ConnectedRecordDiscoveryTest,org.odk.collect.android.recordwork.ConnectedStaleWorkRecoveryTest,org.odk.collect.android.recordwork.ConnectedRecoveryUiDenialTest \
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
  local klass="${2:-$CLASS}"
  local output
  output="$(adb shell am instrument -w -e class "${klass}#${method}" "$instrumentation")"
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

# A third, separate test process runs only after the earlier read-only,
# admission, encrypted-restart and stale-conflict tests have finished.
# This prevents a newly-created follow-up from changing their expected lists.
run_one independentFollowUpIsCreatedOnceAndOriginalRevisionRemainsImmutable \
  org.odk.collect.android.recordwork.ConnectedFollowUpSubmissionTest
echo "U06-T04C-03A: real Android HTTPS follow-up upload + replay guard: PASS"

# Real ODK first-launch project configuration uses the full CollectTestRule
# device/reset lifecycle. It must run from a fresh application state in an
# independent instrumentation process, not be mixed into the earlier live-API
# probe suite. The preceding tests have already verified and cleaned their
# synthetic private draft and confirmed the backend's durable postconditions
# are checked in the next workflow step.
# The security regression above MUST run with normal Android trust and
# verify our disposable CA is rejected by the production-default policy.
# Only after it succeeds do we temporarily trust CI's ephemeral CA in a
# rebuilt debug APK, so the actual browser Activity can fetch the HTTPS
# fixture using its own project settings. Never modify the release policy.
mkdir -p collect_app/src/debug/res/xml collect_app/src/debug/res/raw
cp /tmp/u06-t04a-tls/ca.crt collect_app/src/debug/res/raw/u06_ci_ephemeral_ca.pem
cat >collect_app/src/debug/res/xml/network_security_config.xml <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
  <base-config cleartextTrafficPermitted="true">
    <trust-anchors>
      <certificates src="@raw/isrgrootx1"/>
      <certificates src="system"/>
      <certificates src="@raw/u06_ci_ephemeral_ca"/>
    </trust-anchors>
  </base-config>
</network-security-config>
XML
test ! -e collect_app/src/main/res/raw/u06_ci_ephemeral_ca.pem
./gradlew :collect_app:installDebug --no-daemon --stacktrace >/tmp/u06-ui-debug-install.log 2>&1 || {
  echo "::error::Ephemeral-CA debug APK rebuild/install failed"
  tail -n 60 /tmp/u06-ui-debug-install.log
  exit 1
}
# The existing AndroidTest APK remains installed. A clean app profile
# avoids carrying stale authenticated context into first-launch UI tests.
adb shell pm clear "$APP_ID" >/dev/null
# Run each first-launch UI test in its own instrumentation process.
# Report only method names and counts; no private runner details.
ui_class=org.odk.collect.android.recordwork.ConfiguredRecordPreparationUiTest
for method in \
  historicalRecordOffersIndependentVisitButNeverAmendment \
  currentRevisionShowsAmendmentChoiceWithoutPreauthorizingSubmission \
  genuineBackendRecordSelectionOpensRealPreparationActivity \
  realBrowserActivityFetchesServerRowsAndOpensHistoricalPrepareActivity \
  supersededRecordCanReviewAuthorizedFollowUpTargetsWithoutCreatingWork
do
  ui_output_file="/tmp/u06-ui-${method}.txt"
  adb shell pm clear "$APP_ID" >/dev/null
  adb shell am instrument -w -e class "${ui_class}#${method}" \
    "$instrumentation" >"$ui_output_file" 2>&1 || true
  if ! grep -Eq '^OK \(1 test\)' "$ui_output_file" ||
     grep -Eq '^FAILURES|^INSTRUMENTATION_FAILED' "$ui_output_file"; then
    echo "::error::Isolated Android first-launch UI method FAILED: ${method}"
    # Emit only exception type and Android/Espresso/application stack methods.
    # Do not print assertion messages containing server response bodies or
    # connection/credential values; raw output stays local to the runner.
    grep -E '^FAILURES|^INSTRUMENTATION_FAILED|^Tests run:|^Time:|^java[.]lang[.](AssertionError|RuntimeException|IllegalStateException)|^androidx[.]test[.]espresso[.][A-Za-z.]+Exception|^[[:space:]]+at (org[.]odk[.]collect[.]android[.]recordwork[.]|androidx[.]test[.]espresso[.]|androidx[.]test[.]core[.]app[.])' "$ui_output_file" | tail -n 35 || true
    exit 1
  fi
  echo "Android first-launch UI method PASS: ${method}"
done
echo "U06-T04D-04A: five independently isolated Android UI tests PASS"
