#!/usr/bin/env bash
# Retry only explicit dependency transport failures, never failed assertions.
set -euo pipefail
log=$(mktemp)
trap 'rm -f "$log"' EXIT
for attempt in 1 2 3; do
    set +e
    ./gradlew "$@" 2>&1 | tee "$log"
    rc=${PIPESTATUS[0]}
    set -e
    if [[ "$rc" == 0 ]]; then exit 0; fi
    if [[ "$attempt" == 3 ]] || ! grep -Eq 'Received status code 429|HTTP[^[:alnum:]]*429|429 Too Many Requests' "$log"; then
        exit "$rc"
    fi
    # Only retry build dependency acquisition, not a completed assertion suite.
    if grep -Eq ' [0-9]+ tests completed, [1-9][0-9]* failed| > .* FAILED$' "$log"; then
        exit "$rc"
    fi
    echo "Dependency server rate limit; bounded retry $attempt/3"
    sleep "$((attempt * 45))"
done
