# Analysis: Face Liveness & PAD for the MOSIP Android Registration Client

**Problem statement:** MOSIP Prob 04 – Face Liveness Detection and Presentation Attack Detection (PAD) for Registration and Biometric Authentication
**Scope of this document:** Android Registration Client only (Desktop is out of scope except where parity is noted)
**Complexity:** High

> **Reading note.** Items tagged **[VERIFY]** reflect my understanding of the MOSIP Android Registration Client (1.2.x) architecture and common SBI behaviour. They must be confirmed against the `mosip/android-registration-client` repository and the target device vendor's SBI documentation before implementation starts.

---

## 1. What is being asked (in one paragraph)

Before the Android Registration Client accepts a face biometric (resident capture) or completes a face-based authentication (operator, supervisor), it must prove the face belongs to a **live, physically present person** and is **not a presentation attack** (print, screen, replay). It does so with a **hybrid approach**: a silent **passive** check on the incoming frame stream first; if the score is below a configurable threshold, it automatically escalates to an **active** challenge-response (random blink / smile / head turn). Everything must run **locally** so it behaves identically online and offline. It must be **vendor-neutral** (device adapter pattern), **configurable**, **auditable**, and aligned with **ISO/IEC 30107**.

---

## 2. Requirements breakdown

### 2.1 Functional

| ID | Requirement | Source section |
|----|-------------|----------------|
| F1 | Receive face frame stream from L0/L1 device | Overview, Mandatory §1 |
| F2 | Passive liveness → score → compare to threshold | Mandatory §2 |
| F3 | PAD (print, screen, replay at minimum); block capture/auth on attack | Mandatory §3 |
| F4 | Auto-trigger active liveness when passive score is insufficient | Mandatory §4 |
| F5 | Dynamic, unpredictable, system-selected challenge (user never chooses) | Overview |
| F6 | Validate facial action from subsequent frames; re-evaluate liveness during active phase | Mandatory §4 |
| F7 | Integrate into resident capture, operator auth, supervisor auth | Mandatory §5–7 |
| F8 | Retry with configurable limit; configurable behaviour on repeated failure | Mandatory §5, §8 |
| F9 | Per-workflow policy (resident / operator / supervisor) | Configuration |
| F10 | Error handling for 13 listed conditions (see §9 below) | Mandatory §9 |
| F11 | UI/UX per spec (preview, status, prompts, feedback, failure, PAD generic message, retry) | Mandatory §10 |
| F12 | Audit + diagnostic logging | Expected tasks |

### 2.2 Non-functional

| ID | Requirement |
|----|-------------|
| N1 | **Offline-first:** passive, PAD, challenge generation and action validation all on-device |
| N2 | Secure sync of config and model updates when online |
| N3 | Vendor independence (no vendor type leaks above the adapter) |
| N4 | Works on low-resource Android devices (bonus: HW acceleration) |
| N5 | Privacy: no raw frames persisted or transmitted by default |
| N6 | ISO/IEC 30107-1/-3 alignment; ISO/IEC 19794-5 face quality alignment |
| N7 | Consistent UX with Desktop |

---

## 3. Existing Android Registration Client – what we are plugging into

| Area | Understanding | Status |
|------|---------------|--------|
| UI layer | Flutter (Dart) screens, state held in existing providers | [VERIFY] |
| Native layer | Java; Flutter ↔ Java via **Pigeon**-generated channels | [VERIFY] |
| Local storage | SQLite via Room; global params/config synced from server to local DB | [VERIFY] |
| Biometric devices | Secure Biometric Interface (SBI) devices, discovered/invoked through Android **Intents**; capture returns a device-signed biometric payload | [VERIFY] |
| Biometric matching | MOSIP Biometric SDK interface (vendor/mock implementation) used for offline operator/supervisor matching against locally synced templates | [VERIFY] |
| Audit | Existing audit manager writes events to local DB, synced later | [VERIFY] |
| Auth modes | Operator/supervisor can authenticate by password/OTP/biometrics; biometric auth is local-first when offline | [VERIFY] |

### 3.1 Critical architectural gap: capture is not a stream

SBI capture on Android is request/response (Intent in → signed biometric out). The problem statement assumes a **face frame stream**. This is the single most important discovery item:

| Scenario | Implication |
|----------|-------------|
| Device exposes a preview/stream (e.g. MJPEG/bound service/vendor SDK callback) | Build a `StreamingFaceFrameSource` adapter for it |
| Device exposes **only** one-shot capture | Liveness cannot be computed on a stream from the device. Options: (a) vendor SDK extension, (b) use phone camera frames for liveness *and* device capture for the signed image, linked by face-consistency check (§6.4), (c) ask the vendor/MOSIP to extend SBI |
| L0 (unsigned/embedded camera) | Android Camera (CameraX) is the natural frame source |

**Action for week 1:** obtain the target device's SBI integration guide and confirm which scenario applies. The architecture below supports all three behind one interface.

---

## 4. Candidate technologies

### 4.1 Camera / frame acquisition
| Option | Use | Notes |
|--------|-----|-------|
| **CameraX `ImageAnalysis`** | L0 / built-in camera path | Backpressure strategy `STRATEGY_KEEP_ONLY_LATEST`, YUV_420_888 |
| Vendor SDK / SBI stream | L1 devices | Via adapter; format conversion to common frame model |
| Mock file/video replay | Dev + CI | Bonus deliverable, needed early |

### 4.2 Face detection, landmarks, pose, expression (for active actions + quality gating)
| Option | Pros | Cons |
|--------|------|------|
| **ML Kit Face Detection (bundled model)** | Simple API, offline, gives eye-open prob, smile prob, head Euler angles, tracking id | Coarse; no gaze; Google-owned |
| **MediaPipe Face Landmarker** | 478 landmarks, blendshapes (eyeBlink, smile), head pose matrix, offline | Slightly heavier, more integration code |
| Custom TFLite | Full control | Highest effort |

**Recommendation:** MediaPipe Face Landmarker as primary (richer, better for blink/turn robustness); ML Kit as lighter fallback for very low-end devices. Both behind `FaceAnalyzer`.

### 4.3 Passive liveness / PAD
| Option | Pros | Cons |
|--------|------|------|
| **Open-source CNN (e.g. MiniFASNet-family anti-spoofing, exported to TFLite/NCNN)** | Free, small (a few MB), runs on CPU, offline | **No ISO 30107-3 certification**; weaker against replay on high-res screens; training-data licence needs checking |
| **Commercial on-device SDK (iBeta/ISO 30107-3 tested)** | Evidence of PAD performance; handles replay/masks better | Licence cost; vendor lock-in risk (mitigated by interface) |
| Server-side PAD | Strong models | **Violates offline requirement** – reject |
| Hardware PAD (IR/depth on L1 device) | Strongest | Device dependent; treat as an optional extra signal via adapter |

**Recommendation:** Define `LivenessEngine` interface. Ship an **open-source reference engine** for the hackathon/PoC and a documented path to swap in a **certified commercial engine** for production. Be explicit in documentation that the reference engine is **not** a certified PAD solution.

### 4.4 Inference runtime
- **TensorFlow Lite** with delegate chain: NNAPI / GPU (if available) → XNNPACK CPU fallback.
- Models int8/fp16 quantised; target < 5 MB per model.

---

## 5. Proposed architecture (Android)

```
┌────────────────────────── Flutter (Dart) ───────────────────────────┐
│  Resident Face Capture │ Operator Auth │ Supervisor Auth screens    │
│        └── LivenessViewModel (state, prompts, retries)              │
└───────────────▲──────────────────────────────┬──────────────────────┘
        events  │ (Pigeon FlutterApi)          │ commands (Pigeon HostApi)
┌───────────────┴──────────────────────────────▼──────────────────────┐
│                     Native Java layer (new `liveness` module)       │
│                                                                      │
│   LivenessOrchestrator (state machine)  ◄── LivenessPolicyProvider  │
│        │            │             │                (config)          │
│        │            │             └── ChallengeSelector (SecureRandom)
│        │            │                                                │
│        ▼            ▼                                                │
│   FaceFrameSource   LivenessEngine ── PassiveScorer                  │
│   (adapter iface)   (iface)        ├─ PadDetector                    │
│     ├ CameraXSource                └─ ActionVerifier                 │
│     ├ SbiStreamSource                      │                         │
│     └ MockSource             FaceAnalyzer (quality/pose/blink/smile) │
│                                                                      │
│   LivenessEvidenceSigner (Android Keystore) · AuditBridge · ModelStore
└───────────────┬──────────────────────────────────────────────────────┘
                │ existing integrations
        Biometric device (SBI Intent) · Biometric SDK · Audit · Config sync
```

### 5.1 Key design decisions

1. **Orchestrator owns the flow**, not the UI. Flutter only renders state and forwards the "start/cancel" commands. This guarantees Resident/Operator/Supervisor flows behave identically.
2. **Liveness is a gate in front of the existing capture/auth steps**, not a replacement. Success yields a *Liveness Result* object that the existing flow consumes.
3. **Frames stay native.** Preview is rendered via a Flutter `Texture`; raw frames are never passed through the Pigeon channel (too slow, and a privacy risk).
4. **Everything behind interfaces:** `FaceFrameSource`, `LivenessEngine`, `FaceAnalyzer`, `LivenessPolicyProvider`.
5. **Fail closed:** any exception, timeout, or missing model = liveness failed (never "skip").

### 5.2 Passive → active decision

```
frames ─► quality gate ─► passive score (windowed median) + PAD flags
                                 │
        PAD attack flagged ──────┼──► FAIL attempt (generic message, audit detail)
                                 │
        score ≥ threshold ───────┼──► PASS ─► proceed to capture/auth
                                 │
        score < threshold ───────┴──► ACTIVE: N random challenges
                                         each: verify action AND re-run PAD/score
                                         all pass ─► PASS ; any fail/timeout ─► FAIL attempt
```

Notes:
- **Windowed scoring** (e.g. median over the last 8–15 quality-passing frames, ~0.5–1 s) avoids single-frame flukes.
- **PAD flag vs low score:** a *detected attack* fails immediately; a merely *low score* escalates to active. This follows the spec's wording ("not accept when PAD fails" vs "initiate active when threshold not met").
- **Active phase re-evaluates PAD** (spec: "re-evaluate liveness during active verification"), so a video replay of a person blinking is still caught by PAD.

### 5.3 Dynamic challenge design
- Pool configurable; defaults: `BLINK, SMILE, TURN_LEFT, TURN_RIGHT`.
- Selection with `SecureRandom`; **no immediate repeat**; sequence unique per session; count ≥ configured minimum.
- Each challenge has a **timeout** and a **session nonce + sequence id** recorded in the evidence record.
- `LOOK_UP/DOWN` map to head pitch (true gaze estimation is not reliably available on-device; document this limitation).
- Challenge types not supported by the selected engine are filtered out at runtime via **capability discovery**.

| Action | Detection approach | Anti-false-positive rule |
|--------|--------------------|--------------------------|
| Blink | Eye-open probability / blink blendshape or EAR: open → closed → open within window | Require full cycle; reject if eyes closed for > ~1 s (sleeping/photo-cutout) |
| Smile | Neutral baseline → smile score above threshold, sustained for ≥ N frames | Baseline captured before prompt |
| Turn left/right | Yaw delta from baseline beyond ± threshold (≈ 20–25°) | Direction mirrored correctly for front camera; face remains tracked |
| Look up/down | Pitch delta | Same |

### 5.4 Face-to-capture binding (anti-substitution)

A secure liveness check is meaningless if a different image gets captured afterwards. Required binding:

1. Liveness session ID + nonce created by orchestrator.
2. The frame (or its hash) used for capture/auth must come from the **same session**, within a short validity window.
3. For L1 signed capture: after the device returns the signed face, run a **1:1 face consistency check** between the best liveness frame and the signed capture (on-device matcher) [VERIFY availability]. Mismatch = fail.
4. The orchestrator emits a **signed Liveness Evidence record** (Android Keystore key; fields: session id, user role, policy version, model version, scores, challenges, result, timestamp, hash of final frame). Stored in the audit trail; optional inclusion in packet metadata (requires MOSIP-side agreement — treat as open question).

---

## 6. Workflow integration

### 6.1 Resident registration
`Start capture → open frame source → passive/PAD → [active] → PASS → trigger normal capture (device/SBI) → binding check → accept face`. Retry resets the session (new nonce, new challenge sequence). Retry limit per policy.

### 6.2 Operator authentication
Liveness gate **before** face match. On pass → capture → match (local SDK offline / auth service online) → result. Liveness result and match result are independent; both must pass.

### 6.3 Supervisor authentication
Same gate with `role=SUPERVISOR`. Additional rule: supervisor identity must differ from the logged-in operator; physical presence is the justification for stricter policy defaults (e.g. min 2 challenges).

### 6.4 Behaviour after repeated failure (configurable)
| Mode | Meaning | Risk |
|------|---------|------|
| `BLOCK` | Face step cannot complete; guide to recovery | Safest |
| `FALLBACK_OTHER_MODALITY` | Operator/supervisor use another configured auth mode | Acceptable if policy allows |
| `LOCKOUT_TEMPORARY` | Cool-down timer then retry | Mitigates brute-force probing |
| `SUPERVISOR_EXCEPTION` | Exception flow with audit | Weakens control; off by default; always audited |

---

## 7. Configuration design

Use the existing global-param / config sync framework; keys under `mosip.registration.liveness.*`. Per-workflow overrides take precedence over defaults.

| Key | Type | Default (proposed) |
|-----|------|--------------------|
| `...enabled` | bool | true |
| `...passive.threshold` | float 0–1 | 0.80 |
| `...passive.window_frames` | int | 10 |
| `...active.enabled` | bool | true |
| `...active.min_challenges` | int | 1 (supervisor: 2) |
| `...active.challenge_types` | csv | BLINK,SMILE,TURN_LEFT,TURN_RIGHT |
| `...active.challenge_timeout_sec` | int | 8 |
| `...max_retries` | int | 3 |
| `...on_repeated_failure` | enum | BLOCK |
| `...resident.* / operator.* / supervisor.*` | overrides | inherit |

Guard rails:
- Validate on load; invalid value ⇒ use safe default and log.
- **Security floor:** hard-coded minimums (e.g. threshold cannot be set below a floor; `enabled=false` for supervisor requires explicit build flag) to stop a misconfigured server from silently disabling the control.
- Config is cached locally ⇒ identical offline behaviour. Policy version recorded in every evidence record.

---

## 8. Online / offline

| Capability | Where it runs |
|------------|---------------|
| Frame capture, passive, PAD, challenge selection, action validation | **Device, always** |
| Policy/config | Synced when online, cached locally |
| Model updates | Downloaded when online, **signature + hash verified**, atomic swap, rollback to previous version; offline sideload of signed bundle (bonus) |
| Audit/metrics | Stored locally, synced later |

No liveness decision may depend on connectivity.

---

## 9. Error handling matrix

| Condition | Detection | Counts as attempt? | User message (generic, actionable) | Recovery |
|-----------|-----------|--------------------|------------------------------------|----------|
| Device unavailable / connection failure | Adapter error, intent failure | No | "Device not connected. Check connection." | Re-discover; retry |
| Camera unavailable / permission denied | CameraX / permission result | No | "Camera not available." | Permission prompt; settings link |
| Face not detected | Analyzer, no face > 3 s | No | "Look directly at the camera." | Continue |
| Multiple faces | Analyzer count > 1 | No | "Only one person in view." | Continue |
| Poor quality (blur/light/size/pose) | Quality gate | No | Specific guidance (lighting, distance) | Continue |
| Liveness below threshold | Passive score | No | (auto) → challenge | Active phase |
| PAD failure | PAD flag | **Yes** | "Face verification could not be completed. Please try again." | Retry/lockout |
| Active challenge failure | Verifier | **Yes** | "We could not verify face liveness. Please try again." | Retry |
| Challenge timeout | Timer | **Yes** | same | Retry |
| Max retries exceeded | Counter | – | "Could not complete. <recovery path>" | Per `on_repeated_failure` |
| Invalid/incomplete frames | Frame validator | No (after 3 consecutive → device error) | "Device error." | Reconnect |
| Engine/model error | Exception, hash mismatch | No | "Verification unavailable." | **Fail closed**; diagnostic log |

PAD details go only to audit/diagnostics, never the UI.

---

## 10. Security & privacy

**Threats and mitigations**

| Threat | Mitigation |
|--------|-----------|
| Print / screen / replay (ISO 30107 scope) | PAD model + active challenge with re-evaluation |
| Predictable challenge | `SecureRandom`, no repeats, sequence ≥ min count, per-session nonce |
| **Digital injection** (virtual camera, hooked camera API, emulator) – *outside ISO 30107 scope* | Prefer L1 signed device path; root/hook/emulator detection; Play Integrity (when online) as a risk signal; consistency check vs signed capture |
| Swap after liveness | Session binding + signed evidence + 1:1 consistency check (§5.4) |
| Model tampering | Signed model manifest, SHA-256 verification at load, store in app-private storage |
| Config tampering | Config from existing authenticated sync; floor values in code |
| Data exposure | Frames in memory only; `FLAG_SECURE` on liveness screens; no logging of images; audit contains scores not pixels |
| Brute-force probing of PAD | Retry limit + temporary lockout; generic PAD messages |

**Privacy:** biometric frames are never stored or sent for liveness; metrics are anonymised (no user id, bucketed).

---

## 11. ISO/IEC 30107 alignment

| Standard | Relevance | Plan |
|----------|-----------|------|
| 30107-1 | Terminology/framework (PAI, PAD subsystem, attack presentation) | Use consistent vocabulary in design and logs |
| 30107-3 | Test & reporting: **APCER** (per PAI species), **BPCER**, reporting | Build attack test set: print (paper, photo), screen (phone/tablet/monitor), replay video; report APCER per species and BPCER; log in a reproducible test report |
| 19794-5 | Face image data/quality | Quality gate rules (pose, size, illumination, expression neutrality) borrowed for capture acceptance |

Important honesty points for the report:
- Self-testing is **not** certification. Formal conformance requires an accredited lab (e.g. iBeta Level 1 covers print/screen/replay; Level 2 adds masks).
- The open-source reference engine should be reported with measured APCER/BPCER on our own dataset and flagged **uncertified**.
- Mask/3D attacks may exceed a passive RGB model; document this as residual risk and show the certified-engine swap path.

---

## 12. Performance plan (low-resource devices)

- Analyse at ≤ 640×480, 10–15 fps; process every 2nd frame if frame time budget exceeded (adaptive).
- Single analysis `HandlerThread`; drop-oldest backpressure; never block camera/UI threads.
- Delegates: GPU/NNAPI → XNNPACK fallback; warm-up inference at session start.
- Targets (to validate): passive decision ≤ 1.5 s on mid-range device; per-challenge ≤ timeout; steady-state memory increase ≤ ~60 MB; no UI jank (> 95 % frames ≤ 16 ms render).
- Metrics: avg liveness time, challenge completion time, retry rate, failure rate.

---

## 13. Testing strategy

| Layer | Approach |
|-------|----------|
| Unit | Challenge selector (randomness, no repeat), policy validation, state machine transitions, action verifiers using synthetic landmark sequences |
| Component | `MockFaceFrameSource` replays labelled videos (genuine, print, screen, replay, blink, smile, turn) – deterministic |
| Integration | Pigeon bridge tests; resident/operator/supervisor flows end-to-end with mock device |
| Device matrix | Low (2–3 GB RAM, older SoC), mid, high; with and without GPU/NNAPI |
| Offline | Airplane-mode runs for all three workflows |
| Security | Tampered model, tampered config, virtual camera, replayed evidence |
| PAD evaluation | APCER/BPCER report per 30107-3 style methodology |
| Interop | At least 2 device adapters (mock + one real or CameraX) proving vendor independence |

---

## 14. Risks & open questions

| # | Item | Impact | Mitigation / owner |
|---|------|--------|--------------------|
| R1 | SBI on Android may not expose a stream | Blocks F1 for L1 | Confirm in week 1; fallback to dual-source (camera + signed capture) |
| R2 | Open-source PAD accuracy vs high-quality replay/masks | Security claim | Certified engine swap path; honest reporting |
| R3 | No certified engine licence available for the project | Compliance | Document clearly as PoC-grade |
| R4 | Flutter ↔ native preview latency | UX | Texture-based rendering, native-side drawing of overlays |
| R5 | Where liveness evidence lives in the packet | Backend change | Start with audit-only; propose packet field separately |
| R6 | Face-consistency matcher availability on-device | Binding check | Reuse Biometric SDK if it supports 1:1 face; else embed lightweight matcher |
| R7 | Accessibility (users unable to blink/turn head) | Inclusion | Challenge types configurable; exception path via supervisor with audit |
| R8 | Front-camera mirroring and orientation bugs | False rejects | Dedicated tests per device rotation |

**Questions to resolve:**
1. Which L0/L1 device(s) and SBI version are the target?
2. Is a commercial liveness SDK permissible for the production path?
3. Is changing the packet structure/metadata in scope, or audit-only?
4. Expected accessibility/exception policy for residents who cannot perform actions?
5. Minimum supported Android API level and device RAM?

---

## 15. Suggested phasing

| Phase | Deliverable |
|-------|-------------|
| 0 (≈1 wk) | Discovery: SBI stream capability, repo walkthrough, dataset collection plan |
| 1 | Interfaces + `MockFaceFrameSource` + orchestrator state machine + policy/config + Pigeon API (end-to-end with fake engine) |
| 2 | CameraX source, FaceAnalyzer, quality gate, passive engine + PAD (reference model) |
| 3 | Active challenges + action verifiers + dynamic selection |
| 4 | Integrate resident → operator → supervisor flows; retry/failure modes; UI/UX |
| 5 | Audit, evidence signing, model store/update, offline hardening |
| 6 | Performance tuning, PAD evaluation report, interop tests, documentation |

---

## 16. Summary

The feasible Android design is a **native `liveness` module orchestrated by a state machine**, exposed to Flutter through Pigeon, fed by **pluggable frame sources**, scored by a **pluggable engine**, driven by **cached policy**, and **fail-closed**. The two things that most determine success are (1) confirming whether the target device can actually stream frames and (2) choosing an engine whose PAD claims can be defended — the architecture keeps both decisions swappable.
