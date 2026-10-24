# PRD — MOSIP Face Liveness & PAD Service

**Product:** Face Liveness Detection and Presentation Attack Detection (PAD) for the
[MOSIP](https://mosip.io/) Registration Client
**Artifact:** `io.mosip.liveness:pad-liveness-backend` (Spring Boot fat jar / Docker image)
**Version:** `${revision}`, `1.0.0-SNAPSHOT` by default; release tags stamp the version
**Runtime:** Java 17+ (CI builds and tests on 17; the Mockito/ByteBuddy test stack does not run on 25 yet), Spring Boot 4.1.1, native OpenCV, optional ONNX / MediaPipe / TFLite backends (artifact bytecode remains Java 17-compatible for the MOSIP Android host)
**Default branch:** `development`

This document is a **self-contained product requirements and system reference**: what the
product is for, who uses it, what the repository contains, how the pipeline actually works,
every API endpoint, the data model, the configuration surface, the security model, the
evaluation harness, the tests and CI gates, and the known gaps. It intentionally repeats
detail that also lives in the deeper documents listed in [§18](#18-documentation-set) so that
reading this one file is enough.

---

## 0. How to read this document

Every substantive claim is tagged so a reader can tell designed behaviour from verified
behaviour:

| Tag | Meaning |
| --- | --- |
| **Implemented** | Present in the source in this repository and exercised by a test or a CI gate. |
| **Configured** | A default or budget set in `application.yml`, `pom.xml`, or a CI workflow. |
| **Assumed** | A host-integration expectation that has **not** been validated against a real MOSIP Registration Client. |
| **Gap** | Known missing, unverified, or explicitly deferred work. |

### Contents

| § | Section | § | Section |
| --- | --- | --- | --- |
| [0](#0-how-to-read-this-document) | How to read this document | [11](#11-isoiec-30107-3-alignment-and-evaluation) | ISO/IEC 30107-3 alignment and evaluation |
| [1](#1-product-summary) | Product summary | [12](#12-security-model) | Security model |
| [2](#2-users-roles-and-integration-contexts) | Users, roles and integration contexts | [13](#13-java-service-file-map) | Java service file map |
| [3](#3-what-the-repository-contains) | What the repository contains | [14](#14-clients-and-host-integration) | Clients and host integration |
| [4](#4-how-the-system-works) | How the system works | [15](#15-companion-projects) | Companion projects |
| [5](#5-functional-requirements) | Functional requirements | [16](#16-testing-gates-and-ci) | Testing, gates and CI |
| [6](#6-non-functional-requirements) | Non-functional requirements | [17](#17-packaging-and-deployment) | Packaging and deployment |
| [7](#7-api-reference) | API reference | [18](#18-documentation-set) | Documentation set |
| [8](#8-data-model) | Data model | [19](#19-known-limitations-and-open-gaps) | Known limitations and open gaps |
| [9](#9-configuration-reference) | Configuration reference | [20](#20-status-and-change-control) | Status and change control |
| [10](#10-pluggable-scoring-the-livenessbackend-spi) | Pluggable scoring: the `LivenessBackend` SPI | | |

### Terms and acronyms

The document deliberately keeps the domain vocabulary small. These are the only terms it uses
without re-explaining them.

| Term | Meaning |
| --- | --- |
| **MOSIP** | Modular Open Source Identity Platform — the national-scale identity system this service plugs into. |
| **Registration Client** | MOSIP's field application that enrols residents; the primary host of this service. |
| **Liveness** | Evidence that a live person is physically present at capture time. This service returns a score and a verdict — never a template or an image. |
| **PAD** | Presentation Attack Detection — deciding whether the face in front of the camera is a real person rather than a photo, a screen or a mask. |
| **Presentation attack** | The attack itself: a printed photo, a phone/screen replay, a video replay, or a 3D mask. |
| **PAI** | Presentation Attack Instrument — the object an attacker presents. "Per-PAI species" means results split by which instrument was used. |
| **APCER** | Attack Presentation Classification Error Rate — share of attacks wrongly accepted as live. Lower is better. |
| **BPCER** | Bona-fide Presentation Classification Error Rate — share of genuine faces wrongly rejected. Lower is better. |
| **ACER** | Average Classification Error Rate — `(APCER + BPCER) / 2`. |
| **ISO/IEC 30107-3** | The international standard that defines the PAD testing and reporting vocabulary (the APCER/BPCER/ACER family). |
| **Passive vs active** | Passive = decided from ordinary capture frames; active = the client is asked to perform an action (blink, turn) and to submit the frames that show it. |
| **Action / verdict** | The single next step the client acts on: `proceed`, `escalate_to_active` or `reject`. |
| **SPI** | Service Provider Interface — the seam a replacement implementation plugs into (`LivenessBackend`, §10). |
| **SBI** | MOSIP's Secure Biometric Interface — the device protocol through which compliant capture devices deliver frames. |
| **L0 / L1 device** | MOSIP device capability classes. Here an **L1** device exposes a continuous MJPEG STREAM that `SbiStreamDeviceAdapter` consumes, while an **L0** device is polled with discrete capture bursts. |
| **Flyway / Alembic** | Schema-migration tools. Flyway owns the Java schema (§8); Alembic owns the Python backend's (§15.1). |
| **DTO** | Data Transfer Object — the JSON request/response shapes in `io.mosip.liveness.dto` (§7). |
| **HMAC** | Keyed-hash message authentication code — the keyed mode of the audit chain (§12.3). |

---

## 1. Product summary

### 1.1 The problem

A biometric enrolment or authentication flow that only compares a captured face to a
reference template cannot tell a live person from a **presentation attack**: a printed
photograph, a photo shown on a phone screen, a replay video, or a 3D mask. MOSIP's
Registration Client captures faces in the field — often on low-cost hardware, sometimes
entirely offline — so the check has to run locally, in real time, on the capture host.

### 1.2 What the product does

It is a **local liveness and PAD decision service**. A Registration Client streams decoded
face frames to it; it decides whether the face belongs to a live person physically present
in front of the camera, and returns a single actionable verdict (`proceed`, retry, reject).
It covers three workflows:

| Workflow | Purpose |
| --- | --- |
| **Resident registration** | Verify a resident's face during ID enrolment. |
| **Operator authentication** | Verify the operator signing into the workstation. |
| **Supervisor authentication** | Verify the supervisor approving registrations. |

### 1.3 Product goals

1. **Local-first.** Liveness enforcement must not depend on WAN connectivity. Only policy and
   model updates require a network. *Implemented* — the service runs as a local process; no
   call-out is on the decision path.
2. **Defence in depth.** Passive scoring → PAD → active challenge, so defeating the flow
   requires defeating more than one signal. *Implemented*.
3. **Vendor independence.** Any compliant L0/L1 capture device works, because the service
   consumes decoded frames and sits above the device layer. *Implemented* as a device-adapter
   SPI; *Assumed* against real MOSIP hardware.
4. **Explainable, auditable decisions.** Every decision is recorded in a structured, tamper-
   evident audit trail, and end users see only generic messages. *Implemented*.
5. **Swappable scoring.** A vendor or in-house model can replace the reference scorer without
   touching the pipeline. *Implemented* — the `LivenessBackend` SPI (§10).

### 1.4 Non-goals

- **Not a face matcher.** It does not compare a face to a stored template; that is the
  Registration Client's job.
- **Not a certified PAD product.** It is aligned with ISO/IEC 30107-3 and ships an evaluation
  harness, but the bundled model is a **reference integration** that must be replaced with a
  properly evaluated model or vendor SDK before production use (§11).
- **Not the Registration Client.** The Android project here is a pinned build harness and
  host-integration fragment set, not the full MOSIP client (§14.1).
- **Not cloud-hosted.** No multi-tenancy, no central fleet management, no remote model
  serving.

---

## 2. Users, roles and integration contexts

| Actor | What they need |
| --- | --- |
| **Resident** | To enrol in seconds, with clear, non-technical guidance and no biometric data exposure. |
| **Operator** | To authenticate without friction; repeated failures must not let a spoof through, and must not lock a legitimate operator out unfairly. |
| **Supervisor** | To approve enrolments with an auditable liveness record. |
| **Registration Client integrator** | A stable HTTP contract, pinned versions, and a documented failure taxonomy. |
| **Deployment / ops** | A single artifact that boots predictably on a low-end host, with measurable resource budgets. |
| **Security reviewer** | Evidence that a database-write attacker cannot silently rewrite history, and that abusive clients are throttled. |

Three integration surfaces exist:

1. **HTTP** — the primary contract (`/api/v1/...`), used by Desktop and Android clients alike.
2. **In-process Java API** — `LivenessClient` with `DesktopLivenessAdapter` /
   `AndroidLivenessAdapter` for hosts that embed the engine directly.
3. **Device adapters** — `DeviceAdapter` implementations for capture sources (§14.3).

---

## 3. What the repository contains

Five independent projects plus repository tooling. Full details in §13–§17.

| Path | Kind | What it is |
| --- | --- | --- |
| `src/`, `pom.xml` | Java / Maven | The service and shared engine. **The active codebase.** 190 main classes, 88 test classes (751 tests). |
| `pad_liveness_backend/` | Python / FastAPI | Independent PostgreSQL-backed reference implementation of the same decision flow, with Alembic migrations and real-PostgreSQL tests. Its own service, not wired into the Java build. |
| `android_client/` | Flutter + Android | Pinned Flutter 3.10.4 build harness, Pigeon bindings, CameraX frame source, keystore evidence signer. Not the full Registration Client. |
| `docs/` | Markdown | Cross-project documentation (this file included). |
| `training/` | Python / TensorFlow | Synthetic-data training pipeline for the MiniFASNet liveness model. Runs on a workstation; **not** covered by CI and not wired into the service. |
| `scripts/` | Shell + Node | Repository gates: secret scan, boundary check, Java import and package layout, jar smoke, jar size, cold-boot median, Docker and Windows-launcher smoke, `start.sh` / `start.bat`. |

Size at a glance: 190 Java main classes, 88 Java test classes (seven more
files under `src/test/java` are support helpers) running 751 tests, 49 Python tests,
12 Flyway migrations (V1–V13; one number in the range was never used), 15 CI jobs,
9 repository gate scripts.

---

## 4. How the system works

### 4.1 Runtime topology

```
Registration Client (Desktop / Android)
        │  base64 frames, session + challenge calls (HTTP, localhost)
        ▼
┌──────────────────────────────────────────────────────────────┐
│  Spring Boot service (io.mosip.liveness)                     │
│                                                              │
│  api/          controllers, filters (security, rate limit)   │
│  services/     orchestration: Passive, PAD, Decision,        │
│                Challenge selection, ImageUtils, Calibration  │
│  engine/       pipeline, state machine, decision logic       │
│  backend/      LivenessBackend SPI + 4 implementations       │
│  device/       capture adapters (mock, SBI stream, burst R)  │
│  challenge/    evaluators + selector                         │
│  config/       policy resolution and validation              │
│  audit/        structured audit + metrics                    │
│  eval/         ISO/IEC 30107-3 attack-scenario harness       │
│                                                              │
│  PostgreSQL  ── sessions, frames, challenges, policy, audit  │
│  (H2 in the `dev` profile)                                   │
└──────────────────────────────────────────────────────────────┘
```

*Implemented.* Schema is owned by Flyway; Hibernate runs with `ddl-auto: validate`.

### 4.2 End-to-end decision flow

An end-to-end run looks like this:

1. **`POST /api/v1/sessions`** — the client opens a session for a workflow. A signed-in
   caller's workflow is its account's role and a body naming another type is refused (403);
   an anonymous caller, the registration client's resident capture, names it in the body. The
   engine resolves an **effective policy** for that workflow (system defaults ⊕ per-workflow
   policy overrides) and snapshots it onto the session, so later policy edits cannot change
   the rules mid-session.
2. **`POST /api/v1/sessions/{id}/frames`** — the client streams frames (base64).
3. **PAD runs first**, before any liveness scoring. A flagged presentation attack short-
   circuits the request: the session fails with a PAD reason and no liveness score is computed
   — a spoof never gets to influence the score.
4. **Passive liveness** scores face quality and liveness across a short window of frames
   (`passive-min-frames` / `passive-window-frames`). Above `passive-threshold`, the session
   passes.
5. **Active challenge** is escalated to only when passive scoring is inconclusive. The engine
   issues a challenge (e.g. `blink`, `smile`, `turn_left`) with a timeout.
6. **`POST /api/v1/sessions/{id}/challenges/validate`** — the client submits the frame burst
   captured in response to the challenge.
7. On a pass, the liveness score is **re-evaluated** over the challenge window; if it dropped,
   the session fails with `E506` rather than passing on a technicality.
8. Outcome: session `PASSED`, or `FAILED` with a coded reason, or `EXPIRED` (timeout / explicit
   close). Every step writes to `audit_logs`.

### 4.3 Passive liveness

*Implemented* in `PassiveScoringService` + `ImageUtils` + the selected backend.

- Face detection: Haar cascades (`haarcascade_frontalface_default.xml`,
  `haarcascade_eye.xml`, bundled in `opencv`).
- Quality signals: `sharpnessScore` (Laplacian variance) and `brightnessScore`.
- Liveness: the selected `LivenessBackend` — by default MiniFASNet-V2 (ONNX, 1.7 MB bundled
  at `src/main/resources/models/minifasnet_v2.onnx`) when it loads, otherwise a deterministic
  OpenCV quality heuristic.
- Frame guards (**Implemented**, `ImageUtils`): payload ≤ 6 MB, any dimension ≤ 8192, ≤ 40 M
  pixels, and frames above **1280 px** on the long edge are downscaled before analysis so a
  1080p/4K capture does not dominate CPU time.
- The response echoes `faceDetected`, `multipleFaces`, `faceQuality`, `livenessScore`, `action`
  and a user-facing `message`.

### 4.4 Presentation attack detection

*Implemented* in `PadEngineService` over the selected backend's `assessPad`, producing a
`PadVerdict`. A flagged attack yields `action: reject`, sets `padFlag` and `padAttackType`
(one of `PRINTED_PHOTO`, `SCREEN_REPLAY`, `VIDEO_REPLAY`, `OTHER`), stores the frame event with the PAD confidence, writes a
`PAD_REJECTED` audit entry, and fails the session with
`failure_reason = presentation_attack:<type>`. The user-facing message stays generic.

### 4.5 Active challenge stage

*Implemented* in `challenge/ChallengeEvaluators` + `ChallengeSelector` (chosen through
`ChallengeSelectorService`).

- **Evaluators** cover blink, smile, turn-left, turn-right and look-direction.
- **Selection** is dynamic/unpredictable: the selector picks from the workflow's allowed
  challenge types and avoids immediately repeating the previous one, so a recorded replay
  cannot be pre-scripted.
- **Timing**: `challenge-timeout-ms` (15 s by default) with `min-challenge-window-ms` as a
  floor a per-workflow value is raised to. Raising it only makes the flow more patient.
- **Retries**: `max-retries` (2 by default) bounds attempts; exceeding it produces `E503` and
  the policy's `onRepeatedFailure` action (`lock` / `escalate` / `allow_retry`) decides what
  happens next.
- Challenge outcomes are `issued` → `passed` / `failed` / `timeout`.

### 4.6 Session lifecycle and state

```
ACTIVE ──passive pass──────────────────────────────► PASSED
   │  └─ escalate ──challenge pass──► PASSED
   ├─ PAD flagged ────────────────────────────────► FAILED (presentation_attack:*)
   ├─ retries exhausted / liveness failed ─────────► FAILED
   └─ timeout or POST /close ─────────────────────► EXPIRED
```

Stages: `PASSIVE` → `ACTIVE` → `COMPLETED`. Statuses are stored as **enum names** in plain
`VARCHAR` columns (`ACTIVE`, `PASSED`, `FAILED`, `EXPIRED`), which is what makes them readable
back into the Java enums; the Python reference backend stores the same names as native
PostgreSQL enum types instead (§8, §15.1).

### 4.7 Error taxonomy

User-visible failures carry a stable code from `core/LivenessErrorCode`, grouped by area:

| Range | Area | Examples |
| --- | --- | --- |
| `E1xx` | Device | `E101` device unavailable, `E103` camera unavailable |
| `E2xx` | Capture quality | `E201` no face, `E202` multiple faces, `E203` poor quality |
| `E3xx` | Passive liveness | `E301` score below threshold |
| `E4xx` | PAD | `E401` PAD failure |
| `E5xx` | Active challenge / model | `E501`–`E503` challenge failure / timeout / retries, `E504` session timeout, `E505` model integrity, `E506` liveness dropped during challenge |
| `E6xx` | Protocol | `E601` invalid frame data, `E602` unknown/closed session, `E603` invalid state, `E604` bad configuration |
| `E9xx` | Internal | `E901` internal error |

Every HTTP error body is `{"error": "<code>", "message": "<safe text>", "status": <int>,
"timestamp": "<ISO-8601>"}`. Stack traces and exception messages are never returned
(`server.error.include-stacktrace: never`, `include-message: never`).

---

## 5. Functional requirements

### Session lifecycle
- **FR-1** Create a session for `resident`, `operator` or `supervisor`. *Implemented.*
- **FR-2** Resolve and **snapshot** the effective policy at creation, so policy edits cannot
  change a running session's rules. *Implemented* (V4 `session_policy_snapshot`).
- **FR-3** Read a session's current state; close an active session explicitly, producing an
  audit-friendly summary with duration and counters. *Implemented.*
- **FR-4** Reject operations on closed/expired sessions with a coded error. *Implemented.*

### Frame handling
- **FR-5** Accept base64-encoded frames and evaluate them in the passive stage. *Implemented.*
- **FR-6** Enforce frame limits (bytes, dimensions, pixels) and downscale large frames before
  analysis. *Implemented.*
- **FR-7** Persist one `frame_events` row per evaluated frame, including PAD outcome fields.
  *Implemented.*
- **FR-8** Return a single actionable verdict per frame (`proceed` / `escalate_to_active` /
  `reject`) plus a user-safe message. *Implemented.*

### PAD
- **FR-9** Evaluate PAD **before** liveness scoring so a spoof cannot contaminate the score.
  *Implemented.*
- **FR-10** Record attack type and confidence, and fail the session with a machine-readable
  `presentation_attack:<type>` reason. *Implemented.*

### Active challenge
- **FR-11** Issue an unpredictable challenge from the workflow's allowed set, avoiding
  immediate repetition. *Implemented.*
- **FR-12** Validate a submitted frame burst against the issued challenge. *Implemented.*
- **FR-13** Time out a challenge and honour the configured retry budget and failure policy.
  *Implemented.*
- **FR-14** Re-evaluate the liveness score over the challenge window and fail the session if it
  dropped (`E506`). *Implemented.*

### Policy and configuration
- **FR-15** Serve per-workflow policy (`GET /api/v1/config/{workflowType}`) and its resolved
  effective form (`/effective`). *Implemented.*
- **FR-16** Allow runtime policy updates without a client redeploy, gated by a signed-in
  `SUPERVISOR` account that **fails closed** without a usable session. *Implemented* (V12
  accounts and sessions replaced the shared admin API key).
- **FR-17** Validate policy values on write and at startup. *Implemented*
  (`EffectivePolicyValidator`, `SecurityValidator`).
- **FR-18** Keep configuration, YAML defaults, and policy rows consistent — one threshold is
  the single source of truth across all three. *Implemented* (V2 migration).

### Audit, metrics, diagnostics
- **FR-19** Write a structured audit entry for every decision point (session created, PAD
  rejected, challenge issued/passed/failed, session passed/failed/expired, config change).
  *Implemented.*
- **FR-20** Chain audit entries so tampering is detectable, and expose
  `GET /api/v1/config/audit/verify`. *Implemented* (V9 immutability migration).
- **FR-21** Expose anonymised aggregate metrics with **no** subject identifiers or biometric
  data. *Implemented* (`GET /api/v1/metrics`).
- **FR-22** Record configuration changes in the audit trail. *Implemented* (V7, V8).
- **FR-23** Offer an opt-in, **loopback-only** diagnostics snapshot for local debugging that
  returns the same 404 as a remote caller when disabled, so the mode never advertises itself.
  *Implemented* (default off).

### Engine and extensibility
- **FR-24** Keep scoring behind a stable SPI so a vendor model can be dropped in. *Implemented.*
- **FR-25** Select the backend from one property that drives both the HTTP path and the SPI
  bean, with no silent fallback for explicitly named backends. *Implemented.*
- **FR-26** Support alternative capture sources through device adapters. *Implemented* for
  mock, SBI stream, and burst capture; *Assumed* for real MOSIP hardware.
- **FR-27** Provide a threshold-calibration endpoint to sweep operating points. *Implemented*
  (`GET /api/v1/eval/threshold-sweep`).

---

## 6. Non-functional requirements

| ID | Requirement | Status / budget |
| --- | --- | --- |
| **NFR-1** | **Offline operation** — no network on the decision path. | Implemented. |
| **NFR-2** | **Boot time** — packaged jar reaches a serving `/health` quickly from a *cold* OpenCV native cache. | Configured: CI measures 5 cold boots and fails above **120 s** median (≈3× the ~43 s observed). |
| **NFR-3** | **Artifact size** — bounded so downloads and image pulls stay predictable. | Configured ceilings: **245 MiB** full jar, **110 MiB** slim jar; enforced by `scripts/check-jar-size.sh`. |
| **NFR-4** | **Container footprint** — small, non-root. | Implemented: Alpine image ≈293 MiB of layers, runs as `mosip`. |
| **NFR-5** | **Throughput** — a few frames per second per session is enough. | Implemented: Tomcat max 50 threads, Hikari pool 5. |
| **NFR-6** | **Frame-cost bound** — large captures must not dominate CPU. | Implemented: 1280 px analysis cap + byte/dimension/pixel guards. |
| **NFR-7** | **Request-size bound** — oversized bodies rejected before parsing. | Implemented: 24 MiB (`max-request-body-bytes`). |
| **NFR-8** | **Abuse resistance** — per-IP and per-session rate limits, with trusted-proxy handling. | Implemented: 30 session creates/min/IP, 60 frames+validations/10 s per session. |
| **NFR-9** | **Determinism** — the same frame sequence gives the same verdict. | Implemented for the heuristic and mock backends; asserted by the test suites. |
| **NFR-10** | **ISO/IEC 30107-3 alignment** — an evaluation path that reports APCER/BPCER/ACER, including per-PAI-species breakdown. | Implemented as a harness (`io.mosip.liveness.eval`); the bundled model is **not** a certified/evaluated PAD (§11). |
| **NFR-11** | **Privacy** — no biometric data or subject identifiers in metrics, logs, or responses; frames are never persisted. | Implemented by design; enforced by review and tests. |
| **NFR-12** | **Tamper evidence** — history cannot be silently rewritten by a database-write attacker. | Implemented: HMAC-SHA-256 keyed audit chain (§12.3). |
| **NFR-13** | **Reproducible builds** — pinned toolchains and locked dependencies. | Implemented: Maven wrapper, Flutter 3.10.4 / Dart 3.0.3, `pubspec.lock`, pinned Android SDK + NDK, pinned Python requirements. |
| **NFR-14** | **No secrets in the repository** — configuration comes from the environment; live keys and real biometrics stay out of source, tests, logs and docs. | Enforced by repository rules and review. |

---

## 7. API reference

Base URL `http://localhost:8000` (configurable via `SERVER_PORT`). All session-scoped routes
are under `/api/v1`.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Liveness of the service; reports whether the engine is `available`. |
| `POST` | `/api/v1/sessions` | Create a session (`workflowType`, `deviceId`, optional `subjectRef`, `online`). With a session cookie the workflow must match the account's role (`SUPERVISOR`/`OPERATOR`); a mismatch is `403` and nothing is created. |
| `GET` | `/api/v1/sessions/{id}` | Current session state + the snapshotted effective policy. |
| `GET` | `/api/v1/sessions` | Recent sessions, newest first (`workflowType`/`status` filters, `limit` 1..200, default 50). **Requires the operator API key or a signed-in session.** |
| `POST` | `/api/v1/sessions/{id}/close` | Close an active session; returns a summary (counters, duration). |
| `POST` | `/api/v1/sessions/{id}/frames` | Submit one base64 frame; returns the verdict. |
| `POST` | `/api/v1/sessions/{id}/challenges/validate` | Validate a frame burst against the issued challenge. |
| `POST` | `/api/v1/sessions/{id}/active-liveness` | Issue a challenge for an extra active check. `201` with the challenge; `409` when the session has not passed, the workflow disables active liveness or has no challenge types. An already-open challenge is returned unchanged. |
| `POST` | `/api/v1/sessions/{id}/evidence` | Check liveness evidence from an online client gate (`EvidenceVerificationRequest` → `EvidenceVerificationResponse`): session status, nonce binding, timestamp order and challenge results. Rejections are `verified: false` with a reason; a missing signature is reported as a warning, not a rejection (`LivenessEvidenceVerifier`). |
| `GET` | `/api/v1/sessions/{id}/audit` | The session's audit trail. |
| `GET` | `/api/v1/rejections` | Recent failed sessions plus their `PAD_REJECTED`/`CHALLENGE_FAILED` events, newest first. **Requires the operator API key or a signed-in session.** |
| `GET` | `/api/v1/metrics` | Anonymised operational metrics. |
| `GET` | `/api/v1/config/{workflowType}` | Policy for one workflow. |
| `GET` | `/api/v1/config/{workflowType}/effective` | The policy after defaults ⊕ overrides are resolved. |
| `PUT` | `/api/v1/config/{workflowType}` | Update policy. **Requires a `SUPERVISOR` session**; fails closed without one. |
| `GET` | `/api/v1/config/audit` | Configuration-change audit trail. |
| `GET` | `/api/v1/config/audit/verify` | Verify the audit chain; reports whether keyed HMAC mode is active. |
| `GET` | `/api/v1/eval/threshold-sweep` | Sweep thresholds over a label set to pick an operating point. **Requires a `SUPERVISOR` session.** |
| `GET` | `/api/v1/diagnostics` | Local-only debug snapshot; loopback callers only, and only when enabled. |
| `POST` | `/api/v1/auth/login` | Exchange a username and password for a session. The token is returned only in an `HttpOnly` `Set-Cookie` header, never in the body. One generic `401` for every failure. |
| `POST` | `/api/v1/auth/logout` | End the presented session; idempotent, and clears the cookie. |
| `GET` | `/api/v1/auth/me` | The signed-in account. `401` without a usable session, including when it was disabled since the token was issued. |
| `POST` | `/api/v1/auth/change-password` | Replace the caller's own password (the current one is required). Revokes every session the account holds, including the caller's, and issues a replacement. |
| `GET` | `/api/v1/users` | Every account (name, role, flags — never a hash). **Requires a `SUPERVISOR` session.** |
| `POST` | `/api/v1/users` | Create an account. **Requires a `SUPERVISOR` session.** The account owes a password change. |
| `PUT` | `/api/v1/users/{username}` | Change a role, disable or re-enable, or reset the password (which revokes the account's sessions). **Requires a `SUPERVISOR` session.** |
| `DELETE` | `/api/v1/users/{username}` | Remove an account. **Requires a `SUPERVISOR` session.** `409` when it is the last enabled supervisor. |
| `GET` | `/api/v1/auth/role` | Which role the caller proved (`SUPERVISOR`/`OPERATOR`); the console's role probe. **Requires the operator API key or a signed-in session.** |

**Key response shapes** (DTOs in `io.mosip.liveness.dto`):

- `SessionResponse` — `id`, `workflowType`, `deviceId`, `status`, `currentStage`,
  `retryCount`, `online`, `finalResult`, `failureReason`, `nonce` (the anti-replay
  nonce every frame, challenge and evidence payload is bound to), `createdAt`,
  `updatedAt`, `closedAt`, plus the effective `policy`.
- `FrameProcessResult` — `sessionId`, `stage`, `faceDetected`, `multipleFaces`,
  `faceQuality`, `livenessScore`, `padFlag`, `padAttackType`, `action`
  (`proceed` / `escalate_to_active` / `retry_passive` / `reject`), optional
  `challenge {challengeId, challengeType, timeoutMs, attemptNumber}`, `message`.
- `ChallengeValidationResult` — `sessionId`, `challenge`, `passed`, `action`
  (`proceed` / `retry_challenge` / `reject`, plus the budget-exhausted
  `locked` / `escalate_to_operator` / `failed`), `message`, and — only on a
  terminal budget-exhausted verdict — `mayRetrySession`.
- `SessionSummary` — counters and `durationMs` for a closed session.
- `SessionListItem` — one row of the operator feeds: `id`, `workflowType`, `deviceId`,
  `status`, `currentStage`, `retryCount`, `finalResult`, `failureReason`, `createdAt`,
  `closedAt` — never `subjectRef`, the frozen policy or frame data.
- `RejectionFeed` — the rejections answer: `limit`, `sessions` (failed
  `SessionListItem`s, newest first) and `events` (`{id, eventType, sessionId, details,
  createdAt}` for `PAD_REJECTED` and `CHALLENGE_FAILED`, newest first), each list capped
  at `limit`. The `details` payload is the one the session's own trail already returns.
- `OperationalMetrics` — totals, pass rate, escalation rate, frames/challenges per session,
  retry rate, failure rate, PAD rejection rate.

Interactive docs: Swagger UI at `/swagger-ui/index.html` when the service is running.

**Worked end-to-end flow.** A copy-pasteable script lives in [../README.md](../README.md); the
shape is:

```bash
# 1. Open a session — the effective policy is resolved and snapshotted here.
SESSION_ID=$(curl -s -X POST localhost:8000/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"workflowType":"RESIDENT","deviceId":"L1-CAM-01"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")

# 2. Stream a frame (base64 JPEG). PAD runs before liveness scoring.
curl -s -X POST localhost:8000/api/v1/sessions/$SESSION_ID/frames \
  -H "Content-Type: application/json" -d "{\"frameBase64\":\"$(base64 -w0 face.jpg)\"}"
# -> look at "action": proceed | escalate_to_active | reject

# 3. Only when action == escalate_to_active: capture the burst for the issued challenge.
curl -s -X POST localhost:8000/api/v1/sessions/$SESSION_ID/challenges/validate \
  -H "Content-Type: application/json" \
  -d '{"challengeId":"<from the frame response>","framesBase64":["<f1>","<f2>","<f3>"]}'

# 4. Close explicitly for a summary, then read the audit trail.
curl -s -X POST localhost:8000/api/v1/sessions/$SESSION_ID/close
curl -s localhost:8000/api/v1/sessions/$SESSION_ID/audit
```

Policy writes and user management need a signed-in `SUPERVISOR` account, and the
service refuses them entirely without a usable session (fail-closed, §12.4). The role
probe and the two operator feeds are the only *reads* behind a credential, and there
the smaller role is enough — the operator API key, or a signed-in session of either
role (a key is never answered `SUPERVISOR`):

```bash
# sign in; the session comes back as an HttpOnly cookie, so a jar is all curl needs
curl -s -c jar.txt -X POST localhost:8000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"$MOSIP_AUTH_BOOTSTRAP_USERNAME","password":"$MOSIP_AUTH_BOOTSTRAP_PASSWORD"}'

curl -s -b jar.txt -X PUT localhost:8000/api/v1/config/RESIDENT \
  -H "Content-Type: application/json" \
  -d '{"passiveThreshold": 0.85}'

curl -s "localhost:8000/api/v1/sessions?status=FAILED&limit=25" \
  -H "X-Operator-API-Key: $MOSIP_OPERATOR_API_KEY"
```

---

## 8. Data model

Schema owned by **Flyway** (`src/main/resources/db/migration/`), validated by Hibernate.
Checked-in revisions: `V1__init_schema`, `V2__unify_passive_threshold`,
`V3__challenge_window_15s`, `V4__session_policy_snapshot`, `V5__init_audit_table`,
`V7__config_change_audit`, `V8__audit_logs_workflow_type`, `V9__audit_chain_immutability`,
`V10__drop_unused_audit_log_table`, `V11__challenge_manual_flag`,
`V12__auth_users_and_tokens`, `V13__session_anti_replay`.
**Gap:** there are missing migration files in the version sequence; the numbering has gaps
(one number in the range was never used).

| Table | Holds | Notable columns |
| --- | --- | --- |
| `liveness_sessions` | One verification attempt. | `workflow_type` (enum name, indexed), `device_id`, `subject_ref`, `status`, `current_stage`, `retry_count`, `online`, `final_result`, `failure_reason`, timestamps + `closed_at`, policy snapshot, `nonce` + `last_frame_seq` (V13, anti-replay). |
| `frame_events` | One row per evaluated frame. | `stage`, `face_detected`, `multiple_faces`, `face_quality`, `liveness_score`, `pad_flag`, `pad_attack_type`, `pad_confidence`. |
| `challenges` | One issued active challenge. | `challenge_type`, `status`, `attempt_number`, `timeout_ms`, `manual` (V11: requested by hand rather than by escalation), `issued_at`, `completed_at`. |
| `config_policies` | Per-workflow policy (unique on workflow). | thresholds, `challenge_types` (JSON text), retries, `on_repeated_failure`. |
| `audit_logs` | Append-only decision trail. | `event_type`, `details` (JSON text), `workflow_type`, `prev_hash` / `entry_hash` chain fields. |
| `auth_users` | One account (V12). | `username` (unique, stored lower-cased), `password_hash` (BCrypt, never a password), `role`, `must_change_password`, `disabled`, `failed_attempts`, `locked_until` (a timestamp, so a lock expires by itself), `password_changed_at`. |
| `auth_tokens` | One issued session (V12). | `token_hash` (SHA-256 of the token — the token itself is never stored), `issued_at`, `expires_at`, `revoked_at`, `last_used_at`; `user_id` cascades from `auth_users`. |

**Note on column types.** Two further differences are deliberate. These JSON payloads are
stored as `TEXT` (not `jsonb`) — the application maps them through a converter and never
queries them with JSON operators — whereas the Python backend uses real `jsonb` columns. And
`V9` adds a trigger that makes `audit_logs` rows append-only rather than relying on the
application never issuing an `UPDATE`.

**How enum values are stored.** In the Java schema every enum-valued column is a plain
`VARCHAR` (`status VARCHAR(16) DEFAULT 'ACTIVE'`), holding the Java enum *name*; no
`CREATE TYPE` appears in any Flyway migration, so the schema is portable and needs no
superuser. The Python backend's Alembic baseline instead declares eight **native** PostgreSQL
enum types — `workflow_type`, `session_status`, `liveness_stage`, `frame_stage`,
`challenge_type`, `challenge_status`, `failure_policy`, `config_workflow_type` — with the same
value names. The two services therefore store interchangeable enum names in structurally
different columns.

---

## 9. Configuration reference

`src/main/resources/application.yml`; everything is overridable by environment variable.
`application-dev.yml` switches to in-memory H2 for local runs.

| Key | Default | Notes |
| --- | --- | --- |
| `server.port` | `8000` | `SERVER_PORT`. |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/pad_liveness` | `POSTGRES_HOST/PORT/DB`. |
| `spring.datasource.username` / `password` | `mosip` / *(required from env)* | `POSTGRES_USER`, `POSTGRES_PASSWORD`. |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Flyway owns the schema. |
| `spring.datasource.hikari.maximum-pool-size` | `5` | `DB_POOL_SIZE`. |
| `mosip.liveness.backend` | `auto` | `auto`\|`heuristic`\|`mock`\|`onnx-minifasnet-v2`\|`mediapipe-facemesh`\|`tflite-minifasnet`. |
| `mosip.liveness.passive-threshold` | `0.80` | Calibrate with `/api/v1/eval/threshold-sweep`. |
| `mosip.liveness.min-face-quality` | `0.50` | Quality gate before scoring. |
| `mosip.liveness.passive-min-frames` / `passive-window-frames` | `5` / `7` | Scoring window. |
| `mosip.liveness.min-challenge-count` | `2` | Challenges per session. |
| `mosip.liveness.challenge-timeout-ms` | `15000` | Per-challenge timeout. |
| `mosip.liveness.min-challenge-window-ms` | `15000` | Floor applied to per-workflow timeouts. |
| `mosip.liveness.max-retries` | `2` | Attempt budget. |
| `mosip.liveness.diagnostics-enabled` | `false` | Local-only diagnostics endpoint. |
| `mosip.security.auth.bootstrap-username` / `...-password` | *(empty)* | Create the first `SUPERVISOR` account, and only on a database with no accounts at all. Both or neither; the account owes a password change. Leaving them set is a no-op from the second boot on. |
| `mosip.security.auth.token-ttl-seconds` | `3600` | Session lifetime — the value that bounds a stolen token. |
| `mosip.security.auth.max-failed-attempts` / `...-lockout-seconds` | `5` / `900` | Failed sign-ins before the lock, and how long it lasts (stored, so a restart cannot clear it). |
| `mosip.security.auth.bcrypt-cost` | `12` | BCrypt work factor; refused below `10` outside the `dev` profile. |
| `mosip.security.auth.cookie-secure` | `false` | `Secure` on the session cookie. Leave `false` for loopback HTTP and set `true` behind TLS. |
| `mosip.security.audit-hmac-secret` | *(empty)* | Keys the audit chain (min 16 chars, startup fails below). Blank = plain SHA-256 (tamper-*detecting*). |
| `mosip.security.audit-hmac-previous-secret` | *(empty)* | Accepted by verification during rotation; rejected when set alone. |
| `mosip.security.max-request-body-bytes` | `25165824` | 24 MiB body cap. |
| `mosip.security.rate-limit.*` | on, `30`/60 s per IP, `60`/10 s per session | `RATE_LIMIT_ENABLED`, `SESSION_CREATE_LIMIT`, `FRAME_LIMIT`. |
| `mosip.security.rate-limit.trusted-proxies` | *(empty)* | Peers whose forwarded headers are believed. Empty = trust nobody. |

> **Note — threshold divergence.** The Java service's YAML default is `0.80`, while the Python
> reference backend's default is `0.75` with a 1-challenge minimum, an 8 s timeout and 3
> retries. They are separate services with separate policy stores, but the two must be brought
> to the same operating point deliberately before either is treated as interchangeable.

---

## 10. Pluggable scoring: the `LivenessBackend` SPI

`io.mosip.liveness.backend.LivenessBackend` is the seam everything else depends on:

```java
String id();
void initialize(Map<String, String> options);
FaceSignals analyzeFrame(Frame frame);
double scorePassiveLiveness(Frame frame, FaceSignals signals);
PadVerdict assessPad(Frame frame, FaceSignals signals);
void shutdown();
```

Four implementations ship here:

| Backend id | Nature |
| --- | --- |
| `mock` | Scripted placeholder. Deterministic; used for model-less CI and debug. |
| `onnx-minifasnet-v2` | MiniFASNet-V2 via ONNX Runtime; the bundled 1.7 MB model. |
| `mediapipe-facemesh` | MediaPipe Face Mesh based signals. |
| `tflite-minifasnet` | TFLite MiniFASNet variant. |

Selection is deliberate and strict:

- One property, `mosip.liveness.backend`, drives **both** the HTTP scoring path
  (`PassiveScoringService`) and the SPI bean (`AppConfig.livenessBackend`).
- `auto` keeps the historical behaviour: the bundled ONNX model on the HTTP path when it
  loads, the scripted mock on the SPI path.
- An explicitly named backend is honoured — **no silent fallback** if it cannot load.
- An unknown value fails at startup with `IllegalArgumentException` rather than quietly
  choosing a different scorer.

Adding a backend: implement the interface, name it in `mosip.liveness.backend`, and register
it in the selection vocabulary (`LivenessBackendSelection`). Runtime dependencies, determinism
and observed behaviour of the shipped backends are measured in
[backend-interoperability-report.md](backend-interoperability-report.md); the practical
walk-through is [backend-swapping.md](backend-swapping.md).

---

## 11. ISO/IEC 30107-3 alignment and evaluation

*Implemented* — harness in `io.mosip.liveness.eval`, exposed for calibration through
`GET /api/v1/eval/threshold-sweep`.

| Component | Role |
| --- | --- |
| `AttackScenarioHarness` | Drives a corpus through a backend under scripted attack scenarios. |
| `ProxyPresentationCorpus` | Synthetic presentation-attack corpus (no real faces). |
| `PresentationLabel` | Attack-species labels used for per-species reporting. |
| `PadMetrics` | APCER / BPCER / ACER computation. |
| `ThresholdSweep` | Sweeps an operating point across thresholds. |
| `ScenarioReport` | Per-run report: aggregate `apcer()` plus `apcerBySpecies()`, keyed by `PresentationLabel`. |

**What this does *not* claim.** The repository provides the *methodology and tooling*. The
bundled model is a reference integration, and the service must not be described as a certified
or field-evaluated PAD. Before production use: replace the reference scorer with a properly
evaluated model or vendor SDK, and report APCER/BPCER/ACER over a labelled corpus using the
harness above — as [backend-interoperability-report.md](backend-interoperability-report.md)
does for the shipped backends. Thresholds are calibratable and must be re-measured against real
capture hardware. Injection attacks (a compromised camera feed) are explicitly **out of ISO/IEC
30107-3 scope**; the threat model and its residual risk are analysed in
[design.md](design.md) §6.

---

## 12. Security model

### 12.1 Transport and headers
- `SecurityHeadersFilter` sets `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy:
  no-referrer`, `Cross-Origin-Opener-Policy`, `Permissions-Policy` (`camera=(self)`,
  `microphone=()`, `geolocation=()`), and a strict CSP.
- CSP is strict and self-only; Swagger UI and `/webjars` are exempt from the CSP header
  alone (their bundled bootstrap scripts are inline) while still receiving every other header.
- CORS is an explicit allow-list — no wildcard, `allow_credentials: false`.
- The session cookie is `HttpOnly` (no script can read the token), `SameSite=Lax` (not attached
  to a cross-site POST), `Path=/`, and `Secure` when the deployment terminates TLS. No token is
  ever written to a response body, and no credential is ever logged.
- Stack traces and exception messages never leave the service.

### 12.2 Abuse resistance
- `RateLimitFilter` with in-memory fixed windows: 30 session creates per IP per minute, 60
  frame/validation calls per session per 10 s (shared budget). Sign-in attempts and
  operator-key attempts are metered on that same per-IP budget under their own buckets
  (`auth-login`, `operator-key`) and counted separately in `/api/v1/metrics`, so a password
  guess and a key guess are visible as different incidents.
- `ClientIpFilter` + `ClientIpResolver` honour `X-Forwarded-For` / `X-Real-IP` **only** from
  configured `trusted-proxies`. Empty means trust nobody, and the budget keys on the socket
  address — a client cannot spoof its way past the limiter.
- `SessionOwnershipFilter` enforces resource-level ownership on every route in
  `RouteInventory.SESSION_SCOPED`: a session created with a `subjectRef` answers only a caller
  presenting it in `X-Subject-Ref` (`UserSessionGuard`, constant-time compare), and the refusal
  happens before the handler. The list, not a per-handler call, decides what is covered, and
  `ApiRouteInventoryTest` fails a session-scoped route that is missing from it — so a new
  endpoint cannot be added unguarded by omission. This is isolation between honest clients, not
  authentication: see `docs/brain.md` and §12.5.
- 24 MiB body cap, checked before parsing.

### 12.3 Tamper-evident audit trail
- Entries are hash-chained; the chain is keyed with **HMAC-SHA-256** using
  `audit-hmac-secret`, which lives on the app server rather than in the database, so an
  attacker with database write access cannot recompute the chain.
- Blank secret degrades to plain SHA-256 over the same canonical form — tamper-**detecting**,
  not tamper-proof — and `GET /api/v1/config/audit/verify` reports `hmac: false` so the weaker
  mode is visible rather than assumed.
- `V9__audit_chain_immutability` makes `audit_logs` rows append-only. Because history is
  immutable there is no re-key operation yet, so a rotation must keep the previous secret
  configured while historical hashes need it.

### 12.4 Configuration writes and user management
`PUT /api/v1/config/{workflowType}` and the calibration sweep require a session whose account
holds `SUPERVISOR`, and every route under `/api/v1/users` requires the same; all of them **fail
closed** with `401` when no session resolves and `403` when the session's role is too small.
The shared admin API key these used to accept is gone, so a policy write is now attributable to
a named account instead of to a value in an environment file, and revoking one person no longer
means rotating everyone's credential. Every change is recorded in the configuration audit trail
with the username that made it.

### 12.5 Privacy
Responses carry only derived signals — never pixels, templates, or subject identifiers.
Metrics are aggregates. Frames are not persisted; only derived numeric fields are.

---

## 13. Java service file map

`src/main/java/io/mosip/liveness/` — 158 classes.

| Package | Contains |
| --- | --- |
| *(root)* | `PadLivenessApplication` — Spring Boot entry point. |
| `app/config/` | `AppConfig` — bean wiring, OpenCV init, SPI bean selection. |
| `api/` | `SessionsController`, `FramesController`, `ChallengesController`, `ConfigController`, `AuditController`, `MetricsController`, `HealthController`, `CalibrationController`, `DiagnosticsController`, `AuthController`, `UsersController`; `SecurityHeadersFilter`, `RateLimitFilter`, `RateLimitCounters`, `ClientIpFilter`, `ClientIpResolver`, `AuthContextFilter`, `SupervisorGateFilter`, `OperatorKeyFilter`, `OperatorKeyGuard`, `AuthGate`, `SessionCookie`, `SessionOwnershipFilter`, `UserSessionGuard`, `RouteInventory`, `FilterRefusal`, `GlobalExceptionHandler`. |
| `auth/` | `AuthService` (the only place a password is checked and a session begins or ends), `PasswordHasher` (BCrypt), `SessionTokens`, `AuthPrincipal`, `AuthRefusals`, `AuthUserBootstrap`. |
| `dto/` | Request/response records: `SessionCreateRequest`, `SessionResponse`, `SessionSummary`, `FrameSubmitRequest`, `FrameProcessResult`, `ChallengeValidateRequest`, `ChallengeValidationResult`, `ChallengeResponse`, `ConfigPolicyResponse`, `ConfigPolicyUpdate`, `AuditLogEntry`, `OperationalMetrics`, `LoginRequest`, `ChangePasswordRequest`, `UserCreateRequest`, `UserUpdateRequest`, `UserResponse`. |
| `services/` | `DecisionEngineService`, `PassiveScoringService`, `PadEngineService`, `LivenessEngineService`, `ChallengeSelectorService`, `ConfigService`, `ThresholdCalibrationService`, `ImageUtils`. |
| `engine/` | `LivenessPipeline`, `FaceLivenessEngine`, `LivenessDecisionLogic`, `LivenessSession`, `FrameAssessment`, `AssessmentStatus`, `ValidationResult`, `SessionSummary`. |
| `backend/` | `LivenessBackend`, `LivenessBackendSelection`, `MockLivenessBackend`, `OnnxMiniFasNetBackend`, `MediaPipeFaceMeshBackend`, `TfLiteMiniFasNetBackend`. |
| `challenge/` | `ChallengeEvaluators`, `ChallengeSelector`. |
| `device/` | `DeviceAdapter`, `CaptureSource`, `FrameListener`, `DeviceCapabilities`, `MockL0L1Device`, `SbiStreamDeviceAdapter`, `BurstRCaptureDeviceAdapter`, `MjpegDecoder`, `PixelFormats`, `SyntheticScene`. |
| `core/` | Domain types: `Frame`, `FaceSignals`, `PadVerdict`, `Challenge`, `ChallengeProgress`, `CombinedLivenessScore`, `ActiveFrameResult`, `WorkflowType`, `LivenessErrorCode`, `LivenessException`. |
| `config/` | `LivenessConfig`, `LivenessPolicy`, `EffectivePolicy`, `WorkflowPolicyDefaults`, `EffectivePolicyValidator`, `SecurityValidator`. |
| `audit/` | `StructuredAuditLogger`, `AuditLogger`, `AuditEvent`, `AuditEventType`, `AuditChain`, `AuditChainKey`, `MetricsCollector`, `MetricsSnapshot`. |
| `metrics/` | `PipelineTimers`. |
| `eval/` | `AttackScenarioHarness`, `PadMetrics`, `ThresholdSweep`, `ScenarioReport`, `PresentationLabel`, `ProxyPresentationCorpus`. |
| `models/` | JPA entities (`LivenessSession`, `FrameEvent`, `ChallengeEntity`, `ConfigPolicy`, `AuditLog`), enums, and JSON converters. |
| `crud/` | Spring Data repositories for the five entities. |
| `client/` | Host integration: `LivenessClient`, `LivenessHttpClient`, `DesktopLivenessAdapter`, `AndroidLivenessAdapter`, `LivenessChallengeOverlay` (JavaFX + FXML + CSS), `LivenessOverlayPresenter`, `ServiceLivenessEventMapper`, `LivenessClientException`. |
| `android/` | Orchestration for the Android host: `AndroidLivenessOrchestrator`, `LivenessGatePolicy`, `LivenessEvidence` + `LivenessEvidenceSigner`, `SignedManifestModelStore`, `SignedModelManifest`, `InMemoryModelStore`, `ModelStore`, `LockoutStore`, `FrameRateMeter`, `FaceFrameSource`, `MockFaceFrameSource`, state/hint/failure types. |
| `tools/` | `ModelManifestTool`. |
| `messages/` | Reserved for message resources (currently empty). |

> Three simple names exist twice on purpose, so pair a name with its package when referring to
> it: `SessionSummary` (`engine/` record of a finished session vs `dto/` HTTP response),
> `LivenessSession` (`engine/` runtime state vs `models/entity/` JPA row) and `WorkflowType`
> (`core/` domain enum vs `models/enums/` persisted enum).

**Resources:** `application.yml`, `application-dev.yml`, `db/migration/*.sql`, `fxml/`
(`LivenessChallengeOverlay.fxml`, `liveness-overlay.css`), `models/minifasnet_v2.onnx`,
`static/` (console: `index.html`, `console.js`, `console.css`).

---

## 14. Clients and host integration

### 14.1 Android client (`android_client/`)
**Implemented** as a pinned Flutter/Android build harness — not the full Registration Client.

- Flutter **3.10.4** / Dart **3.0.3**, pinned Android SDK platform 34, build-tools 34.0.0,
  NDK 25.1.8937393, Java 17; `pubspec.lock` enforced with `flutter pub get --enforce-lockfile`.
- Dart side: `lib/main.dart`, `lib/liveness/liveness_view.dart`,
  `lib/liveness/liveness_view_model.dart`, generated `lib/pigeon/liveness.dart`.
- Java side: `MainActivity.kt`, `LivenessPigeonBridge`, `CameraXFaceSource`,
  `AndroidModelStore`, `AndroidKeystoreEvidenceSigner`, generated `pigeon/Liveness.java`.
- Pigeon bindings are **checked in** and regenerated by `tool/generate_pigeon.sh`; CI
  regenerates them and fails if the output differs, then checks Dart formatting, runs
  `flutter analyze`, and builds a debug APK.
- The Android README's shared-suite figure is now the measured one: 751 Java tests green
  (`./mvnw --batch-mode test`, 2026-10-17), with 13 of them in
  `AndroidLivenessOrchestratorTest`.

### 14.2 Desktop overlay and web console
- **Desktop:** `LivenessChallengeOverlay` is a JavaFX overlay (FXML + CSS) driven by
  `LivenessOverlayPresenter`, so a desktop host can show challenge prompts without building
  its own UI. Smoke-tested headlessly via Monocle (no display/GPU needed).
- **Web console:** `src/main/resources/static/` is plain browser JS with **no build step** —
  `index.html` + `console.js` + `console.css`. It shows sessions, policy editing, the audit
  trail and metrics. Its unsaved-changes guards (dirty marker, per-field diff, discard guard,
  unload warnings) are exercised by a DOM harness in a real browser environment, which is why
  that check lives outside Maven (§16).

### 14.3 Device adapters
`DeviceAdapter` abstracts capture: `MockL0L1Device` (synthetic scenes for tests),
`SbiStreamDeviceAdapter` (MJPEG stream), `BurstRCaptureDeviceAdapter` (burst capture), with
`MjpegDecoder` and `PixelFormats` helpers and `SyntheticScene` for generated frames.
*Assumed:* behaviour against real L0/L1 MOSIP hardware.

---

## 15. Companion projects

### 15.1 Python reference backend (`pad_liveness_backend/`)
An **independent** FastAPI + PostgreSQL implementation of the same decision flow, used as a
readable reference and a second implementation to compare against. Not wired into the Java
build or the Spring service.

- **Stack:** FastAPI 0.115, SQLAlchemy 2.0.35, psycopg2, Alembic 1.13.2, OpenCV headless,
  pydantic-settings. Python 3.12.
- **Layout:** `app/api/routes/{sessions,frames,challenges,config,audit,metrics}.py`,
  `app/services/{decision_engine,liveness_engine,pad_engine,challenge_selector,image_utils}.py`,
  `app/models/*`, `app/crud/*`, `app/db/*`, `app/core/config.py`.
- **Schema:** owned by Alembic. A single reviewed baseline revision
  (`633dd91383c5_initial_schema`) creates the same five tables with **native** PostgreSQL
  types — enum types, `uuid` primary keys, `jsonb` columns, `timestamptz`. Databases created
  with the `app.db.init_db` shortcut must be adopted with `alembic stamp head`.
- **Tests (46):** API behaviour on in-memory SQLite (9), OpenCV heuristic fixtures (12),
  fresh-interpreter import guards (6), Alembic migration behaviour on real PostgreSQL (7),
  session persistence on real PostgreSQL (8), and the cross-backend scenario script (4). The PostgreSQL suites run the real `alembic`
  CLI against a `postgres:16-alpine` container and are skipped without Docker — CI sets
  `PAD_LIVENESS_REQUIRE_POSTGRES=1` so a Docker-less runner fails instead of silently skipping.
- **Known gaps:** no frame-size cap equivalent to the Java engine's 1280 px downscale, so large
  frames cost proportionally more here; and its policy defaults differ from the Java service's
  (§9). Its repeated-failure vocabulary does **not** differ: a spent retry budget now reports the
  same `action` / `failure_reason` / `may_retry_session` as the Java service (see its README).

### 15.2 Training pipeline (`training/`)
A **workstation-only** synthetic-data pipeline for MiniFASNet: `generate_synthetic_data.py`,
`train_model.py`, `run_training_pipeline.py`, `cleanup_dataset.py`.

- Generates **synthetic data only** — no real faces, no biometric samples, ever.
- Must run in a dedicated virtual environment; the project's dependencies are not pinned yet
  (**Gap**), and installing TensorFlow/OpenCV into another project's environment can violate
  that project's lockfile.
- Generated artifacts (`data/`, `models/`, `*.tflite`, `*.keras`) are gitignored and must be
  cleaned up with `cleanup_dataset.py --confirm` after a run.
- The model hand-off is **manual and unverified**: no build or deploy path consumes the
  produced `.tflite`, and the service's bundled model is separate. Do not describe a model as
  shipped, evaluated, or integrated without naming the artifact, its hash, and where it was
  measured.
- Deliberately **not** covered by CI (`boundary-allow-no-ci`): there are no tests.


---

## 16. Testing, gates and CI

### 16.1 Test suites

| Suite | Scope | How to run |
| --- | --- | --- |
| Java unit | Everything except the pipeline integration and PostgreSQL schema tests | `./mvnw --batch-mode test` |
| Java integration | `LivenessPipelineIntegrationTest` | `./mvnw --batch-mode test -Dtest='LivenessPipelineIntegrationTest'` |
| PostgreSQL schema | `PostgresSchemaTest` — entities vs Flyway migrations on real PostgreSQL via Testcontainers | `./mvnw --batch-mode test -Dtest='PostgresSchemaTest'` |
| Cross-backend contract | `CrossBackendContractTest` — one spent retry budget driven on both services, terminal verdicts compared | `./mvnw --batch-mode test -Dtest='CrossBackendContractTest'` (needs `PAD_LIVENESS_PYTHON`) |
| Console DOM | Real `index.html` + `console.js` guards in a DOM | `node src/test/js/console-guards.test.mjs` |
| Python backend | 49 tests (SQLite behaviour, OpenCV fixtures, imports, PostgreSQL migrations, persistence, the cross-backend scenario script) | `python -m pytest -q` in `pad_liveness_backend/` |
| Android client | Locked dependency resolution, binding regeneration + diff, formatting, analyse, debug APK; a tagged release also builds the installable harness APK | see [android_client/README.md](../android_client/README.md) |

88 Java test classes / 751 tests. The unit and integration suites run as two
parallel, independent jobs so one failure cannot cancel the other.

### 16.2 Repository gates

| Gate | Script | Enforces |
| --- | --- | --- |
| Project boundaries | `node scripts/check-project-boundaries.mjs` | Guidance per project, project-map entries, no cross-scope toolchain instructions, **every documented verification command actually runs in CI**, and **every CI gate is documented**. |
| systemd floor | `./scripts/smoke-systemd-compat.sh` | Boots the unit on the oldest supported systemd (Debian 11 / Ubuntu 22.04 in containers) and fails when that host would silently ignore a directive: every property must exist with the declared value, and the service must still answer `/health` and restart. The kernel floor (5.8) is a precondition of the gate. |
| Documentation links | `node scripts/check-doc-links.mjs` | Every relative markdown link resolves from the file that contains it (so a root-relative path written inside `docs/` is reported), and every `#heading` anchor matches a heading in the target, with GitHub's duplicate suffixes. External URLs are not fetched. |
| Java imports | `node scripts/check-java-imports.mjs` | Every `io.mosip.liveness.*` import under `src` names a source file that declares it, annotated with the commit that removed the class and a `git show` restore command. |
| Java packages | `node scripts/check-java-packages.mjs` | Every Java file's declared package matches its directory; a package declaration at a source root is rejected. |
| Secret scan | `./scripts/check-secrets.sh` | The checkout and its history carry no committed credential, private key or production identifier. |
| Release publish rehearsal | `./scripts/release-assets.sh rehearse` | Staging, checksum, asset-count and stamped-name guards, plus the exact `gh` argv for create, upload and canary — with no tag, token or network; the same script publishes the real release. |
| Packaged jar boots | `./scripts/smoke-jar.sh` | The jar's `BOOT-INF/lib` matches the runtime classpath `pom.xml` resolves (nothing dropped, nothing leaked), then the fat jar boots and reports the engine available. |
| Windows launcher | `pwsh -NoProfile -File ./scripts/smoke-windows-launcher.ps1` | The full jar boots through `start.bat` on Windows — JDK probe, port check, `/health` wait and engine report — with a JDK under a path with a space. |
| Jar size | `./scripts/check-jar-size.sh 'target/pad-liveness-backend-*-slim.jar' 110 'slim jar'` | Size ceilings (110 MiB slim / 245 MiB full — pass the pattern, ceiling and label). |
| Cold boot median | `./scripts/boot-median.sh` | Median of 5 cold-cache boots under budget. |
| Docker image | `./scripts/smoke-docker.sh` | Image builds, boots, non-root, `/health` ok. |

The boundaries gate implements five rules (R1–R5). A project that is deliberately outside CI
declares it in the root map with `boundary-allow-no-ci: <reason>`; a single line can carry
`boundary-allow: <reason>` to exempt a legitimate exception. Both are printed with their
reason on every run, so escapes stay visible instead of silently weakening the gate.

### 16.3 CI jobs (15)

`test` (Unit / Integration matrix) · `console` · `boundaries` · `imports` (*Java source
layout*: imports and packages) · `secrets` · `python-backend` · `cross-backend` ·
`android-client` · `package` (full + slim matrix) · `windows-launcher` · `docker` ·
`boot-time` · `schema` · `release` (tag only) · `docker-release` (tag only).

Every push and pull request runs the first thirteen (`release` and `docker-release` are gated on a
`refs/tags/v*` ref); tags additionally publish a GitHub Release with both jars plus `.sha256`
checksums, and a `ghcr.io` image. Artifact uploads are gated on the
default branch or a `v*` tag, so no fork can write to storage.

### 16.4 Why some gates live outside Maven
- **Console DOM guards** — the console is browser JS with no build step; a green Java suite
  says nothing about whether it is still wired.
- **Cold boot** — the smoke test proves the jar *can* boot; nothing else notices it getting
  slow, and the case a fresh deployment hits is a *cold* OpenCV native cache, which is why the
  cache is cleared before every measured boot.
- **Packaging** — the test jobs run against `target/classes` with the full `~/.m2` classpath,
  so they cannot catch a packaging-only regression (lost natives, wrong `Start-Class`,
  truncated repackage).
- **Windows launcher** — every other gate runs on Linux, so nothing that ships to a Windows
  user was executed. `start.bat` reads `java -version` (stderr) through `cmd.exe`, which strips
  the quotes around an executable in a piped `for /f`, so a JDK under `C:\Program Files` was read
  as `C:\Program` — invisible on a runner whose JDK sits in `C:\hostedtoolcache`. The job installs
  a JDK under Program Files on purpose, and the smoke script refuses to run against one whose
  path has no space, so a green run cannot mean the quoting path was skipped. It also boots the
  full jar through the launcher, which is the only place its Windows OpenCV native is loaded.

---

## 17. Packaging and deployment

| Path | How |
| --- | --- |
| **Local, one command** | `./start.sh` (macOS/Linux/Git Bash) or `start.bat` (Windows). |
| **Local, manual** | `./mvnw clean package -DskipTests -Pslim` then run the jar with the `dev` profile (H2) or default profile (PostgreSQL). |
| **Docker Compose** | `docker compose up --build` — PostgreSQL 16 + the API. |
| **Full jar** | `./mvnw clean package` — natives for every platform, ~200 MiB (ceiling 245). |
| **Slim jar** | `./mvnw clean package -Pslim` — only the Linux x86_64 natives a Linux host can load; ceiling 110 MiB. This is what CI, the image, and most deployments run. |
| **Release** | Push a `v*` tag → both jars, checksums, and a `ghcr.io/<owner>/<repo>:<tag>` image. A `-canary` tag publishes as a prerelease with an identical pipeline. |

**Operational caveat — Docker and the engine.** The image is Alpine (musl) while the OpenCV
native is glibc-linked, so **frame processing is unavailable inside the container by design**:
`/health` reports `status: ok` but not `engine: available`. The API, policy/audit, rate
limiting and the console all work. Use the **jar** on a glibc host when you need the engine.
This is why the container assertion is `status: ok` and the engine assertion lives in the jar
smoke tests.

---

## 18. Documentation set

| Document | Covers |
| --- | --- |
| **This file** | Product requirements + full system reference. |
| [brain.md](brain.md) | The long-form codebase walkthrough (every file, type and tool). |
| [design.md](design.md) | Technical design: layers, frame pipeline, decision architecture, ISO/IEC 30107 alignment, injection-attack threat model, thread safety. |
| [configuration.md](configuration.md) | Configuration and policy in depth. |
| [client-integration-guide.md](client-integration-guide.md) | How a Registration Client integrates. |
| [backend-swapping.md](backend-swapping.md) | How to replace the scoring backend. |
| [backend-interoperability-report.md](backend-interoperability-report.md) | Measured comparison of the shipped backends. |
| [ui-ux-design.md](ui-ux-design.md) | User-facing flow and messaging. |
| [status-report.md](status-report.md) | Delivery status against the specification. |
| [acceptance-audit.md](acceptance-audit.md) | Acceptance criteria audit. |
| [resource-compliance.md](resource-compliance.md) | Compliance against the source research files. |
| [../README.md](../README.md) | Quick start, architecture sketch, security notes, example flow. |

---

## 19. Known limitations and open gaps

**Engine and models**
- The bundled model is a reference integration, not an evaluated PAD; thresholds are
  calibratable placeholders that must be re-measured on real capture hardware before a verdict
  is trusted. *Gap.*
- The injection-attack surface (compromised camera feed) is deliberately out of scope; residual
  risk is documented in [design.md](design.md) §6. *Accepted.*
- The container cannot process frames (musl/glibc); it serves the API only. *By design.*

**Cross-project consistency**
- The Java (0.80) and Python (0.75) passive thresholds and challenge budgets differ. *Gap.*
- The Python backend has no frame-size cap equivalent to the Java 1280 px analysis limit, so an
  oversized frame costs proportionally more there. *Gap.*
- Flyway revisions are present with gaps in the version sequence. *Gap.*

**Verification and tooling**
- There is no test suite for the boundary gate itself; its rules are validated by hand-built
  fixtures rather than committed assertions. *Gap.*
- Audit-chain key rotation has no re-key or archive operation, so the previous secret must stay
  configured while historical hashes need it. *Gap.*
- The Android client is a harness, not the real Registration Client, so host integration is
  **unvalidated** end to end. *Assumed.*
- The Android README's shared-suite count was re-verified against a full run (751 tests).

**Deliberately out of CI** — `training/` (no tests; workstation TensorFlow environment),
declared via `boundary-allow-no-ci` in the root project map.

---

## 20. Status and change control

- **Implemented and gated:** the decision pipeline (passive → PAD → active), policy handling,
  audit and metrics, the SPI and its four backends, security filters, the console, the
  evaluation harness, the Android build harness, and the CI gate set described above.
- **Requires work before production claims:** model evaluation (§11), host integration against
  a real Registration Client, and threshold calibration on real hardware.
- **Change control:** the repository's [AGENTS.md](../AGENTS.md) and per-project `AGENTS.md`
  files are authoritative for how to change each project. Keep a public contract and its
  documentation in sync in the same change, never weaken a check to make it pass, and run
  `node scripts/check-project-boundaries.mjs` after editing guidance, a README, or a workflow —
  it will fail if a documented command is not run by CI, or if CI runs a gate that no document
  describes.
