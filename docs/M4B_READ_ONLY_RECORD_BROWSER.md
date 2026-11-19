# M4B — Connected Android record and pending-work browser

Date: 2026-11-20  
Branch: `codex/m4b-remote-record-browser`  
Base: `codex/m4-record-work-contract` (M4A draft PR #18)  
Backend: `jeresaf/data-collector` `codex/m3-longitudinal-workflows` (draft PR #4)  

**Status: IMPLEMENTED SOURCE, AUTOMATED QUALIFICATION PENDING. No real Android/backend E2E claim.**

## User-visible change

The branded **Data Collector** app's main menu has **Server records and visits**. It opens a read-only list of records owned by the current App User, and READY server-owned amendment and follow-up work items. The main menu does not show this option on the official ODK application-ID build.

This is **not** a record editor, offline visit downloader or a replacement for ODK's on-device draft and sent-form lists. The browser cannot import source XML, create work, prefill values, edit, finalize or submit.

## Read transport and auth boundaries

- Reuses **ODK project settings** `KEY_SERVER_URL`, `KEY_USERNAME`, and `KEY_PASSWORD`, resolved with an explicit project ID. It has no second credential store, and does not access temporary host-wide credentials belonging to another project.
- Requires `https://<host>/v1/projects/<UUID>`, without URL-embedded credentials, query or fragment. Endpoint routes are built only from the validated project base.
- Uses a dedicated cache-free, redirect-disabled OkHttp client. This prevents forwarding Basic credentials to a redirected origin and avoids storing raw record responses in the HTTP cache.
- Retrieves `GET /records` and `GET /record-work-items`. Uses only explicit 200 + JSON; 301/302/303/307/308 are refused, 401 prompts updating project credentials, 403 is access denied, and other codes fail closed.
- Limits JSON response bodies to 1 MiB and each array to 100 entries, matching the M3 API bounds. Unknown/malformed work types, completed work, missing visit keys and invalid identifiers are rejected.
- No request data or server response body is logged by M4B.
- Records are held in memory only; the UI drops them on stop, rejects a stale response after project/account/URL switch, and does not create any local database changes.

## Contracts

Backend M3 decides ownership and assignment. The Android read layer does not consider having a project URL and password to be proof of authorization: only successful server responses display records.

**AMENDMENT** is a revision of the same form, possibly at a later form version.  
**FOLLOW_UP** is an independent visit/observation, possibly via another assigned form.  
M4A's approval gate remains in place, but the M4B screen **cannot execute either operation**.

## Automated coverage

`RecordWorkReadOnlyClientTest` supplies synthetic OkHttp responses and checks:
- Exact active-project paths with Basic credentials from the current project's ODK settings.
- No network call when the URL or credentials are invalid.
- 401, 403, 302 and 503 errors; no partial list exposure.
- Malformed JSON and oversized bodies fail closed.
- READY and visit-key parsing.

`RecordWorkContractsTest` is retained from M4A. GitHub Actions runs both tests and compiles the branded debug APK. HTTP tests are interceptor-based **synthetic tests**, not evidence that an Android device connected to a real M3 backend.

## Still blocked

1. **Backend version catalog** for picker of same-form newer published versions and assigned follow-up questionnaires.
2. Android M4B-2 authorized **record selection and server work creation** (without editing), plus `/prepare` read-only preview. Exact work ID and target XForm must remain scoped to the active account/project.
3. M4C explicit mapping manifest, JavaRosa target-version validation, repeat/choice/media migration, human review, independent draft creation and safe work-linked upload.
4. M4D offline work queue and durable stable household identity, including cross-enumerator authorization design.
5. **Release blockers:** physical Android tests deferred by owner; full credential/path integration tests with the actual backend; signing and installed-device data preservation; default-admin remediation; Flyway history migration qualification; exact-commit CI must be green.

Leave stacked PRs in **Draft**, do not merge into main/master or deploy to field devices.
