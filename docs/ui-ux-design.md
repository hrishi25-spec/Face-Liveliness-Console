# MOSIP Face Liveness & PAD — UI/UX Design

**Scope:** resident registration, operator authentication, supervisor authentication —
one interaction model, three roles. Desktop (JavaFX), Android (Flutter) and the
browser console implement the same states and the same message catalog.

**Implemented reference surfaces:**

| Surface | Artifact |
|---|---|
| Browser console (working reference UI) | `src/main/resources/static/index.html` + `console.js` + `console.css` |
| Flutter (Android) | `android_client/lib/liveness/liveness_view.dart` + `liveness_view_model.dart` |
| Desktop (JavaFX) | `src/main/java/io/mosip/liveness/client/LivenessChallengeOverlay.java` + `src/main/resources/fxml/LivenessChallengeOverlay.fxml` (guide §3.6) |
| Message catalog (single source) | `LivenessView.defaultMessages`, `LivenessErrorCode.userMessage()` |

---

## 1. Screen: Face Capture (all three roles)

```
┌──────────────────────────────────────┐
│                                      │
│           Live camera preview        │   ← native preview / webcam
│         ┌────────────────┐           │
│         │   ◯ oval guide │           │   ← positioning guidance
│         └────────────────┘           │
│                                      │
│  ┌────────────────────────────────┐  │
│  │  Checking face liveness…       │  │   ← status / prompt / failure card
│  └────────────────────────────────┘  │
│  [ Retry ]  [ Cancel ]               │   ← context-sensitive actions
└──────────────────────────────────────┘
```

- **Live face preview** — native Texture (Android, frames never cross the
  Pigeon channel) or webcam `<video>` (console); JavaFX ImageView path.
- **Positioning guidance** — oval mask + hint messages (`liveness.hint.*`).
- **Liveness status** — the status card always shows the current state.
- **Capture progress** — progress bar fed by the orchestrator's normalized
  progress value; capture button enabled only on PASSED + valid gate.
- **No technical exposure** — the UI shows generic, localised keys only;
  scores, PAD detail and model internals live in audit/diagnostics.

### State → UI mapping (shared across platforms)

| Orchestrator state | UI |
|---|---|
| `INITIALIZING` / `POSITIONING` | Status card: "Look directly at the camera." / hint |
| `PASSIVE_EVALUATING` | "Checking face liveness…" + indeterminate progress |
| `CHALLENGE_PROMPT` | Challenge card: "Please blink" / "Please smile" / turns + index (1/N) |
| `CHALLENGE_VERIFYING` | Feedback line: "Please continue" → "Action detected" → "Hold still" + progress |
| `CHALLENGE_PASSED` | "Action detected"; next challenge auto-issued when required |
| `PASSED` | "Good, face captured successfully"; capture enabled |
| `ATTEMPT_FAILED` / `RETRY_WAIT` | Failure card + Retry button (retry available) |
| `TERMINAL_FAILURE` | Recovery guidance per `onRepeatedFailure` mode; no retry |
| `DEVICE_ERROR` | Device-specific recovery message (connection / permission) |
| `ABORTED` | Cancelled state; restarting starts a fresh gate |

## 2. Passive liveness UX

- Displays **"Checking face liveness…"** while the scoring window warms.
- **Proceeds automatically** when the median score meets the threshold — no
  user action, no confirmation tap.
- The user is never told a score or a "liveness percentage".

## 3. Active challenge UX

- Triggered **automatically** when passive liveness is below threshold.
- The challenge is **system-selected**; the user never chooses an action.
  The UI renders only the prompt for the issued challenge.
- Prompt catalog: `prompt.blink`, `prompt.smile`, `prompt.turn_left`,
  `prompt.turn_right` (+ LOOK_* variants for pools that include them).
- Real-time feedback while frames are analysed: "Please continue" →
  "Action detected" → "Hold still"; a progress bar reflects combined scoring.
- Challenge index (e.g. "Challenge 1 / 2") when the policy requires several.

## 4. Failure, PAD and retry UX

| Condition | Message (key) | Rules |
|---|---|---|
| Liveness below threshold, active disabled | `liveness.failed.try_again` | Clear + actionable; retry offered |
| Active challenge failed / timed out | `liveness.failed.try_again` | Retry offered while budget remains |
| PAD detected | `liveness.pad.generic` | **Never** exposes detection details; detail only in audit |
| Max retries reached | `liveness.max_retries.recovery` | Guides to recovery; no retry button |
| LOCK_OUT applied | recovery + "Try again in Ns" | Countdown when the client surfaces `lockoutSeconds` |
| Device unavailable / disconnected | `liveness.device.unavailable` / `.disconnected` | Reconnect guidance; does not consume attempts |
| Camera unavailable / denied | `liveness.camera.unavailable` | Permission prompt / settings path |
| Invalid frames (3 consecutive) | `liveness.device.error` | Treated as device error, not an attempt |

Retry affordances: the Retry action appears only in `ATTEMPT_FAILED` /
`RETRY_WAIT` / recoverable `DEVICE_ERROR` states; each retry restarts the gate
with a fresh session (new nonce + challenge sequence) — the UI simply returns
to "Checking face liveness…".

## 5. Online / offline behaviour

- The interaction is **identical** offline: every decision is on-device; there
  is no connectivity spinner in any liveness state.
- Policy and model updates sync only when online and apply to *new* sessions —
  a running gate never changes behaviour mid-session.
- The only online-adjacent surface is the admin config API, which is out of
  the capture UX.

## 6. Consistency across Desktop and Android

- One state machine (the orchestrator) drives all surfaces; Desktop, Android
  and the console render the **same states and the same message keys**.
- The service-mediated path is not a second rendering story: when the desktop
  client talks to the backend instead of running the orchestrator in process,
  `ServiceLivenessEventMapper` turns each response into the same event pair, so
  the overlay renders the same states and keys on either path.
- Identical per-role policy: supervisor stricter (2 challenges, higher
  threshold, LOCK_OUT), resident most forgiving — but visually identical.
- Operator/supervisor auth dialogs reuse the capture screen component with the
  role parameter; liveness starts automatically, never manually.
- Localisation: message keys are the contract; `.arb`/i18n tables map them
  per deployment. Default English strings ship in `LivenessView.defaultMessages`
  and `LivenessErrorCode`.
