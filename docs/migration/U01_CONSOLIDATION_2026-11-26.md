# U01 Android consolidation

Development seed a7740e8f4ead8e2943b0f7a41f7a453e1de0ab36: CI 45771369719 all four profiles PASS. Exact ancestry of all 82 selected PR heads verified against 433 paginated commits after forward-port d6c4ecd0f1d470b501bcdbaaf43294820deeb61f. Legacy ubos-local and master unchanged.

- PR #41 at a80dee503d6a434e100cdb994cb4a92facf186da: preserve its real API 35 Keystore synthetic conflict suite, standalone qualification workflow and documentation; second commit parent retains provenance. Run on consolidated heads; synthetic only, not live backend. Original CI 45163927368 passed 2/2.
- #71: original PR explicitly superseded by #72; failed compilation, not merged. #72 is an ancestor of the seed.
- #73: original PR explicitly prefers #72's per-operation authenticated READY and reviewed-field intake. Current canonical ConfiguredRecordPreparationUiTest.kt retains native JPEG and PNG exact digest/bytes, read-only revisit and unchanged ciphertext, bad MIME rejection (JPEG declared PNG), wrong-field rejection, replacement, clear/missing-asset denial, no upload. Preserve alternative branch and evidence 45411454780; no duplicate probe/unguarded direct store imported. Its PNG-declared-JPEG negative case is added to the canonical intake test.
- Paired backend pin 81ded784256ac288d13cff36bf3b69ba10f42129 is a consolidation candidate, not yet qualified on this head. Retain all four real HTTPS profiles and run the restored offline recovery qualification separately. No production security, trust, source-transfer or retransmission changes.

No historical PRs closed; final integrated CI, owner review, legacy parity, native reference refresh, physical-device and release gates pending.
