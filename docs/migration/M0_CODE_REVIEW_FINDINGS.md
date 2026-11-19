# M0 — Source Code Review Findings and Forward-Port Decisions

**Date:** 2026-11-19  
**Legacy:** `ubos-local` at `951dafd3d893003493db3c2de2d7568153889165`  
**Upstream:** ODK Collect `v2026.4.0` at `d6c4ecd0f1d470b501bcdbaaf43294820deeb61f`

## Findings (static review, not runtime defects certified)

### CRITICAL C01 — Existing entries deleted even after replacement download fails

Source: `collect_app/src/main/java/org/odk/collect/android/entrymanagement/ServerEntryDownloader.java`, method `downloadEntry`.

Observed structure:
- `preExistingEntriesWithSameInstanceId = entriesRepository.getAllByInstanceId(...)` when the remote content hash differs.
- The replacement operation invokes `processOneEntry(...)` inside `try`.
- In `finally`, the code unconditionally iterates `preExistingEntriesWithSameInstanceId` and invokes `entriesRepository.delete(...)`.
- The `finally` body executes even if the replacement download or installation throws an exception.

**Risk:** same-instance records, potentially including local corrections, can be discarded after a failed replacement attempt. Reproduction must use synthetic entries, an intentionally failed network read, and a local unsent edit.

**Decision:** **do not port unchanged.** Download into temporary storage, verify XML/hash/form version/media and authorization, commit replacement/record metadata atomically where possible, and only retire old versions after success with recovery. Preserve unsent edits. Prove failure/cancel/retry/reboot invariants.

### HIGH H01 — Remote entry URL concatenation and query construction

Source: `collect_app/src/main/java/org/odk/collect/android/openrosa/OpenRosaEntrySource.java`, methods `getEntryURL` and `getEntryListURL`.

- The entry-list URL concatenates `?username=` with the raw username without URL encoding. This breaks URL semantics for names containing `&`, `?`, `#`, etc.
- The entry-detail URL manipulates spaces and colons using string replacement and then prepends `serverURL`. This is fragile for existing percent escapes, reserved characters and absolute-versus-relative URLs.

**Decision:** replace with a standard URI builder and server-issued authenticated path semantics; validate path origin, project identity and scoped authorization. Test encoded characters and hostile paths.

### HIGH H02 — Proprietary login API coupled to PDMIS response

Source: `FirstLaunchActivity.kt`, `OpenRosaLoginSource.java`, `LoginDetails.java` and `OpenRosaConstants.kt` in `ubos-local`.

- The legacy client calls `/api/v1/login` and persists returned `api_username`/`api_password` into project settings; admin-unit fields are set directly.
- The Data Collector backend currently uses OpenRosa Basic authentication under project-specific URLs and has different user/role semantics.

**Decision:** standard ODK project authentication by default. PDMIS-style session endpoints must be negotiated and undergo credential-security review, not silently transplanted into the new base. No credentials in build-time URLs.

### MEDIUM M01 — User-specific administrative fields must become generic

Source: `MainMenuActivity.kt`, `ProjectSettingsDialog.kt`, `AdminUnitsTask.java`.

Region/district/subcounty/parish/village values are currently fetched and assigned as PDMIS-specific project settings. Replace with generic project reference/list API metadata; let backend define hierarchy types, validation and assignment scope.

### HIGH H03 — Historical form-version compatibility is mandatory

Source: last legacy commit `951dafd3` and `FormFillingActivity.java`. The remote-entry edit flow may need the exact original form version. Upstream `LocalInstancesUseCases.kt` obtains a form by the original instance's `formId` and `formVersion` for **locally stored** edits. This does not prove support for importing/editing remote server records against historical versions.

**Decision:** integrate with upstream instance history only after verifying exact historical blank-form resolution and roundtrip preservation with real household XForms.

## Verified relevant upstream functionality

- `collect_app/src/main/java/org/odk/collect/android/instancemanagement/LocalInstancesUseCases.kt` includes `editInstance`, using the original form ID and version, and blocks some duplicate edits.
- `collect_app/src/main/java/org/odk/collect/android/formmanagement/finalization/EditedFormFinalizationProcessor.kt` sets `deprecatedID` and regenerates `instanceID` when finalizing a local edit.
- `collect_app/src/main/java/org/odk/collect/android/instancemanagement/send/OpenRosaServerInstanceUploader.kt` handles the modern OpenRosa upload flow.
- `gradle/libs.versions.toml` pins JavaRosa 7.0.0, min SDK 26, Kotlin 2.4.0 and Android Gradle Plugin 9.2.1.
- No exact matching `EntryDownloadListActivity`, `DatabaseEntriesRepository`, `EntryProvider` or `OpenRosaEntrySource` exists in the upstream 2026 source tree. This supports a likely **remote-entry extension gap**, but must be confirmed at the behavioral/API level before implementation.

## Test acceptance matrix to implement

| Test ID | Scenario | Expected |
|---|---|---|
| REMOTE-001 | Download replacement fails with network disconnect | Previous instance and unsent edit preserved byte-for-byte |
| REMOTE-002 | Server sends duplicate instance ID | No silent discard, no unintended duplicate |
| REMOTE-003 | Download cancelled | No modified persisted state |
| REMOTE-004 | Existing remote record uses older form version | Original form ID/version and answer hierarchy preserved |
| REMOTE-005 | Multipart correction upload loses ACK after server commit | Safe retry without duplicate audit/source records |
| REMOTE-006 | Offline edit, reboot, then sync | Local edits survive and revision chain remains correct |
| AUTH-001 | Username special characters in remote listing | URL is correctly encoded and scoped |
| AUTH-002 | Unauthorized cross-project record URL | Request rejected, no alternate-host fetch |
| AUTH-003 | Project access revoked mid-session | No new data access; local retention follows explicit policy |
| XFORM-001 | PDMIS household repeats, ages and cascading villages | Cross-engine semantics match approved test vectors |
| XFORM-002 | Farm form nested repeats, media and external lookups | No data loss; JavaRosa 4.3.2 vs 7 behavior recorded |

## State

These are **evidence-backed source observations**, not proof that the current legacy APK fails every listed scenario. No destructive test has been executed and no deployed data has been touched.
