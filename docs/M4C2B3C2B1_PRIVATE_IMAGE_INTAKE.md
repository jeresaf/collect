# M4C-2B-3C-2B-1 — Protected, field-specific image intake

Date: 2026-11-20.  
Android branch: `codex/m4c2b3c2b1-private-image-intake`, stacked on
M4C-2B-3C-2A Draft PR #28.  
Status: **DRAFT — protected media intake only, not a saved/submitted native ODK media answer**.

## Scope and operator workflow

Repeat groups and attachments cannot safely be enabled together until
repeat-instance identity, media ownership, serialization, transfer semantics
and server upload validation agree. This slice deliberately qualifies the
attachment-intake boundary first; **repeat-group editing remains blocked**.

For work that has already received a complete field-by-field review and a
fresh READY server authorization, operators can now open a separate,
non-exported `QuarantinedImageIntakeActivity` from the protected work
preparation screen. It shows only **native, non-repeating JavaRosa
`image/*` upload fields** recognized from the exact authorized target
version, allows choosing or replacing a NEW image per field using Android's
system document picker, or removing a locally staged image.

Before **every** open/import/read/clear action, the app re-fetches the exact
READY work under the active project/username, verifies source instanceID,
source XML SHA-256, original/target XForm hashes, operation/visit identity
and local private work binding. The target XForm is parsed into a new native
JavaRosa FormDef, then `QuestionDef.CONTROL_IMAGE_CHOOSE`, bound field
reference and `TreeElement.DATATYPE_BINARY` are checked. Repeat and external
itemset XForms are refused for this intake route.

## Encrypted attachment handling

`QuarantinedPrivateImageStore` stages a single *new* JPEG, PNG or WebP
image per authorized target field. Images are bounded to **1,500,000 bytes**,
and at most **four eligible image fields** per work item can be staged.
The system document picker URI is opened immediately with a bounded
InputStream and never retained, logged or saved as a URI permission.

The app validates MIME against magic bytes, computes SHA-256 of original
bytes, writes a typed private envelope and encrypts it with the existing
non-exportable Android Keystore AES-256-GCM key. Associated data binds the
exact project, actor, READY work, old submission/instance, source and target
form versions, source/target SHA-256, new reserved instance ID,
operation/visit key, original review counts and **specific media field path**.
The field-path digest determines a deterministic private filename, so
original user filenames never enter the vault. A replacement is attempted
by atomic rename without deleting the prior ciphertext first.

The store allows authenticated readback with field/path + byte-hash +
file-signature verification, and clears decrypted byte buffers after the
consumer returns. A separate **inert** `ProtectedImageReceipt` exposes
only field, MIME, byte count and digest. It structurally forbids
`canFinalize` and `canSubmit`.

No image bytes, source submission XML, original attachments, target XForm,
credentials or readable media filenames are persisted outside the encrypted
asset slot. The existing `recordwork-drafts` ordinary-OpenRosa upload
denial still covers every asset. A missing/changed source record, target
version, actor, work assignment, MIME signature or AES-GCM tag refuses
operation.

## Deliberate limits and subsequent gates

This is **NOT** a native saved JavaRosa binary answer; a staged receipt is
not a completed instance XML attachment reference. Neither this activity
nor the existing scalar editor exposes finalize/submit.

The following remain **NOT implemented**:
- Repeat-group row creation/edit/delete, stable row identities,
  per-repeat attachment relationships and repeat-version migration.
- Media preview/decoding, resizing, videos/audio, arbitrary file uploads,
  field-level source attachment transfer or byte-safe cross-version copy.
- A schema-aware native ODK binary answer with generated attachment
  filename, media directory and safe instance XML serialization.
- Durable approved old-answer prefill, offline conflict/revocation-safe
  queues, dedicated idempotent work-item multipart uploader and
  AMENDMENT `deprecatedID` / independent FOLLOW_UP submission linkage.
- Real-device URI permission/Keystore tests, actual Android UI E2E,
  server upload compatibility and production-grade media scanning.

Future **M4C-2B-3C-2B-2** must validate repeat-instance identity and
row-specific media mapping (or continue to refuse unsupported repeats).
M4C-2B-3C-3 must persist durable per-path mapping decisions and audit
approved migration. M4C-2D must build a dedicated work-item uploader
with end-to-end byte integrity and retry correctness.

Owner-deferred physical Android tests are **not waived**. No merges,
production-data modifications, APK signing/distribution or production
release are authorized.

## Qualification

`.github/workflows/m4c2b3c2b1-private-image-intake.yml` preserves
the previous M4A–M4C-2B-3C-2A tests and adds real-JavaRosa image
upload-field tests plus synthetic AES-GCM media tests for bounded
streaming, MIME validation, content hash, field isolation, tamper,
recovery, replacement, clear, actor/source/form drift, and
never-finalize/never-upload invariants. The job also compiles the
branded debug APK and checks the application identity.
