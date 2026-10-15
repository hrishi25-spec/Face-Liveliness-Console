# Acceptance Audit — Face Liveness Detection & PAD (MOSIP Prob 04)

**Date:** 2026-10-06 · **Branch:** `development` · **Test baseline:** `mvnw test` → 349/349 green (exit 0)

Status of the consolidated task checklist against the implemented code.
Legend: ✅ done · 🟡 partial · ❌ not done.

## 1. Expected Tasks (21 items → 18 ✅ / 3 🟡 / 0 ❌)

| # | Task | Status | Evidence |
|---|------|--------|----------|
| 1 | Analyse existing biometric device integration | ✅ | `device/` — `SbiStreamDeviceAdapter` (L1 MJPEG stream), `BurstRCaptureDeviceAdapter` (L0 rCapture), `DeviceCapabilities` |
| 2 | Analyse Desktop/Android face capture workflows | ✅ | `docs/client-integration-guide.md` §3–§4; `client/DesktopLivenessAdapter`, `client/AndroidLivenessAdapter`; `io.mosip.liveness.android` orchestrator |
| 3 | Face frame streaming interface | ✅ | `core.Frame` (NV21/YUV420/RGB_GRAY), `device/DeviceAdapter` + `FrameListener`; Android `android/FaceFrameSource` |
| 4 | Liveness/PAD processing interface | ✅ | `backend/LivenessBackend` SPI; `engine/LivenessPipeline` |
| 5 | Evaluate liveness/PAD technologies | ✅ | `docs/status-report.md` §4 (ONNX vs TFLite vs commercial SDK; documented blockers + corrections) |
| 6 | Evaluate vs ISO/IEC 30107 | 🟡 | `eval/PadMetrics`, `eval/AttackScenarioHarness` (APCER/BPCER/ACER per species). Self-test on **proxy** corpus only; no accredited lab / physical corpus — residual risk documented |
| 7 | Passive liveness detection | ✅ | `engine/LivenessDecisionLogic` (windowed median, cold-start guard); `backend/OnnxMiniFasNetBackend` (real MiniFASNet-V2, bundled, SHA-256 verified on load) |
| 8 | Presentation Attack Detection | ✅ | print/screen/replay detection; `engine/PadBlockingTest`; generic PAD message only |
| 9 | Active liveness challenge-response | ✅ | `engine/FaceLivenessEngine` escalation; PAD + passive re-eval during active (G1/G2); combined score (G10) |
| 10 | Dynamic challenge selection | ✅ | `challenge/ChallengeSelector` — SecureRandom shuffled bag, no back-to-back repeats |
| 11 | Facial action detection | ✅ | `challenge/ChallengeEvaluators` — blink (full EAR close→open cycle), smile, head turns ±12°, gaze directions |
| 12 | Configurable thresholds | ✅ | `application.yml mosip.liveness.*`; runtime `PUT /api/v1/config/{workflow}` (admin-key, fail-closed, audited) |
| 13 | Integrate — resident face capture | ✅* | `RESIDENT_REGISTRATION` workflow + Desktop guide §3.4 + Android gate; *merge into actual client screens pending |
| 14 | Integrate — operator authentication | ✅* | `OPERATOR_AUTH` + guide §3.5 (`SessionContext.validateFace` gate before `authValidator`) |
| 15 | Integrate — supervisor authentication | ✅* | `SUPERVISOR_AUTH` (threshold 0.85, 2 challenges, LOCK_OUT) |
| 16 | Online/offline processing | ✅ | No network calls in the decision path (verified by search across `engine/`, `services/`, `backend/`); policy + models local; CI runs offline |
| 17 | Retry and failure handling | ✅ | Retry budget; `LOCK_OUT` / `FALLBACK` / `ESCALATE_TO_OPERATOR`; persisted `lockoutUntil` (Android `LockoutStore`) |
| 18 | UI/UX changes | 🟡 | Browser console (`static/console.js`), Flutter `LivenessView`/`LivenessViewModel`, Desktop overlay guide; **localisation + real client screens pending** |
| 19 | Configuration changes | ✅ | `config_policies` per workflow (V2 migration); audited edits with old→new values; hard security floor |
| 20 | Audit and diagnostic logging | ✅ | Chained `audit_logs` (HMAC-SHA-256 keyed, tamper-evident, PG triggers block UPDATE/DELETE); `StructuredAuditLogger`; no pixels in logs |
| 21 | Performance & interop testing | 🟡 | `metrics/PipelineTimers` per-stage timers; mock + CameraX/SBI adapters share one suite; **no physical device matrix run** |

## 2. Mandatory groups (10)

| Group | Status | Notes |
|---|---|---|
| 2.1 L0/L1 device integration (7 items) | ✅ | Stream + burst adapters, frame formats, failure injection, reconnect backoff, common abstraction. *No physical vendor device tested yet* |
| 2.2 Passive liveness (5) | ✅ | Threshold compare, escalation decision, offline-safe |
| 2.3 PAD (5) | ✅ (4/5) | Blocks capture **and** auth on attack; ISO evaluation 🟡 (self-measured, uncertified — labelled honestly) |
| 2.4 Active liveness (7) | ✅ | Auto-initiate, dynamic select, prompt, analyse, verify, re-evaluate, gate completion |
| 2.5 Resident registration (5) | ✅ | Gate before capture; auto-prompt on escalation; retry + configured limit (`AndroidLivenessOrchestratorTest`) |
| 2.6 Operator authentication (6) | ✅ | Passive+PAD+active; auth refused on failure; offline (local templates) path |
| 2.7 Supervisor authentication (6) | ✅ | Stricter policy; same guarantees |
| 2.8 Configuration (8 keys) | ✅ | Enablement, threshold, active policy, challenge types, min count, timeout, retries, per-workflow overrides |
| 2.9 Error handling (13 conditions) | ✅ | All enumerated in `core/LivenessErrorCode` (E101→E901) with user-safe messages; `engine/ErrorConditionsTest`; 3-invalid-frames → device error; engine/model errors fail closed |
| 2.10 UI/UX | ✅ | All spec strings implemented (`LivenessView.defaultMessages`, engine `userMessage()`, console); system-generated prompts (user never selects); real-time feedback (continue/detected/hold); generic PAD message; retry indication + recovery guidance; operator/supervisor share the resident interaction model |

## 3. Bonus tasks (8 → 4 ✅ / 4 🟡)

| Task | Status | Evidence / gap |
|---|---|---|
| Mock L0/L1 device | ✅ | `device/MockL0L1Device`, `SyntheticScene`, android `MockFaceFrameSource` |
| Automated liveness scenarios | ✅ | 349 green tests; engine flow/escalation/error suites |
| Automated PAD attack scenarios | ✅ | `AttackScenarioHarness` (240 presentations, seeded), `PrintAttackFixtureTest` |
| Interop testing framework | 🟡 | Shared suite runs across mock/ONNX/MediaPipe backends + 2 device adapters; no formal cross-vendor report |
| Model version management | ✅ | sha256 + version + rollback (`android/ModelStore`; bundled model hash verified at load) |
| Secure offline model updates | 🟡 | Hash path done; **production signature verification lives in the Android glue template** (needs vendor PKI) |
| Low-resource Android optimization | 🟡 | `frameSamplingRate`, ≤640×480 analysis, keep-only-latest; **no on-device benchmark** |
| Hardware acceleration | 🟡 | NNAPI/GPU → XNNPACK delegate chain documented + pluggable; not measured on-device |

## 4. Good-to-have (9+4 → 8 ✅ / 1 🟡)

| Task | Status |
|---|---|
| Configurable challenge pools | ✅ `allowedChallenges` per workflow |
| Multiple facial actions | ✅ 9 challenge types (incl. LOOK_* aliases) |
| Per-workflow policies | ✅ resident/operator/supervisor tables + overrides |
| Configurable user guidance | ✅ i18n keys end-to-end (`uiMessageKey`) |
| Diagnostic mode | ✅ audit scores + Android `diagnosticsSnapshot()`; opt-in raw-score mode: `mosip.liveness.diagnostics-enabled` → `GET /api/v1/diagnostics` (loopback-only, identical 404 otherwise, `no-store`) feeds the console's local-only debug panel — raw scores, per-frame ms, FPS, scorer delegate, no pixels (`DiagnosticsServiceTest`, `DiagnosticsControllerTest`, `ClientIpResolverTest`) |
| Multiple device vendors | ✅ `DeviceAdapter` / `FaceFrameSource` SPIs, 3 backends |
| Anonymized operational metrics | ✅ `GET /api/v1/metrics` (no user ids) |
| Perf metrics: avg processing time / challenge completion / retry rate / failure rate | ✅ `MetricsCollector` + `PipelineTimers` |
| Device capability discovery | ✅ `DeviceCapabilities`, `SourceCapabilities` |

## Remaining gaps (the honest shortlist)

1. **Physical-device testing** — vendor L0/L1 hardware, device matrix, on-device performance targets (2.1, task 21, two bonus items).
2. **Formal ISO/IEC 30107 evaluation** — physical print/screen/replay corpus; self-testing ≠ certification (docs state this explicitly).
3. **Client-app integration** — merging engine + Pigeon/Dart layer into `mosip/android-registration-client` screens, `.arb` localisation, gating `Biometrics095Service` / `SessionContext` in the real repos ([VERIFY] items in the docs).
4. **Signed-manifest model updates at the Android glue** — interface + template exist; production signature check requires the deployment's signing key.
