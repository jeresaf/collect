# M0 — Verified Hosted Build Evidence (19 November 2026)

## Exact upstream and tested source

- Official ODK Collect `v2026.4.0`: `d6c4ecd0f1d470b501bcdbaaf43294820deeb61f`, released 2026-11-17.
- Clean-build branch: `codex/odk-2026-clean-ci-baseline`, baseline source plus only `.github/workflows/odk-2026-clean-baseline.yml`.
- URL feature branch: `codex/odk-2026-url-bootstrap`, GitHub Actions head `f973d9df234e48bd64e6d4b4b8e9897ef52b64b3`.

## Verified actions results

| Test | GitHub run | Result |
|---|---|---|
| Clean official ODK `./gradlew :collect_app:assembleDebug` | [44565689141](https://github.com/jeresaf/collect/actions/runs/44565689141) | **PASS** |
| URL provisioning focused JUnit tests | [44565105844](https://github.com/jeresaf/collect/actions/runs/44565105844) | **PASS** |
| URL-configured `./gradlew :collect_app:assembleDebug` | [44565105844](https://github.com/jeresaf/collect/actions/runs/44565105844) | **PASS** |

The first three URL branch attempts failed **before Gradle execution** due to GitHub hosted SDK provisioning (`android-actions/setup-android@v3` tried to install obsolete `tools`, then the SDK tool was not on PATH, then `platforms;android-37` was unavailable versus `platforms;android-37.0`). After replacing the tool setup with `android-actions/setup-android@v4` and using Android SDK 37.0, both builds completed successfully.

## Boundaries

A successful APK assembly and Robolectric/unit test is not proof of Android device installation or compatibility with Data Collector's backend. These remain **NOT RUN**:
- Device and emulator first launch with URL-only configuration, login and project switching.
- Production-compliant app signing, package ID, branding and provider authority qualification.
- End-to-end mobile login → formList → manifest/media → offline fill → submission against disposable PostgreSQL server.
- Historical PDMIS remote record retrieval/editing and correction retry.
- JavaRosa 4.3.2 server to JavaRosa 7.0.0 client XForm semantic parity.

New active work:
- [Draft PR #16 URL provisioning](https://github.com/jeresaf/collect/pull/16)
- [Draft PR #17 distinct app identity](https://github.com/jeresaf/collect/pull/17)
- [Data Collector backend contract draft PR #1](https://github.com/jeresaf/data-collector/pull/1)

No APK was publicly distributed, and no production records were modified.
