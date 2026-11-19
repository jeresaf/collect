# M4 — Android longitudinal record workflows (ODK Collect 2026)

**Implementation status (2026-11-20):** M4A contract guard committed for automated qualification. The record browser, real HTTP client, updated-form editor, and offline visit queue are **NOT implemented**. Physical Android acceptance is intentionally deferred by the owner, not waived.

## Stacked repository dependencies

- Android: `jeresaf/collect` branch `codex/m4-record-work-contract`, based on `codex/odk-2026-app-identity` (draft PR #17), which depends on URL bootstrap (draft PR #16) and the clean ODK Collect v2026.4.0 foundation.
- Backend: `jeresaf/data-collector` branch `codex/m3-longitudinal-workflows` (draft PR #4), whose OpenRosa and PostgreSQL dependencies are themselves stacked and not yet released.
- The configured Android `COLLECT_SERVER_URL` points to an **HTTPS project base** of the form `https://host/v1/projects/<UUID>`. No credentials or security tokens may be included in the APK URL.

## Workflows: do not conflate their meanings

| Concern | AMENDMENT | FOLLOW_UP |
| --- | --- | --- |
| Source | Existing authorized submission | Existing authorized submission |
| Target | A published version of the **same** form | An assigned published form (can be a different questionnaire) |
| New `instanceID` | Required | Required |
| `deprecatedID` | Exact original `instanceID` | **Absent** |
| Outcome | New revision; original audit/history preserved | New observation/visit; Visit 1 not altered |
| Tracking | Server-owned single-use work item | Server-owned single-use work item and stable `visitKey` |
| Submission header | `X-DataCollector-Work-Item-Id` | `X-DataCollector-Work-Item-Id` |

The backend currently restricts source records to those submitted by the **same verified, active App User**, within the active project and assigned form(s). Cross-enumerator visits and shared household assignments are **not** authorized yet.

## M4A committed: policy-only contract

`RecordWorkContracts.kt` defines strictly project-scoped API routes for `GET /records`, `GET /records/{id}/timeline`, `GET/POST /record-work-items`, `GET /record-work-items/{id}/prepare`, and normal `POST /submission`.

It validates the work type, idempotency token and `visitKey`; rejects URL credentials and cross-project paths; and stops a completion plan until the same work ID, source/target versions, explicit field/repeat/choice/media review, JavaRosa target validation and enumerator approval have all been confirmed. It generates a distinct `uuid:` instance ID, applies `deprecatedID` only for AMENDMENT, and exposes the required work-item submission header. These checks are safety **preconditions**, not evidence that the original answer XML can be reused.

**No M4A class performs HTTP, handles passwords, edits XML, imports media, persists work offline, creates forms, or changes the existing ODK main menu.** M4A test mocks/method calls do not constitute network or installed-device evidence.

## M4B next: browse, select, and prepare using existing ODK authentication

1. Add a real authorized transport client using the active ODK project settings and existing auth/network stack. Never build a separate Basic-auth credential store. Keep Authorization restricted to the configured project origin across retries and redirects, with strict redirect-origin enforcement.
2. Build a record list (`GET /records`) and pending visits (`GET /record-work-items`) with loading, failure, revoked-access and empty states. Bind to the active project, not global app state.
3. From a source record, choose **Amend existing** or **New visit**. Fetch assigned published target version options. Create an idempotent work item and obtain `/prepare` using the same project session. The first implementation should be read-only preview until mapping is verified.
4. Preserve `workId`, `sourceInstanceId`, exact source/target form-version IDs and `visitKey` when navigating. No stale response or project switch may re-target the work.
5. Exercise HTTP authorization expiry (401), denied access (403/404), unknown target version, duplicate work (409), mismatched instance/work header and interrupted connection. Never expose raw XML or auth values in logs.

**Backend gap:** the current backend does not yet expose a catalog of selectable published target form-version IDs in the M3 record endpoints. The Android picker must use an authenticated authoritative source (or request a new assigned-versions endpoint), not guess the latest version.

## M4C: updated XForm editing and audited mapping

- Download **both** source XML and the selected exact target XForm XML; never present source XML as if it were a filled newer form.
- Create a mapping manifest keyed by source version, target version and stable question paths. Require explicit mapping for renamed/removed fields, choice value migration, parent/child nested repeats, external lists/administrative hierarchies and attached media; record unmapped source provenance.
- Evaluate target constraints, calculations, relevance and required questions in JavaRosa **under the target XForm**. Newly required fields are answered by the enumerator, not populated with guesses.
- Display old and proposed new values and a required review screen before saving a local draft.
- Preserve the original source submission and its media; store any new draft as an independent, local instance. On upload, include the server work-item header and correct replacement semantics. Retry after lost acknowledgements without manufacturing new work items.
- A correction targeting an old form must remain possible when that version is published/authorized; changes across versions must be explicitly reviewed.

## M4D: offline planned visits and identity

- Cache only scoped authorized work with user/project/version provenance; work-item retrieval should happen online and queued work should remain visibly distinguishable from completed visits.
- Keep a stable **household/subject identity separate** from per-visit `instanceID` and correction revision identity. Backend M3 currently uses the initial source submission as its lineage anchor and does not implement the full household-entity register.
- Make offline drafts recoverable, protect against duplicate completion after reconnection, and handle reassignment/permission revocation and form-version changes before sync.
- Never rely on the device's clock, a free-text address, or matching household names as a stable household identifier.

## Qualification and release boundaries

M4A unit policy tests can run automatically in GitHub Actions. M4B onward requires authenticated HTTP integration tests against the exact backend M3 head on H2 and PostgreSQL, then Android instrumentation/device tests when the owner resumes physical testing.

**DO NOT** merge the stacked draft PRs into main, roll out to field devices, run production DB migrations, or overwrite legacy Kobo/PDMIS packages. Release remains blocked by physical-device qualification, signing/data-preservation evidence, default-admin remediation, real legacy-Flyway upgrade analysis, and exact-head green CI.
