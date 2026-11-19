# M4C-2B-1 — Native JavaRosa verification before versioned editing

Date: 2026-11-20  
Android branch: `codex/m4c2b-javarosa-staging-gates`, stacked on M4C-2A draft PR #22.  
Backend dependency: exact historical XForms from M3C draft PR #6.  
Release status: **DRAFT / editing and submission NOT enabled**.

## The core safety constraint

Native ODK Collect's `FormEntryUseCases.loadDraft()` restores a saved XML
instance into the `FormDef` selected by the draft's form ID + version. It is
not a general cross-version migration engine. Calling it with a different form
version could silently coerce, omit or reinterpret old field values.

M4C-2B-1 introduces `JavaRosaStagingProbe`: the first **native JavaRosa
FormDef parsing + target blank model initialization** for work-item preparation
rather than merely reading XML metadata. It receives exactly the selected
server-authorized `targetXFormXml`, exact historical `sourceXFormXml` if
present, and the M4C-2A mapping plan. Before parsing, it independently checks
the SHA-256 digest of each form definition against the reviewed plan.

The target XForm is actually parsed by ODK's
`FormEntryUseCases.createFormDefFromCacheOrXml()`, wrapped with
`FormEntryController(FormEntryModel(...))`, and initialized with
`FormDef.initialize(true, InstanceInitializationFactory())`. If the archived
original XForm exists, it gets the same separate JavaRosa parsing and blank
initialization; its actual household answers are **never imported**.

## Data boundaries

- The source submitted **instance XML is never written or imported** in this
  milestone. No answer values or media are returned from the validator.
- Temporary XForm definitions are written under an isolated random directory
  within the app-private cache; they are always deleted in a `finally` block,
  even on error.
- No ODK instance repository, draft saving, finalization, submission service,
  external media resolver, project settings or credentials are modified.
- DTD/entity declarations, unsupported external `src=` resources and
  oversized files fail closed, as does mismatched work ID, version or XForm
  digest.
- Native evidence reports only target/historical model initialization,
  matching form hashes, candidate count and operator-review progress.
- `NativeStagingEvidence` structurally rejects any values for
  `canImportAnswers`, `canCreateDraft` or `canSubmit` other than false.
- A new UI label shows whether the native blank target and historical models
  initialized. **This is NOT a claim that the user can edit or submit yet.**

## Workflow and automated qualification

Branch-only Android CI runs M4A, M4B, M4B-2, M4C-1, M4C-2A regression suites
and new `JavaRosaStagingProbeTest` synthetic tests. These run the **real
JavaRosa parser and initialization**, checking cleanup, missing historical
schema, record hash mismatch, operation semantics, human review binding, no
answer leakage and no writable draft. The branded APK is built and package
identity verified. First-party tests use no physical devices or production
records.

## M4C-2B-2 / M4C-2B-3 release blockers

The actual editor integration is still required:
- Create a separate target-version-specific draft only after original source
  ownership + work state are revalidated and the exact target FormDef is
  available in the active ODK project.
- Implement typed answer migration through JavaRosa instance APIs, **only**
  for individually approved paths. No unsafe auto-transfer of changed types,
  choices, repeats, media, calculation or relevance rules.
- Provide explicit human review and approval for renamed fields, orphaned
  repeats and media, recalculations and incomplete mandatory fields.
- Bind source instance ID, new instance ID, target form version, work item,
  approved mapping and attachments in durable encrypted/protected storage.
- Intercept ODK finalize/upload paths to require the
  `X-DataCollector-Work-Item-Id` header and correct amendment `deprecatedID`
  vs independent follow-up identity. Without interception, a generic ODK draft
  must **not** be exposed because it could bypass server work semantics.
- Add real device/backend E2E for logout/project switch, offline/retry,
  attachment integrity, schema updates and upgrade/signing.

Physical tests remain deferred at owner's direction, not waived. No branch
merges, production schema/data changes or APK distribution.
