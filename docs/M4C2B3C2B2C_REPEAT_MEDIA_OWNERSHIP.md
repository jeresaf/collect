# M4C-2B-3C-2B-2C — Encrypted image ownership for repeat rows

Date: 2026-11-20.  
Android branch: `codex/m4c2b3c2b2c-repeat-media-ownership`.  
Stacked on M4C-2B-3C-2B-2B Draft PR #31.  
Status: **Draft, no native media-answer XML and no submission authority**.

## Scope

The exact server-authorized target-version JavaRosa repeat schema may now
include up to **two direct native image-upload fields per simple top-level
repeat group**, alongside previously supported text/integer/decimal/date
leaves. The native `QuestionDef` must expose `CONTROL_IMAGE_CHOOSE`,
its template `TreeElement` must have `DATATYPE_BINARY`, and every image
path must appear in the operator-reviewed target inventory with the
`MEDIA` and `REPEAT` risks. A repeat must still have at least one
reviewed scalar field; nested or dynamic repeats, conditions, external
resources, non-image uploads and unsupported controls remain **blocked**.

`JavaRosaPrivateRepeatAnswerSession` verifies blank image leaves when
reusing the native first repeat and still validates scalar answers for all
current active UUID rows. No source instance or image bytes enter the
native scratch form.

## Protected image lifecycle

`QuarantinedRepeatImageStore` stores a **new explicitly selected**
JPEG/PNG/WebP image, up to **1,500,000 bytes**, under both:
1. The permanent repeat-row UUID (not a changing JavaRosa position).
2. The exact native-reviewed image-question path.

A file's private name consists of a permanent UUID plus a digest of the
question path, rather than a device filename or public URI. The entire
payload (UUID, group, creation sequence, question, MIME, SHA-256, original
image bytes) is encrypted with the existing non-exportable Android
Keystore AES-256-GCM. The associated data binds the project/actor,
READY work item, original instance, source and target XForm versions and
hashes, newly reserved instanceID, operation/visit identity, permanent
repeat UUID and creation request, question path and complete reviewed
repeat schema. Authenticated reads also check MIME magic and SHA-256
over the recovered bytes.

Import, replace, read and clear execute under the existing shared
**process-local repeat-row identity lock**, and check that the row is
active and the path belongs to its native group. When a row is archived,
its image becomes inaccessible; following rows never inherit it even if
their native ordinal changes. Replacement writes ciphertext to a
temporary private file and atomically replaces the prior image
without a delete-first fallback. There is no unencrypted image, URI,
source attachment or ODK instance file on disk.

## Operator UI

`QuarantinedRepeatImageActivity` is **non-exported** and opened only
from a current active repeat UUID with native-reviewed image fields.
It uses the Android document picker, displays the selected image's
size and abbreviated digest (not bytes), and permits replacement and
removal. Before every open, import, read and clear, the app re-fetches
the authenticated server READY preparation; checks current project,
actor, original source XML/instance, exact target version/hash,
AMENDMENT versus FOLLOW_UP and visit key; and reprobes the JavaRosa
repeat schema. The storage method also checks active encrypted row
ownership under the same in-process lock.

## Explicit restrictions

- This is **staged encrypted media**, not a serialized JavaRosa
  `BinaryData` answer or an ODK `Instance` attachment.
- No old-record image copying, automatic media migration or
  cross-version historical prefill is allowed.
- No repeat-image processing beyond basic signature, size and
  digest validation. Real image decoding, content scanning,
  media previews and transfer-time byte reconciliation are
  separate gates.
- No offline/cross-device editing, cross-process locking, transaction
  across multiple asset files, finalization, ordinary OpenRosa
  upload or dedicated work-item multipart submission.
- Parent native repeat limits remain 3 groups, 12 scalar fields
  per group and 20 active rows per group. Images in nested repeats,
  dynamic form structures and audio/video/file controls are denied.

## Qualification

`.github/workflows/m4c2b3c2b2c-repeat-images.yml` runs all prior
M4A–M4C-2B-3C-2B-2B preservation tests and adds real JavaRosa
mixed scalar/image repeat tests, AES-GCM row-ownership and archive
shift checks, MIME and size protection, account/visit drift,
tamper rejection, and never-upload invariants. It also builds the
branded APK and verifies the application identity. The workflow's
official Maven Central fallback remains limited to an ephemeral CI
checkout, with no committed dependency changes.

## Next milestone and release gates

M4C-2B-3C-3: persist **explicit approved mapping decisions** and
provenance, securely migrate only approved historical values
while preserving original source/revision history.

M4C-2D: create actual JavaRosa-target instance XML and media
references and a dedicated idempotent sender using mandatory
work-item HTTP header, fresh instanceID, amendment-specific
`deprecatedID` and distinct FOLLOW_UP `visitKey`, including
attachment byte integrity and retries.

Actual Android Keystore, real-device picker lifecycle and full
backend E2E are owner-deferred but **not waived**.
No branch merges, production writes, release APK signing or distribution.
