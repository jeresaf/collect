# M4C-2A — Historical schema-bound operator mapping review (not editor)

Date: 2026-11-20  
Branch: `codex/m4c2-historical-schema-review` (stacked on Android M4C-1 draft PR #21)  
Backend dependency: M3C `codex/m3c-source-schema-prepare` draft PR #6, stacked on M3B #5 and M3 #4  
Status: Draft, branch Android CI pending.

## Exact version provenance

When `GET /v1/projects/{projectId}/record-work-items/{workId}/prepare`
returns a legacy source XForm, the Android app now compares **three exact objects**:
the original submitted XML (`sourceXml`), the original stored version's
`sourceXFormXml`, and the selected published target `targetXFormXml`.
The server's selected target identity and version are checked against the parsed
target XForm. The historical XForm must itself match the submitted source
instance's `id` and `version`. SHA-256 hashes bind both XForm definitions
to the mapping-review model. No live operator data is copied or saved.

If a historical XForm is absent, all proposed transfers are blocked; there is
**no fallback** to the latest version. An amendment with mismatched source and
target form IDs is also blocked. Independent follow-ups never suggest copying
old answers even when form names and paths look identical.

## Mapping candidates

The new `HistoricalSchemaMappingEngine` reads only form structure, binds and
select/repeat/upload controls, using secure bounded XML parsing followed by
ODK's JavaRosa-backed metadata reader to check exact XForm identity.

Each target field is classified for:
- missing source field or incompatible binding type;
- repeat group or nested repeat context;
- select/select1 with changed values or dynamic itemset;
- media and upload attachments;
- calculated or read-only questions;
- changed relevance, constraint or required binding;
- unbound fields or unsupported relative XForm references.

**Only unambiguous unchanged scalar paths** may be proposed for eventual
transfer; these proposals still require future full JavaRosa FormDef instance
evaluation and explicit operator approval before any answer can be copied.

## On-screen review

After selecting a READY server work item, the app displays a bounded
list of target field paths (max 30 on this initial UI), risk names, old/new
version metadata and removed questions, **without answer values**. Operator
decisions are in-memory only:
- Re-enter answer manually in a future editor;
- Leave target answer empty in a future editor;
- Request a same-path reviewed transfer, only for structurally safe
  scalar candidates.

The review does not create or modify an ODK draft, source submission, media
file, or remote record. Review models and snapshots structurally keep
`canPrefill = false`, `canEdit = false`, and `canSubmit = false`. The
`RecordMappingApproval`/completion gate is not invoked. Every field must
eventually be reviewed; hiding fields beyond the 30-field UI cap can never
satisfy the complete review condition.

## Qualification / remaining work

Synthetic Android tests cover exact historical XForm, absent original, wrong
source version, changed types, choice sets, media, repeats, calculated fields,
cross-form follow-up, malicious DTD, relative paths, hashes, answer-value
leakage and unforgeable upload denial. Existing M4A/M4B/M4B2/M4C1 suites run
as preservation tests; CI also builds the branded debug APK and verifies the
application identity. Backend M3C runs exact-commit H2 and PostgreSQL 15 tests.

**Not completed:** JavaRosa-backed actual editing, transfer execution,
renamed field mapping, orphaned repeats, media downloading/verification,
relevance recalculation, signed mapping manifest persistence, offline queues,
full Android device/emulator E2E and upgrade/signing qualification. Physical
tests remain deferred by owner but mandatory before production.

Do not merge draft PRs, deploy to production, migrate real household records,
or distribute this APK to enumerators.
