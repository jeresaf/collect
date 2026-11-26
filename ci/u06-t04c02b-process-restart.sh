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
    # Emit only the fixed-format recovery diagnostic, not raw output.
    grep -Eo 'U06_RESTART_FAILURE phase=(CHECKPOINT|PERSISTED_PROJECT|PROJECT_RESTORE|ACTOR_SCOPE|BINDING|CIPHERTEXT|LIVE_READY|EDITOR_REOPEN|POSTCONDITIONS) kind=(AssertionError|PreparationException|ReadOnlyRecordException|IllegalStateException|IllegalArgumentException|IOException|SecurityException|OtherException)' <<<"$output" | sort -u || true
    grep -Eo 'U06_MULTIPART_FAILURE phase=(SETUP|DECRYPTED_FRAME|CAPTURE_SETUP|WRONG_DIGEST|SEND|RECEIPT|HEADERS|MULTIPART|REPLAY|REDIRECT|WRONG_RECEIPT|POSTCONDITIONS) kind=(AssertionError|IllegalArgumentException|IllegalStateException|IOException|OtherException) line=-?[0-9]+ origin=(ProtectedMultipartSubmission|ProtectedCommittedDraftStore|ProtectedFinalizedPackageFile|ProtectedSubmissionAdmission|ProtectedNativePackageBuilder|RecordWorkPreparationClient|RecordWorkReadOnlyClient|AndroidKeyStorePrivateDraftCipher|Unknown):-?[0-9]+' <<<"$output" | sort -u || true
    grep -Eo 'U06_NATIVE_POST_FAILURE phase=(SETUP|UNCONFIRMED_UI|CONFIRM_UI|POST|ACK|RECONCILE|RECONCILIATION_INSPECTION|COMPLETION_EVIDENCE|RECOVERED_RECEIPT|REPLAY|ORACLE) line=-?[0-9]+' <<<"$output" | sort -u || true
    grep -Eo 'U06_LOST_ACK_FAILURE phase=(SETUP|POST|REMOTE_COMMIT|ORACLE|UNCONFIRMED_UI|RECOVER|PRESERVE) line=-?[0-9]+' <<<"$output" | sort -u || true
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
  supersededRecordCanReviewAuthorizedFollowUpTargetsWithoutCreatingWork \
  confirmedSecondVisitCreatesExactlyOneReadyWorkWithoutSubmittingObservation \
  serverReadyFollowUpWithoutLocalApprovalCannotOpenProtectedEditor \
  reviewedFollowUpStagesOnlyAfterEveryTargetFieldAndExplicitApproval \
  approvedScalarFollowUpOpensAndSavesNewAnswersWithoutUploadRights
do
  ui_output_file="/tmp/u06-ui-${method}.txt"
  adb shell am force-stop "$APP_ID"
  adb shell pm clear "$APP_ID" >/dev/null
  # Fresh synthetic diagnostic window; no raw logcat is exported.
  adb logcat -c
  adb shell am instrument -w -e class "${ui_class}#${method}" \
    "$instrumentation" >"$ui_output_file" 2>&1 || true
  if ! grep -Eq '^OK \(1 test\)' "$ui_output_file" ||
     grep -Eq '^FAILURES|^INSTRUMENTATION_FAILED' "$ui_output_file"; then
    echo "::error::Isolated Android first-launch UI method FAILED: ${method}"
    # Emit only exception type and Android/Espresso/application stack methods.
    # Do not print assertion messages containing server response bodies or
    # connection/credential values; raw output stays local to the runner.
    grep -E '^FAILURES|^INSTRUMENTATION_FAILED|^Tests run:|^Time:|^java[.]lang[.](AssertionError|RuntimeException|IllegalStateException)|^androidx[.]test[.]espresso[.][A-Za-z.]+Exception|^[[:space:]]+at (org[.]odk[.]collect[.]android[.]recordwork[.]|androidx[.]test[.]espresso[.]|androidx[.]test[.]core[.]app[.])' "$ui_output_file" | tail -n 35 || true
    # Capture only allowlisted phase tokens and exception/stack identities.
    # This test class disables only optional failure screenshots; retain
    # original exception identities and fixed phase tokens, never raw logs.
    adb logcat -d -v brief -s U06_UI_PHASE:I AndroidRuntime:E StrictMode:V TestRunner:I \
      > /tmp/u06-first-launch-logcat.txt 2>&1 || true
    python3 - "$ui_output_file" /tmp/u06-first-launch-logcat.txt <<'PY'
import re, sys
from pathlib import Path
phase = re.compile(r"\b(?:HISTORICAL_(?:TEST_ENTER|PROJECT_CONFIGURED|ACTIVITY_LAUNCHED|INITIAL_ASSERTIONS_PASSED|ACTIVITY_RECREATED|RECREATED_ASSERTIONS_PASSED)|CONFIG_(?:OPEN_MANUAL|INPUT_URL|INPUT_USERNAME|INPUT_PASSWORD|ADD_PROJECT|PROJECT_ADDED)|UI_DIAGNOSTICS_INSTALLED|ESPRESSO_ORIGINAL_FAILURE)\b")
exception = re.compile(r"\b(?:android\.os\.strictmode\.[A-Za-z]+Violation|androidx\.test\.espresso\.[A-Za-z.]+Exception|java\.lang\.(?:AssertionError|RuntimeException|IllegalStateException|IllegalArgumentException|NullPointerException)|org\.junit\.ComparisonFailure)\b")
stack = re.compile(r"\bat ((?:org\.odk\.collect\.(?:android\.(?:recordwork|support)|testshared)|androidx\.test\.(?:espresso|core\.app)|android\.os\.StrictMode)[\w.$]+)\(([\w.$]+\.kt|[\w.$]+\.java):([0-9]+)\)")
for file in sys.argv[1:]:
    output = []
    for line in Path(file).read_text(errors="replace").splitlines():
        if "U06_UI_PHASE" in line:
            output.extend("U06_UI_PHASE " + x.group(0) for x in phase.finditer(line))
        output.extend("U06_UI_EXCEPTION " + x.group(0) for x in exception.finditer(line))
        output.extend("U06_UI_STACK " + ":".join(x.groups()) for x in stack.finditer(line))
    for item in dict.fromkeys(output):
        print(item)
PY
    rm -f /tmp/u06-first-launch-logcat.txt
    exit 1
  fi
  echo "Android first-launch UI method PASS: ${method}"
done
echo "U06-T04D-04A: nine independently isolated Android UI tests PASS"

# Preserve the ninth method's actual saved work and configured project.
# Separate instrumentation invocation after force-stop, without pm clear.
adb shell am force-stop "$APP_ID"
run_one actualEditorRecoversSavedAnswerAfterForceStop \
  org.odk.collect.android.recordwork.ConnectedProtectedEditorRestartTest
echo "U06-T04E-03B: actual protected editor reopened saved answer after force-stop; unchanged ciphertext and READY work."

# Add the conditional target only after all earlier V1/V2 and force-stop
# gates, so their catalogs and preservation assumptions stay identical.
PGPASSWORD=synthetic-ci-only psql -v ON_ERROR_STOP=1 -h 127.0.0.1 \
  -U odk_user -d odk_platform -f ci/u06-t04e03c4-conditional-form.sql
adb shell pm clear "$APP_ID" >/dev/null
run_one reviewedConditionalFollowUpValidatesNativeRelevanceAndCalculations "$ui_class"
echo "U06-T04E-03C4: reviewed conditional target native validation PASS."

# Qualify a repeat-only media form after all existing target catalog gates.
PGPASSWORD=synthetic-ci-only psql -v ON_ERROR_STOP=1 -h 127.0.0.1 \
  -U odk_user -d odk_platform -f ci/u06-t04e03c5c-repeat-image-form.sql
adb shell pm clear "$APP_ID" >/dev/null
run_one reviewedRepeatImagesRetainUuidAcrossArchiveAndOrdinalChange "$ui_class"
echo "U06-T04E-03C5C: repeat UUID image association and ordinal compaction PASS."

# No pm clear, APK reinstall or project recreation between these two phases.
adb shell am force-stop "$APP_ID"
run_one combinedRepeatDraftRecoversAfterForceStop \
  org.odk.collect.android.recordwork.ConnectedProtectedEditorRestartTest
echo "U06-T04E-03C6B: combined repeat answers and images recovered after force-stop; ciphertext and READY work unchanged."

# Seed V5 only after repeat-only force-stop recovery completes.
PGPASSWORD=synthetic-ci-only psql -v ON_ERROR_STOP=1 -h 127.0.0.1 \
  -U odk_user -d odk_platform -f ci/u06-t04e03c6c-mixed-form.sql
adb shell pm clear "$APP_ID" >/dev/null
run_one reviewedMixedDraftRebuildsDirectAndRepeatAnswersAndImages "$ui_class"
echo "U06-T04E-03C6C: direct and repeat scalars and binary references rebuilt together PASS."

# D2: terminate the app within the validated-candidate hook, before pointer publication.
# Never print raw instrumentation output. The next phase requires an actual
# durable pre-kill checkpoint, distinct PID and an abandoned candidate.
adb shell am force-stop "$APP_ID"
restart_class=org.odk.collect.android.recordwork.ConnectedProtectedEditorRestartTest
interrupted="$(timeout 120s adb shell am instrument -w -e class "${restart_class}#terminateProcessBeforeCombinedPublication" "$instrumentation" 2>&1)" || true
if grep -Eq '^OK \(1 test\)|^FAILURES' <<<"$interrupted" || ! grep -Eqi 'Process crashed|INSTRUMENTATION_CODE: 0' <<<"$interrupted"; then
  echo "::error::Expected instrumentation process termination was not observed"
  exit 1
fi
adb shell am force-stop "$APP_ID"
run_one recoverCommittedDraftAndAbandonedCandidateAfterProcessDeath "$restart_class"
echo "U06-T04E-03C6D2: real interrupted publication recovered previous checkpoint, removed marked candidate and retried successfully."

# D3 graphical qualification on the same persisted V5 work after D2 recovery.
adb shell am force-stop "$APP_ID"
run_one mixedChildEditorsSaveAndPublishGraphically "$restart_class"
adb shell am force-stop "$APP_ID"
run_one mixedGraphicalEditorReopensAfterForceStop "$restart_class"
echo "U06-T04E-03C6D3: mixed child UI saves, checkpoint publication and graphical process recovery PASS."

# Competing worker publication and stale working text CAS gate.
adb shell am force-stop "$APP_ID"
run_one competingCheckpointPublicationsRejectStaleWorkingState "$restart_class"
echo "U06-T04E-03C6D3: competing publication and stale working text rejection PASS."

# Finalization eligibility only; no serialization, native Instance or upload.
adb shell am force-stop "$APP_ID"
run_one finalizationEligibilityRequiresCompleteCurrentCommittedNativeModel "$restart_class"
echo "U06-T04E-04A: strict native completeness, current generation and reserved instance identity eligibility PASS."
echo "U06-T04E-04B: native in-memory XML serialization and independent exact-tree validation PASS."
echo "U06-T04E-04C: native attachment filenames, authenticated bytes and exact package reconciliation PASS."

# Finalized envelope publication and real process restart, no pm clear.
adb shell am force-stop "$APP_ID"
run_one persistFinalizedPackageAndRejectCorruption "$restart_class"
adb shell am force-stop "$APP_ID"
run_one finalizedPackageRecoversAfterForceStop "$restart_class"
echo "U06-T04E-04D: encrypted finalized package publication, corruption rejection and actual force-stop recovery PASS."

# Graphical finalize/check/cancel/confirm and actual restarted UI status.
adb shell am force-stop "$APP_ID"
run_one graphicalFinalizationConfirmsCurrentGeneration "$restart_class"
adb shell am force-stop "$APP_ID"
run_one graphicalFinalizedStatusRecoversAfterForceStop "$restart_class"
echo "U06-T04E-04E: graphical finalization confirmation, unsaved/dirty denial and restarted finalized status PASS."

# Fresh authenticated admission only: do not POST the finalized observation.
adb shell am force-stop "$APP_ID"
run_one finalizedSubmissionAdmissionBindsCurrentReadyWorkWithoutSending "$restart_class"
echo "U06-T04E-05A: current finalized package submission admission, stale/corrupt denial and no transmission PASS."

# Captured HTTPS multipart transport only; no actual backend POST yet.
adb shell am force-stop "$APP_ID"
run_one finalizedMultipartUsesExactPersistedBytesWithoutRealPost "$restart_class"
echo "U06-T04E-05B: authenticated finalized multipart bytes, receipt and redirect/duplicate attempt denial PASS."

# Both independent profiles use the same real finalized work and database
# preflight. The baseline preserves every existing acknowledged regression.
export PGPASSWORD=synthetic-ci-only
python3 ci/u06-t04e05c-native-postflight.py before /tmp/u06-native-history.json
adb shell am force-stop "$APP_ID"
case "${U06_ACK_PROFILE:-acknowledged}" in
acknowledged)
  run_one actualFinalizedMultipartCreatesIndependentLinkedObservation "$restart_class"
  adb exec-out run-as "$APP_ID" cat no_backup/u06-native-submission-oracle.json > /tmp/u06-native-submission-oracle.json
  python3 ci/u06-t04e05c-native-postflight.py after /tmp/u06-native-history.json /tmp/u06-native-submission-oracle.json
  adb shell am force-stop "$APP_ID"
  run_one submittedReceiptRecoversAfterForceStopAndRejectsCorruption "$restart_class"
  echo "U06-T04E-05C: actual Android HTTPS finalized submission and independent PostgreSQL exact-byte/link verification PASS."
  echo "U06-T04E-05D: authenticated durable submitted receipt, actual restart, corruption and edit/replay denial PASS."
  echo "U06-T04E-05E: explicit graphical submission, unsaved/cancel/partial-attempt denial, real acknowledged POST and read-only restarted submitted status PASS."
  echo "U06-T04E-06A: protected stale-session text/media/repeat/checkpoint editing denial, partial/receipt/symlink marker preservation and real graphical submission PASS."
  echo "U06-T04E-06B: authenticated READY/linked-record inspection without receipt publication or retransmission, integrity and conflict denial PASS."
  echo "U06-T04E-06C2: authenticated completed-work evidence GET, exact identity and bounded digest inventory, malformed/redirect/READY denial PASS."
  echo "U06-T04E-06C3: exact authenticated local package and remote evidence comparison, recovered receipt publication, corruption denial and restart PASS."
  ;;
lost-response|interrupted-upload)
  # Replace only the disposable TLS front end, not production app/server code.
  # Arm it for the exact synthetic work ID; it refuses all other POSTs.
  work="$(adb exec-out run-as "$APP_ID" cat no_backup/u06-publication-restart.properties | tr -d '\r' | sed -n 's/^work=//p')"
  [[ "$work" =~ ^[0-9a-f-]{36}$ ]]
  event=/tmp/u06-native-lost-ack-event.json
  rm -f "$event" "${event%.json}.tmp"
  docker stop u06-t04a-tls >/dev/null
  python3 ci/u06-t04e06c4-lost-ack-proxy.py "$work" "$event" "${U06_ACK_PROFILE}" >/tmp/u06-native-lost-ack-proxy.log 2>&1 &
  proxy_pid=$!
  restore_tls() {
    kill "$proxy_pid" >/dev/null 2>&1 || true
    wait "$proxy_pid" 2>/dev/null || true
    docker start u06-t04a-tls >/dev/null || true
    rm -f "$event" "${event%.json}.tmp" /tmp/u06-native-lost-ack-proxy.log
  }
  trap restore_tls EXIT
  for i in $(seq 1 20); do
    kill -0 "$proxy_pid"
    if curl --fail --silent --cacert /tmp/u06-t04a-tls/ca.crt \
        --resolve localhost:8443:127.0.0.1 https://localhost:8443/actuator/health | grep -q '"status":"UP"'; then break; fi
    sleep 1
  done
  curl --fail --silent --cacert /tmp/u06-t04a-tls/ca.crt \
    --resolve localhost:8443:127.0.0.1 https://localhost:8443/actuator/health | grep -q '"status":"UP"'
  if [[ "${U06_ACK_PROFILE}" == interrupted-upload ]]; then
    python3 ci/u06-t04e06d1-no-commit-oracle.py before /tmp/u06-interrupted-baseline.json
    run_one actualFinalizedUploadInterruptedBeforeForwarding "$restart_class"
    python3 ci/u06-t04e06d1-no-commit-oracle.py after /tmp/u06-interrupted-baseline.json
    adb shell am force-stop "$APP_ID"
    run_one inspectInterruptedUploadAfterForceStop "$restart_class"
    adb shell am force-stop "$APP_ID"
    run_one inspectInterruptedUploadAfterForceStop "$restart_class"
    python3 ci/u06-t04e06d1-no-commit-oracle.py after /tmp/u06-interrupted-baseline.json
    python3 - "$event" <<'PY'
import json, sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text()) == {
    "posts": 1, "forwarded": 0, "upstream201": 0, "dropped": 1, "partial": 1
}, "Expected exactly one interrupted ingress POST and no forwarding"
PY
    rm -f /tmp/u06-interrupted-baseline.json
    echo "U06-T04E-06D1: interrupted ingress, unchanged PostgreSQL rows, restart and no retransmission PASS."
  else
  run_one actualFinalizedPostLosesResponseWithoutPublishingReceipt "$restart_class"
  check_drop() {
    python3 - "$event" <<'PY'
import json, sys
from pathlib import Path
assert json.loads(Path(sys.argv[1]).read_text()) == {
    "posts": 1, "forwarded": 1, "upstream201": 1, "dropped": 1
}, "Expected one real upstream 201 discarded without another POST"
PY
  }
  check_drop
  adb exec-out run-as "$APP_ID" cat no_backup/u06-native-submission-oracle.json > /tmp/u06-native-submission-oracle.json
  python3 ci/u06-t04e05c-native-postflight.py after /tmp/u06-native-history.json /tmp/u06-native-submission-oracle.json
  echo "U06-T04E-06C4: server committed exact native XML/media; one upstream 201 response discarded before Android receipt."
  adb shell am force-stop "$APP_ID"
  run_one recoverActualLostAcknowledgementAfterForceStop "$restart_class"
  adb shell am force-stop "$APP_ID"
  run_one submittedReceiptRecoversAfterForceStopAndRejectsCorruption "$restart_class"
  check_drop
  python3 ci/u06-t04e05c-native-postflight.py after /tmp/u06-native-history.json /tmp/u06-native-submission-oracle.json
  echo "U06-T04E-06C4: real lost HTTP response, distinct-process exact receipt recovery, second restart and one POST only PASS."
  fi
  restore_tls
  trap - EXIT
  ;;
*)
  echo "::error::Unknown acknowledgement qualification profile"
  exit 1
  ;;
esac
