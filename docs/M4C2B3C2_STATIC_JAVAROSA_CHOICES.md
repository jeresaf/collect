# M4C-2B-3C-2A — native static selection questions (restricted)

Date: 2026-11-20  
Android branch: `codex/m4c2b3c2-static-native-choices`, stacked on M4C-2B-3C-1 / Draft PR #27.  
State: **Draft, not full ODK editing, not ready to submit**.

## Implemented

The protected private editor now handles six JavaRosa target primitives: text,
integer, decimal, ISO date, **static select-one** and **static select-multiple**.

The new choice pathway:
1. Fetches server-owned READY work using the active ODK project credentials
   and verifies unchanged source instance/hash, target XForm version/hash,
   operation/visit identity and user/project context **at every open and save**.
2. Builds a blank **current target-version JavaRosa FormDef**, without loading
   original submission XML into the newer form.
3. Walks native `QuestionDef` controls, checks the exact bound target path,
   unique question inventory, **native control type** (`CONTROL_SELECT_ONE` /
   `CONTROL_SELECT_MULTI`), native answer type
   (`DATATYPE_CHOICE` / `DATATYPE_MULTIPLE_ITEMS`) and the native
   `SelectChoice` list. Duplicate question references, duplicate/unsafe
   values, missing/oversized lists (>40 items) and unsupported controls
   fail closed. Externally driven itemsets remain blocked.
4. Renders a select-one dropdown with an explicit incomplete-draft
   "no answer" option, or select-multiple checkboxes. The list comes from
   the current JavaRosa form's static choices. If localization of a
   choice label is unavailable, the stored choice code is shown rather
   than guessing a translated caption.
5. Applies manually chosen values to a **fresh native FormDef** using
   `SelectOneData(Selection(...))` or
   `SelectMultiData(List<Selection>)`, checks target JavaRosa rules,
   invokes `FormDef.setValue` and verifies readback before returning
   canonical values for the existing AES-GCM private draft store.
   Select-multiple values are canonicalized in **current form option order**,
   space-separated as required by XForms; unknown, repeated or malformed
   tokens are rejected.
6. Saves and reopens under the prior project/user/work/form-associated
   AES-256-GCM Android Keystore ciphertext at
   `Context.noBackupFilesDir/recordwork-drafts/<project>/<work>`.
   No original XML, XForm, credentials or plaintext answers are persisted
   in the private binding. The ordinary ODK uploader is still blocked.

### Updating an older version

The prior form may have offered a different list of choices. The enumerator
**manually selects from the new target form**, and removed old values
are rejected on read or save. No prior selection is silently copied,
relabelled or mapped. Earlier M4C-2B-2 scalar transfer evidence stays
ephemeral, and no new reviewed old-answer transfer is enabled here.

Independent FOLLOW_UP remains its own visit observation with unique
`visitKey`, never an amendment of the earlier record.

### Remaining restrictions

Dynamic/cascading selects (`itemset`), repeats, media and attachments,
geolocation, calculated/conditional values, externally loaded datasets,
complex ODK widgets, language/appearance-specific widgets, approved
cross-version answer prefill, offline work, and form submission remain
**not implemented**. Full localization-aware native choice rendering and
complete production questionnaire coverage require more work.

This is **not** a native ODK `Instance`; no finalization, `deprecatedID`
XML, server work-item header, upload, or production data mutation occurs.

## Qualification

`.github/workflows/m4c2b3c2-native-static-choices.yml` preserves earlier
M4A–M4C-2B-3C-1 tests, adds real-JavaRosa select-one/multiple tests
(including changed lists, unknown/duplicate values, malformed separators,
dynamic/repeat/media rejection, target drift and independent follow-ups),
and builds/verifies the branded Android debug APK and app identity.
The synthetic tests do **not** establish real-device/Keystore acceptance.

## Next gates

M4C-2B-3C-2B: repeat groups and attachments require separately reviewed
per-repeat identity, lifecycle, filenames, media integrity and permissions.
M4C-2B-3C-3: durable approved cross-version mapping and field-specific
prefill, including complex/question-type migrations.
M4C-2D: dedicated server-bound idempotent upload with mandatory work-item
header, new instanceID and correct amendment `deprecatedID`, independent
follow-up observation, retry and attachment semantics.

Owner-deferred physical Android tests, security review, device migration
and production acceptance remain mandatory. All PRs remain Draft. No
merges, APK release/distribution or production data writes.
