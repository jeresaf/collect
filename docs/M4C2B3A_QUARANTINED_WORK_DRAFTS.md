# M4C-2B-3A — Quarantined work-item draft bindings

Status: **Draft; this is a durable work-binding shell, NOT an editable ODK draft.**
Date: 2026-11-20
Branch: `codex/m4c2b3-quarantined-work-drafts`
Stacked on M4C-2B-2 draft PR #24 and the M4C-2B-1/M4C-2A/M4C-1 chain.
Backend source-version authority: M3C draft PR #6.

## Why this slice is needed

ODK's existing `FormLoaderTask` restores a draft into the *same form version*
recorded for the instance and `SaveFormToDisk` can save or finalize any
registered ODK `Instance`. Native `InstanceSubmitter` routes automatic,
manual and external uploads through `InstanceUploader`; its ordinary
`OpenRosaServerInstanceUploader` sends without the custom
`X-DataCollector-Work-Item-Id` header. Therefore, exposing a prematurely
registered "new version" ODK draft would risk unaudited submission without
the server's authorized amendment/follow-up semantics.

## Implemented: durable but inert vault

`QuarantinedWorkDraftVault` writes an atomic, idempotent project/work-item
binding under the caller's **`Context.noBackupFilesDir`**, inside
`recordwork-drafts/<project>/<work>/binding.properties`. This storage is
app-private and excluded from Android's regular device backup.

The binding stores **no XForm XML, original answers, target answers,
attachments, password or auth token**. It contains only:
- active local project and hash of the active project URL/username context;
- immutable server work ID, source submission ID and source instance ID;
- authorized target form ID/version ID/version string;
- source XML SHA-256 and original/target XForm SHA-256 fingerprints;
- full review count and native-validated eligible text-transfer count;
- operation type, separate `visitKey` for FOLLOW_UP when applicable;
- a reserved **new** `uuid:` instanceID, never the source instanceID;
- an explicit amendment `requiresDeprecatedId` flag, **not** a completed
  amendment link in upload XML.

The stored state is always `QUARANTINED_AWAITING_EDITOR`. All instances of
the model enforce `canOpenEditor=false`, `canFinalize=false` and
`canUpload=false`. Restaging the same unchanged work returns the **same**
reserved ID and does not overwrite the binding. Changed original-answer hash,
target form, project, account context or operator review is rejected. The
manifest has a deterministic SHA-256 integrity check to detect accidental
corruption; **this is not a cryptographic MAC** and is not used as security
authority to submit. Any future editor must reauthenticate and revalidate
against the server on each resume/save/finalize/upload. Only a verified
server-owned READY item can initially stage.

The online client re-fetches the exact server preparation before staging,
checks historical and target form version evidence, and invokes M4C-2B-2
native text validation again for amendments. Follow-ups require an explicit
visit key, full target-field review and **no answer copying**. Operators get
an explicit confirmation that staging reserves only a work-binding shell,
not an editable or uploadable record.

## Implemented: hard denial at old upload paths

`QuarantinedUploadBoundary` refuses any ordinary upload of a file whose
raw or canonical path contains a `recordwork-drafts` segment. It is
called:
1. by generic `InstanceSubmitter.submitInstances`, which covers manual,
   external and scheduled/auto-send submission routes; and
2. by `OpenRosaServerInstanceUploader.uploadOneSubmission` before HTTP HEAD
   and before changing any local instance status.

This defense-in-depth check prevents an accidentally registered quarantined
file from being sent as an ordinary ODK submission. It does **not** create a
custom uploader, bypass existing authentication or weaken the normal ODK
submission path for ordinary non-quarantined instances.

## Tests and limitations

Synthetic tests exercise no-backup manifest creation/restart/idempotence,
cross-project/account isolation, changed review/source hash, corrupted
manifest rejection, distinct follow-up identity, zero answer persistence,
non-forgeable state booleans, raw path traversal, and denial before any
OpenRosa network access. The branch CI also preserves all prior M4A–M4C-2B-2
tests and compiles/verifies the branded debug APK.

**Not yet implemented:** a native editable form, persistence of transferred
answers, full repeat/media/select/numeric migration, durable operator
approvals, reconciliation of form downloads and assignments, `deprecatedID`
in an edited amendment instance, a dedicated scoped work-item uploader with
custom header, idempotent retry/offline replay, reauthentication at every
editor lifecycle boundary, physical Android E2E and production signing.

The reserved new instanceID is **not** a submitted instance identity until
a future guarded editor/sender authorizes and constructs the actual XForm.
Physical-device testing was deferred by the owner, not waived. No merges,
production data, APK release, native draft registration or production deploy.
