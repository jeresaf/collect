# M4C-2B-3C-2B-2A — Native repeat structure and stable encrypted row identities

Date: 2026-11-20  
Android branch: `codex/m4c2b3c2b2a-repeat-row-identity`  
Parent: M4C-2B-3C-2B-1 Draft PR #29  
Release state: **DRAFT; repeat answers, native ODK form finalization and sending are NOT permitted**.

## Rationale

A JavaRosa repeat's index is **not** a durable row identifier. Archiving a
row changes the ordinal numbers of later active rows. An earlier image or
answer must not subsequently be attached to the wrong person's row.
This slice establishes permanent UUID identities and retained tombstones
before implementing native repeated answers or per-row media transfers.

## Native target schema proof

`JavaRosaPrivateRepeatStructureProbe` reuses the exact, authenticated READY
work and the already reviewed historical/target schemas from earlier
milestones. It initializes a **fresh JavaRosa FormDef** from the authorized
target XForm, checks native top-level `GroupDef.getRepeat()` structures,
bind paths and repeat instance templates, and compares every direct scalar
child to the full explicit mapping inventory.

This slice supports at most three **flat top-level repeat groups**, each with
at most twelve direct plain text, integer, decimal or date questions.
Nested repeats, non-repeat groups mixed in the same repeat-only UI,
unreviewed children, static/dynamic select questions, media, calculations,
relevance, constraints, count/noAddRemove rules, external data,
unsupported controls and forms with more than 30 reviewed repeat fields
are **refused**, not partially interpreted.

The original submission is **never loaded into** the new target
FormDef; no actual JavaRosa repeat row data is instantiated or saved.

## Protected row identity lifecycle

`QuarantinedRepeatRowVault` maintains a single AES-GCM-encrypted row ledger
under `Context.noBackupFilesDir/recordwork-drafts/<project>/<work>/`.
Each row has:
- A permanent UUID independent of its position.
- A native JavaRosa verified top-level repeat-group path.
- A monotonic creation sequence.
- A unique client operation UUID for exactly-once logical create.
- A permanent **archived** state/tombstone with a separate operation UUID.

The private vault holds up to twenty **active** rows per eligible group,
and a bounded total of 120 active plus archived rows across the work item.
It never reuses an archived UUID. An active row's **current index** is derived
by filtering/sorting active rows, never permanently recorded as the row
identity. Repeating an already applied creation or archive request is
idempotent; reusing operation keys across row creation/archival or groups
is denied.

The entire ledger is authenticated-encrypted with the same non-exportable
Android Keystore AES-256-GCM key introduced previously. The associated
data commits to work/source/target instance and form identities, SHA-256s,
the authenticated actor/project, operation/visit key, reserved new instanceID,
review counts, and the full native-probed group path and leaf-type inventory.
Local metadata corruption, wrong server account/work/schema, ciphertext
tampering, invalid paths/UUIDs or ambiguous row histories fail closed.

An atomic file replacement retains the last encrypted inventory on failure;
plaintext is never written to the filesystem. A process-level lock protects
read–modify–write operations across in-process vault instances. These are
private metadata, **not ODK instance records**.

## Operator screen and online authorization

`QuarantinedRepeatRowsActivity` is non-exported and only reachable after
staging the complete reviewed work binding. It shows each authorized group,
the current active row order and permanent UUIDs; it offers **Reserve new
repeat row** and confirmed **Archive repeat row** actions.

Every open, create and archive independently calls the authenticated server
READY work preparation endpoint to verify source instanceID and XML digest,
exact target form version/digest, operation/visit identity and active
account/project; it then reprobes the native repeat structure before reading
or changing the encrypted ledger.

The UI intentionally says these are **row IDs only**, not editable answer
instances.

## Explicitly NOT supported

- **Editing, importing, saving or rendering actual repeated answers.**
  The existing scalar editor still refuses repeat groups.
- **Per-row attachment ownership or media import.** The prior private image
  intake still refuses media inside repeats; images cannot be linked to a row.
- Reordering/reparenting nested repeat rows, row restoration after archive,
  active record XML generation, answer mapping across changed versions.
- Form-finalization, ordinary ODK instance creation, generic upload,
  work-item-specific multipart submission, `deprecatedID` or
  follow-up submission linkage.
- Offline editing, cross-device row synchronization, cross-process update
  locking, Android Keystore device/upgrade acceptance and end-to-end
  backend tests.

Before a complete repeat editor can be enabled, **M4C-2B-3C-2B-2B**
must build a real JavaRosa answer persistence and instance lifecycle
guard with row UUID to current native index mapping and transactional
per-row media association. **M4C-2B-3C-3** must persist approved
old-version mapping decisions and provenance. **M4C-2D** must add
work-aware, idempotent, authenticated submission.

## Automated qualification

`.github/workflows/m4c2b3c2b2a-repeat-rows.yml` runs M4A through
M4C-2B-3C-2B-1 preservation tests, native JavaRosa repeat template and
adversarial schema tests, synthetic encrypted row ledger tests covering
restart/idempotency, deletion-shift identity, 20-row limits, wrong
actor/version, replay-key conflicts and AES-GCM tampering, plus branded
`assembleDebug` and package-identity checks.

The workflow uses Maven Central's **official alternative hostname** in the
ephemeral checkout only, to avoid observed HTTP 429 throttling from the
original hostname; dependency versions and committed Gradle build source
remain unchanged.

Owner-deferred physical Android testing is **not waived**. No production
data, app-signing, public APK distribution, merges or deployment.
