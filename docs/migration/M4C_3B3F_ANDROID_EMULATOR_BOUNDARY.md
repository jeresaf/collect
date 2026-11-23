# M4C-3B3F — Android emulator interoperability boundary probe

**Source branch:** `codex/m4c2b3c3b3f-emulator-interop-probe`, stacked on Android Draft PR #40 (`a6ab4b0f`, [CI #45159405106 SUCCESS](https://github.com/jeresaf/collect/actions/runs/45159405106)).  
**Backend reference:** Data Collector Draft PR #30 (`7c029ede`, [CI #45158378181 SUCCESS](https://github.com/jeresaf/data-collector/actions/runs/45158378181)).

## Scope

Use the actual Android emulator environment to verify selected privacy and HTTP-contract boundaries previously qualified only in Robolectric/JVM tests. The instrumented class `RecordWorkEmulatorInteropProbeTest` runs under `AndroidJUnit4` on API 35. It does **not** fetch production data, log in to the real server, or publish a Collect APK.

### Executable checks

1. **Android transport/JSON boundary:** with an exact synthetic project URL/credentials and intercepted `HttpURLConnection`, load server-shaped `GET /records` and `GET /record-work-items` responses. Verify the old source is `SUPERSEDED`/not available for new amendment while the newer revision is `CURRENT`. No historical XML is requested.
2. **Stale authorization:** a simulated HTTP 409 from `GET /record-work-items/{workId}/prepare`, with synthetic sensitive source text in its body, must become typed `PreparationError.CONFLICT`. The fixed recovery disposition permits *manual navigation only* and never grants editor, save, retarget, auto-retry or upload access. The sensitive text must never appear in the recovery message.
3. **Actual AndroidKeyStore:** generate/use the app's AES-256-GCM key, seal a synthetic household answer under project/work/actor-specific associated data, persist ciphertext under app `noBackupFilesDir`, verify ciphertext does not contain the plaintext, and that a conflict leaves its bytes intact. Reading with a different actor's associated data must fail. Test directories are unique and cleaned up after execution.

### CI

- First job: reuse prior JVM protected-work tests, APK assembly and application identity checks.
- Second job: only after first succeeds, boot an API 35 x86_64 emulator using `reactivecircus/android-emulator-runner@v2` on an ephemeral Ubuntu runner with KVM; run only `RecordWorkEmulatorInteropProbeTest` via Android instrumentation; upload emulator XML/result reports.
- A missing KVM, dependency-rate-limit, emulator boot failure, or flaky AndroidKeyStore must be recorded as failed/blocked evidence, not claimed PASS.

### Not yet accepted

**This is an emulator-native synthetic transport smoke test, not a connected end-to-end test.** The Android responses are mocked from the API contract. The next connected gate must start the actual Spring Boot backend with ephemeral PostgreSQL/Flyway, provision synthetic users/forms/records, expose a short-lived reachable HTTPS endpoint to the emulator, and exercise the same workflow against the real backend. No bypass of TLS validation, release keys, private credentials, network traffic to production, or physical device testing is permitted.

Still missing: real offline draft capture while disconnected, real server refresh and post-conflict interaction by UI, multi-device races, lost acknowledgement reconciliation, signed typed mapping authority and physical Android acceptance. All PRs remain Draft/unmerged; no deployment or APK distribution is authorized.
