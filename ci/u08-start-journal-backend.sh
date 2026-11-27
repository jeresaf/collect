#!/usr/bin/env bash
# Disposable connected CI only. Preserve one ephemeral JWT key across JVM restart.
set -euo pipefail
export SPRING_PROFILES_ACTIVE=prod
export ODK_PROTECTED_ATTEMPTS_ENABLED=true
export ODK_PROTECTED_AMENDMENTS_ENABLED=false
if [[ "${U06_ACK_PROFILE:-}" == bound-amendment* ]]; then
  export ODK_PROTECTED_AMENDMENTS_ENABLED=true
fi
export ODK_ATTACHMENT_JOURNAL_ENABLED=true
export ODK_ATTACHMENT_JOURNAL_RECOVERY_ENABLED=true
export ODK_ATTACHMENT_JOURNAL_RECOVERY_DELAY_MS=1000
export SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/odk_platform
export SPRING_DATASOURCE_USERNAME=odk_user
export SPRING_DATASOURCE_PASSWORD=synthetic-ci-only
export STORAGE_LOCAL_PATH=/tmp/u06-t04a-data
export ODK_STORAGE_SUBMISSIONS_ROOT=/tmp/u06-t04a-data/submissions
export ODK_MULTIPART_SPOOL_ENABLED=true
export ODK_MULTIPART_SPOOL_ROOT=/tmp/u06-t04a-multipart
mkdir -p "$STORAGE_LOCAL_PATH" "$ODK_STORAGE_SUBMISSIONS_ROOT" "$ODK_MULTIPART_SPOOL_ROOT"
key=/tmp/u08-disposable-jwt-key
if [[ ! -f "$key" ]]; then
  (umask 077; openssl rand -hex 48 > "$key")
fi
export JWT_SECRET="$(cat "$key")"
jar=/tmp/u08-backend/backend.jar
test -s "$jar"
if [[ -f /tmp/u06-t04a-backend.pid ]] && kill -0 "$(cat /tmp/u06-t04a-backend.pid)" 2>/dev/null; then
  # A zombie is no longer a live server; never replace a running backend.
  test "$(ps -o stat= -p "$(cat /tmp/u06-t04a-backend.pid)" | cut -c1)" = Z
fi
nohup java -jar "$jar" >>/tmp/u06-t04a-backend.log 2>&1 &
echo "$!" >/tmp/u06-t04a-backend.pid
for i in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    exit 0
  fi
  kill -0 "$(cat /tmp/u06-t04a-backend.pid)" || exit 1
  sleep 2
done
exit 1
