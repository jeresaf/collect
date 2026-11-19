# M0 — Initial Upstream Source Crosswalk

**Date:** 2026-11-19  
**Target:** official getodk/collect `v2026.4.0` at `d6c4ecd0f1d470b501bcdbaaf43294820deeb61f`  
**Historical source:** `jeresaf/collect` `ubos-local` at `951dafd3d893003493db3c2de2d7568153889165`  
**Comparison:** `v2023.2...ubos-local` (historical branch, not assumed official ODK release).  
**Status:** source-tree inventory; no compilation, device verification, or correctness determination.

## Verified results

- Historical change set: **187** files across **66** commits (**0** added, **0** modified).
- **70** historical file paths exist unchanged as *paths* in upstream 2026. This does **not** imply matching contents.
- **4** historical file paths no longer exist; another file has the same basename at a different path. These are *unverified* rename candidates only.
- **113** historical paths have no exact match and no same-basename candidate in the modern tree. Many belong to custom server-remote record editing; others disappeared because upstream's modules and Gradle structure changed.
- The source inventory covers **3628** total upstream tree entries and is not truncated.

## Verified upstream build differences

- Official `gradle/libs.versions.toml`: Android compile SDK **37**, min SDK **26** (Android 8.0), target SDK **36**, Kotlin **2.4.0**, AGP **9.2.1**, JavaRosa **7.0.0**.
- `collect_app/build.gradle` uses the modern plugin and build configuration. Existing `ubos-local` build patches against the earlier Gradle environment must not be carried forward unchanged.
- Current `ubos-local` hardcodes PDMIS project URL in `collect_app/src/main/java/org/odk/collect/android/preferences/Defaults.kt`; clean upstream uses a demo-server default.
- Official release notes include current remote form-download improvements, JavaRosa changes to nested repeats and time handling, and bug fixes around editing forms. These are reasons to compare behavior before porting custom patches.

## Feature-level initial decisions and proof requirements

| Feature | Current source comparison | Work needed |
|---|---|---|
| F01 Login and first-run UI | Legacy login implementation is not present at matching source paths. Upstream uses standard project configuration/authentication. | Compare ODK login contract with Data Collector and PDMIS; keep one standard mode with optional capabilities. |
| F02 URL configuration | Defaults and Gradle exist upstream; a legacy hardcoded PDMIS URL must not be copied. | Implement validated `-PCOLLECT_SERVER_URL` on a dedicated branch, and test first-run provisioning. |
| F03 Remote entries | `EntryDownloadListActivity.java`, `OpenRosaEntrySource.java`, `ServerEntryDownloader.java` absent by exact path and basename. | Design remote-record extension and server authorization; do not confuse with upstream local sent-form editing. |
| F04 Local entry database | `DatabaseEntriesRepository.java` and `EntryProvider.java` absent by exact path and basename. | Modern ODK storage must be respected; design migration rather than copy old DB tables. |
| F05/F11 Corrections, resubmit | ODK 2026 has `EditedFormFinalizationProcessor.kt` and `OpenRosaServerInstanceUploader.kt`; legacy modules differ. | Test instanceId/deprecatedId, source/revision, reattempt, attachments, audit and permissions. |
| F06 Historical versions | Legacy last commit addresses older form versions; upstream behavior not yet verified. | Regression with actual PDMIS XForm revisions, retained old blank forms, and replay. |
| F07 Repeats | Upstream JavaRosa 7.0.0 changes nested repeats; legacy quick-add fixes must be independently tested. | Test nested and counted repeats with PDMIS and farm form fixtures. |
| F09 Admin units | Legacy refresh and PDMIS units are specific to existing server. | Generic reference-data API, capability detection, and revocation behavior. |
| F10 OpenRosa transport | Modern ODK has a separate `open-rosa` module; legacy paths obsolete. | Implement contract tests against Data Collector's standard endpoints. |
| F12/F14 UI and branding | Upstream layout/resource structure has changed. | Configure variant app ID/icon/name without overwriting official branding and attributions. |
| F13 Build and R8 | Different Gradle/AGP and Kotlin versions. | Fresh build of untouched official tag; no transplanting 2023 Gradle tweaks. |

## M0 remaining acceptance gates

1. Clean upstream `assembleDebug` and unit-test build: **BLOCKED in ChatGPT container** (no external Git DNS/network and no installed Android SDK/Gradle distribution).
2. Physical Android 8+ device tests: **NOT RUN**.
3. Function-by-function Java/Kotlin behavioral audit: **IN PROGRESS**, not certified merely from matching filenames.
4. Exact ODK-vs-Kobo historical fork provenance (not just branch names): **PENDING**, including a historical ODK tag ancestry check.
5. Server/ODK field tests: **NOT RUN**.

## Safety

No legacy branch was changed. Do not merge `ubos-local` wholesale into new ODK. Never migrate database tables, edit installed app data, rotate signing keys or release APKs without explicit upgrade qualifications.
