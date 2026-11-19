# M4B-2 — Select eligible version, create server work, preview safely

**Date:** 2026-11-20  
**Status:** implementation; exact-commit automated qualification required. Physical Android acceptance deferred, not waived.  
**Android branch:** `codex/m4b2-record-selection-prepare`, stacked on M4B draft PR #19.  
**Backend dependency:** `jeresaf/data-collector` M3B draft PR #5, stacked on M3 draft PR #4.  

## Implemented

- The branded app's read-only **Server records and visits** browser now has source-record and pending-work selection buttons.
- From a source record, the enumerator chooses **Amend existing record** or **Schedule independent follow-up visit**.
- The target picker calls the new M3B authorized version catalog, rather than inventing a target form-version ID. It restricts amendment candidates to same-form versions; follow-ups may select different assigned published forms.
- A follow-up requires an enumerator-provided, stable `visitKey`. At this stage no `plannedFor` scheduler or automatic offline reminder is shown.
- After explicit confirmation, the app `POST`s a single server-owned work request with a unique `clientRequestId` and selected published target version. The in-memory client request key is retained for retries of the same selection within the activity and across saved activity state; an edit to the selection creates a distinct key. The backend also checks work-item idempotency and visit-key uniqueness.
- The app loads `GET /record-work-items/{id}/prepare` and validates the server work ID, original source, target version, workflow, READY state, nonempty source XForm/target XForm and `mappingApprovalRequired=true`.
- The preview shows **only work metadata and a warning that editing is disabled**. Although the preparation response contains source XML and exact target XForm XML, M4B-2 neither logs, displays nor stores those raw XML documents; the in-memory HTTP response is not retained in the activity.
- Existing READY work items can be opened in the same read-only preview.
- The source/project/user active session is rechecked after asynchronous results. No ODK draft/instance store, source record or attachments are changed by this client.

## Security constraints

- HTTPS only, scoped to active ODK project URL `/v1/projects/{UUID}` and explicitly selected source/work IDs.
- Reuse exactly that project's existing ODK username/password; no URL secrets, secondary password store, host-wide temporary credential leakage, cookie forwarding or HTTP caching.
- Redirects disabled, including across hosts; content-type and bounded response parsing; explicit handling of 401, 403, 404, 409 and other errors. The server remains authoritative for active App User, record ownership, assigned published target form and work state.
- The UI may create a durable **READY** work item but **cannot use it to edit/submit**. No automatic acceptance of revised XForm mappings.
- If the response is interrupted after the server creates work, the user should reopen the pending-work browser rather than attempt duplicate visits using new keys. A persisted/idempotent offline action queue is a later milestone.

## Test plan / nonclaims

- Synthetic HTTP unit tests cover catalog selection, cross-form amendment denial, work-creation identifiers and headers, PREPARE metadata binding, redirect refusal and non-200 responses.
- Previous M4A domain/approval-gate and M4B record-browser regression tests run in the same CI.
- Android CI must also compile the debug APK and inspect package identity. CI builds use a deliberately fake HTTPS project; a successful build/test **does not** prove real HTTPS server integration or physical device behavior.
- Backend M3B has its own real HTTP H2 and PostgreSQL 15 tests.
- **Release blockers:** end-to-end Android-on-backend test, actual Android/emulator acceptance (deferred), full M4C JavaRosa mapping review (including nested repeats, choices, media and schema changes), M4D offline repeat work, signature/upgrade preservation, backend default-admin remediation and legacy Flyway upgrade evidence.

## Next: M4C

Build a versioned source-to-target mapping manifest and a JavaRosa-backed editor that only imports mapped values after explicit source/target/repeat/choice/media review, target-XForm recalculation and enumerator approval. Amendments must use new `instanceID` with original `deprecatedID`; follow-ups must be independent instances with **no** `deprecatedID`. The server work-item header must accompany uploads and failed acknowledgements must be retried idempotently.

No production migration, no merging draft PRs into main/master and no distribution of this APK to field enumerators at this point.
