# ODK Collect 2026 Forward-Port — Evidence and Migration Plan

**Prepared:** 2026-11-19  
**Repository:** `jeresaf/collect`  
**Historical source:** `ubos-local` @ `951dafd3d893003493db3c2de2d7568153889165`  
**Current stable upstream target:** `getodk/collect` release `v2026.4.0` (2026-11-17; release short SHA `d6c4ecd`)  
**Audit-only branch:** `codex/odk-2026-migration-audit` (based on historical source).  
**Future implementation branch:** `codex/odk-2026-forward-port` — MUST be created from the **upstream ODK tag**, not from `ubos-local`.

## Purpose and boundaries

Forward-port **behaviors**, not indiscriminate file patches, from the historical customized KoboCollect-derived code into a clean 2026 ODK Collect base. Preserve source provenance, make every deviation explicit, and test every critical workflow. **No historical Android source code was changed in this audit.**

The authoritative audit file `LEGACY_CHANGED_FILES.csv` lists all **187** files different between the historical Kobo-derived branch named `v2023.2` and `ubos-local` (**66** intervening commits; **71 added, 116 modified**). This is a **historical delta**, not an assertion that all files contain unique functionality or that the implementation is correct. Comparing only the later `pmt-server` to `ubos-local` yields **54 commits, 158 files**, but omits the earlier login/onboarding customization.

For rigorous provenance, establish three comparisons:
1. Historical ODK release (identify actual matching commit/tag) versus the Kobo source from which the fork was derived: **Kobo-specific changes**.
2. That historical Kobo-derived base versus `ubos-local`: **our original extensions**.
3. `ubos-local` behaviors versus **clean ODK Collect v2026.4.0**: **functionality already upstream, remaining gaps, and regression risks**.

Do **not** assume that a historical branch name `v2023.2` is an official ODK release tag; verify Git ancestry and the actual commit SHAs. GitHub compare shows that the historical fork's `master` has diverged significantly from `ubos-local`, so `master` is not an appropriate upgrade base.

## Important upstream changes

- ODK Collect v2026.4.0 incorporates **JavaRosa 7.0.0**, map/data download changes, and fixes involving nested repeats and date/time semantics.
- ODK Collect v2025.2+ natively supports **editing finalized and sent submissions still present locally** when the form and compatible server permit edits. This is NOT equivalent to downloading **arbitrary remote existing records** for correction.
- Newer releases require **Android 8+**. Qualify the fleet before switching.
- Our Spring Boot `jeresaf/data-collector` currently uses **JavaRosa 4.3.2**. Cross-version semantics must be verified with identical production XForms (including `PDMIS Updated Household`) before treating the new APK as compatible.

References: https://github.com/getodk/collect/releases/tag/v2026.4.0 ; https://docs.getodk.org/collect-forms/ ; https://github.com/kobotoolbox/collect

## Candidate feature reconciliation (NOT a pass report)

| ID | Behavior/extension in ubos-local | Evidence anchors | 2026 migration decision | Required proof |
|---|---|---|---|---|
| F01 | Custom username/password login, PDMIS login payload and login screen | `FirstLaunchActivity.kt`, `login/*`, `OpenRosaLoginSource.java` | REDESIGN: use standard ODK project authentication first; optional discovered extension if still required | Valid/invalid credentials, 401 challenge, role/assignment enforcement; credentials never embedded in APK or logs |
| F02 | Hardcoded PDMIS URL and project branding | `preferences/Defaults.kt`, `collect_app/build.gradle`, resources | REIMPLEMENT as build-time URL (single required input), optional separate app ID/brand | Two APKs from same code, no Java/Kotlin edits, correct URL from first launch |
| F03 | Download authorized existing records from server | `OpenRosaEntrySource.java`, `EntryDownloadListActivity.java` | RETAIN AS OPTIONAL EXTENSION: upstream local sent-form editing is not remote record retrieval | Paginated authorized listing, ownership checks, XML/media fidelity, downloads offline |
| F04 | Custom entry domain, database and provider | `forms/.../entries/*`, `database/entries/*`, `EntryProvider.java` | REEVALUATE against 2026 submission/instance storage; DO NOT copy old DB layer unchanged | No data loss, identity preserved, tests against current storage migrations |
| F05 | Re-edit existing records and resubmit updates | `FormUriActivity.kt`, `EditedInstancePreloadUpdater.java`, `InstanceServerUploader.java`, `SaveFormToDisk.java` | PARTIAL UPSTREAM REPLACEMENT; only port server-remote edit deltas after proof | `instanceID`, `deprecatedID`, form/version, audit, idempotency, retry after lost ACK |
| F06 | Open existing record with its historical XForm version | `FormFillingActivity.java`, last `ubos-local` commit | KEEP REQUIREMENT; design against 2026 version/upgrade behavior | Edits of old-version records do not use incompatible new definition or lose answers |
| F07 | In-screen quick add repeat and save current answers | `FormEntryViewModel.java`, `FormFillingActivity.java` and 2026-03-16 commits | EVALUATE upstream JavaRosa 7 and UI first; likely reimplement only unique UX | Counted/nested repeat no invalid reference, pre-existing answers preserved |
| F08 | Entry list status, counts and deletion | `MainMenuActivity.kt`, `EntryChooserList.java`, `EntryListCursorAdapter.java` | REEVALUATE against upstream list UI; optional remote-entries feature | Correct unsent/finalized/sent semantics, no accidental data deletion |
| F09 | Admin-unit refresh and access-revocation logout | `AdminUnitsTask.java`, `ProjectSettingsDialog.kt` | MOVE to generic reference/capability API, eliminate PDMIS-specific assumptions | Refresh scope and authorization; offline failures; revocation cannot expose data |
| F10 | Custom OpenRosa URL paths and XML parsing | `OpenRosaConstants.kt`, `OpenRosaResponseParserImpl.kt` | Prefer STANDARD OpenRosa; add negotiated adapters only if necessary | FormList, manifest, download, Basic Auth, submission for both server profiles |
| F11 | Custom finalize/submitted/resubmit states | `InstanceServerUploader.java`, `InstanceUploader.java`, commits Feb–Mar 2026 | COMPARE with native editing and retransmission; avoid double submission | Same source/revision IDs across retry/correction, accurate UI states |
| F12 | Form progress, alerts and rejection reason flow | Main menu/layout changes, `FormEntryTracker.java`, `docs/USER_ISSUES_AND_SOLUTIONS.md` | Preserve only meaningful behavior not already upstream | Correct current progress/reason; accessibility and Android back navigation |
| F13 | Build/dependencies/Google services and R8 fixes | `gradlew`, all Gradle scripts, `proguard-rules.txt` | DO NOT PORT 2023 BUILD PATCHES into 2026 base; start upstream-clean | Clean Gradle build, tests, release minify/proguard, reproducible signed APK |
| F14 | Branding/resources, application ID and signing | `collect_app/build.gradle`, manifest, drawables/layouts | RECONFIGURE via variants; preserve notices and attribution | Distinct package IDs can coexist; upgrade path only if same ID+signing key+DB-compatible |
| F15 | Local form deletion, project-switching and media lifecycle | Storage/provider/form code | REEVALUATE upstream; retain actual user requirements | Drafts, unsent data, attachments and saved instances survive upgrades |
| F16 | PDMIS compatibility | Custom login/admin units/remote entries and PDMIS complex XForm | KEEP as a regression profile, not generic core logic | PDMIS all flows remain certified before any migration or replacement |

For every row, record: source paths/commits, requirement, old observed behavior, upstream v2026 observed behavior, decision (UPSTREAM / KEEP / REDESIGN / DROP / BLOCKED), test link, result, risk and reviewer.

## Recommended phased execution

### M0 — Provenance and regression inventory (read-only)
- Record exact commit SHAs and inspect merged PRs/commit history, compare Kobo and ODK release ancestry, identify user-originated additions and inherited rebranding.
- Classify each of the 187 paths in `LEGACY_CHANGED_FILES.csv` and map them to F01–F16.
- Build a snapshot of known-working PDMIS legacy behavior on disposable test data.
- Build/test clean upstream `v2026.4.0` **unchanged** before porting anything.
- Report any existing device data/signing constraints; do not uninstall user APKs.

### M1 — New clean ODK branch and build-time configuration
- In a **separate checkout**, fetch `getodk/collect` tag `v2026.4.0`.
- Create `codex/odk-2026-forward-port` from the upstream tag. Preserve `ubos-local`, `master` and `codex/generic-collector-foundation`.
- Implement configurable `COLLECT_SERVER_URL` with validation and first-run project provisioning; a separate app ID only when separate concurrent installations are desired.
- No secrets/passwords embedded in APK; use HTTPS for credentials and production.

### M2 — Standard OpenRosa to Data Collector
- Prove app-user authentication, challenge handling, project assignment, published form listing, XML/media manifests, offline fill and multipart submit.
- Backend reference `https://<host>/v1/projects/<UUID>` is NOT the legacy `/api/v1/formlist` contract. Fix the server contract or implement deliberate endpoint negotiation, never replace URLs blindly.

### M3 — Remote record editing, only where native 2026 support is insufficient
- Define server-side authenticated record search/detail/export/correction contract first.
- Implement remote record download, historical version resolution, media preservation and revision links on top of 2026 repositories and APIs.
- Test duplicate source ID, retries after lost acknowledgement, edits when the form changes, deleted/archived user, and revocation.

### M4 — Form and reference data compatibility
- Validate `PDMIS Updated Household` XForm against ODK JavaRosa 7 and backend JavaRosa 4.3.2, including age/date calculations, household name/member repeats, historical versions, and cascaded village choices.
- Validate `farm_data_collection_test.xlsx` with nested counted repeats, external CSV/XML, geopoint, photo, signature.
- Standardize generic reference lists; do not hardcode Uganda-specific location tables into the mobile client.

### M5 — Device and rollout certification
- Test Android 8+, at least one Android 11 device previously used, plus current supported Android versions; old unsupported devices remain on old builds pending migration policy.
- Test clean install **and** installed-app upgrade separately. Existing Android application ID, signing certificate, DB versions and stored media paths must be checked before promoting in-place upgrades.
- Preserve draft, finalized, unsent, sent and downloaded records. Run data backup/restore dry run.
- Publish signed, versioned APK only after Gradle tests, instrumented tests, two-server-profile contracts and PDMIS acceptance all pass.

## Safe local Git startup

```bash
git clone https://github.com/jeresaf/collect.git collect-odk-2026
cd collect-odk-2026
git remote add odk https://github.com/getodk/collect.git
git remote add kobo https://github.com/kobotoolbox/collect.git
git fetch origin ubos-local pmt-server v2023.2-UG v2023.2
git fetch --no-tags odk refs/tags/v2026.4.0:refs/tags/odk-v2026.4.0
git switch -c codex/odk-2026-forward-port refs/tags/odk-v2026.4.0
# Verify official release source and build clean before pushing or editing.
git log -1 --oneline
# After baseline verification:
git push -u origin codex/odk-2026-forward-port
```

Never do `git merge ubos-local` or bulk `git cherry-pick` in the new ODK branch; carry forward approved features independently with exact source and test links.

## Blocking quality/security checks
1. ODK v2026.4 uses JavaRosa 7; backend uses 4.3.2. Version-skew may cause semantic differences. Do not upgrade backend automatically.
2. Legacy PDMIS login passes separate API credentials. Review how passwords are returned/stored; never port insecure credential transport by habit.
3. Legacy remote entry download stores XML and replaces local records. Verify security, cancellation, duplicate handling and local-unsent edit preservation.
4. Do not claim in-place application upgrade safety without validating package ID, signing key, local schema migrations and file layouts.
5. Preserve Apache 2.0 attribution notices and third-party licenses; select our own branding and package IDs rather than impersonating an official client.

## Evidence rule

Current status: **AUDITED SOURCE COMPARISON ONLY**. No clean 2026 APK has been built here, no migration patch has been applied and no Android compatibility result is claimed.
