# M4C-2B-3C-1 — Native JavaRosa typed scalar editing

Date: 2026-11-20  
Repository: `jeresaf/collect`  
Branch: `codex/m4c2b3c1-native-typed-fields` stacked on M4C-2B-3B Draft PR #26.  
Status: **DRAFT / submission prohibited / physical tests deferred, not waived**.

## What changed

The first private editor in M4C-2B-3B supported only manually re-entered
strings. M4C-2B-3C-1 adds **native JavaRosa typed field validation** for:

| Target XForm bind type | JavaRosa class | Input and storage policy |
| --- | --- | --- |
| `string` | `StringData` | Literal text, at most 16,384 characters |
| `int` | `IntegerData` | Signed 32-bit integer, no leading-zero or exponent ambiguity |
| `decimal` | `DecimalData` | Dot decimal, no exponents, finite double, at most 15 significant digits, magnitude at most 10^12 |
| `date` | `DateData` | Strict Gregorian `YYYY-MM-DD`, years 1900 through 2100, local-calendar date |

The new `JavaRosaPrivateScalarSession` builds a fresh exact target-version
`FormDef`, resolves each authorized field path, checks the actual
`TreeElement.dataType`, constructs native typed answers and evaluates
target rules with `FormDef.evaluateConstraint` and
`FormDef.setValue`. It verifies readback, normalizes the saved
representation and destroys the model. A failed validation **does not write
any answers**. Blank answers are valid for incomplete drafts, not
finalization.

The `QuarantinedTextDraftActivity` now renders the right input keyboard for
text, signed integer and decimal fields, plus an ISO date text control. It
retains exactly the previous encrypted save/reopen implementation:
`EncryptedPrivateTextDraftStore` under `Context.noBackupFilesDir` using a
non-exportable Android Keystore AES-256 GCM key and work/project/user/form
associated data. The class names still refer to "Text" for compatibility,
but saved values are now the **validated canonical strings of supported
typed primitives**, never a serialized ODK instance.

## Version compatibility

- The original submission remains immutable. The editor **never**
  calls `loadDraft` on the original submission with the newer form version.
- A changed old-to-new data type can now be **manually re-entered** with the
  target's typed validation, but is **never silently copied or converted**.
- The same source instance ID and SHA-256, target form ID/version and
  SHA-256, server READY work item and active enumerator/project are
  revalidated online on every open and save as in M4C-2B-3B.
- Amendment and independent periodic follow-up remain separate operations.
  Follow-up values are not copied from earlier observations.
- A changed native type or question inventory at save time invalidates
  the open editor session. Ciphertext remains bound to the original
  target-version fingerprint and protected work identity.

## Still blocked

This is **not yet the full ODK form engine**. Explicitly rejected:
select-one/select-multiple, repeat groups, media/attachments,
geolocation, dynamic choices, calculations, conditional relevance,
constraints, external form resources, complex/unsupported control
types, unsupported date-time/time/boolean data types, and forms
with more than 30 target questions.

Because a saved private draft is not finalized, full required-field
validation is not performed. No ODK `Instance` is registered,
no old `deprecatedID` is written, no arbitrary generic OpenRosa
upload is permitted, and no server submission/approval is recorded.

## Qualification

`.github/workflows/m4c2b3c1-native-typed-fields.yml` runs all earlier
preservation and private-encryption tests, new real-JavaRosa typed-field
and rejection tests, branded APK compilation and application identity
checks. Tests use synthetic forms, not identifiable household data.

## Next release gates

M4C-2B-3C-2: native choices, repeats and richer form semantics with
complete operator-approved source mapping and attachment byte integrity.
M4C-2D: dedicated idempotent project-scoped sender with READY server
reauthorization, new instanceID, amendment-specific `deprecatedID`,
independent follow-up and mandatory work-item HTTP header. Later:
offline/revocation safety, Android Keystore/device lifecycle,
real backend E2E, upgrade and signing acceptance.

No merges, no production writes, no APK distribution. Physical
tests remain deferred by owner but release-blocking.
