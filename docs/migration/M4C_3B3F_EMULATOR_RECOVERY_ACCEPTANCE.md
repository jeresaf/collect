# U06-T03 / M4C-3B3F — Android emulator recovery acceptance

**Base:** Android Draft PR #40 at `a6ab4b0f6f55c0520a3b3c06ec1039bf4d51e8bf`, CI #45159405106 SUCCESS.  
**Server contract:** backend Draft PR #30 at `7c029eded1fecf4fa18517b56fde470b08089669`, CI #45158378181 SUCCESS.

## Implemented acceptance gate

The branch creates `collect_app/src/androidTest/java/org/odk/collect/android/recordwork/U06OfflineConflictEmulatorIT.kt` and the GitHub Actions workflow `.github/workflows/m4c2b3c3b3f-emulator-recovery-acceptance.yml`.

On an **Android API 35 emulator**, the test:
- Stages a synthetic, actor-scoped AMENDMENT binding with exact source/target/form hashes, an existing reserved instance ID, and inert native-review proof. This fixture simulates the state **after** successful server admission; it does **not** assert that mocked proof grants real historical answer-transfer permission.
- Writes a synthetic household answer using the production `AndroidKeyStorePrivateDraftCipher` and `EncryptedPrivateTextDraftStore` under an isolated `Context.noBackupFilesDir` test root, then verifies ciphertext does not contain the plaintext.
- Applies an HTTP 409 *disposition* with `ProtectedWorkRecovery`; asserts no editor, finalize, upload, automatic retry or automatic source-retarget capability is granted.
- Opens a **metadata-only** `QuarantinedConflictReview` and confirms no answer/XML/media read capability. It does not exercise the UI activity itself.
- Parses a synthetic JSON payload matching the backend U06-T02 PostgreSQL `GET /records` contract (old V1 source `amendmentSuperseded=true`, current V2 source `false`). It checks the historical source cannot offer AMENDMENT while the current source can be offered for a **new separately authorized** task. Old draft IDs and bytes remain unchanged.
- Reopens the previously saved encrypted answer using a new production Keystore cipher instance; verifies the saved answer is recoverable only under its original project/actor/work binding and cannot be finalized or submitted.
- Tests an old-server record missing `amendmentSuperseded` is UNKNOWN, and revoked/network-denied states do not make a manual review or upload available.

GitHub Actions builds a branded debug APK and Android instrumentation APK; boots an Android API 35 emulator with `reactivecircus/android-emulator-runner`; executes only this bounded instrumentation test; checks package and app label. No APK distribution is part of this gate. A test report is retained as CI evidence.

## Evidence limitations and next gates

**Not covered here:** actual runtime connectivity to a Spring Boot/PostgreSQL server, authenticating a real app user through the emulator, performing server `prepare`/create over TLS, inducing a real concurrent amendment via network, exercising screenshots/taps in the UI activities, supplying real signed typed-mapping rules, or submitting forms/media. Those are separate end-to-end/release gates.

The backend's real PostgreSQL HTTP regression and Android's emulator fixture mirror each other's wire-level JSON and security intent but are **not the same end-to-end network test**. Do not describe this gate as proof that the emulator talks to the backend. No physical Android testing, merge, release, production data mutation or credential use.

**Status:** gate unverified until exact-head Actions run passes. Keep the stacked PR Draft/unmerged.
