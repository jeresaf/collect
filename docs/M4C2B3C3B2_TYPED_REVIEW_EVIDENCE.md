# M4C-2B-3C-3B-2 — Encrypted typed review evidence (inert)

Date: 2026-11-20
Status: **PARTIAL IMPLEMENTATION; CI qualification pending**
Android branch: `codex/m4c2b3c3b2-typed-review-evidence`
Stacked on: M4C-2B-3C-3B-1 Draft PR #34, which passed automated CI at `37a5389`.

## Implemented boundary

- `ProtectedTypedReviewStore` records the operator's **per-target-path** `REENTER`, `LEAVE_EMPTY`, and `REQUEST_SEPARATE_RULE_REVIEW` choices in a separately encrypted, versioned, work-bound manifest.
- AES-GCM authenticated data includes project/actor/work, original submission and source-instance identity, reserved new instanceID, exact original XML and both XForm digests, target-version identity, ordered advisory field paths/native types/verdicts and reasons.
- Readback requires the same persisted `QuarantinedWorkDraft` binding and matching exact ordered inventory. A previously recorded different choice or changed inventory fails closed.
- Historical XML, XForms, answer values, media and credentials are not stored in the manifest.
- An advisory `REQUEST_SEPARATE_RULE_REVIEW` is **not** `REQUEST_REVIEWED_TRANSFER`; blocked fields may not request that rule. `canTransfer`, `canFinalize`, `canUpload` are always false.
- This manifest does **not** replace the existing M4C-2B-3C-3A text approval, change record-work preparation, or modify submission plumbing. Follow-up visits cannot use this historical mapping evidence.

## Qualification

Synthetic tests exercise restart/reopen, first-save immutability, disallowed blocked-type rule selection, actor and original XML hash drift, changed field inventory, ciphertext tampering, and impossible submission rights. Dedicated CI includes prior Android preservation tests, exact-version native mapping tests, debug build and package/label check. Never mark PASS until the exact-head workflow completes.

## Still deliberately blocked

1. The separate evidence object is not yet surfaced through an operator decision screen. No second UI confirmation or side-by-side before/after preview is present.
2. The review evidence captures an *operator request*, not supervisor approval, digitally signed rule authority, or server acceptance. Server-side rule registry, expiry, revocation, semantic sign-off and re-check on eventual submission are required for any changed-schema transfer.
3. No old typed answers are read, transformed, copied or stored. Rule-specific JavaRosa value checks, full target-native validation, the first-save-only transfer transaction and typed provenance ledger belong to later slices.
4. Data is protected by encrypted file integrity and an in-process synchronized entrypoint, but independent Android processes/devices can still race. Cross-process locking and backend idempotent/authoritative work completion are release gates.
5. Normal ODK InstancesRepository, generic OpenRosa sender, original server records and production data remain untouched.
6. No physical Android/Keystore tests or real backend end-to-end run has been performed under this milestone; those tests remain **deferred, not waived**.

## Next

Complete 3B-2 with a non-exported native-reviewed operator UI, review summary and explicit confirmation, consuming freshly authorized server READY work and **new** native findings rather than user-injected inventories. Keep copy/send disabled. Run exact-head CI. Only then design 3B-3 source-answer import with a separately authorized same-path scalar rule and an atomic protected first-save boundary.
