# M4C-2B-3C-3B-3D — manual conflict review (Android, DRAFT)

**Dependencies:** Android Draft PR #38 exact head `b49f1932b691d78ded59056f46e39070121c6e91` ([CI #45149933196 SUCCESS](https://github.com/jeresaf/collect/actions/runs/45149933196)); backend Draft PR #29 `0afb337887876e12d985d280eb89d846ce469b8f` ([CI #45143663235 SUCCESS](https://github.com/jeresaf/data-collector/actions/runs/45143663235)). All PRs draft/unmerged, no production deployment or signed APK.

## Purpose and permitted user journey

1. An enumerator opens previously staged AMENDMENT work, or a protected FOLLOW_UP, under their active ODK project/account. The application rechecks the server's exact READY work identity on open and on every protected save.
2. If the server returns **409 Conflict** because the source acquired a later correction or work completed, the editor clears its rendered fields and in-memory identities. Already saved AES-GCM private drafts remain on disk under the same work and reserved instance ID. **Unsaved edits may be lost**; never claim otherwise.
3. The safe fixed recovery message offers a **manual** button to review server records. It is **not** shown for 401/403/404, malformed response, or unavailable network: access must be restored or connectivity re-established first.
4. On tapping, the editor rechecks the active project, current username and private work binding; a changed actor/project cannot launch the work's local recovery details.
5. The non-exported, FLAG_SECURE `QuarantinedConflictReviewActivity` reads only the intact actor-scoped `QuarantinedWorkDraftVault` metadata. It displays opaque work/source IDs, the selected target form version, work type and reserved local instance ID, and explains that a prior successful encrypted save remains quarantined. It does **not** read or reveal historical XML, answers, media or ciphertext contents, call an editor/save API, send data or grant permissions.
6. The one action, **Review current server records**, revalidates that same local binding and opens the existing `RecordWorkBrowserActivity` with **no source, target or instance extras**. That browser performs a fresh, authenticated, project-scoped list fetch. An enumerator must explicitly select an appropriate server record and use ordinary server-owned work creation/review to initiate a new task. The browser may show historical records and previously READY items; **it does not guarantee the latest revision**. Server-side stale/source ownership/authorization checks remain mandatory.
7. The old quarantined draft cannot be copied, retargeted, finalized, silently retried or uploaded by this flow. An approved transfer or manual reconciliation into new work requires a separate future scoped server authority and verified native mapping, not this UI.

## Protection and automated checks

- `QuarantinedConflictReview` is a metadata-only Kotlin model with hard-false `canReadDraftAnswers`, `canReadHistoricalAnswers`, `canCopyIntoNewWork`, `canRetarget`, `canFinalize` and `canUpload`. A copy attempt cannot promote any flag.
- `ProtectedWorkRecovery.mayManuallyReviewServerRecords` is true **only** for the stale/completed 409 disposition, never outage or revocation. Navigation is explicit on a user tap.
- No exported activity or intent-supplied answers, media, actor hash, XForm or XML. `onStop` clears sensitive on-screen metadata; `onResume` rereads the private binding under the current actor/project without decrypting any answer.
- Dedicated CI runs `QuarantinedConflictReviewTest`, previous ProtectedWorkRecoveryPolicy and preparation tests, encrypted text/repeat/media preservation tests, and the native mapping tests; builds the branded debug APK and checks app identity.

## Release boundaries

This is a **metadata-only reconciliation entry point**, not functional offline draft upload or a replacement editing flow. It does not unlock stale ciphertext for viewing, create revised instances, perform approved typed answer mapping, handle concurrent device writes or lost transport acknowledgement, reconcile media or establish a signed rule issuer. No emulator/physical-device acceptance has been executed here. The user's deferred device tests remain a release block, not a waived check.

Use the exact latest-head CI outcome before claiming this branch qualified; do not merge or deploy while the release gates remain outstanding.
