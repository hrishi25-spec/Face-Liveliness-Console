# Client Integration Guide: MOSIP Registration Client ↔ Liveness/PAD Engine

## Overview

This guide explains how to wire the **MOSIP Registration Client** (Desktop JavaFX and
Android Flutter) to the face liveness + PAD engine via the REST API or in-process library.

**Two integration models are supported:**

| Model | Description | Best for |
|-------|-------------|----------|
| **HTTP Service** (Option B) | Spring Boot service running locally (`:8000`); clients call REST API | Quick integration, existing deployment |
| **In-Process Library** (Option A) | `face-liveness-engine` JAR added directly to client classpath | Zero-latency, offline-first, production |

---

## 1. REST API Endpoints (HTTP Service)

These are the routes a Registration Client actually calls. The full surface — 28
routes across the client-facing, authenticated, operator-keyed and supervisor-keyed
tiers — is in [PRD §7](PRD.md#7-api-reference) and
[docs/api-documentation.json](api-documentation.json).

### Session Lifecycle
```
POST   /api/v1/sessions                     → Create session
GET    /api/v1/sessions/{id}                → Get session status
POST   /api/v1/sessions/{id}/close          → Close session + get summary
```

### Frame Processing
```
POST   /api/v1/sessions/{id}/frames         → Submit a frame (base64 JPEG)
```

### Challenge Validation
```
POST   /api/v1/sessions/{id}/challenges/validate → Validate challenge frames
POST   /api/v1/sessions/{id}/active-liveness     → Request an extra manual challenge
POST   /api/v1/sessions/{id}/evidence            → Submit liveness evidence for verification
```

### Authentication
```
POST   /api/v1/auth/login                    → Sign in (HttpOnly session cookie)
GET    /api/v1/auth/me                       → The signed-in account
POST   /api/v1/auth/change-password          → Replace the caller's own password
POST   /api/v1/auth/logout                   → End the session
```

### Configuration
```
GET    /api/v1/config/{workflowType}         → Get config policy
GET    /api/v1/config/{workflowType}/effective → Get computed effective policy
PUT    /api/v1/config/{workflowType}         → Update config policy (SUPERVISOR session required)
```

### Monitoring
```
GET    /api/v1/metrics                       → Operational metrics
GET    /api/v1/sessions/{id}/audit           → Full audit trail
GET    /health                               → Health check
```

---

## 2. Typical Call Sequence

```
┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
│  Registration     │     │  Liveness        │     │  Registration    │
│  Client (UI)      │────▶│  Engine (API)    │────▶│  Client (Bio)    │
└──────────────────┘     └──────────────────┘     └──────────────────┘
        │                         │                         │
        │  1. createSession()     │                         │
        │────────────────────────▶│                         │
        │  ← session id           │                         │
        │                         │                         │
        │  2. submitFrame()       │                         │
        │────────────────────────▶│                         │
        │  ← action: "proceed"    │ (passive liveness pass) │
        │         OR              │                         │
        │  ← action: "escalate"   │ (needs active challenge)│
        │     + challenge type    │                         │
        │                         │                         │
        │  3. validateChallenge() │ (if escalated)          │
        │────────────────────────▶│                         │
        │  ← action: "proceed"    │                         │
        │         OR "retry"      │                         │
        │         OR "reject"     │                         │
        │                         │                         │
        │  4. closeSession()      │                         │
        │────────────────────────▶│                         │
        │  ← session summary      │                         │
        │                         │                         │
        │  5. proceed to capture  │────────────────────────▶│
        │     (liveness passed)   │                         │
```

---

## 3. Desktop (JavaFX) Integration

### 3.1 Add dependency to `registration-services/pom.xml`

```xml
<dependency>
    <groupId>io.mosip.liveness</groupId>
    <artifactId>pad-liveness-backend</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 3.2 Wire Streamer to LivenessClient

**File: `Streamer.java` — add frame listener:**

```java
// Add field:
private Consumer<byte[]> frameListener;

// Add setter:
public void setFrameListener(Consumer<byte[]> listener) {
    this.frameListener = listener;
}

// In the frame decode loop, after imageBytes = retrieveNextImage(urlStream):
if (frameListener != null) {
    frameListener.accept(imageBytes);
}
```

### 3.3 Create LivenessConfigAdapter

**File: `LivenessConfigAdapter.java` (new):**

```java
package io.mosip.registration.liveness;

import io.mosip.liveness.client.LivenessHttpClient;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class LivenessConfigAdapter {

    private LivenessHttpClient client;

    public LivenessConfigAdapter() {
        String livenessUrl = ApplicationContext.map()
            .getOrDefault("mosip.registration.liveness.service.url", "http://localhost:8000");
        this.client = new LivenessHttpClient(livenessUrl);
    }

    public LivenessHttpClient getClient() { return client; }

    public boolean isLivenessEnabled(String workflow) {
        return client.isLivenessEnabled(workflow);
    }

    public String getWorkflowForModality(String modality) {
        return switch (modality) {
            case "FACE" -> "RESIDENT";  // or OPERATOR/SUPERVISOR based on context
            default -> "RESIDENT";
        };
    }
}
```

### 3.4 Gate Face Capture with Liveness

**File: `BiometricFxControl.java` — modify `scanForModality()`:**

```java
// After bioService.captureModality() returns BiometricsDto:
if ("FACE".equals(modality) && livenessConfig.isLivenessEnabled("RESIDENT")) {
    DesktopLivenessAdapter adapter = new DesktopLivenessAdapter(
        () -> livenessConfig.getClient(),
        () -> "RESIDENT",
        () -> currentDeviceId
    );

    adapter.setCallback(new DesktopLivenessAdapter.LivenessStateCallback() {
        @Override
        public void onLivenessPassed(SessionSummary summary) {
            // Accept the captured face
            processAcceptedCapture(biometricsDto);
        }

        @Override
        public void onLivenessFailed(String message, boolean padAttack) {
            // Show error overlay, do not accept capture
            showLivenessError(message);
        }

        @Override
        public void onChallengeIssued(String type, int timeout, UUID id) {
            // Show challenge overlay: "Please blink" / "Please smile" / etc.
            showChallengeOverlay(type, timeout, id);
        }

        @Override
        public void onFrameProcessed(FrameResult result) {
            // Update UI: progress bar, face quality indicator
            updateLivenessIndicator(result);
        }
    });

    adapter.startSession();
    streamer.setFrameListener(adapter::onFrame);
}
```

### 3.5 Gate Operator/Supervisor Face Auth

**File: `SessionContext.java` — modify `validateFace()`:**

```java
// Before calling authService.authValidator():
if (livenessConfig.isLivenessEnabled("OPERATOR")) {
    LivenessHttpClient client = livenessConfig.getClient();
    LivenessClient.SessionInfo session = client.createSession("OPERATOR", deviceId);

    // Submit the captured face frame
    byte[] faceJpeg = biometricsDto.getFaceImage();
    LivenessClient.FrameResult result = client.submitFrame(session.id(), faceJpeg);

    if (!"proceed".equals(result.action())) {
        // Liveness failed — deny authentication
        client.closeSession(session.id());
        return false;
    }

    client.closeSession(session.id());
}

// Proceed with existing authValidator() call
return authService.authValidator(biometrics);
```

### 3.6 Liveness Challenge Overlay (shipped with this repo)

The overlay is a complete implementation of `docs/ui-ux-design.md` — there is
no stub left to copy:

| Piece | Path |
|---|---|
| FXML: preview slot, oval guide, status/challenge/failure cards, Retry/Cancel | `src/main/resources/fxml/LivenessChallengeOverlay.fxml` |
| Stylesheet (cards, oval + PASSED accent, actions) | `src/main/resources/fxml/liveness-overlay.css` |
| Overlay component (`StackPane` + `@FXML` controller) | `src/main/java/io/mosip/liveness/client/LivenessChallengeOverlay.java` |
| State→view mapping + i18n message catalogue | `src/main/java/io/mosip/liveness/client/LivenessOverlayPresenter.java` |

Wiring (on the JavaFX Application Thread, over the capture screen):

```java
LivenessChallengeOverlay overlay = new LivenessChallengeOverlay();
captureStack.getChildren().add(overlay);   // full-size; unmanaged while hidden

// One state machine drives the surface (design §6): feed it orchestrator events.
orchestrator.setListener(overlay.asListener());  // onState → applyState, onFinal → applyFinal

overlay.setOnRetry(orchestrator::startFreshAttempt); // Retry only appears where design §4 allows
overlay.setOnCancel(orchestrator::cancel);           // cancel → applyFinal(ABORTED)
overlay.setPreviewImage(webcamFrame);                // host-supplied JavaFX Image (ImageView path)
```

Messages resolve through i18n keys: `LivenessOverlayPresenter.DEFAULT_MESSAGES`
mirrors the Flutter `LivenessView.defaultMessages` catalogue string-for-string
(a test parses the Dart source so the two cannot drift), and deployments swap
in their own with `overlay.setMessageLookup(...)`. The FXML deliberately
carries no `fx:controller`: the overlay loads itself as both root and
controller (`FXMLLoader.setRoot(this)` + `setController(this)`). That only
works because the document's root element is an `<fx:root>` element whose
`type` names `javafx.scene.layout.StackPane`
— with a concrete root element `FXMLLoader` insists on building the root
itself and rejects the instance handed to `setRoot`. JavaFX is a `provided`
dependency and is excluded from the service jar — the overlay runs inside the
Registration Client host, never in the backend.

The overlay is covered by a headless render test
(`LivenessChallengeOverlaySmokeTest`) that instantiates the real FXML on
Monocle's headless glass platform and asserts the card each orchestrator state
renders, so its wiring can be checked without a display, a camera or a JavaFX
host.

### 3.7 The overlay without the in-process orchestrator

The overlay is not tied to the on-device orchestrator: the REST path emits the
same `LivenessStateEvent` / `LivenessFinalResult` pair, so the same overlay
renders whether the gate runs in-process or through this service.

```java
DesktopLivenessAdapter adapter = new DesktopLivenessAdapter(
    () -> livenessConfig.getClient(), () -> "RESIDENT", () -> currentDeviceId);

LivenessChallengeOverlay overlay = new LivenessChallengeOverlay();
captureStack.getChildren().add(overlay);
adapter.setListener(overlay.asListener());   // REST responses → the same states
overlay.setOnRetry(adapter::startSession);   // retry restarts the gate: a fresh session
overlay.setOnCancel(adapter::stopSession);   // cancel renders the ABORTED outcome

adapter.startSession();                      // renders the warm-up, then frames:
streamer.setFrameListener(adapter::onFrame);
```

`ServiceLivenessEventMapper` is the whole translation, and it is a pure
function of one response plus the session's frozen policy, so the mapping is
tested without a server. Four things are worth knowing when reading its table:

- The client renders **catalogue keys**, never the service's `message` text —
the English prose in the responses is for logs and audit.
- The challenge counter (`Challenge 1 / N`) and the attempt budget come from the
policy the service froze on the session at creation and returns from
`POST /api/v1/sessions`; without it (a legacy session) the counter is simply
not drawn rather than guessed.
- Re-served challenges do not advance the counter: while a challenge is open
the service answers every frame with the same `escalate_to_active`, and only a
new challenge id means a new step.
- A `reject` is retryable — Retry restarts the gate with a fresh session, which
is exactly what the design's retry rule says. Recovery guidance (no retry)
comes from the budget-exhausted vocabulary (`locked` / `escalate_to_operator` /
`failed`), or from a `reject` whose closed session reports
`max_retries_exceeded`.

Transport failures do not have one answer: `startSession` propagates
`LivenessClientException` (the host has not shown a capture screen yet and the
error table in section 6 applies), while `onFrame` / `submitChallenge` translate
it into the recoverable `DEVICE_ERROR` state and leave the session open for the
next frame.

---

## 4. Android (Flutter) Integration

> **Orchestrator layer.** The on-device liveness gate (state machine,
> per-role policy + security floor, challenge selection, signed evidence,
> gate-validity binding) now lives in `io.mosip.liveness.android` — see
> `android_client/README.md` for the Pigeon/Flutter/Android-embedding
> integration tree. The REST sequence below remains valid for the
> service-mediated (Option B) integration model.

### 4.1 Add dependency to `clientmanager/build.gradle`

```groovy
dependencies {
    implementation 'io.mosip.liveness:pad-liveness-backend:1.0.0-SNAPSHOT'
}
```

### 4.2 Create Pigeon Interface

**File: `pigeon/liveness.dart` (new):**

```dart
import 'package:pigeon/pigeon.dart';

@HostApi()
abstract class LivenessHostApi {
  /// Start a liveness session. Returns session ID.
  String startLivenessSession(String workflowType, String deviceId);

  /// Submit a frame for passive liveness + PAD evaluation.
  /// Returns: action, challengeType, challengeId, timeoutMs, message.
  LivenessFrameResult submitLivenessFrame(String sessionId, Uint8List frameJpeg);

  /// Validate challenge frames.
  /// Returns: action (proceed/retry_challenge/reject), message.
  LivenessChallengeResult validateLivenessChallenge(
    String sessionId,
    String challengeId,
    List<Uint8List> frames,
  );

  /// Close the session and get summary.
  LivenessSessionSummary closeLivenessSession(String sessionId);

  /// Check if liveness is enabled for a workflow.
  bool isLivenessEnabled(String workflowType);
}
```

Run `pigeon.sh` to generate bridge code.

### 4.3 Dart State Provider

**File: `lib/provider/liveness_capture_control_provider.dart` (new):**

```dart
import 'package:flutter/foundation.dart';

enum LivenessState {
  idle,
  scoring,
  challengeActive,
  passed,
  failed,
  padAttackDetected,
}

class LivenessCaptureControlProvider extends ChangeNotifier {
  LivenessState _state = LivenessState.idle;
  String? _sessionId;
  String? _challengeType;
  String? _challengePrompt;
  String? _errorMessage;
  UUID? _currentChallengeId;

  LivenessState get state => _state;
  String? get challengeType => _challengeType;
  String? get challengePrompt => _challengePrompt;
  String? get errorMessage => _errorMessage;

  Future<void> startLivenessCheck(String workflow, String deviceId) async {
    _state = LivenessState.scoring;
    _sessionId = await LivenessHostApi.startLivenessSession(workflow, deviceId);
    notifyListeners();
  }

  Future<void> onFrame(Uint8List frameJpeg) async {
    if (_sessionId == null) return;

    final result = await LivenessHostApi.submitLivenessFrame(_sessionId!, frameJpeg);

    switch (result.action) {
      case 'proceed':
        _state = LivenessState.passed;
        break;
      case 'escalate_to_active':
        _state = LivenessState.challengeActive;
        _challengeType = result.challengeType;
        _challengePrompt = _promptForChallenge(result.challengeType);
        _currentChallengeId = result.challengeId;
        break;
      case 'reject':
        _state = result.padFlag
            ? LivenessState.padAttackDetected
            : LivenessState.failed;
        _errorMessage = result.message;
        break;
    }
    notifyListeners();
  }

  Future<void> submitChallengeFrames(List<Uint8List> frames) async {
    if (_sessionId == null || _currentChallengeId == null) return;

    final result = await LivenessHostApi.validateLivenessChallenge(
      _sessionId!, _currentChallengeId!, frames,
    );

    switch (result.action) {
      case 'proceed':
        _state = LivenessState.passed;
        break;
      case 'retry_challenge':
        // New challenge will be issued on next frame submission
        break;
      case 'reject':
        _state = LivenessState.failed;
        _errorMessage = result.message;
        break;
    }
    notifyListeners();
  }

  String _promptForChallenge(String type) {
    return switch (type) {
      'BLINK' => 'Please blink',
      'SMILE' => 'Please smile',
      'TURN_LEFT' => 'Please turn your head to the left',
      'TURN_RIGHT' => 'Please turn your head to the right',
      'LOOK_DIRECTION' => 'Please look in the indicated direction',
      _ => 'Please follow the instruction',
    };
  }
}
```

### 4.4 Flutter Challenge Overlay Widget

**File: `lib/ui/widgets/liveness_challenge_overlay.dart` (new):**

```dart
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../provider/liveness_capture_control_provider.dart';

class LivenessChallengeOverlay extends StatelessWidget {
  const LivenessChallengeOverlay({super.key});

  @override
  Widget build(BuildContext context) {
    return Consumer<LivenessCaptureControlProvider>(
      builder: (context, provider, _) {
        if (provider.state != LivenessState.challengeActive) {
          return const SizedBox.shrink();
        }

        return Container(
          color: Colors.black54,
          child: Center(
            child: Card(
              margin: const EdgeInsets.all(24),
              child: Padding(
                padding: const EdgeInsets.all(32),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      _iconForChallenge(provider.challengeType),
                      size: 64,
                      color: Theme.of(context).primaryColor,
                    ),
                    const SizedBox(height: 16),
                    Text(
                      provider.challengePrompt ?? '',
                      style: Theme.of(context).textTheme.headlineSmall,
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 16),
                    const LinearProgressIndicator(),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  IconData _iconForChallenge(String? type) {
    return switch (type) {
      'BLINK' => Icons.visibility,
      'SMILE' => Icons.sentiment_satisfied_alt,
      'TURN_LEFT' => Icons.arrow_back,
      'TURN_RIGHT' => Icons.arrow_forward,
      'LOOK_DIRECTION' => Icons.center_focus_strong,
      _ => Icons.face,
    };
  }
}
```

### 4.5 Wire into Existing Capture Flow

**File: `biometric_scan_middle_block.dart` — add liveness overlay:**

```dart
// In the existing build method, wrap the capture area with Stack:
Stack(
  children: [
    // Existing capture UI
    _buildCaptureArea(),

    // Liveness challenge overlay (shows when challenge is active)
    if (livenessProvider.state == LivenessState.challengeActive)
      const LivenessChallengeOverlay(),

    // Liveness status indicator
    if (livenessProvider.state != LivenessState.idle)
      LivenessStatusIndicator(state: livenessProvider.state),
  ],
)
```

---

## 5. Configuration

### 5.1 Server-synced global params (both platforms)

```sql
-- Add to reg-global_param.sql:
INSERT INTO reg_global_param (name, val, is_active, lang_code) VALUES
  ('mosip.registration.liveness.enabled', 'true', true, 'eng'),
  ('mosip.registration.liveness.service.url', 'http://localhost:8000', true, 'eng'),
  ('mosip.registration.liveness.passive.threshold', '0.80', true, 'eng'),
  ('mosip.registration.liveness.active.enabled', 'true', true, 'eng'),
  ('mosip.registration.liveness.active.min_challenges', '2', true, 'eng'),
  ('mosip.registration.liveness.active.challenge_timeout_ms', '10000', true, 'eng'),
  ('mosip.registration.liveness.max_retry', '2', true, 'eng'),
  ('mosip.registration.liveness.on_repeated_failure', 'LOCK', true, 'eng');
```

### 5.2 Per-workflow config via API

`PUT` requires a session whose account holds `SUPERVISOR`: sign in at
`POST /api/v1/auth/login` and send the session cookie (or the same token as
`Authorization: Bearer <token>`) on the write. It is fail-closed — no session is
`401`, an `OPERATOR` session is `403` — and reads (`GET`) need nothing at all.
See [configuration.md](configuration.md#who-may-do-what-accounts-roles-and-the-operator-key)
for creating the first supervisor and for the account-management routes.

```bash
# Sign in once; the session cookie lands in the jar.
curl -s -c jar.txt -X POST localhost:8000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"supervisor","password":"<the account password>"}'

# Make supervisor auth stricter:
curl -X PUT localhost:8000/api/v1/config/SUPERVISOR \
  -b jar.txt \
  -H "Content-Type: application/json" \
  -d '{
    "passiveThreshold": 0.92,
    "minChallengeCount": 3,
    "maxRetryCount": 1,
    "onRepeatedFailure": "LOCK"
  }'

# Disable active liveness for operators:
curl -X PUT localhost:8000/api/v1/config/OPERATOR \
  -b jar.txt \
  -H "Content-Type: application/json" \
  -d '{
    "activeLivenessEnabled": false,
    "passiveThreshold": 0.85
  }'
```

### 5.3 The user type comes from the sign-in, and the policy is frozen

With a staff session cookie, the user type is the account's role: a
`SUPERVISOR` account always opens a `SUPERVISOR` session and an `OPERATOR`
account an `OPERATOR` one. The body still has to name it (`workflowType` is
required), but it is checked rather than trusted — naming a different type is
refused with **403** and the session is not created, so a client cannot ask for
the resident policy while authenticating a supervisor. An **anonymous** caller
has no account to derive a type from; that is the registration client's resident
capture, and there the body decides.

The response carries the **resolved policy that will govern the whole session**
(threshold, challenge pool, window, retry budget, repeated-failure action). Do not
assume the defaults are the same across user types — they are not, and they can be
edited at runtime. Show the user type the screen will run rather than offering a
picker: the service will not honour a different one.

```jsonc
POST /api/v1/sessions  { "workflowType": "SUPERVISOR", "deviceId": "L1-CAM-01" }
// 201 →
{
  "id": "…", "workflowType": "SUPERVISOR", "status": "ACTIVE",
  "policy": {
    "passiveThreshold": 0.85, "minChallengeCount": 2,
    "challengeTimeoutMs": 15000, "maxRetries": 1,
    "allowedChallenges": ["BLINK","SMILE","TURN_HEAD_LEFT","TURN_HEAD_RIGHT"],
    "onRepeatedFailure": "LOCK_OUT", "livenessEnabled": true
  }
}
```

The policy is frozen on the session at creation: a later `PUT /api/v1/config/{wf}`
affects only **new** sessions, never one already running. Surface the user type and
(optionally) the resolved policy in the UI so the operator knows which flow is active.
In the browser console the Verify screen displays that type read-only, and the
Resident screen ends any staff session before it starts, so the kiosk runs as the
anonymous resident flow instead of inheriting the previous user's authority.

### 5.4 Terminal outcomes and `mayRetrySession`

When a session ends, read `action`:

| `action` | Meaning | Client action |
|----------|---------|---------------|
| `proceed` | Liveness verified | Continue the capture/auth |
| `reject` | PAD attack or liveness failed | Show the generic failure message |
| `locked` | Retry budget exhausted, `LOCK_OUT` | Stop; direct to support |
| `escalate_to_operator` | Retry budget exhausted, `ESCALATE_TO_OPERATOR` | Route the subject to an operator |
| `failed` | Retry budget exhausted, `ALLOW_RETRY` | May start a **new** session (`mayRetrySession: true`) |

`mayRetrySession` is `false`/absent for `locked` and `escalate_to_operator`. A
failure action **never** issues another challenge in the same session.

### 5.5 Session ownership and `X-Subject-Ref`

If you create a session **with** `subjectRef` (the resident/operator/supervisor
reference you already have), that session is owned: every session-scoped call —
`GET /api/v1/sessions/{id}`, `POST …/close`, `POST …/frames`,
`POST …/challenges/validate`, `POST …/active-liveness`,
`GET …/audit` — must then repeat the same value in the `X-Subject-Ref` header.

```bash
# Owned: the header must match the subjectRef the session was created with.
curl -s -X POST localhost:8000/api/v1/sessions \
  -H 'Content-Type: application/json' \
  -d '{"workflowType":"RESIDENT","deviceId":"L1-CAM-01","subjectRef":"resident-42"}'
curl -s localhost:8000/api/v1/sessions/$SESSION_ID -H 'X-Subject-Ref: resident-42'
```

A missing or different header answers **403** (`FORBIDDEN`), and a refusal leaves
the session untouched — no frame is scored and no challenge is consumed. A session
created **without** `subjectRef` is anonymous and needs no header, which is what
this repository's own console does.

The shipped `LivenessHttpClient` does the header work for you: create the session
with the three-argument form and it remembers the owner and repeats it on every
session-scoped call it makes for that session. The two-argument form is the
anonymous session and is unchanged.

```java
LivenessClient client = new LivenessHttpClient("http://localhost:8000");

// Owned: the 3-arg create claims the reference, and every later call this client
// makes for the session carries X-Subject-Ref: resident-42.
LivenessClient.SessionInfo session =
        client.createSession("RESIDENT", "L1-CAM-01", "resident-42");
client.submitFrame(session.id(), frameJpeg);
client.validateChallenge(session.id(), challengeId, frames);
client.closeSession(session.id());
```

An HTTP refusal is a `LivenessClientException` whose message carries the status and
the service's own explanation — a 403 reads as a refusal, not as a broken response.

This isolates honest clients from each other; it is not authentication. There is no
identity provider behind the header — the account system (`/auth/login`) authenticates
supervisors and operators for the privileged surface, not session ownership — a
subject reference is not a secret, and MOSIP subject ids are predictable — so keep the service bound to loopback (`SERVER_ADDRESS`, the
default) or behind the host's own authenticated transport, and treat this header as
the claim it is.

---

## 6. Error Handling

### Client-side error codes

| HTTP Status | Error Code | Meaning | Client Action |
|-------------|------------|---------|---------------|
| 404 | NOT_FOUND | Session/challenge not found | Create new session |
| 403 | FORBIDDEN | This session belongs to a subject reference (missing or mismatched `X-Subject-Ref`) | Create the session with its `subjectRef` (the client then sends the header), or send it yourself |
| 409 | CONFLICT | Session not active / challenge not pending | Refresh state |
| 422 | INVALID_FRAME | Frame couldn't be decoded | Re-capture frame |
| 400 | VALIDATION_ERROR | Bad request body | Fix request |
| 500 | INTERNAL_ERROR | Server error | Retry, show generic error |

### User-facing messages (from spec, never leak detection details)

| State | Message |
|-------|---------|
| No face detected | "No face detected. Please look at the camera." |
| Multiple faces | "Multiple faces detected. Only one person may be captured." |
| Poor quality | "Face is not clear. Please improve lighting and hold still." |
| Challenge in progress | "Please continue" |
| Challenge passed | "Action detected" |
| Challenge failed | "Verification action was not completed. Please try again." |
| PAD failure | "Face verification could not be completed. Please try again." |
| Max retries | "Verification could not be completed. Please contact support." |

---

## 7. Testing the Integration

### Quick smoke test (curl)

```bash
# Create session
SESSION_ID=$(curl -s -X POST localhost:8000/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"workflowType":"RESIDENT","deviceId":"L1-CAM-01"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")

# Submit a frame
curl -s -X POST localhost:8000/api/v1/sessions/$SESSION_ID/frames \
  -H "Content-Type: application/json" \
  -d "{\"frameBase64\":\"$(base64 -w0 test_face.jpg)\"}"

# Check audit trail
curl -s localhost:8000/api/v1/sessions/$SESSION_ID/audit | python3 -m json.tool
```

### Programmatic test (Java)

```java
LivenessClient client = new LivenessHttpClient("http://localhost:8000");
LivenessClient.SessionInfo session = client.createSession("RESIDENT", "L1-CAM-01");

byte[] faceJpeg = Files.readAllBytes(Path.of("test_face.jpg"));
LivenessClient.FrameResult result = client.submitFrame(session.id(), faceJpeg);

System.out.println("Action: " + result.action());
System.out.println("Liveness score: " + result.livenessScore());
System.out.println("PAD flag: " + result.padFlag());

LivenessClient.SessionSummary summary = client.closeSession(session.id());
System.out.println("Duration: " + summary.durationMs() + "ms");
```

---

## 8. Security Notes

- Face frames are processed in-memory, on-device, never leave the device (no cloud call)
- Only the final `LivenessAttemptResult` (scores/decision) is written to audit logs
- PAD failure messages are deliberately generic — no detection method details leaked
- Model files bundled in the app should have integrity checksum verification on load
- Config updates via API should use signed manifests in production
