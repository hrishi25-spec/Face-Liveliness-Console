# Backend Interoperability Report — Mock vs ONNX vs MediaPipe

| Field | Value |
|---|---|
| **Document** | Backend interoperability report (`LivenessBackend` SPI) |
| **Date** | 2026-10-06 (per-frame latency added 2026-10-07) |
| **Branch** | `development` (working tree, uncommitted) |
| **Scope** | `MockLivenessBackend`, `OnnxMiniFasNetBackend`, `MediaPipeFaceMeshBackend` |
| **Excluded** | `TfLiteMiniFasNetBackend` (a fourth implementation exists; not in the requested comparison set) |
| **Method** | Static conformance review + dynamic execution of the full Maven test suite + per-frame latency/throughput measurement of both executable backends (section 10) |
| **Suite baseline** | `./mvnw test` → **439 tests, 0 failures, 0 errors, 0 skipped (exit 0)** |
| **Status** | Final — figures below are measured, not projected |

---

## 1. Purpose

The engine never hard-couples to a vendor SDK: every liveness/PAD decision
flows through one seam, the `LivenessBackend` SPI. This report establishes
whether the three compared backends are **interoperable at that seam** — that
is, whether `FaceLivenessEngine` and its callers can accept any of them through
the identical code path, with identical failure semantics, and whether the
suite executes evidence for each one.

It is the evidence artifact for `docs/backend-swapping.md` and is cited by
`docs/status-report.md` §2.5 (mock-harness evaluation numbers).

## 2. The standard under test

```java
public interface LivenessBackend {
    String id();                                              // audit vocabulary
    void initialize(Map<String, String> options);             // one-time, before any session
    FaceSignals analyzeFrame(Frame frame);                    // detection + landmarks
    double scorePassiveLiveness(Frame frame, FaceSignals signals); // [0,1]
    PadVerdict assessPad(Frame frame, FaceSignals signals);   // runs on every frame
    void shutdown();                                          // release models/natives
}
```

Interop contract asserted by `LivenessBackendInteroperabilityTest` (12 tests,
one parameterized leg per backend):

1. **I-IDENTITY** — `id()` non-blank, stable, lowercase-hyphen audit
   vocabulary, distinct across the three backends, verbatim values pinned.
2. **I-LIFECYCLE** — `initialize` either succeeds cleanly or refuses as
   `LivenessException` *with an error code and an actionable message*; raw
   reflection/class-loading errors leaking out are contract violations.
3. **I-CONVERSATION** — after a clean init, the full conversation holds:
   `analyzeFrame` never null with a sane face count, scores and PAD confidence
   in `[0,1]`, `assessPad` never null, **no-face frames never fabricate a PAD
   attack**, `shutdown` idempotent.
4. **I-ENGINE** — `FaceLivenessEngine`'s constructor accepts an available
   backend and rejects an unavailable one with the uniform mapped code
   `LivenessErrorCode.DEVICE_CONNECTION_FAILURE`.

## 3. Methodology

**Static review** — all three implementations read against the SPI javadoc and
the engine's call sites (`FaceLivenessEngine` lines 156–173: face-count gate →
quality gate → PAD → score).

**Dynamic execution** — one run of the complete suite (`./mvnw test`, exit 0
captured directly from Maven before any output filter) including the
parameterized contract above, executed 2026-10-06 on Linux x86_64, Java 17.0.20,
OpenCV natives loaded locally, ONNX Runtime 1.24.2 and the bundled
MiniFASNet-V2 model on the classpath.

**Availability probing, not assumption** — MediaPipe's availability depends on
`org.tensorflow:tensorflow-lite` plus a `face_mesh.tflite` model file, neither
of which ships in this build. The contract therefore *probes* availability and
asserts the corresponding branch, so the same test holds on a machine where
TFLite is present.

## 4. Backend profiles

| Dimension | `mock` | `onnx-minifasnet-v2` | `mediapipe-facemesh` |
|---|---|---|---|
| Class | `MockLivenessBackend` | `OnnxMiniFasNetBackend` | `MediaPipeFaceMeshBackend` |
| Runtime dependencies | none | ONNX Runtime (bundled) | TFLite runtime via reflection (**not bundled**) |
| Model | none (scripted `Subject`) | `models/minifasnet_v2.onnx`, 1,744,116 bytes, SHA-256 `d7b3cd9ba8a7ceb13baa8c4720902e27ca3112eff52f926c08804af6b6eecc7b` verified on load | `face_mesh.tflite` (~5 MB, download-on-demand, absent here) |
| Face detection | scripted (`subject.faceCount`) | Haar cascade, crop with edge clamping | Haar cascade (reflection) |
| Signal fidelity | fully scripted | EAR/smile/pose/gaze from geometry | 468-point landmarks (EAR, smile, pose, gaze) + 15-frame temporal buffer (min 8 frames) |
| PAD basis | scripted verdict | MiniFASNet 3-class head (printed-photo / live / screen-replay) | liveness-score threshold 0.5 → `SCREEN_REPLAY` only |
| Determinism | seeded (`SplittableRandom(42)`), ±0.01 noise | model inference | not executable in this build |
| Availability probe | always | `isRuntimeAvailable()` | `isTfLiteAvailable()` / `isOpenCvAvailable()` |
| Intended use | tests, CI, attack harness | production service path (`PassiveScoringService` `auto`) | high-fidelity landmarks where TFLite is deployable |

## 5. SPI conformance matrix (observed)

| Contract | mock | onnx | mediapipe |
|---|---|---|---|
| I-IDENTITY: audit-safe, stable, distinct id | ✅ `mock` | ✅ `onnx-minifasnet-v2` | ✅ `mediapipe-facemesh` |
| I-LIFECYCLE: clean init **or** coded `LivenessException` refusal | ✅ clean (no-op) | ✅ clean (checksum-verified load) | ✅ **fail-closed refusal**: `ENGINE_INTERNAL_ERROR`, "TensorFlow Lite not on classpath; add org.tensorflow:tensorflow-lite" |
| I-CONVERSATION: full SPI dialogue in `[0,1]`, non-null PAD | ✅ | ✅ | ⚪ n/a (refused init — branch asserted instead) |
| No-face frames must not fabricate an attack | ✅ | ✅ | ⚪ n/a |
| Idempotent `shutdown` | ✅ | ✅ | ✅ (null-guarded; also verified via engine branch) |
| I-ENGINE: `FaceLivenessEngine` accepts iff available | ✅ | ✅ (also driven end-to-end on the genuine-face fixture) | ✅ refusal maps to `DEVICE_CONNECTION_FAILURE` |
| Real inference evidence in-suite | n/a (scripted) | ✅ `OnnxMiniFasNetBackendTest` (10): provenance, gating, softmax, genuine-face > 0.7, bona-fide verdict, print-attack fixture | ❌ not executable here (see §8) |

✅ = asserted and green in this run · ⚪ = branch not reachable in this
environment, asserted as "clean or coded refusal" · ❌ = limitation

## 6. Full-suite results (2026-10-06)

`./mvnw test` → **439 tests, 0 failures, 0 errors, 0 skipped, exit 0**;
no failing suites. Backend-relevant attribution (suite counts read from
Surefire XML):

| Area | Suites | Tests | Backend exercised |
|---|---|---|---|
| Engine core, scripted | `EngineEscalationTest` (9), `EngineFlowTest` (4), `ErrorConditionsTest` (10), `PadBlockingTest` (5) | **28** | mock |
| Android orchestrator | `AndroidLivenessOrchestratorTest` (13), `FrameRateFallbackTest` (3), `LivenessEvidenceBindingTest` (7) | **23** | mock (via `AndroidOrchestratorSupport`) |
| Attack-scenario eval | `AttackScenarioHarnessTest` (4) | **4** | mock (harness default; `backendFactory` injectable) |
| Backend contract + provenance | `OnnxMiniFasNetBackendTest` (10) | **10** | ONNX |
| Service scoring path | `PassiveScoringServiceTest` (5, incl. `auto`-mode model availability), `PrintAttackFixtureTest` (1, model independently classifies the print fixture) | **6** | ONNX (+ heuristic fallback) |
| **Interop contract (all three)** | `LivenessBackendInteroperabilityTest` | **12** | mock + ONNX + MediaPipe |
| Decision core, backend-independent | `LivenessDecisionLogicTest` | 13 | none (pure logic) |
| Remainder (API, audit, device, config, diagnostics, models…) | — | 343 | backend-independent (scorers mocked where relevant, e.g. `DecisionEngineServiceTest`) |
| **Total** | | **439** | |

Coverage per backend: **mock 55 direct + 12 shared · ONNX 16 direct + 12 shared ·
MediaPipe 12 (seam/refusal branch).** MediaPipe is the only one of the three
with **zero inference evidence** in this suite — see §8.

## 7. Findings

**F1 — The seam is genuinely interchangeable.** All three backends pass the
identical parameterized contract; the engine's constructor path is the same
for every one of them. No backend-specific branching exists in
`FaceLivenessEngine`.

**F2 — Unavailability fails closed, uniformly.** A backend that refuses to
start (`LivenessException`) is mapped by the engine to
`DEVICE_CONNECTION_FAILURE` — the same degradation a missing camera produces,
not a crash loop. Verified for MediaPipe's missing-TFLite refusal in this run;
the mapping itself is also covered independently by
`ErrorConditionsTest.backendInitializationFailureMapsToDeviceConnectionError`
(a fourth, deliberately-broken backend).

**F3 — The engine's face-count gate absorbs backend convention drift.**
`MockLivenessBackend.scorePassiveLiveness` returns its scripted score
regardless of signals, while ONNX returns `0.0` for no-face frames. Because
`FaceLivenessEngine` short-circuits on `faceCount == 0` *before* calling
score/PAD, the divergence cannot reach a decision. The contract still pins the
stronger property for every available backend — no-face must not fabricate a
PAD attack — so a future engine change that relaxes the gate cannot silently
turn either convention into an unearned block.

**F4 — PAD fidelity is *not* interchangeable, though the seam is.** ONNX
carries a 3-class attack head (printed-photo, screen-replay);
`MediaPipeFaceMeshBackend.assessPad` derives its verdict from the liveness
score at a fixed 0.5 threshold and can only report `SCREEN_REPLAY`; the mock
is scripted. Swapping backends therefore changes the security posture even
though every interface assertion passes. Deployment selection must follow
`docs/backend-swapping.md` §"When to use", and the audit trail
disambiguates via the stable ids (pinned by
`theReportCitesExactlyTheseBackendIds`).

**F5 — Two wiring paths exist and must stay aligned.** The Spring path
(`PassiveScoringService`, `mosip.liveness.backend = auto|heuristic`) uses
ONNX directly with heuristic fallback, while the SPI bean
(`AppConfig.livenessBackend()`) is the mock, consumed by engine/embedding
callers and injectable into the eval harness.The suite exercises both (6 + 55 tests respectively), but a future backend selection config should cover
both, not just one.

*Resolved 2026-10-07:* `mosip.liveness.backend` is now that single key.
`auto`/`heuristic` keep both paths exactly as they were, and explicit backend
ids (`mock`, `onnx-minifasnet-v2`, `mediapipe-facemesh`, `tflite-minifasnet`)
move both together through one vocabulary (`LivenessBackendSelection`),
refusing to fall back to another scorer when an explicit id cannot load.
`LivenessBackendSelectionTest` pins the shared vocabulary and the SPI bean,
`PassiveScoringServiceTest` the service side (9 tests).

**F6 — Suite totals are dominated by backend-independent logic.** 356 of 439
tests never touch a backend — the 13 decision-logic tests plus the 343-test
remainder (API, audit chain, device, config, diagnostics).
Backend behaviour is concentrated in the 83 attributed tests, so a regression
in any backend shows up in a small, well-identified set of suites — the
report's attribution table doubles as the watch-list.

**F7 — Per-frame cost at the seam is measured, and it is all in the
implementations (2026-10-07).** One SPI conversation on the genuine-face
fixture costs the mock **0.017 ms** and ONNX **156.8 ms** mean (section 10):
the interface itself adds nothing, and ONNX's three near-equal phases expose
that each probability call re-runs detection internally — one frame, three
Haar cascades, two inferences. Single-threaded ONNX throughput (**6.4 fps**
on the test host) sits under the 10–15 fps analysis budget, so camera-rate
deployment depends on removing that duplication or parallelising scoring —
not on anything at the seam.

## 8. Limitations

1. **MediaPipe inference was not executed.** The TFLite runtime and
   `face_mesh.tflite` are not part of this build (by design —
   `backend-swapping.md` documents the optional dependency), so MediaPipe's
   evidence covers identity, fail-closed initialization, and the engine's
   refusal mapping — *not* landmark accuracy, EAR/pose signal quality, or
   temporal-buffer behaviour. To upgrade this row: add
   `org.tensorflow:tensorflow-lite`, supply the model file, and re-run the
   contract; the `I-CONVERSATION` leg will then execute automatically.
2. **TfLiteMiniFasNetBackend** was excluded per the requested scope, so the
   report says nothing about it.
3. **Static findings F4/F5** are code-reading conclusions (cited lines), not
   measurements; the dynamic findings are all traceable to named tests.
4. Figures reflect one run on one host (Linux x86_64, Java 17); the timing in
   section 10 likewise reflects that host and a single 100-frame run of one
   fixture — a latency microbenchmark, not a device or camera-rate study.
   End-to-end/device timing remains out of scope for interoperability and
   lives in `docs/status-report.md` §4.

## 9. Recommendations

1. Treat `LivenessBackendInteroperabilityTest` as the gate: any new backend
   must be added to the `backends()` stream; the contract runs unchanged.
2. When TFLite support is ever enabled, re-run this report — the MediaPipe
   columns in §5 and §6 should convert to ✅ with inference evidence.
3. ~~If a backend-selection config is introduced (F5), extend the interop test
   to construct the engine through that selection path as well.~~ *Done
   2026-10-07: `theBackendSelectionConfigBuildsTheEngineThroughTheSameContract`
   constructs the engine via the selection for every selectable id.*
4. Keep the id-pinning test and this report in lockstep: renaming a backend
   id is a report update, not a silent change.

## 10. Measured per-frame latency and throughput (2026-10-07)

Interop is about the seam; deployments care what a frame costs at it.
`BackendPerFrameLatencyTest` feeds both executable backends the **same
representative input** — the genuine-face fixture
(`src/test/resources/fixtures/real-face.jpg`, 256×320) — through one full SPI
conversation **in engine order** (`analyzeFrame` → `assessPad` →
`scorePassiveLiveness`), single-threaded, image decode excluded (the frame is
built once): 10 warm-up frames (JIT, ORT session, cascade caches) then **100
measured frames** per backend. Host: Linux x86_64, 4 × Intel Core i3-1005G1
@ 1.20 GHz, OpenJDK 17.0.20.1, ONNX Runtime 1.24.2 (CPU execution provider),
OpenCV natives loaded locally. One run, one host; raw output is
`target/backend-latency-results.txt` and Appendix A reproduces it.

| Backend | Phase means (ms) | Frame mean | p50 | p95 | max | Throughput |
|---|---|---|---|---|---|---|
| `mock` | analyze 0.012 · pad 0.001 · score 0.005 | **0.017 ms** | 0.013 | 0.037 | 0.105 | **≈59,515 fps** |
| `onnx-minifasnet-v2` | analyze 46.680 · pad 54.386 · score 55.702 | **156.768 ms** | 148.039 | 206.396 | 309.050 | **6.4 fps** |

> **Follow-up (2026-10-13):** the triple-detection finding below is fixed.
> `OnnxMiniFasNetBackend` now keys one frame's analysis (detection, quality and
> raw probabilities) to the frame's pixel content, so the conversation above
> costs **one** detection and **one** inference — the SPI contract is unchanged
> and the values are bit-identical (`docs/performance-baseline.md` §9 has the
> proof and the full before/after). `BackendPerFrameLatencyTest` therefore
> reports two workloads now: `repeated-frame` (memo hits — no longer a per-frame
> cost) and `distinct-frames` (**61.8 ms** mean, 16.2 fps at the seam). Quote
> per-frame cost from the distinct line. The table below is the 2026-10-07
> repeated-frame record, left as measured.

Percentiles are nearest-rank over the 100 frames; throughput is the
sequential single-threaded rate implied by the mean frame time. Score ranges
across the measured frames: mock `[0.9203, 0.9397]` (seeded noise around the
scripted verdict), ONNX `[0.9950, 0.9950]` — the genuine face classified live
on every frame, in line with `OnnxMiniFasNetBackendTest`'s > 0.7 pin.

What the numbers say:

- **The seam itself is free; the implementations carry all the cost.** The
  identical conversation scripted costs 0.017 ms — roughly 9,000× under
  ONNX. Interoperability overhead at the interface is unmeasurable.
- **ONNX pays triple detection per frame, at the seam and not only in the
  HTTP path.** `analyzeFrame` runs the Haar cascade (~46.7 ms), and both
  `scorePassiveLiveness` and `assessPad` independently call
  `probabilitiesFor(frame)` (`OnnxMiniFasNetBackend` lines 503 and 513),
  each re-running detect + crop + inference (~54–56 ms apiece). One
  qualifying frame costs three cascades and two inferences; the phases are
  too even for anything else to be true, and it corroborates the
  duplicate-work finding the HTTP stage timers produced.
- **6.4 fps single-threaded sits under the 10–15 fps analysis budget.** On
  this host, camera-rate scoring needs the duplicate detection removed
  (reuse the crop/signals for both probability calls) and/or parallel
  scoring. The mock numbers show neither the SPI nor the harness adds
  measurable overhead to that budget.

Caveats: the same fixture frame repeats 100× (a latency microbenchmark —
Haar and ORT cost are content- and resolution-dependent, so other inputs
will differ); JPEG decode, session management and the engine layer are
outside these numbers; **run-to-run variance is real** — a repeat during the
full-suite run measured ONNX at 172.1 ms mean / 5.8 fps (same shape and
phase ordering, ~10% higher) and mock at 0.005 ms / ≈218,830 fps (whose
absolute figure is dominated by host cache/turbo state, not backend logic);
and MediaPipe has none, because it remains non-executable in this build
(see §8).

### Appendix A — Reproduction

```bash
# Full suite (the baseline cited above)
./mvnw test; echo "exit=$?"

# Interoperability contract only
./mvnw test -Dtest='LivenessBackendInteroperabilityTest'

# Per-backend evidence suites
./mvnw test -Dtest='OnnxMiniFasNetBackendTest'
./mvnw test -Dtest='EngineFlowTest,EngineEscalationTest,ErrorConditionsTest,PadBlockingTest'

# Per-suite counts (as used in §6)
awk 'match($0,/tests="[0-9]+"/){print FILENAME": "substr($0,RSTART+7,RLENGTH-8)}' \
    target/surefire-reports/TEST-*.xml

# Per-frame latency + throughput for the mock and ONNX backends (section 10)
./mvnw test -Dtest='BackendPerFrameLatencyTest'
cat target/backend-latency-results.txt
```
