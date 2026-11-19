# M4C-1 — Cross-version risk assessment (read-only safety slice)

**Date:** 2026-11-20  
**Android branch:** `codex/m4c1-cross-version-assessment`, stacked on M4B-2 draft PR #20.  
**Backend dependency:** M3B draft PR #5 + M3 draft PR #4.  
**Status:** implementation with CI qualification. NOT an editor and NOT a release candidate.

## Rationale

The user requires an enumerator to revisit or correct an existing household record **using a newer XForm** and to use separate questionnaires for independent second/third visits. ODK Collect's normal `loadDraft` logic imports XML into the **same form version**; it is unsafe to use it for arbitrary version changes because renamed/removed fields, constraints, select choices and nested repeats may be silently misinterpreted.

M4C-1 adds a review-only assessment on top of the M4B-2 server `prepare` response without replacing the native ODK editing stack.

## Implemented

- `CrossVersionAssessmentEngine.assess` accepts the exact, server-verified source submission XML and selected target XForm XML. The client checks server work ID/source/target/type/READY state and required mapping approval **before** assessment.
- Both XML documents are parsed with a bounded XML parser: secure-processing on, DTD and external entity loading disabled, XInclude and entity expansion disabled, maximum XML size, depth and field count. Fail closed on malformed/unsupported documents.
- The target also passes ODK's **JavaRosa-backed `FormMetadataParser.readMetadata`**; the parsed target form ID and version must exactly match the authenticated `prepare` response. This is *metadata validation*, **not yet complete JavaRosa FormDef validation**.
- Reports data-minimized impact findings: unchanged XML paths, removed source fields, added target fields, repeats, static/dynamic choices and missing choice values, attachment/media bindings, calculated/readonly fields, relevant/constraint expressions, unsupported bind references, cross-form follow-up and source root mismatch.
- SHA-256 digests bind the transient assessment to the exact source and target XML bytes. No answer values, source XML, XForm XML or media are written to storage, returned in an assessment or shown in the screen.
- The prepare screen displays only field-path/risk summaries (first 25 findings), target version and work metadata. Raw answers remain withheld.
- `canPrefill`, `canEdit` and `canSubmit` are structurally **false**. Copying a report to claim these are true throws. There is no JavaRosa draft import, no upload path and no authentication mutation.

## Important limitations

- A matching path **does not prove compatible semantics**. Old form schema is not available in this endpoint, so the report always flags `SOURCE_SCHEMA_UNAVAILABLE` even if names match.
- This assessment does NOT implement a source/target field mapping manifest approved by operator, rename handling, JavaRosa model evaluation, repeat cloning, choice migration, media replication, `calculate` or `relevant` execution, attachment downloading, or form submission. All remain M4C-2 or later.
- A follow-up is always treated as an **independent observation**; there is no automatic copying of the original household's answers.
- Unsupported bind expressions and unusual relative XPath patterns are flagged, not interpreted. More sophisticated form metadata and repeat/choice analysis must use the actual JavaRosa FormDef and instance tree.
- The server's authorization and app-user assignment must remain authoritative at prepare, draft import and submission; a client-held catalog or hash is not permission.

## Automated qualification

Branch CI runs four independent synthetic Android test classes (M4A contracts, M4B server read, M4B-2 work prepare, M4C-1 impact assessor), compiles a branded debug APK, and checks the package identity. M4C-1 synthetic cases exercise:
- removed/added and unchanged field paths;
- repeated/nested data, invalid static choice, media/calculate/relevance markers;
- cross-form follow-up (no prefill);
- target metadata version/ID mismatch;
- hostile DTD/XXE rejection, payload size caps;
- SHA-256 change detection, and no answer values in the report;
- client prepare integration and schema failures.

**Still outstanding:** actual Android device/emulator integration with M3B backend, full JavaRosa form validation and draft creation, field-migration operator UX, offline queue, signing/data upgrade, production security and Flyway migration qualification. Physical Android tests remain deferred by the owner, not passed or waived.

Do not merge into `master` or distribute to enumerators. All changes remain Draft.
