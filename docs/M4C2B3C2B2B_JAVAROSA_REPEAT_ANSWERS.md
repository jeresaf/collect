# M4C-2B-3C-2B-2B — JavaRosa-backed encrypted repeat answers

Date: 2026-11-20  
Android branch: `codex/m4c2b3c2b2b-repeat-answer-drafts`  
Parent: M4C-2B-3C-2B-2A Draft PR #30  
State: **DRAFT; repeated-answer editing only; no ODK finalized or submittable instance**.

## Actual editing scope

The previous milestone created stable UUIDs and tombstones for explicitly
reviewed repeat rows, without answers. This milestone adds **editable
text, integer, decimal and ISO-date answers in those rows**, retaining
independent `FOLLOW_UP` versus `AMENDMENT` work identities.

A **non-exported** `QuarantinedRepeatAnswerActivity` is opened for one
active permanent row UUID from the existing repeat-row manager. The app
does not put household answers, original submission XML, XForms or
credentials into the navigation Intent or Android activity state.

Before every open and save:
1. Recheck active ODK project and configured enumerator account.
2. Fetch authenticated server READY work and exact source
   submission/instanceID, source XML hash, archived source XForm (where
   applicable), target XForm version and hash, operation and visit key.
3. Rebuild and validate the exact **native JavaRosa** repeat schema.
4. Read the encrypted stable-UUID row identity inventory; reject removed,
   archived, foreign, unreviewed and version-mismatched rows.

`JavaRosaPrivateRepeatAnswerSession` creates a completely fresh JavaRosa
`FormDef` of the exact target version, resolves native repeat templates,
and **copies one native template for every currently active UUID row in
that group's current ordinal order**. It then maps each reviewed child
path to its current JavaRosa multiplicity, checks the leaf's actual
`TreeElement.dataType`, and converts raw input into JavaRosa
`StringData`, `IntegerData`, `DecimalData` or `DateData`.
For nonblank answers it calls `evaluateConstraint` then `setValue`,
verifies native readback, and returns canonical validated strings.
Incomplete drafts may contain blank answers, **not** finalizable records.

**The rebuilt native FormDef is discarded, never saved to the ODK
InstancesRepository, serialized as submission XML or sent.** Old source
answers are not silently copied, imported or coerced into the new version.

## Per-row encrypted persistence

`QuarantinedRepeatAnswerStore` writes a separate
`repeat-answer-<permanent-row-UUID>.aesgcm` under app-private
`Context.noBackupFilesDir/recordwork-drafts/<project>/<work>`.

Data is authenticated-encrypted using the established non-exportable
Android Keystore AES-256-GCM key. AAD binds project, enumerator context,
READY work, source/target form IDs/versions and SHA-256s,
original source instance, newly reserved instanceID, operation/visit
identity and **the permanent repeat-row UUID and creation request**.
The exact native group-path/leaf-type inventory also participates in
AAD; a changed target type/schema refuses reopening.

**The current repeat ordinal is NOT stored as identity.** The single
file's canonical answers are keyed by their direct, reviewed native field
paths. Save/reopen reconstructs and JavaRosa-validates all active rows
under the current order; it cannot accidentally promote an archived
row's data into another row after an earlier row is removed. Archived
rows are denied for both read and save. Their original encrypted data
remains inaccessible under its archived UUID pending an explicit audited
retention/erasure policy. The last ciphertext is retained if atomic
replacement fails.

The existing row-vault process lock serializes row create/archive and
answer save/read in the same app process. **Cross-process coordination,
cross-device synchronization and transactionally atomic row+media updates
are not qualified**, so those use cases remain release blockers.

No plaintext answers, submission XML, media file paths or original
attachment bytes are written to the ODK instances directory.

## Restrictions and next steps

The scope continues to be the narrow repeat structure qualified in
M4C-2B-3C-2B-2A: up to three flat top-level repeat groups with up to
twelve directly bound **text/integer/decimal/date** questions each,
no mixed unsupported group structure, no calculations, conditional
relevance/constraints, selects, media, nested repeats or external data.
JavaRosa may initialize exactly one *blank* native repeat at ordinal
zero. This is reused for the first active UUID row only after checking its
entire child inventory, exact native types, and that every answer is null.
Multiple initial rows, unknown fields or populated/default answers are
refused rather than silently imported.

A repeat-row editor **does not render full native ODK widgets**. It
uses separate text/number/ISO-date inputs plus strict native validation.
Required-field completeness is deferred to the eventual finalization
gate. Full old-version repeated-answer migration is NOT implemented.

**Next M4C-2B-3C-2B-2C:** per-row encrypted attachment ownership,
actual JavaRosa binary answer and media reference creation,
cross-process/migration/retention hardening and repeat-specific
relevance/choice/widget behavior.

**M4C-2B-3C-3:** explicit operator-approved mapping manifest and typed
cross-version answer prefill with evidence and provenance.

**M4C-2D:** dedicated server work-item-aware, idempotent multipart sender
with fresh target instanceID, `deprecatedID` for amendments, separate
`visitKey` observations for follow-ups, media and retry integrity.
Ordinary OpenRosa submission routes must never bypass these gates.

## Qualification

`.github/workflows/m4c2b3c2b2b-repeat-answers.yml` preserves M4A
through M4C-2B-3C-2B-2A tests and adds synthetic JavaRosa-backed
repeat-data tests for encrypted save/reopen, invalid typed answers,
old-row deletion/ordinal shift, archived-row denial, foreign
actor/work/form rejection and ciphertext tampering. It also builds
the branded debug APK and checks the application identity.

Real Android device/Keystore, full backend multipart E2E and production
acceptance remain owner-deferred but **not waived**.
All PRs remain Draft; no production data, release APK distribution,
merges or signing are authorized.
