# First Codex Work Package — ODK 2026 M0

Goal: establish a reproducible, **read-only** legacy feature inventory and a clean, **unmodified** ODK v2026.4.0 baseline before porting source.

1. Open a **separate local checkout** for this task. Do not edit, force-push, rebase or merge `ubos-local`, `master`, `codex/generic-collector-foundation`, or current Data Collector backend.
2. Add/getodk and kobotoolbox remotes. Fetch exact upstream `v2026.4.0` tag, record resolved SHA and compare Kobo-provenance commit ancestry.
3. Read `docs/migration/LEGACY_CHANGED_FILES.csv` and `docs/migration/UPSTREAM_2026_FORWARD_PORT_PLAN.md`. Build an evidence-backed per-feature register that distinguishes fork-inherited and user-originated code. Identify functionality already upstream; do not assume every old patch is needed.
4. Baseline the **clean upstream** ODK build, standard unit tests and APK build under pinned Android/JDK/SDK requirements. Fail rather than hide missing SDK/dependency access.
5. Produce `M0_REPORT.md`, `FEATURE_DECISIONS.csv`, `BUILD_EVIDENCE.md`, `RISK_REGISTER.md`, and `M1_IMPLEMENTATION_PLAN.md` with exact hashes, commands, results, tests and blockers. Mark all inferred findings explicitly.
6. Separately inspect ODK v2025.2+ native local sent-form edits vs our remote record-download/edit workflow and JavaRosa 7 vs backend 4.3.2 contract.
7. **Do not port features, change signing/app ID, modify server, delete data, or build a release candidate in M0.** After the baseline, use one small feature branch/PR per approved capability.

Definition of done: reproducible clean ODK baseline, all 187 legacy changed paths classified, per-feature source mapping, explicit keep/replace/drop decisions, meaningful regression test matrix, and unresolved issues documented.

Repository: https://github.com/jeresaf/collect
Historical SHA: `951dafd3d893003493db3c2de2d7568153889165`
ODK target: `v2026.4.0`
