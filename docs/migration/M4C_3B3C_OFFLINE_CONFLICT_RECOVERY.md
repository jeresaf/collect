# M4C-2B-3C-3B-3C — Android offline conflict recovery (Draft)

**Branch:** `codex/m4c2b3c3b3c-offline-conflict-recovery`  
**Stacked on:** Android M4C native typed-value proof Draft PR #37, green CI #44716860033  
**Server dependency:** `jeresaf/data-collector` Draft PR #29 at `0afb3378`, green CI #45143663235  
**Safety:** No release, APK signing, source-answer transfer, automatic remapping, offline submit, or project deployment.

## Reconnection policy

The backend can respond **409 Conflict** to `GET /v1/projects/{projectId}/record-work-items/{workId}/prepare` when a previously READY **AMENDMENT** has become stale because another revision of the same original submission was committed. The backend uses DB-owned revision timestamps and locks the source row; Android must never assume its cached record is still valid simply because it has a local draft. A completed work item can also return 409.

Existing Android preparation HTTP code maps 409 to `PreparationError.CONFLICT`. Previously, nested authorization paths used `parseOrReject` to replace that exception with generic `BAD_RESPONSE`. This change preserves the typed error and gives the protected UI a safe recovery hint.

| Result | Protected UI behavior | Stored encrypted draft | Auto-retry, retarget, upload |
|---|---|---|---|
| 409 stale/completed | Disable editing and explain a new online source/work review is needed | Leave untouched | **No** |
| 401/403/404 or changed project/account | Disable and require authorized sign-in / assignment review | Leave untouched, never display to foreign actor | **No** |
| Network unavailable | Disable; reconnect and revalidate exact work before saving | Leave untouched | **No** |
| Unexpected/malformed/redirect | Generic denial without exception/body data | Leave untouched | **No** |

`ProtectedWorkRecovery` returns only fixed, non-sensitive text and immutable denial flags. It has no filesystem, credential, historical XML or submission sender dependency. The text, repeat-answer and typed-review screens use it on their existing online authorization failures; no new permissions or sender integration are added.

The protected text/repeat screens **already require fresh online authorization for each save**. This increment does not enable saving changes while offline. A previously persisted AES-GCM local draft is retained on disk when server authority is denied. Unsaved text on a failed save is not guaranteed durable; the UI must not claim it was saved.

## Bounded test matrix

- Pure policy tests: conflict vs network vs access revocation vs invalid/unexpected errors; saved local ciphertext preserved by design, no permission escalation or exception-body leak.
- Mocked HTTP test: `authorizePrivateTextForm` nested READY refresh returns a true typed `PreparationError.CONFLICT` on 409, not generic parse error. It never parses or displays the server's historical-answer body.
- CI: run existing protected editor, mapping, repeat/media, uploader-denial and native proof regressions; compile branded debug APK and verify its `org.jeresaf.datacollector.android` identity.

## Still release-blocking

The user's intended full offline editing needs separately authorized offline persistence/reconnect and a conflict-resolution design. This increment **only** improves safe denial and diagnostics for existing encrypted draft workflows. It does not implement server-issued signed typed mapping rules, ODK `Instance` export, background retries, attach/recover/send semantics, failed-ack reconciliation, anti-replay across devices, offline revocation, actual Android emulator/device E2E, or production upgrade validation.

Any offline draft whose server record changed must be re-reviewed under a *new* authorized work item; the app must not copy its answers or media automatically to a later version.
