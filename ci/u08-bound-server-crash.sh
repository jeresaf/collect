# Sourced only by the disposable connected emulator harness.
# The PostgreSQL trigger stalls acceptance AFTER all multipart writes and work
# completion, while the actual server transaction is still uncommitted.
run_bound_server_crash() {
  run_one boundAttemptPreparesAndPersistsAcrossForceStop "$restart_class"
  adb shell am force-stop "$APP_ID"
  local attempt old_pid send_pid ready=0
  attempt="$(adb exec-out run-as "$APP_ID" cat no_backup/u06-publication-restart.properties | tr -d '\r' | sed -n 's/^boundAttempt=//p')"
  [[ "$attempt" =~ ^[0-9a-f-]{36}$ ]]
  old_pid="$(cat /tmp/u06-t04a-backend.pid)"
  [[ "$old_pid" =~ ^[0-9]+$ ]]
  kill -0 "$old_pid"
  query_crash() {
    psql -tAX -v ON_ERROR_STOP=1 -h 127.0.0.1 -U odk_user -d odk_platform -c "$1"
  }
  python3 ci/u06-t04e06d1-no-commit-oracle.py before /tmp/u08-server-crash-before.json
  psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U odk_user -d odk_platform <<SQL
CREATE FUNCTION u08_ci_pause_acceptance() RETURNS trigger LANGUAGE plpgsql AS \$\$
BEGIN
  IF NEW.attempt_id = '$attempt'::uuid AND NEW.accepted_submission_id IS NOT NULL THEN
    PERFORM pg_sleep(120);
  END IF;
  RETURN NEW;
END;
\$\$;
CREATE TRIGGER u08_ci_pause_acceptance BEFORE UPDATE OF accepted_submission_id
ON protected_submission_attempts FOR EACH ROW EXECUTE FUNCTION u08_ci_pause_acceptance();
SQL
  (run_one boundAttemptSurvivesServerTermination "$restart_class") &
  send_pid=$!
  # A PgSleep inside the exact acceptance UPDATE proves the writer has
  # reached the database transaction's post-media, pre-commit boundary.
  for i in $(seq 1 80); do
    if [[ "$(query_crash "SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='PgSleep' AND query LIKE '%UPDATE protected_submission_attempts%'")" == 1 ]]; then
      ready=1
      break
    fi
    kill -0 "$send_pid" || break
    sleep 0.2
  done
  test "$ready" = 1
  # Assert actual journal markers and payloads exist on disk, rather than
  # assuming the trigger fired after media writes.
  python3 ci/u08-server-crash-evidence.py before-kill
  kill -KILL "$old_pid"
  wait "$send_pid"
  # Terminating a JVM may leave its PostgreSQL backend executing pg_sleep
  # until it observes the disconnected socket. Cancel only the CI-triggered
  # statement, after proving the application JVM is gone.
  test "$(ps -o stat= -p "$old_pid" | cut -c1 || true)" = Z || ! kill -0 "$old_pid" 2>/dev/null
  query_crash "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='PgSleep' AND query LIKE '%UPDATE protected_submission_attempts%'" >/dev/null
  psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U odk_user -d odk_platform <<'SQL'
DROP TRIGGER u08_ci_pause_acceptance ON protected_submission_attempts;
DROP FUNCTION u08_ci_pause_acceptance();
SQL
  python3 ci/u08-server-crash-evidence.py after-kill
  bash ci/u08-start-journal-backend.sh
  test "$(cat /tmp/u06-t04a-backend.pid)" != "$old_pid"
  for i in $(seq 1 60); do
    if python3 ci/u08-server-crash-evidence.py recovered; then break; fi
    sleep 1
  done
  python3 ci/u08-server-crash-evidence.py recovered
  python3 ci/u06-t04e06d1-no-commit-oracle.py after /tmp/u08-server-crash-before.json
  adb shell am force-stop "$APP_ID"
  run_one boundAttemptRemainsAdmissibleAfterInterruptedTransmission "$restart_class"
  adb shell am force-stop "$APP_ID"
  run_one boundAttemptRemainsAdmissibleAfterInterruptedTransmission "$restart_class"
  python3 ci/u06-t04e06d1-no-commit-oracle.py after /tmp/u08-server-crash-before.json
  adb shell am force-stop "$APP_ID"
  run_one boundAttemptSendsExactPackageAndRecoversReceipt "$restart_class"
  adb shell am force-stop "$APP_ID"
  run_one boundReceiptReopensAfterForceStop "$restart_class"
  echo "U08 actual server SIGKILL, durable journal recovery, exact no-commit oracle, explicit retry and receipt PASS."
}
