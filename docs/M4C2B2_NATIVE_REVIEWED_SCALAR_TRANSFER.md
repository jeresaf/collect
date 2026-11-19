# M4C-2B-2 — Reviewed JavaRosa text transfer (ephemeral / no editor)

Date: 2026-11-20. Android branch `codex/m4c2b2-native-scalar-transfer`,
stacked on M4C-2B-1 Draft PR #23. Backend requires M3C Draft PR #6.
This is **not** a production-ready editor.

## Implemented

This slice moves beyond schema comparison by **actually setting approved
string answers in a newly initialized JavaRosa target FormDef**, entirely
in memory. It is deliberately narrower than all possible XLSForm question
types; the engine refuses unqualified types rather than guessing.

The transfer button appears only for an AMENDMENT with both JavaRosa
models initialized and a target field inventory at most 30 questions.
The enumerator must explicitly classify **every target field** as
`REENTER`, `LEAVE_EMPTY`, or `REQUEST_REVIEWED_TRANSFER`.
Only exact unchanged scalar paths may be proposed for transfer.
The engine additionally limits transfers to actual JavaRosa TEXT
nodes; all integer, decimal, repeat, select, media, calculated,
constraint/relevance-changed and other complex cases must be handled
by a later typed migration/editor or manually re-entered.

The client **refetches the complete authenticated READY work item**
on each attempted validation and proves:
- same work item, submitting project, source submission, target version and
  AMENDMENT semantics;
- server-required mapping approval and source `instanceID`;
- identical source XML SHA-256 as the previous preview (so edits/replacements
  made between approval and validation are refused);
- exact historical and target XForm SHA-256 fingerprints, and recomputed
  mapping plan equality;
- every target path reviewed with explicit operator decisions;
- a separately initialized native JavaRosa source and target FormDef.

Only then does `ReviewedScalarTransferProbe` read the source instance
transiently, reject missing or ambiguous/repeated paths, validate
target native node type and constraint, and call JavaRosa
`FormDef.setValue(StringData, TreeReference, true)`. This invokes
target-form calculation/trigger handling instead of directly replacing XML.
A readback verifies the copied text value was preserved, then the entire
in-memory model is discarded.

The resulting `ReviewedTransferEvidence` contains **counts and hashes only**,
never answer values or the staged instance. Source XML, new answers,
household media, login secrets, and record-work metadata are not saved
to the local instance repository or uploaded. The app-private scratch
directory temporarily contains only XForm definitions and is deleted
in a `finally` block. No `instanceID`/`deprecatedID` is assigned in
this milestone.

## Explicit boundary

- `canSaveDraft=false` and `canSubmit=false` are enforced in every
  transfer evidence object; forging a positive value throws.
- An independent FOLLOW_UP is **not an amendment** and never receives a
  copy from the source record under this transfer method.
- When the historical source XForm is missing, ambiguous or changed,
  transfer is refused, never silently falling back to the latest XForm.
- Repeat groups, media and numeric/data-type changes are **not**
  handled by this minimal transfer engine. No misleading "success"
  is reported for untransferred fields.
- All UI review decisions and validation evidence are transient; none
  authorize a generic ODK draft, native form launch or OpenRosa upload.

## Automated qualification

The branch CI runs the preservation suites for M4A through M4C-2B-1,
new real-JavaRosa `ReviewedScalarTransferProbeTest` tests and HTTP
prepare/refetch integration tests in
`RecordWorkPreparationClientTest`, then compiles the branded debug APK
and verifies package/application label. Tests are synthetic and do not
use real household data or a physical device.

## Remaining M4C-2B-3 and M4C-2D blockers

Before enabling an **editable versioned draft**, implement:
- binding of exact target form to the active ODK project + enumerator
  and a protected/durable reviewed mapping manifest;
- typed migration for integer, decimal, boolean, dates, geopoints,
  static/dynamic choices, repeat groups and media with semantic checks;
- versioned local instance creation, collision-safe new `instanceID`,
  amendment-specific `deprecatedID`, and independent follow-up semantics;
- capture of source record and server work ID in durable draft metadata;
- interception of native finalize/submit paths so any work-item draft
  cannot be sent as an ordinary unlinked form and server scope/permissions
  are rechecked on resume/retry;
- attachment byte integrity, offline/retry/revocation safety, UI
  localization/accessibility, upgrade/signing and physical Android
  integration tests.

No merges, production data, server migrations or APK distribution.
Physical Android acceptance remains deferred at the owner's direction
but is NOT waived.
