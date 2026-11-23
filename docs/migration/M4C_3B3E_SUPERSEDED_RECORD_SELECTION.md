# M4C-3B3E — superseded record selection contract (Android, draft)

**Stacked on:** Android Draft PR #39, exact head `52ba6957d1f61ab2f1a3f4851b8df5d913de69d4` ([CI #45152501419 SUCCESS](https://github.com/jeresaf/collect/actions/runs/45152501419)).

**Server dependency:** Backend Draft PR #30 stacked on #29. The backend adds `amendmentSuperseded` to `GET /v1/projects/{projectId}/records` and rejects new `AMENDMENT` work creation against previously archived source submissions, using a source-row lock in a database transaction.

## Status table for client-side selection

| Backend JSON | Android status | New AMENDMENT option | FOLLOW_UP option |
|---|---|---|---|
| `"amendmentSuperseded": false` | CURRENT (point-in-time only) | Shown; server must **still** revalidate/lock on POST | Shown |
| `"amendmentSuperseded": true` | SUPERSEDED | Hidden/disabled; choose a newer revision for correction | Shown, with explicit visit key |
| Missing/null field (legacy API) | UNKNOWN | Hidden/disabled | Shown, subject to server authorization |
| Malformed string or non-Boolean | Invalid response | Neither option until server response is corrected | Neither option until valid response |

The read-only record browser labels each state, but still lists historical submissions for audit and valid independent follow-up. The browser passes only the Boolean **UI hint** to the non-exported work-preparation screen. Without this explicit extra, new AMENDMENT creation is disabled. That screen also blocks AMENDMENT at both version-catalog selection and confirmation. Existing READY work previews retain the server's original ownership/stale verification and do not treat the UI hint as authorization. Backend POST is always the authoritative gate.

## Privacy, data and CI

No previous answer XML or attachment is transmitted by the record browser. Historical records remain owner/project/form assignment restricted. No encrypted old draft is deleted, moved, decrypted, re-targeted, or auto-reused. New FOLLOW_UP remains a separate visit.

Android JVM tests parse the exact new backend Boolean values; they verify UNKNOWN is fail-closed and coercible JSON strings are rejected rather than treated as authorization. Existing read-only-client tests check project-specific credentials, redirects and data limits. Backend PostgreSQL/Flyway tests verify actual HTTP JSON response and work admission. The two suites jointly qualify the contract syntax and decisions; they **do not** constitute an emulator/backend end-to-end run.

## Release blocks

Both PRs are draft/unmerged. Production Android+backend interoperation, full offline sync, typed mapping rule approval, lost-response recovery, signed client APK, migration rehearsal, multi-server concurrency stress and user-deferred physical-device acceptance remain outstanding.
