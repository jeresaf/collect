# Data Collector — Android build identity and URL provisioning

This branch is a **stacked development branch** on top of `codex/odk-2026-url-bootstrap`. It is **not production-qualified**.

## Default build

```bash
./gradlew :collect_app:assembleDebug \
  -PCOLLECT_SERVER_URL=https://collector.example.org/v1/projects/PROJECT_UUID
```

The resulting APK uses a separate app ID **`org.jeresaf.datacollector.android`** and displays **Data Collector**. It cannot replace the official `org.odk.collect.android` installation.

To deliberately build a second independent client:

```bash
./gradlew :collect_app:assembleDebug \
  -PCOLLECT_APPLICATION_ID=org.jeresaf.lgscollect.android \
  -PCOLLECT_SERVER_URL=https://collector.example.org/v1/projects/OTHER_PROJECT_UUID
```

Use a unique app ID when multiple branded clients must coexist. Avoid changing an app ID *after* distributing it, as Android then treats the new package as a different application rather than an upgrade. Installed-app upgrades also require the **same signing key**, compatible stored-data migration and a suitable version code.

## Safety and implementation notes

- Android content-provider authorities for forms/instances now derive from `BuildConfig.APPLICATION_ID`; the manifest uses `${applicationId}` so parallel installs do not conflict.
- Our custom build uses **NoopAnalytics** and skips upstream Google Services / Crashlytics plugins. It never intentionally reports collection activity to ODK's Firebase project.
- The app-level `collect_app_name` resource overrides the upstream library's display name, without changing other libraries or license notices.
- `COLLECT_APPLICATION_ID` is optional. Only the server URL is needed for the default Data Collector build.
- The build URL is required to use HTTPS and excludes user info, query and fragment. Credentials are configured by users in the app.
- The embedded URL is provisioned only when there are no existing projects, so a new build won't replace existing project settings/data.
- `Data Collector` still uses ODK-derived classes, internal package namespaces and code resources. Apache 2.0 source notices remain intact.
- **Do not install on existing production devices or distribute as a release** until actual APK manifest, device first launch, signing, data preservation and server protocol tests pass.
- **Server integration is not proved** by a successful build or Robolectric tests. The configured endpoint must be an OpenRosa project base.


## Build and retrieve a project-specific debug APK through GitHub

1. Open **Actions → ODK 2026 Branded APK Qualification → Run workflow** in `jeresaf/collect`.
2. Select branch `codex/odk-2026-app-identity`.
3. Enter `server_url`: an HTTPS **OpenRosa project base URL** (e.g. `https://collector.example.org/v1/projects/PROJECT_UUID`). Never embed usernames, passwords, tokens or URL query secrets.
4. Leave `application_id` at `org.jeresaf.datacollector.android` unless testing a second concurrently installed client. Keep the same application ID between APK upgrades.
5. After the workflow succeeds, open its **Artifacts → data-collector-debug-apk**. Extract the ZIP to obtain the unsigned/repository-debug-signed debug APK for **test devices only**.
6. Confirm the APK label and package. Test a **fresh installation on a disposable device/profile**, supply username/password through the app, download a synthetic test form, save offline, restart, and submit to a **disposable test backend**. Record HTTP status, installed package name, and any errors.

Automatic pushes still build with a deliberately non-functional `collector.example.org` sample URL. The uploaded APK from a push event is therefore **not configured for your real project**. Run the manual workflow to produce a URL-specific test artifact.

The public project URL supplied in the workflow input may appear in GitHub Actions logs or APK metadata. It is **not a secret**; use a non-production test endpoint until the deployment is ready. Debug builds are not production-signed; do not use them to upgrade an existing PDMIS APK, and do not distribute to field enumerators.

## Qualification

Run `BrandedContentProviderContractsTest`, `PreconfiguredProjectInitializerTest`, and an Android debug build; inspect the APK package and label via Android SDK's `aapt`. Test clean install next to official ODK and existing Kobo/PDMIS installs, plus provider URI resolution.
