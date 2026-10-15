# MOSIP Face Liveness & PAD Engine — Technical Design

## 1. Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    Host Application                      │
│  (Registration Client / Operator Auth / Supervisor Auth) │
└───────────────┬─────────────────────────────────────────┘
                │  LivenessPipeline interface
                ▼
┌─────────────────────────────────────────────────────────┐
│                  FaceLivenessEngine                       │
│  ┌────────────┐  ┌──────────────┐  ┌────────────────┐  │
│  │  Session    │  │   Decision   │  │  Challenge     │  │
│  │  Manager    │  │   Logic      │  │  Selector      │  │
│  └────────────┘  └──────────────┘  └────────────────┘  │
│  ┌────────────┐  ┌──────────────┐  ┌────────────────┐  │
│  │  Audit     │  │   Metrics    │  │  Config        │  │
│  │  Logger    │  │   Collector  │  │  Resolver      │  │
│  └────────────┘  └──────────────┘  └────────────────┘  │
└───────────────┬─────────────────────────────────────────┘
                │  LivenessBackend interface
                ▼
┌─────────────────────────────────────────────────────────┐
│              Pluggable Backend Layer                      │
│  ┌──────────────┐  ┌─────────────────┐  ┌────────────┐ │
│  │ MockBackend   │  │ TfLiteMiniFasNet│  │ (vendor)   │ │
│  │ (testing)     │  │ (real PAD)      │  │ (swap)     │ │
│  └──────────────┘  └─────────────────┘  └────────────┘ │
└─────────────────────────────────────────────────────────┘
```

### Layer Responsibilities

| Layer | Responsibility |
|-------|---------------|
| **Host Application** | Camera frame capture via device adapter, UI prompts, workflow orchestration |
| **FaceLivenessEngine** | Session lifecycle, passive→active decision flow, challenge management, audit logging |
| **LivenessBackend** | Frame analysis, face detection/landmarks, passive liveness scoring, PAD verdict |
| **Config Resolver** | Base config + per-user-type policy overrides → effective session policy, resolved once at session start and **frozen** on the session (`policy_snapshot`) so an in-flight session cannot drift when config is edited. See [configuration.md](configuration.md#per-user-type-policy-at-session-start). |
| **Audit/Metrics** | Structured decision logs (no PII), anonymized operational metrics |

---

## 2. Frame-Streaming Pipeline

### 2.1 Interface

```java
public interface LivenessPipeline {
    String initSession(WorkflowType workflow);
    FrameAssessment pushFrame(String sessionId, Frame frame);
    Challenge requestChallenge(String sessionId);
    ValidationResult validateChallenge(String sessionId, List<Frame> frames);
    SessionSummary closeSession(String sessionId);
}
```

### 2.2 Frame Processing Flow

```
pushFrame(sessionId, frame)
  │
  ├─ Session already PASSED → return passed (idempotent)
  ├─ Session already FAILED → throw INVALID_STATE
  ├─ Liveness disabled → mark bypass, return PASSED
  │
  ├─ State = IN_CHALLENGE:
  │   └─ checkChallengeDeadline()
  │       ├─ No timeout → return CHALLENGE_IN_PROGRESS
  │       ├─ Timeout + budget left → return ESCALATED_TO_ACTIVE
  │       └─ Timeout + budget exhausted → return FAILED
  │
  └─ Normal frame processing:
      ├─ backend.analyzeFrame(frame) → FaceSignals
      │   ├─ faceCount == 0 → RETRYABLE_ERROR (FACE_NOT_DETECTED)
      │   ├─ faceCount > 1  → RETRYABLE_ERROR (MULTIPLE_FACES)
      │   └─ quality < min  → RETRYABLE_ERROR (POOR_QUALITY)
      │
      ├─ backend.assessPad(frame, signals) → PadVerdict
      │   └─ attackDetected → PAD_BLOCKED (terminal, distinct audit event)
      │
      ├─ backend.scorePassiveLiveness(frame, signals) → score
      │
      ├─ State = SCORING:
      │   ├─ Add score to sliding window
      │   ├─ Enough frames? → compute median, decide:
      │   │   ├─ median >= threshold → PASSED
      │   │   └─ median < threshold → ESCALATED_TO_ACTIVE (or FAILED)
      │   └─ Not enough → return SCORING
      │
      └─ State = ESCALATED → return ESCALATED_TO_ACTIVE
```

### 2.3 Active Challenge Flow

```
requestChallenge(sessionId)
  │
  ├─ Assert not terminal, state == ESCALATED
  ├─ selector.next(allowedChallenges) → randomized Challenge
  ├─ issueChallenge(now) → state = IN_CHALLENGE
  └─ Return Challenge

validateChallenge(sessionId, frames)
  │
  ├─ Assert not terminal, state == IN_CHALLENGE
  ├─ checkChallengeDeadline()
  │
  ├─ For each frame:
  │   ├─ backend.analyzeFrame(f) → signals
  │   ├─ faceCount == 0 → failChallenge (face_lost)
  │   ├─ backend.assessPad(f, signals) → if attack → PAD_BLOCKED
  │   └─ Collect signals into sequence
  │
  ├─ ChallengeEvaluators.evaluate(challenge, sequence) → boolean
  │
  ├─ If passed:
  │   ├─ challengesPassed++
  │   ├─ remaining = minChallengeCount - challengesPassed
  │   ├─ remaining == 0 → markPassed(true), return finalPass
  │   └─ remaining > 0 → awaitNextChallenge(), return partialPass
  │
  └─ If failed:
      └─ failChallenge()
          ├─ retriesUsed++
          ├─ budget exhausted? → applyRepeatedFailurePolicy (hard fail)
          └─ budget left? → awaitNextChallenge(), return retryAvailable
```

---

## 3. Passive → Active Decision Architecture

### 3.1 Passive Liveness (Stage 1)

Applies to both execution paths; the HTTP path (`DecisionEngineService`) and the
engine library (`FaceLivenessEngine`) share `LivenessDecisionLogic`, so the rules
below are identical for the running service and for desktop/Android embedding.

- **Score source**: `PassiveScoringService` — the live-class probability of
  **MiniFASNet-V2** (`onnx-minifasnet-v2`, bundled under `resources/models/`,
  Apache-2.0, checksum-verified on load) when the model loads
  (`mosip.liveness.backend: auto`), otherwise the OpenCV quality heuristic
  (`heuristic` mode / graceful fallback). The active scorer id is reported by
  the calibration endpoint.
- **Cold start**: until `passiveMinFrames` (5) scored frames exist, the window
  is *undecidable* — every response is the non-terminal `retry_passive`
  ("Checking face liveness..."). Autofocus, exposure and pose settle during
  the first second of a capture; a verdict on one or two frames is noise.
- **Temporal median voting**: once warm, the **median** of the last
  `passiveWindowFrames` (7) scores is compared to the threshold.
  `LivenessDecisionLogic.decidePassiveWindow()` implements this, and the
  calibration sweep evaluates that *same* function, so measured BPCER/APCER
  describe deployed behaviour rather than a single-frame approximation.
- **Median** (not mean) rejects outliers (motion blur, a blink, an exposure
  shift) without a second model.
- **Decision**: median ≥ threshold → proceed (session `PASSED`); median <
  threshold → **automatic initiation of active liveness** (Stage 2), or a hard
  fail when `activeLivenessEnabled=false`.
- **PAD runs on every frame** alongside passive scoring — the FFT/texture/
  brightness heuristics **OR** the MiniFASNet print/replay verdict. An attack is
  terminal (no retry), but it must be **confirmed across `PAD_CONFIRM_FRAMES` (2)
  consecutive frames**: a genuine screen replay is consistent, while a one-off
  model flip (motion blur, exposure, a large/edge face crop) is not — this is
  what caused device-specific false rejects on some cameras.
- **Challenge lock**: the `PASSIVE → ACTIVE` transition is atomic with issuing
  the challenge (pessimistic row lock on `liveness_sessions`,
  `LivenessSessionRepository.findByIdForUpdate`). While a challenge is open,
  frame submissions are answered with the *same* open challenge
  (`escalate_to_active`, idempotent for the client): a late-arriving high-score
  frame can neither pass the session without the action being performed nor
  issue a duplicate challenge. Final verdicts are written only from the
  passive stage (while unlocked) or from challenge validation.

### 3.2 Active Liveness (Stage 2)

- **Triggered automatically** when passive liveness score is below threshold.
- **System-selected challenges** using `ChallengeSelector`:
  - Shuffled bag per cycle (SecureRandom) — every type appears once before repeat.
  - No challenge is issued twice in a row while alternatives remain.
  - LOOK_DIRECTION targets are randomly chosen from {up, down, left, right}.
- **Rule-based evaluation** (`ChallengeEvaluators`):
  - **Blink**: EAR (Eye Aspect Ratio) dips below close threshold, then recovers above open threshold.
  - **Smile**: smile score exceeds threshold at least once in the window.
  - **Head turn**: yaw reaches target angle (≥ 12° for right, ≤ -12° for left).
  - **Gaze direction**: normalized gaze vector stays within tolerance of target for ≥ 50% of frames.
- **Retry policy**: configurable maxRetries; after exhaustion, applies `RepeatedFailureAction` (LOCK_OUT / FALLBACK / ESCALATE_TO_OPERATOR).

---

## 4. Device Adapter Pattern

```
┌──────────────────────┐     ┌─────────────────────────┐
│  L0/L1 Device SDK    │────▶│  Device Adapter          │
│  (vendor-specific)   │     │  implements:             │
│                      │     │  • open() / close()      │
│                      │     │  • startCapture()        │
│                      │     │  • onFrame(callback)     │
│                      │     │  • Frame → io.mosip.     │
│                      │     │    liveness.core.Frame   │
└──────────────────────┘     └──────────┬──────────────┘
                                        │
                                        ▼
                               LivenessPipeline.pushFrame()
```

The `Frame` class is format-agnostic (RGB_GRAY, RGB_888, NV21, YUV420). Device adapters normalize raw camera output into `Frame` objects. The engine and backend never touch device-specific APIs.

---

## 5. ISO/IEC 30107 Alignment

| ISO/IEC 30107 Concept | Implementation |
|------------------------|---------------|
| APCER (Attack Presentation Classification Error Rate) | `PadMetrics.apcer()` — proportion of attack presentations accepted as bona fide |
| BPCER (Bona Fide Presentation Classification Error Rate) | `PadMetrics.bpcer()` — proportion of bona fide presentations rejected as attacks |
| ACER | `PadMetrics.acer()` — arithmetic mean of APCER and BPCER |
| PAD granularity | Per-frame PAD verdict; attack type classified as an **attack presentation** by PAI class (PRINTED_PHOTO, SCREEN_REPLAY, VIDEO_REPLAY, OTHER — mapped in §6.4) |
| PAD is terminal | PAD failure blocks session immediately — no retry, distinct audit event |
| Evaluation harness | `AttackScenarioHarness` — runs labeled presentations through the full pipeline |

### Evaluation Methodology

1. **Dataset**: labeled presentations — **bona fide presentations** plus **attack presentations** across three PAI classes (Part-1 wording; mapped in §6.4) with varied quality.
2. **Pipeline execution**: end-to-end through `FaceLivenessEngine` with `MockLivenessBackend` (or real model backend).
3. **Metrics**: APCER, BPCER, ACER, FAR, FRR, escalation rate, average latency.
4. **Simulated imperfections**: configurable attack-miss rate (default 3%) and bona-fide FP rate (default 1%).

---

## 6. Injection-Attack Threat Model (Out of ISO/IEC 30107 Scope)

ISO/IEC 30107-1:2023 gives this design its shared vocabulary: a **bona fide
presentation** is the live subject presented to the capture device; an
**attack presentation** places a **presentation attack instrument (PAI)** in
front of the capture device (a printed photograph, a replayed screen — the PAI
classes mapped to our evaluation labels in §6.4). Part 1's scope covers **only
attacks at the capture device during presentation**. Attacks injected
*digitally past* the capture device are outside ISO 30107 entirely and are
modelled here as a separate threat.

### 6.1 Threat surface

| Threat | Mechanism | What the attacker controls |
|---|---|---|
| **Virtual camera** | OS-level software camera (OBS virtual device, `v4l2` loopback, spoofed camera provider) replaces the physical device's output before the app sees it | The entire frame stream delivered to `FaceFrameSource` |
| **Hooked camera API** | Runtime instrumentation (Frida-style hooks, patched camera provider, hooks on CameraX/Camera2 or the SBI device calls) substitutes frames or metadata in-process | Frame buffers at the API boundary, plus camera metadata (timestamps, lens info) |
| **Frame replay into the pipeline** | Frames recorded earlier (leaked stream, prior capture) re-fed to `onFrame` or `POST /sessions/{sessionId}/frames` outside the live device path | A previously-valid stream, replayed after the fact |

In every case **no PAI is presented to the capture device**: there is no
attack presentation for the PAD subsystem to classify, so APCER/BPCER (Part-3
metrics computed over labelled attack presentations) say nothing about
injection resistance. The engine cannot distinguish injected frames from
camera frames by content alone — `Frame` is deliberately format-agnostic bytes
(§4 Device Adapter Pattern) — which is why the countermeasures sit *around*
the pipeline rather than inside the scorer.

### 6.2 Countermeasures (defence in depth)

| Layer | Mechanism | What it buys |
|---|---|---|
| **Signed capture binding** | Evidence from a passed gate is RSA-signed (`LivenessEvidenceSigner`, fail-closed) and carries the best frame's sha256 + per-attempt nonce; the downstream capture must satisfy `bindingOk = isGateValid(sessionId) && consistency(bestFrame, signedCapture)` | A forged liveness stream alone cannot complete authentication — the gate binds to the separately signed capture, so the attacker must also own the capture path |
| **Session + nonce binding** | Every attempt gets a fresh engine session id and SecureRandom nonce (spec §4 invariant); the gate expires after `gateValiditySec` and `isGateValid` fails closed | Replayed frames target a dead session: they neither revive an expired gate nor transfer across attempts |
| **PAD detectors still apply** | Replay-shaped injection (recorded media fed through a virtual camera) is what `SCREEN_REPLAY` / `VIDEO_REPLAY` PAD detection scores per frame | Raises the cost of naive injection — a detection aid, not a proof (§6.3) |
| **Fail-closed pipeline** | Source errors, invalid-frame bursts, missing/invalid model, engine exceptions → `DEVICE_ERROR` / terminal failure, never a skip (R1; tested) | A hook that corrupts or stalls the camera path fails the gate instead of passing it |
| **Session-scoped, rate-limited ingestion** | The desktop service's only frame endpoint is `POST /sessions/{sessionId}/frames` — 404 without a session, 409 outside `ACTIVE` — behind `RateLimitFilter` / `SecurityHeadersFilter`; the SBI device stream is local (loopback) | No anonymous, sessionless frame-ingestion API; remote exposure is an explicit deployment decision |
| **Keyed, tamper-evident audit trail** | HMAC-chained audit hashes (`AuditChainKey`, `MOSIP_AUDIT_HMAC_SECRET`) with DB triggers blocking UPDATE/DELETE | Post-hoc detection: the recorded session/frame/challenge trail cannot be quietly rewritten to hide an injected run |
| **Model integrity** | SHA-256 (+ vendor signature on Android) verified before activation (`ModelStore`) | Blocks substitution of the liveness model itself |

### 6.3 Residual risk (explicit assumptions)

- **Endpoint integrity is assumed, not enforced here.** A rooted/jailbroken
  device or an in-process hook below the camera API defeats any user-space
  frame pipeline. Camera HAL integrity, OS hardening and app/device attestation
  belong to the Registration Client / platform layer; this design documents
  that boundary rather than pretending to cover it.
- **Synthetic live-looking media may evade PAD.** PAD classifies attack
  presentations by their artefacts; injected frames synthesised to look bona
  fide carry no PAI artefacts to catch. The binding and session checks — not
  APCER — are what bound this case.
- **Streams are trusted only as far as they are signed.** `SourceCapabilities`
  carries a `signedStream` flag, and the orchestration spec's dual-source note
  (`liveness.requireSignedStream`) is the high-assurance lever for deployments
  that can require signed capture streams; source selection is not yet
  automated.

### 6.4 PAI vocabulary ↔ evaluation labels

| `PresentationLabel` (eval corpus) | ISO/IEC 30107-1 term | PAI class |
|---|---|---|
| `BONA_FIDE` | Bona fide presentation | — (live subject; no PAI) |
| `PRINTED_PHOTO` | Attack presentation | Printed photograph re-enactment |
| `SCREEN_REPLAY` | Attack presentation | Display presenting captured imagery |
| `VIDEO_REPLAY` | Attack presentation | Displayed recording of the subject |
| *unmodelled: masks* | Attack presentation | 3D mask — residual gap, documented with the corpus |

---

## 7. Sequence Diagram: Full Liveness Check

```
Host                Engine              Backend           Audit
  │                   │                   │                 │
  │─ initSession() ──▶│                   │                 │
  │                   │─ initialize() ───▶│                 │
  │                   │◀──────────────────│                 │
  │◀── sessionId ─────│                   │                 │
  │                   │─────────────────────────────────log─▶
  │                   │                   │                 │
  │─ pushFrame(f1) ──▶│                   │                 │
  │                   │─ analyzeFrame() ─▶│                 │
  │                   │◀── signals ───────│                 │
  │                   │─ assessPad() ────▶│                 │
  │                   │◀── verdict ───────│                 │
  │                   │─ scorePassive() ─▶│                 │
  │                   │◀── 0.92 ──────────│                 │
  │◀── SCORING ───────│                   │                 │
  │                   │                   │                 │
  │  ... (repeat pushFrame) ...           │                 │
  │                   │                   │                 │
  │◀── ESCALATED ─────│                   │                 │
  │                   │─────────────────────────────────log─▶
  │                   │                   │                 │
  │─ requestChallenge()▶                  │                 │
  │◀── Challenge(BLINK)│                  │                 │
  │                   │─────────────────────────────────log─▶
  │                   │                   │                 │
  │─ validateChallenge(frames)──▶          │                 │
  │                   │─ analyzeFrame() ─▶│                 │
  │                   │─ assessPad() ────▶│                 │
  │                   │─ evaluate() ──────│                 │
  │◀── partialPass(1) │                   │                 │
  │                   │                   │                 │
  │─ requestChallenge()▶                  │                 │
  │◀── Challenge(SMILE)│                  │                 │
  │                   │                   │                 │
  │─ validateChallenge(frames)──▶          │                 │
  │◀── finalPass ─────│                   │                 │
  │                   │─────────────────────────────────log─▶
  │                   │                   │                 │
  │─ closeSession() ─▶│                   │                 │
  │◀── SessionSummary │                   │                 │
  │                   │─────────────────────────────────log─▶
```

---

## 8. Thread Safety

- `FaceLivenessEngine` uses `ConcurrentHashMap` for session storage.
- `MetricsCollector` methods are `synchronized`.
- `StructuredAuditLogger` synchronizes on output.
- `LivenessSession` is not thread-safe — single-session-per-thread is assumed (typical for desktop/Android UI threads).
