# M4C-2B-3B — Private, JavaRosa-backed editable text drafts

Date: 2026-11-20  
Android branch: `codex/m4c2b3b-native-private-text-editor` (stacked on M4C-2B-3A Draft PR #25)  
Backend dependency: M3C historical form preparation (Draft PR #6).  
Release state: **DRAFT ONLY — NOT AN ODK finalized/submittable instance**.

## Scope delivered

This milestone introduces the first **actually editable and resumable, target-version-specific**
record-work draft, restricted to **text-only XForms** which JavaRosa can
parse and initialize. It uses a separate, non-exported
`QuarantinedTextDraftActivity` and **does not call** native
`FormUriActivity`, `SaveFormToDisk`, `InstancesRepository.save`,
`finalizeDraft` or `OpenRosaServerInstanceUploader`.

The design uses M4C-2B-3A's existing idempotent, project/work/user-scoped
binding and reserved new instance ID. The existing server work remains in
READY state; local `canFinalize` and `canUpload` remain **false**.

**Key user workflow:**
1. Select a source record and an explicitly authorized new form version,
   classify work as AMENDMENT or independent FOLLOW_UP, and review every field.
2. Stage the work-item binding in the private no-backup vault. AMENDMENT
   requires the real M4C-2B-2 JavaRosa reviewed-transfer proof; FOLLOW_UP
   requires its unique `visitKey` and **no** copied old answers.
3. Use **Open protected text-only editor**. The app re-fetches the server
   work under the currently active ODK project account. It checks READY,
   source instanceID, source XML SHA-256, exact historical/target XForm SHA-256,
   work operation, visit key and the active project/account hash.
4. The private editor builds a **new blank** target FormDef using ODK's
   JavaRosa loader. It shows only fields that resolve to native JavaRosa text
   leaf nodes. Existing encrypted answers (if any) are decoded and validated
   under the same target version before display.
5. The enumerator can enter and revise text. Save reauthorizes the work
   **again** online, recreates a native JavaRosa FormDef, applies all text
   values with `FormDef.setValue(StringData, TreeReference, true)` after
   native constraint checks, then encrypts them with **AES-256 GCM** using a
   non-exportable Android Keystore key.
6. The encrypted `private-answers.aesgcm` is saved atomically beside the
   work binding in `Context.noBackupFilesDir/recordwork-drafts`. After
   app/process restart, reopening fetches the exact form again and restores
   the saved values. Nothing in `files/instances` is changed.

## Deliberate limitations and safeguards

- **Only text-only questions are editable** in this slice. Max 30 target
  fields; max 16,384 Unicode characters per value. Native form type must be
  `Constants.DATATYPE_TEXT`. Repeats, select/select1 and dynamic choices,
  media, numeric/date/geopoint, calculations, relevance/constraints, parent
  actions/unsupported controls, unsupported binds and externally loaded
  resources fail closed. This editor is a simple restricted text UI; it is
  **not** ODK's full native widget presentation.
- **No automatic source answer prefill:** Earlier M4C-2B-2 scalar transfer
  validation is still ephemeral and is proof for stage approval only. For this
  initial editing slice, enumerators explicitly **re-enter** text. Approved
  source-answer prefill into *persisted* versions requires a separate
  fully reviewed and auditable mapping manifest. No silent or unapproved copy.
- **Online-only on open and save.** If the server revokes the task, the
  project/username changes, original data changes or the form version
  changes, the editor refuses to load or save. Offline work is not yet
  supported.
- **AES-GCM associated data** pins project, account hash, work ID, source and
  target form IDs/versions, reserved new instanceID, source/target digests,
  operation and review counts. Ciphertext tampering and wrong binding
  decryption fail closed. Original source XML, XForms and credentials are
  never persisted in the draft. A local SHA-256 binding manifest detects
  accidental changes but is NOT a trusted authority/MAC.
- **No native ODK Instance is registered**. Quarantined path-denial hooks in
  the ordinary submission coordinator and OpenRosa uploader continue to
  prevent accidental generic sends.
- **No finalization/submission buttons**, no server work completion, no
  `deprecatedID` submission XML and no custom work-item header. The
  reserved new ID is metadata only until a future qualified exporter.
- **Required-field completion** is deliberately NOT enforced for incomplete
  saves. Full relevance/constraint/required, media integrity and field
  semantics require the eventual work-aware finalized form engine.
- Editor requests are not exported from the app; no XML or answers are passed
  in Intents, activity state bundles, notifications or logs. Screenshots and
  task-preview capture are restricted with `FLAG_SECURE`.

## Tests and remaining milestones

The branch workflow runs M4A–M4C-2B-3A preservation tests, native JavaRosa
text-entry/change-form/unsafe-widget tests, encrypted save/reopen/tamper/cross-
account regressions and authenticated server source/form/revocation tests.
It also builds the branded debug APK and verifies the application identity.

**Next:** M4C-2B-3C must implement richer JavaRosa widget types, approved
prefill into the persisted model and durable mapping provenance; M4C-2D must
create a dedicated idempotent project-scoped sender that constructs the actual
new instance with correct amendment `deprecatedID` or independent follow-up
identity, attaches validated media and sets the work-item HTTP header.
Offline queues, schema evolution during drafts, real-device E2E, upgrade and
release signing are not yet qualified.

Physical Android tests remain deferred by owner but mandatory for release.
All PRs remain Draft, no production data/migration or APK distribution.
