# P1 Architecture Audit — Muse Android R1

- Repository: `thanhtupppp/muse-android-r1`
- Audited branch: `main`
- Audited commit: `834ec2e6e083d33a0edbe2a0ec86ce4b06c37ff8`
- Audit date: 2026-11-12
- Scope: architecture, module boundaries, Android manifests, provisioning entry points, Muse session boundary, build/test/CI presence.
- Mode: read-only source inspection. No application code, configuration, or production behavior was changed.

## Executive summary

The repository has a clear two-module split:

- `app`: headless Agent for Phicomm R1 (Android 5.1.1 / API 22).
- `mobile`: Android phone application (minSdk 26, targetSdk 34).

The split is understandable, but the current implementation and documentation are not yet aligned. Provisioning security and Android network policy need to be addressed before relying on the setup flow with real Wi-Fi credentials.

## Findings

### P1-001 — Provisioning HTTP endpoint has no request authentication

- Severity: High
- Evidence:
  - `app/src/main/java/vn/tutu/muser1/LocalProvisionServer.kt` accepts `POST /provision-wifi` and applies the supplied SSID/password without authenticating the caller.
  - The server is started by `AgentService.onStartCommand()` on every service start.
  - `docs/PROVISIONING.md` describes the server as intended for hotspot provisioning, but code does not enforce hotspot-only operation.
- Impact: Any client able to reach the listener may attempt to change the R1 Wi-Fi configuration. The request carries the Wi-Fi password over plain HTTP on the local network.
- Recommended remediation:
  1. Introduce a short-lived, per-device provisioning challenge/token established during pairing.
  2. Bind/enable the HTTP listener only during an explicit provisioning window and stop it after success, timeout, or cancellation.
  3. Enforce the expected provisioning network/interface rather than relying on documentation.
  4. Avoid global cleartext enablement; define the narrowest possible network security policy if HTTP remains necessary.
  5. Add negative tests for missing, expired, replayed, and invalid tokens.

### P1-002 — Exported provisioning broadcast receiver is not permission-protected

- Severity: High
- Evidence:
  - `app/src/main/AndroidManifest.xml` declares `.ProvisionReceiver` with `android:exported="true"`.
  - No receiver-level permission is declared.
  - The receiver accepts Wi-Fi provisioning and Muse token configuration actions.
- Impact: Other installed applications can send explicit or implicit broadcasts to the exported receiver and attempt to replace device Wi-Fi credentials or the stored Muse SDK token.
- Recommended remediation:
  1. Make the receiver non-exported if external app access is not required; retain ADB shell recovery only through a deliberately designed, documented mechanism.
  2. If external access is required, protect it with a signature-level permission and validate caller identity where supported.
  3. Separate the low-trust Wi-Fi action from the sensitive token-configuration action.
  4. Add tests proving untrusted callers cannot mutate configuration.

### P1-003 — Mobile cleartext HTTP policy conflicts with local provisioning URLs

- Severity: High (runtime behavior to verify on device)
- Evidence:
  - `mobile/build.gradle.kts` sets `targetSdk = 34`.
  - `mobile/src/main/AndroidManifest.xml` does not declare `usesCleartextTraffic` or a network security configuration.
  - `mobile/src/main/java/vn/tutu/musemobile/data/R1ApiClient.kt` calls `http://$speakerIp:8080/health`, `/status`, and `/provision-wifi`.
- Impact: Android's default cleartext policy for apps targeting API 28+ can reject these HTTP requests. The main provisioning path may fail before reaching the R1 server.
- Recommended remediation:
  1. Prefer an authenticated encrypted transport if feasible.
  2. If local HTTP is retained for constrained R1 hardware, use a narrowly scoped network security configuration and document the threat model; do not enable cleartext globally without justification.
  3. Add an instrumented test on target API levels that verifies the intended connection policy.

### P1-004 — Provisioning server lifecycle is broader than documented

- Severity: Medium
- Evidence:
  - `AgentService.onStartCommand()` always calls `startProvisioningServer()`.
  - The server's own class comment says it should run only while R1 is in hotspot/configuration mode.
  - No explicit provisioning-state gate is visible in the service startup path.
- Impact: The unauthenticated local HTTP surface may remain available outside the intended setup window.
- Recommended remediation: Model provisioning as an explicit state machine with bounded lifetime; start/stop server from state transitions and verify after reboot/reconnect.

### P1-005 — Architecture and feature documentation disagree

- Severity: Medium
- Evidence:
  - `README.md` says MQTT is not used at the current stage.
  - `docs/ARCHITECTURE.md` describes MQTT as the primary control channel.
  - The Agent includes `MqttAgent` and starts it when MQTT host/device ID are configured.
  - `docs/MUSE_INTEGRATION.md` describes a WebSocket flow while `MuseGadgetClient.kt` explicitly states that full Noise XX/session/pairing integration remains future work.
- Impact: Contributors and operators cannot reliably determine which paths are implemented, optional, experimental, or production-ready.
- Recommended remediation: Establish one source of truth for current architecture and label each integration as implemented, partial, experimental, or planned. Keep README, architecture, provisioning, and integration docs synchronized.

### P1-006 — Muse session lifecycle and protocol completeness are not established

- Severity: Medium
- Evidence:
  - `MuseSessionAdapter.start()` launches a raw background thread and attempts refresh/fetch/connect once.
  - WebSocket failure/close callbacks log errors but do not schedule reconnection.
  - `MuseGadgetClient.kt` states that full Noise XX session and pairing protocol remain to be implemented.
  - The current WebSocket adapter sends `link.register` and dispatches incoming text to the command surface.
- Impact: The service may remain running while the Muse session is disconnected; a successful socket handshake alone does not establish full SDK compatibility.
- Recommended remediation: Implement an explicit, cancellable session state machine with bounded retries/backoff, token refresh handling, lifecycle-safe shutdown, protocol conformance tests, and end-to-end pairing validation against the official SDK.

### P1-007 — Automated quality gates and test suites were not found in inspected paths

- Severity: Medium
- Evidence:
  - No `.github` directory was present at the inspected repository root.
  - `app/src/test` and `mobile/src/test` were not present in the inspected module trees.
  - No workflow run was returned for the audited latest commit by the PR-triggered workflow-run lookup.
- Impact: There is no verified automated gate in this audit snapshot to prevent regressions in ADB installer, provisioning, security, or compatibility.
- Qualification: This is an observation about the inspected branch/paths and connector lookup, not proof that no external CI or device-side test process exists.
- Recommended remediation: Add CI for Gradle validation and module builds, then add unit/instrumentation tests for the highest-risk flows. Publish test evidence for Android API 22 compatibility separately from phone-app target API testing.

### P1-008 — Downloaded Agent APK is checked by package name but not pinned digest/signing identity

- Severity: Medium
- Evidence:
  - `mobile/src/main/java/vn/tutu/musemobile/data/R1Installer.kt` downloads an APK and checks that its package name is `vn.tutu.muser1`.
  - No expected SHA-256 digest or trusted signing-certificate fingerprint is checked in this download path.
- Impact: Package name alone does not establish artifact integrity or publisher identity.
- Recommended remediation: Verify a release-published SHA-256 digest and/or pinned signing certificate before ADB installation; fail closed on mismatch and show the verified version/digest to the operator.

## Architecture consistency matrix

| Area | Current evidence | Assessment |
|---|---|---|
| Module split | `app` Agent + `mobile` phone app | Clear baseline |
| R1 OS compatibility | Agent minSdk 22, Java/Kotlin target 17 | Requires actual API 22 device/emulator build/runtime validation |
| Phone compatibility | Mobile minSdk 26, targetSdk 34 | Clear declared target |
| Provisioning | HTTP API plus Bluetooth RFCOMM server | Multiple entry points need one security/lifecycle model |
| Command handling | Muse command whitelist exists | Positive design, but payload/logging/replay controls need review |
| Muse integration | Token refresh + VM fetch + WebSocket adapter | Partial; full protocol explicitly incomplete |
| MQTT | Implemented conditionally | Documentation is inconsistent |
| Tests / CI | No test dirs or workflow directory found in inspected paths | No verified automated quality gate |

## Recommended P1 follow-up order

1. Fix the trust boundary first: exported receiver and unauthenticated provisioning HTTP.
2. Resolve mobile cleartext policy with a narrowly scoped secure design and device verification.
3. Make provisioning lifecycle explicit and bounded.
4. Reconcile architecture documentation and feature status.
5. Define Muse session state/reconnect and protocol conformance requirements.
6. Add CI and tests for the above before merging behavioral changes.
7. Add artifact integrity verification before installation.

## Audit limitations

- This is a source-level architecture audit, not a successful Gradle build or physical Phicomm R1 test.
- No credentials, production device, broker, or Muse account were accessed.
- No source code or production configuration was changed.
- The ADB installer protocol, Bluetooth provisioning parser, MQTT authentication/TLS, token storage hardening, and detailed command payload validation require dedicated follow-up audits.
