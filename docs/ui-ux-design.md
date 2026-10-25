# MOSIP Face Liveness & PAD — UI/UX Design

**Scope:** resident registration, operator authentication, supervisor authentication —
one interaction model, three roles. Desktop (JavaFX), Android (Flutter) and the
browser console implement the same states and the same message catalog.

**Implemented reference surfaces:**

| Surface | Artifact |
|---|---|
| Browser console (working reference UI) | `src/main/resources/static/index.html` + `console.js` + `console.css` |
| Console design system (tokens, components, states) | `src/main/resources/static/console.css` (tokens, states, dark mode) |
| Console screens (role picker, capture, policy, credential modal, saved state) | `src/main/resources/static/index.html` + `src/main/resources/static/console.js` |
| Flutter (Android) | `android_client/lib/liveness/liveness_view.dart` + `liveness_view_model.dart` |
| Desktop (JavaFX) | `src/main/java/io/mosip/liveness/client/LivenessChallengeOverlay.java` + `src/main/resources/fxml/LivenessChallengeOverlay.fxml` (guide §3.6) |
| Message catalog (single source) | `LivenessView.defaultMessages`, `LivenessErrorCode.userMessage()` |
| Console behaviour guards | `src/test/js/console-guards.test.mjs` (CI job *Console DOM tests*) |

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
- The console's own entry tab follows the role's job, not the design's tab
  order: a **supervisor** lands on *Policy* (the screen its authority is for) and
  an **operator** on *Sessions* (its read-only review surface). The operator's
  tab row is the four read-only views — Verify, Sessions, Audit, Rejections — and
  the admin tabs are removed from it, not merely refused on save.
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

---

## 7. Browser console design system (implemented)

The console implements a "modern institutional enterprise" design system:
crisp 1px borders, white containers over a tinted canvas, high-contrast slate
type and a cobalt accent. The tokens live in `console.css` as CSS custom
properties, providing the reference implementation for the role picker,
resident capture, supervisor policy editor, and credential auth dialog.

### 7.1 Tokens

| Token group | Values |
|---|---|
| Canvas / surface | `#F8FAFC` page, `#FFFFFF` container, `#F1F5F9` subtle fill, `#E2E8F0` / `#CBD5E1` borders, `#020617` camera surface |
| Primary | `#1E56D0`, hover `#1B4DBA`, active `#003EAA`, subtle `#EFF6FF`, badge border `#BFDBFE`, focus ring `rgba(59,130,246,.35)` |
| Text | `#0F172A` primary, `#475569` secondary, `#64748B` muted |
| Status | success `#15803D` / `#DCFCE7` / `#86EFAC`, danger `#B91C1C` / `#FEE2E2` / `#FCA5A5`, warning `#B45309` / `#FEF3C7` / `#FCD34D`, neutral `#475569` / `#F1F5F9` / `#CBD5E1` |
| Banners | one notch lighter than the pills: success `#F0FDF4` on `#86EFAC`, danger `#FEF2F2` on `#FCA5A5`, in-progress `#FEFCE8` on `#FDE047` |
| Radii / elevation | 8px controls and banners, 12px cards, 16px role cards, pills full; 1px border plus a micro-shadow, no blurred drop shadows |

**Type.** Inter is specified for UI and JetBrains Mono for hashes,
nonces and diagnostics. The console ships under `style-src 'self'`, so it loads
no font CDN: both families are used when the workstation already has them and
the platform UI/mono stack otherwise.

**Theme.** The console follows the workstation's light/dark preference by
default (`prefers-color-scheme`), and the header's Auto / Light / Dark control
overrides it for rooms whose OS setting does not match the lighting. The dark
palette reuses the same tokens as the light one, because the console is also
read on dim enrolment workstations, and `color-scheme` lets native controls
match whichever palette is active.

### 7.2 Layout

- 1440px content cap, 24px gutters, 32px vertical rhythm; role picker as a
  3-card grid that collapses to one column under 900px.
- **Resident capture**: preview 1fr beside a 340px panel (step card,
  instructions, capture facts), collapsing to one column under 860px.
- **Staff capture**: `capture-grid` — preview beside a 340px control panel
  (workflow, device, the four actions, verdict), because a 4/3 preview across a
  1440px card is ~1080px tall and pushed Run/Stop below the fold on a laptop.
- **Policy**: form 1fr beside the audit-chain sidebar at 340px; the workflow
  segmented picker sits in its own bordered header row.

### 7.3 Components

- **Screen headers** — the picker, Resident capture and Sessions surfaces open with
  the Stitch eyebrow row: a cobalt context chip (`Access gate`, `Live capture`,
  `Session review`) beside the PAD standard in mono, so the page states its
  context before its title.
- **Header** — two-line identity (Face Liveliness / Console) beside the face-scan mark, live service
  chips as dot + uppercase state text (`service: ok`, `engine: available`,
  `chain: intact`, `session <id>`), signed-in name, role badge, Log out / Switch
  role.
- **Status pills** — a 6px dot and a 12px label on the four status triads; role
  badges use the cobalt `#EFF6FF`/`#BFDBFE` pair.
- **Banners** — icon circle plus title and body, one component (`verdict`) with
  `proceed` / `reject` / `escalate` variants; the resting state is neutral.
- **Camera surface** — hardened `#020617` container, dashed guide with four
  calibration ticks, a scan sweep that runs only while a run is analysing, a
  pinned translucent guidance pill, and a monospace HUD. The guide is sized from
  the viewfinder's own height (74% of it at most, capped at 378 px) so a larger
  oval cannot push itself out of a short preview. Guide states:
  white dashed (idle) → cobalt solid (analysing) → amber (challenge open) →
  green (session passed) or red (rejected).
- **Capture follows the guide** — the frame sent to the engine is the oval's
  region of the camera image plus a 15% margin, not the whole webcam view, so
  the face fills the captured frame the way it fills the oval. The mapping
  undoes `object-fit: cover`, is clamped inside the camera frame, and falls back
  to the whole frame when the guide has no layout (a hidden screen, or a DOM
  without layout in `src/test/js/console-guards.test.mjs`).
- **Current-step card** — eyebrow with an activity dot, title, one-line note, a
  6px progress track and a monospace stage row (`Stage 1 of 3` / `Idle`).
- **Tables** — uppercase label headers on a subtle fill, hairline dividers,
  monospace session ids, status pills in the status column.
- **Footer** — platform line plus the PAD standard; no invented build numbers.

### 7.4 The screen never asserts what it has not read

The design review that produced this implementation found three places where a
pretty screen had started lying, and the rules that came out of it are part of
the design:

- **The camera HUD reports facts.** Its left side is the capture budget actually
  in force (`window.CAPTURE_BUDGET`: 640px long edge, JPEG q0.7, 8 frames/s) and
  its right side is the stream's own state; the card's status pill flips from
  "Camera ready" to "Camera live" with the stream. The mock's station number,
  FPS, lux and trace ids are not rendered — there is nothing behind them here.
- **The Sessions table reports the service.** Rows are the sessions this browser
  tab created (the service exposes no session-list endpoint), their
  status/retries/reason are read back from `GET /sessions/{id}` after a run
  settles and on Refresh, and a column with nothing known says `UNKNOWN` rather
  than defaulting to `PASSED`. While no real session exists the design's
  populated table is shown with placeholder rows, each tagged `SAMPLE`, its
  Open action disabled, and a footnote saying what it is.
- **The Users panel lists only what it read.** `GET /api/v1/users` supplies the
  username, role badge, the service's own state flags (disabled outranks an owed
  password change) and the creation time; the panel states that account writes
  (create, role change, disable, password reset) are not wired into the table,
  rather than offering controls that do nothing.
- **Nothing technical reaches the capture screen.** No score, quality or PAD
  detail in any state — unchanged from §2 above; the step card and HUD only ever
  restate the state the server reported.

### 7.5 Deliberate design decisions for production console

| Feature / Pattern | Console Implementation | Rationale |
|---|---|---|
| `supervisor_policy_credential_auth_modal` — in-page username/password form, FIDO2 row | the same form, in a dialog the console opens itself (`#auth-modal`) | the FIDO2/Smartcard row is omitted: there is no FIDO2 or OTP backend to drive, so it would be an affordance that does nothing. The sign-in state offers Resident, Operator and Supervisor; Supervisor is preselected for a policy save, and the selected role's developer defaults from the role cards are prefilled. Resident needs no credential, so its fields are hidden. The username is read-only once the account is known (an account changes its own password; it does not switch to another one mid-dialog), and it is editable when the console has no name yet and is signing in |
| `supervisor_policy_saved_verified_state` — disabled "Policy saved" button and a sync pill | the status chip (`Saved <WF> policy.`) plus the pending-diff panel | the enabled/disabled state of Save is derived from whether the form differs from the loaded policy, which is what the operator needs to see; a permanently disabled button would hide that |
| Tailwind CDN, Google Fonts, inline `style=` attributes | one hand-written stylesheet of custom properties | the page ships under `style-src 'self'` with no `unsafe-inline` (see `SecurityHeadersFilter`), so CDN CSS would not load |
| `SESSION: INIT-…`, `GATE_POLICY: …`, node version | omitted | static trace strings on a live console read as system state |

### 7.6 Where the console's behaviour is pinned

`src/test/js/console-guards.test.mjs` (CI job *Console DOM tests*) loads the real
`index.html` and runs the real `console.js` against a minimal DOM: the dirty
marker and per-field diff, the workflow-switch guard, discard, unload warnings,
risk classification in the change history, the extra active check, the capture
budget and pacing, the drop-never-queue rule, the credential dialog (§7.7), and —
with §7.4 — that placeholders are tagged and unopenable and that the status,
retry count and reason columns come from `GET /sessions/{id}`. The Java suites
cannot see `src/main/resources/static`, so this harness is what makes a console
regression a red check.

### 7.7 The one dialog, and why nothing is asked through a platform prompt

Every credential the console wants is entered in `#auth-modal`, in the page:

- **Authorising a policy save.** `Save policy` signs in first and the PUT is
  authorised by that session; the dialog is what that sign-in looks like. It
  offers Resident, Operator and Supervisor with Supervisor preselected. Choosing
  Operator signs in read-only, and Resident opens the capture view: neither can
  write policy, so the save is abandoned and the decision log records that a
  supervisor is required.
- **Setting a first password.** The service confines a bootstrapped account to
  `POST /auth/change-password` until it sets one, so the dialog asks for the new
  password on the way in rather than letting the operator land in a session that
  answers `403` everywhere.

Both used to be `window.prompt()` calls. A platform prompt cannot say what it is
asking for or which account it is asking about, tells "cancelled" and "typed
nothing" apart only by an empty string, and some embedded webviews suppress it
entirely — which left a first-time supervisor being asked nothing and then told
to sign in again. The sign-in state opens with the Supervisor role selected and its developer
defaults (the same values printed on the role cards) prefilled, so a local run is
one click; selecting Operator or Resident swaps the defaults, labels and submit
verb, and Resident hides the credential fields entirely. The guard harness now
pins that `console.js` makes no `window.prompt` call, that the role choice and
defaults are present, that an open dialog sends nothing, that cancelling sends
nothing and aborts the save, that the answer is what goes on the wire, and that
the password is cleared from the field and appears nowhere in the page's text
afterwards. The password lives only in that input while the dialog is open; the
thing that persists is the session, an HttpOnly cookie no script can read.

`window.confirm` remains in one place — the unsaved-edits guard before a reload —
where a browser-level yes/no is the whole question and the guard already fails
closed when dialogs are unavailable.
