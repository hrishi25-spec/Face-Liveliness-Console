# Resource-File Compliance Audit

Checks the implementation against the six research files in `/Downloads/Resources/`
(ISO/IEC 30107-1:2023, ISO/IEC 30107-3:2023, ISO/IEC 19794-5:2011, MOSIP Desktop RC
docs 1.2.0, MOSIP Android RC docs 1.2.0, MOSIP SBI spec). Verified by code inspection
on 2026-10-06. Companion to `acceptance-audit.md` (tasks.md checklist).

Legend: ✅ aligned · 🟡 partial · 🔴 gap.

## 01 — ISO/IEC 30107-1:2023 (Framework)

| Requirement | Status | Evidence |
|---|---|---|
| Shared vocabulary (attack/bona fide presentation, PAD subsystem) | ✅ | `PadEngineService`, `AssessmentStatus` ("presentation attack detected — terminal"), `design.md §5`, "bona fide" used throughout `eval` |
| PAD framework: categorise + report attacks | ✅ | `PadVerdict` + `PadAttackType` (PRINTED_PHOTO / SCREEN_REPLAY / VIDEO_REPLAY / OTHER) per frame; terminal, distinct audit event |
| Scope boundary: only attacks at the capture device | ✅ | ISO scope boundary adopted and stated: digital **injection attacks** (virtual camera, hooked camera API, frame replay) are threat-modelled as a separate class — `design.md` §6 (threat surface, layered countermeasures, explicit residual risk) using Part-1 scope wording |
| "PAI" (presentation attack instrument) terminology | ✅ | Part-1 vocabulary defined and used in `design.md` §5–§6 — PAI, attack presentation, bona fide presentation, PAD subsystem — incl. the `PresentationLabel` → PAI class mapping (§6.4) |
| Self-testing ≠ certification | ✅ | `status-report.md` L176, `acceptance-audit.md` L79 |

## 02 — ISO/IEC 30107-3:2023 (Testing & Reporting)

| Requirement | Status | Evidence |
|---|---|---|
| APCER / BPCER metrics | ✅ | `eval.PadMetrics.apcer/bpcer/acer`; `ThresholdSweep` finds the operating point |
| Attack species classification (Annex A style) | ✅ | `PresentationLabel` + `ProxyPresentationCorpus`: printed photo, screen replay, video replay (masks documented as residual risk) |
| **APCER reported per PAI species** | ✅ | `ScenarioReport.apcerBySpecies()` — one immutable `SpeciesApcer` row (presentations / accepted-as-bona-fide / APCER) per attack `PresentationLabel`; aggregate `apcer` remains the presentation-weighted mean of the rows; bona fide excluded (its rate is BPCER). `AttackScenarioHarness.report()` groups the labelled results; tested by `AttackScenarioHarnessTest` (full-run grouping + exact hand-built math + zero-count rows) |
| Honest reporting (method, limits) | 🟡 | Simulation is disclosed (3% miss / 1% FP injected, `status-report.md` L90), but no Part-3-style report skeleton (devices, subjects, lighting, threshold, limitations) exists in docs |
| Not claiming certification | ✅ | "certification-ready, not self-certifying" (`brain.md` L874, `status-report.md`) |

## 03 — ISO/IEC 19794-5:2011 (Face image data / capture conditions)

| Requirement | Status | Evidence |
|---|---|---|
| Scene constraints → quality gate | ✅ | `services.ImageUtils`: Laplacian sharpness → BLURRY, luminance → TOO_DARK, Haar face detect → NO_FACE / MULTIPLE_FACES, face size → TOO_FAR |
| Single-face rule | ✅ | `LivenessHint.MULTIPLE_FACES` → `liveness.hint.single_person` |
| UI guidance messages map to constraints | ✅ | All hints are i18n keys (`LivenessHint.uiMessageKey`), localised in `LivenessView.defaultMessages`; no hard-coded English |
| Pose/expression guidance | ✅ | LOOK_STRAIGHT / HOLD_STILL hints; active challenges cover pose deviations |
| Output still meets interchange expectations | ✅ | Best frame passed to signed capture unchanged; SBI/CBEFF handling stays device-side |

## 04 — MOSIP Desktop Registration Client docs 1.2.0

| Requirement | Status | Evidence |
|---|---|---|
| Reach devices through SBI | ✅ | `SbiStreamDeviceAdapter` speaks local HTTP MJPEG `GET /stream`; `BurstRCaptureDeviceAdapter` for one-shot capture |
| Desktop discovery: scan loopback **4501–4600** | 🟡 | Not implemented — adapter takes an explicit URI; `MockL0L1Device.serve(port)` accepts any port (tests use 0 → ephemeral). Compatible by configuration, but no scan exists (arguably the RC's job, not the liveness layer's) |
| Tamper-evident config (UI-SPEC hash pattern) | ✅ | `ModelStore`/`InMemoryModelStore` verify SHA-256 before activation; `SignedManifestModelStore` binds activation to a vendor-signed manifest (spec §13: signature → digest → atomic swap → rollback; fail-closed, tamper-tested by `SignedManifestModelStoreTest`); HMAC-chained audit trail; Postgres triggers block UPDATE/DELETE |
| Retry model consistent with capture retries | ✅ | `WorkflowPolicyDefaults` per-role thresholds/retries/timeouts; orchestrator maps engine failures to retries without double-penalising |
| Offline-first | ✅ | No network in any decision (orchestrator invariant R4); policy floor is in code |
| Packet-level liveness evidence | 🟡 | Not done (would need packet-structure change); signed `LivenessEvidence` is the substitute anchor |

## 05 — MOSIP Android Registration Client docs 1.2.0

| Requirement | Status | Evidence |
|---|---|---|
| Pigeon bridge pattern | ✅ | `android_client/pigeon/liveness.dart` + `LivenessPigeonBridge`; matches the RC's `pigeon.sh` workflow |
| Vendor-pluggable engine (IBioApiV2 / global-params pattern) | ✅ | `FaceFrameSource` SPI + swappable `LivenessBackend` (`OnnxMiniFasNetBackend`, `TfLiteMiniFasNetBackend`) mirror the provider-registration idea |
| Code-level security floor (supervisors can weaken local config) | ✅ | `AndroidLivenessPolicyProvider`: hard floor threshold ≥ 0.60, retries ≤ 5, window 3–20 s; disable requires `mosip.liveness.android.allow-disable` build flag |
| Mock SBI as base for mock device | ✅ | `MockL0L1Device` embeds SBI HTTP server (`/info`, `/stream`) with L0/L1 capability reporting |
| Localised prompts (Arabic/French/English) | ✅ | Key-only UI (`LivenessStateEvent.challengePromptKey`, `LivenessHint`), arb files to be added in the RC repo |
| Operator/supervisor face authentication | 🟡 | Implemented as biometric login dialogs (role param) per the problem statement; the MOSIP docs describe **username/password** auth — assumption still unresolved with the RC repo/config |
| Version constraints (Flutter 3.10.4, Dart 3.0.3, SDK 31, Java 11 platform) | ✅ | Pinned + verified against the live RC repo `main` in `android_client/README.md` §Version constraints: Flutter 3.10.4 / Dart 3.0.3; docs-SDK-31 vs repo-`compileSdkVersion 34` drift flagged; CameraX ≥ 1.3.0 (the glue needs `ResolutionSelector`); `pigeon: ^10.0.1` (the repo's own pubspec pin); Java-11-vs-17 resolved — 11 = *backend platform* figure, RC app builds at Java 21 so the `--release 17` engine jar links/dexes, Spring service needs JDK 17+ |
| Auto-logout interplay (long liveness sessions) | 🟡 | Documented in `android_client/README.md` §Auto-logout: bounded worst case (≤ `maxRetries` × `maxSessionDurationMs` ≈ 150 s by policy floor; typical pass < 10 s), decision = host suppresses the inactivity timer between `startSession` and `onFinal` (refresh on `onState`), fail-safe proven (dispose → `cancelSession` → ABORTED → gate fails closed). RC-side pause wiring is integration-pending (tracker G4) |
| Landscape + phone layouts | 🟡 | `LivenessView` is plain Flutter (responsive by default); no orientation testing or spec note |

## 06 — MOSIP SBI spec (supplementary)

| Requirement | Status | Evidence |
|---|---|---|
| Device stream = M-JPEG preview | ✅ | `SbiStreamDeviceAdapter` decodes `multipart/x-mixed-replace`; `MjpegDecoder` |
| Stream is untrusted → binding check needed | ✅ | `LivenessEvidence.bestFrameSha256` "for post-capture binding checks"; engine-side `isGateValid(sessionId)` with the Dart `ensureGateValidForCapture()` wrapper; per-attempt nonces |
| Liveness finishes **before** RCAPTURE (frames exclusive) | ✅ | Capture button enabled only on PASSED + valid gate window (§14); no frames expected during capture |
| Signed capture is the trust anchor | ✅ | `LivenessEvidenceSigner` (SHA-256withRSA), fail-closed on signing failure or missing model |
| **3 fps minimum → adaptive fallback** | ✅ | `FrameRateMeter` measures real delivery at the SPI boundary (5 s sliding window over the source's frames); the orchestrator excludes BLINK below 5 fps — **measured**, not configured — and the engine falls back to TURN/SMILE (`challengeExclusions`; `CHALLENGE_ISSUED.excluded` audit field; unmeasurable rates fail closed). `SourceCapabilities.maxFps` stays the configured ceiling |
| Auth-purpose devices have no stream → dual source | 🟡 | Phone-camera liveness + signed capture is exactly the `CameraXFaceSource` + evidence-binding design, so the architecture supports it; the fallback *selection* between stream and camera is not automated |
| Android transport = intent name | 🔴 | Not modelled — all transports here are HTTP. Documented as a vendor question |
| Desktop stream on 127.0.0.1:4501–4600 | 🟡 | Compatible (explicit URI), discovery scan absent (see file 04) |
| Match API not needed for this problem | ✅ | Correctly not implemented |

## Summary

**Tally: 30 aligned ✅ · 8 partial 🟡 · 1 gap 🔴** (39 checks). The trust model
(untrusted preview → gate → signed capture with binding), PAD taxonomy, quality
gate, pluggability, and fail-closed behaviour all match the six files.

**Two concrete gaps, ordered by value:** (the injection-threat note + PAI
vocabulary, the measured-fps fallback, the Android version pins +
auto-logout interplay, and per-species APCER have all landed — see
`design.md` §6, the §01/§02/§05/§06 rows above, and `CHANGELOG.md`)

1. **Operator/supervisor face-auth assumption** — still unresolved against the RC
   repo; document the fallback (username/password) if face auth is not wired there.
2. **Android SBI intent transport + desktop discovery scan** — integration-layer
   work, dependent on vendor answers (open questions list in file 06 §5).

## Gap Tracker — the five original 🔴 gaps

Single place to drive the red rows above to closure. Created 2026-10-06.
Owners are **proposed from git history** (last substantive touch of the
relevant area — the repo has no CODEOWNERS) and should be confirmed before
work starts. Effort: **S** ≤ ½ day · **M** ½–2 days · **L** > 2 days. Status:
⬜ not started · 🔄 in progress · ⛔ blocked · ✅ done — update the cell as
work lands, and only flip the source row from 🔴 once the gap is verifiably
closed (evidence added to the table above).

| ID | Gap (source) | Owner | Effort | Status | Next action |
|---|---|---|---|---|---|
| G1 | **APCER reported per PAI species** (§02) | Dhrubajoti Thakur | S (~½ d) | ✅ Done (2026-10-06) | Group `AttackScenarioHarness` results by `PresentationLabel` and carry a per-species `apcer` through `ScenarioReport` — every presentation is already labelled, so only the aggregation and report fields are missing |
| G2 | **“PAI” (presentation attack instrument) terminology** (§01) | Dhrubajoti Thakur | S (~½ d) | ✅ Done (2026-10-06) | Adopt Part-1 wording in `design.md`: define PAI, map each `PresentationLabel` species to its PAI class, use the term in the PAD sections |
| G3 | **Version constraints pinned** (§05) | Hrishi Banerjee | M (~1 d) | ✅ Done (2026-10-06) | Verify Flutter 3.10.4 / Dart 3.0.3 / SDK 31 / Java 11 platform, CameraX and Pigeon versions, and the Java-11-vs-17 engine-jar question against the RC repo; pin the results in `android_client/README.md` |
| G4 | **Auto-logout interplay (long liveness sessions)** (§05) | Hrishi Banerjee | M (~1–2 d) | 🔄 In progress (2026-10-06) | Decide the mitigation for the race between RC inactivity logout and an active challenge — pause the timer while a session is live (RC side) vs orchestrator keep-alive — then handle and document it in `android_client/README.md` |
| G5 | **Android transport = intent name** (§06) | Hrishi Banerjee | M (~1–2 d after answer) | ⛔ Blocked — vendor question | Get the vendor answer for the intent-based SBI transport, then model it beside the HTTP transport or record “HTTP only” as the confirmed scope |
