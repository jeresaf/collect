# M4C-2B-3C-3B — Reviewed typed mapping and provenance contract

Date: 2026-11-20
Status: **ENGINEERING CONTRACT ONLY — implementation and qualification NOT STARTED**
Branch: `codex/m4c2b3c3b-typed-mapping-contract`
Parent: `8f26f67e0f8e39bb3613f2df0ab44123938b8602` (M4C-2B-3C-3A, Draft PR #33)
Dependencies: Android Draft PRs #21–#33; backend `jeresaf/data-collector` M3/M3B/M3C Draft PRs #4–#6.

## Purpose

Safely extend the existing **manual, per-target-path**, encrypted approval and unchanged-TEXT migration mechanism to carefully bounded type-compatible changes in an AMENDMENT opened with a newer published form version. This is **not** a general-purpose schema converter and must not permit old answers to leak into FOLLOW_UP work.

## Non-negotiable invariants

1. The original submission and its historical form-version association are immutable. A correction creates a new instance identity and later references the original with `deprecatedID` only in M4C-2D.
2. A follow-up is a new visit: no historical answer import, no `deprecatedID`, a distinct work item and `visitKey`, and independently entered observations.
3. Never silently migrate on form/editor open. Every target path requires a persisted explicit operator decision; a changed-schema rule requires an **independent rule ID**, explanation, before/after preview, explicit second consent, and a stronger authorization gate.
4. Re-check READY work authorization, actor/project/form assignment, source instance and XML SHA-256, exact old/target XForm hashes, target version, work type, visit key and reserved new instance identity **before each use**.
5. Work approval is not server permission to submit. Current `canFinalize=false` and `canUpload=false` remain. The normal ODK InstancesRepository and generic OpenRosa upload must never see quarantine assets.
6. Persist no plaintext old XML, answers, XForms, credentials, or original media. All authorized drafts and approval records remain authenticated encrypted, under private no-backup storage.
7. Any ambiguous source path, multiple candidate nodes, unsupported type, failed native constraint, changed meaning, calculated/relevant/required semantics, altered options or mismatched fingerprint fails closed. No string-truncation, rounding, locale-based coercion, guesswork, unreviewed rename or default answer injection.
8. Existing enumerator edits are never overwritten. Reuse the `saveIfAbsent` first-save semantics, with tamper and replay detection; cross-process/device concurrency remains a release blocker.
9. Backend must validate any server-authoritative mapping rule/approval at issuance **and again at eventual M4C-2D submission**. Offline approval cannot substitute for fresh server authorization.

## Proposed restricted migration rule kinds

Start with strict **identity-safe** conversions; broaden only in subsequent, independently reviewed slices.

| Rule | Eligibility | Explicitly refuse |
| --- | --- | --- |
| `TEXT_UNCHANGED` | Existing 3A same path / native TEXT / no risk | Any change, ambiguity, constraints, repeat/media/select |
| `INTEGER_SAME` | Same canonical path; both native INTEGER; strict 32-bit canonical numeric encoding; no changed native relevance/constraint semantics | Floating-point representation, overflow, changed question meaning, computed value |
| `DECIMAL_SAME` | Same path; native DECIMAL both versions; canonical finite decimal within existing bounded precision and target native constraint | Exponent notation, locale commas, rounding, changed scale/meaning |
| `DATE_SAME` | Same path; both native DATE; strict YYYY-MM-DD and valid Gregorian date; target native constraint | Date-time zone conversions, partial dates, changed interpretation |
| `CHOICE_SAME_CODE` | Same path; static native choice list; exactly same stable code(s) in source and destination; new target options may exist if explicitly reviewed | Relabeling that changes meaning, deleted/renamed codes, dynamic itemsets, duplicate codes, unknown choices |
| `RENAMED_PATH` | **BLOCKED by default** until a server-issued, source/target-version-specific mapping rule with semantic equivalence evidence, provenance and owner authorization is implemented | Similar-looking labels, heuristic match, prefix/suffix changes, field moves into repeats, many-to-one or one-to-many |
| `TYPE_CHANGE` | **BLOCKED by default**, even for apparently castable values, until separately audited conversion policy and explicit server authorization | Any inferred text-to-number, number-to-text, decimal-to-integer, date/time conversions |
| `REPEAT_ROW` / `MEDIA` | **BLOCKED** for historical transfers pending UUID-row ownership, semantic row matching and attachment provenance approval | Position/index matching, source attachment reuse without digest/ownership proof |

Any existing `REENTER` or `LEAVE_EMPTY` decision leaves the corresponding target blank. Missing/contradictory decisions never become implicit permission.

## Proposed versioned rule + approval evidence

Separate the existing 3A local per-path decision manifest from the stronger, optional 3B changed-schema rule attestation. A local operator decision must never be interpreted as a supervisor or server authorization.

A single typed rule record should bind: `ruleId`, `ruleRevision`, `ruleKind`, `sourcePath`, `targetPath`, `sourceNativeType`, `targetNativeType`, `sourceFormId/version/XFormSHA256`, `targetFormId/version/XFormSHA256`, `sourceXmlSHA256`, `sourceSubmissionId/instanceID`, `workId/workType`, `projectId`, `actorId`, `reservedInstanceId`, `visitKey` where relevant, `decision`, `approvalId`, `approvalTimestamp`, target constraint/choice digest, and canonical ordered rule inventory digest. Use authenticated encryption and bind those to 3A work AAD. Do not persist original answer values in approval evidence.

A future backend authority must assign versioned rule IDs and issue time-bound authorization scoped to a published source/target pair and work item. Signature verification and revocation need contract tests; do **not** accept a locally fabricated approval UUID as server authorization.

## Implementation slices (separate commits and tests)

**3B-0 (this branch):** engineering contract and no runtime behavior modifications.

**3B-1:** extend read-only native JavaRosa schema probes and canonical typed-value comparison for same-path INTEGER, DECIMAL, DATE and static choices; classify conflicts without importing answers or changing 3A behavior.

**3B-2:** add complete per-field typed-rule review UI, per-rule provenance ledger and authenticated immutable approval manifest; require a second explicit confirmation. Keep renamed paths and changed types BLOCKED.

**3B-3:** transiently extract only operator- and native-approved same-path typed values, validate entire candidate target map in a fresh JavaRosa target FormDef, and first-save only as encrypted private draft. Preserve old 3A text behavior; prohibit FOLLOW_UP transfer.

**3B-4:** add backend authoritative rule registry / approval API where required, including reauthorization, expiry/revocation and purpose-bound work-item/actor/schema verification. The Android client must reject unavailable or stale server authority instead of silently falling back to local approval for changed-schema transfers.

**3B-5:** regression matrix, security abuse tests, CI exact-head Android build, backend H2+PostgreSQL 15 authorization tests, and documentation of excluded conversion cases. PR remains Draft pending real-device and full backend integration.

## Required tests

- Golden: INTEGER/DECIMAL/DATE/static choice, exact unchanged paths, authorized AMENDMENT, explicit two-step consent, durable approval across restart, encrypted first-save and native readback.
- Deny: FOLLOW_UP historical transfer, wrong actor/project/READY work, revoked assignment, old/target XForm version drift, original XML digest drift, tampered/replayed approval, source path ambiguity, duplicate source nodes, target constraint fail, integer overflow, decimal exponent/precision overflow, invalid calendar day, noncanonical choice code, changed choice meaning, renamed paths, changed type, altered relevance, dynamic itemsets, unsupported repeat/media, stale backend authorization.
- Integrity: no implicit prefill, no draft overwrite, no original record mutation, no plaintext source persistence, no ODK Instance registration, no generic sender access, no release/sign/deploy.
- Acceptance: exact-head independent Android regression + branded `assembleDebug`; backend H2/PostgreSQL 15 HTTP authorization checks once endpoint exists; physical Android/Keystore and real backend end-to-end remain deferred but **release-blocking**.

## Next milestone after 3B

M4C-2D must create a new actual target-version JavaRosa submission XML with validated media binaries, a new instanceID, AMENDMENT-only `deprecatedID`, independent FOLLOW_UP `visitKey`, a dedicated scoped idempotent work-item multipart uploader and retry/attachment digest enforcement. **Do not** unblock 2D until all planned submission authorization and integrity rules are designed and tested.

## Scope and release constraints

All stacked pull requests remain **Draft** and unmerged. No changes to production DB, `main`, signing keys, distribution, device configuration or existing survey records. Physical tests are deferred by the owner, not waived. This contract records the next safe engineering tasks; it does **not** claim implementation or passing tests.
