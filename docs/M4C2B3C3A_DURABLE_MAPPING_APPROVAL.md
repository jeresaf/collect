# M4C-2B-3C-3A — Durable per-path mapping approval and opt-in unchanged-text import

Date: 2026-11-20  
Android branch: `codex/m4c2b3c3a-durable-approved-text-transfer`  
Stacked on M4C-2B-3C-2B-2C Draft PR #32.  
Status: **Draft; local approved, native-validated text-only prefill; not a server mapping authorization or uploadable ODK Instance.**

## Why this change

The earlier historical form review persisted only `reviewedPaths` and the
number of native-validated transfers inside the inert
`QuarantinedWorkDraft` binding. The operator's **actual per-target-path
decisions** (`REENTER`, `LEAVE_EMPTY`, or
`REQUEST_REVIEWED_TRANSFER`) remained volatile. No earlier submitted answer
could safely be restored from those counts.

The app now saves a **complete encrypted, immutable review manifest** for
each staged work item. Every individual decision, including non-transfers,
survives process restart while staying bound to the original form, source
submission, target form version, enumerator and visit identity.

## Operator workflow

1. An enumerator prepares a server-owned READY AMENDMENT or independent
   FOLLOW_UP, reads the target field/risk inventory and explicitly chooses a
   decision for **every** target path. Missing decisions block staging.
2. For an AMENDMENT, the existing
   `ReviewedScalarTransferProbe.validate` independently validates the exact
   old and new JavaRosa XForms and all unchanged same-path **TEXT** transfer
   candidates. Source instanceID and source XML SHA-256 must match server
   evidence. Dynamic choices, numeric/datetime type changes, repeats, media,
   calculations, relevant/constraint and renamed/missing fields cannot be
   copied automatically.
3. After the app reauthorizes READY server work and stages its inert binding,
   it encrypts every operator decision in
   `mapping-approval.aesgcm`. Any subsequent attempt to replace a
   manifest with different decisions **fails closed**; it cannot silently
   re-label an earlier selection.
4. The ordinary protected scalar editor never fills old answers on opening.
   Only for an AMENDMENT with a validated encrypted approval containing
   at least one reviewed text transfer **and no already saved draft** does
   the app show an explicit button:
   **Apply individually approved unchanged old text answers (one time)**.
5. After a second confirmation dialog, the app revalidates current user and
   active project, fetches READY work again to authorize source access,
   checks the original instance, original and target XForm hashes,
   operation and source XML hash, rereads the encrypted approval, then calls
   `ReviewedScalarTransferProbe` to native-validate both form versions.
   `ApprovedHistoricalTextImporter` extracts **only the explicitly
   approved unchanged text leaves** from the transient source response,
   rejecting ambiguous/repeated source paths.
6. The importer validates the complete new target-value map using a fresh
   `JavaRosaPrivateScalarSession`. Every unapproved field remains blank,
   including text fields marked `REENTER` and `LEAVE_EMPTY`.
   `EncryptedPrivateTextDraftStore.saveIfAbsent` refuses to overwrite any
   previously saved work-item answers and writes only AES-256-GCM encrypted
   target-version values, with immediately checked readback.
7. The source XML and source XForm are never cached, passed via Android
   Intent, written to a file, or stored in an ODK Instance. The original
   submission is never modified. The prior new `reservedInstanceId`
   remains reserved but cannot yet be sent.

## Crypto and provenance

`ProtectedMappingApprovalStore` uses the existing Android Keystore
AES-256-GCM key and a distinct versioned AAD purpose, binding:
- Project and authenticated account fingerprint;
- READY work UUID, source submission UUID and source instanceID;
- Target form ID, **exact version** and target version ID;
- Original XML SHA-256 and both source and target XForm SHA-256;
- Work operation (AMENDMENT or FOLLOW_UP), visit key, reserved new
  instanceID, expected review and transfer counts;
- Exact ordered target field inventory, corresponding original field
  path, risk flags and safe scalar proposal verdict.

The encrypted payload includes a local approval UUID, approval timestamp,
complete ordered path/decision inventory and source/target fingerprint
references. It contains **NO survey answer, source XML or XForm body**.
Decryption checks AAD and payload identity and independently revalidates
all approval decisions against the current exact schema.

The manifest documents **the enumerator's review choices**, not an
independent supervisor's legal approval. Server READY authorization is
still mandatory. No application UI or manifest can grant finalization
or submission permission.

## Deliberate restrictions

- Only unchanged, same-path original **text** answers with **zero schema
  risks** can be transferred into a new target-version private draft.
  Fields with changed meaning/type, required/relevant/constraint state,
  select options, media and repeat structures must be manually reentered
  or handled by a future separately approved migration rule.
- FOLLOW_UP observations are always independent and may **never** copy
  source values; they retain their distinct server-issued visit key.
- No automatic import on editor open, no overwrite of any existing draft,
  no implicit source-answer prefill, no source-to-target coercion, no
  renames or guessed row mappings.
- The temporary importer is available only where the restricted native
  scalar editor supports the **entire** target questionnaire. Mixed
  media/repeat/conditional questionnaires need additional projection
  and transactional approval hardening before any old-answer transfer.
- The in-process `saveIfAbsent` lock is not a cross-process or
  cross-device transaction. The latter remains a production gate.
- No server-side approval endpoint, custom supervisor signatures,
  native ODK binary answer serialization, finalization,
  `deprecatedID`, or work-item multipart submission exists yet.

## Test and release gates

`.github/workflows/m4c2b3c3a-approved-text-transfer.yml` runs all previous
Android preservation tests and new real JavaRosa tests for native
same-path text transfer, per-path decision durability, original
hash/version drift, forged decision refusal, no plaintext/cached old
source, tampered approval and **first-save-only** overwrite protection.
It also builds the branded APK and validates the application identity.

Next M4C-2B-3C-3B may add explicit typed/changed-schema migration rules
with stronger provenance and owner sign-off. The current implementation
must not be misrepresented as automatically migrating every record field.

M4C-2D must create final target-version native instance XML and
verified media references and implement the dedicated idempotent
work-item uploader, including amendment-specific `deprecatedID`,
fresh instanceID, distinct FOLLOW_UP visitKey, attachment integrity and
retry/duplicate prevention.

Real Android hardware/Keystore and server end-to-end acceptance are
owner-deferred but **not waived**. All PRs remain Draft. No merges,
production data writes, release APK signing or distribution.
