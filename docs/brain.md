# 🧠 Brain.md — MOSIP Face Liveness & PAD Service

> **The single document that tells any AI agent or human everything about this codebase:
> what it does, how it works internally, every API endpoint, every tool, every language, and every file.**

---

## 1. What This Project Is

This is a **Face Liveness Detection and Presentation Attack Detection (PAD)** service built for the [MOSIP](https://mosip.io/) Registration Client. It verifies that the face being captured belongs to a **live person physically present at the camera** — not a printed photo, screen replay, or video replay.

It covers three workflows:
- **Resident Registration** — verifying a resident's face during ID enrollment
- **Operator Authentication** — verifying the operator logging into the system
- **Supervisor Authentication** — verifying the supervisor approving registrations

The service is designed to run **entirely offline** (no mandatory cloud calls) on both **Desktop** (JavaFX/Java) and **Android** (Flutter) registration clients.

---

## 2. Project Structure (at a glance)

```
.
├── src/                                    # Java Spring Boot backend (THE ACTIVE CODEBASE)
│   ├── main/java/io/mosip/liveness/
│   │   ├── PadLivenessApplication.java     # Spring Boot entry point
│   │   ├── app/config/AppConfig.java       # Bean wiring, OpenCV init, CORS
│   │   ├── api/                            # REST controllers (HTTP endpoints)
│   │   ├── auth/                           # Accounts, sessions, password hashing
│   │   ├── dto/                            # Request/response DTOs
│   │   ├── services/                       # Business logic layer
│   │   ├── crud/                           # JPA repositories (DB access)
│   │   ├── core/                           # Domain types (Frame, FaceSignals, enums)
│   │   ├── engine/                         # Liveness engine state machine
│   │   ├── backend/                        # Pluggable liveness/PAD backends
│   │   ├── challenge/                      # Active challenge evaluation logic
│   │   ├── config/                         # Configuration and policy merging
│   │   ├── audit/                          # Structured audit logging + metrics
│   │   ├── device/                         # Device adapter layer (frame sources)
│   │   ├── eval/                           # ISO 30107 attack-scenario harness
│   │   ├── client/                         # Desktop / Android integration adapters
│   │   └── models/                         # JPA entities, DB/API enums, JSON converters
│   ├── main/resources/
│   │   ├── application.yml                 # Main Spring config (PostgreSQL)
│   │   ├── application-dev.yml             # Dev profile (H2 in-memory DB)
│   │   ├── haarcascade_eye.xml             # Vendored OpenCV cascades (not shipped by
│   │   ├── haarcascade_frontalface_default.xml  # the Java bindings — without them no
│   │   │                                   #   frame can ever detect a face)
│   │   ├── static/index.html               # Browser test console (selfie-mirrored)
│   │   ├── static/console.css              # Console styles (external — strict CSP)
│   │   ├── static/console.js               # Console logic (external — strict CSP)
│   │   └── db/migration/V1__init_schema.sql  # Flyway DB migration
│   └── test/java/io/mosip/liveness/       # 88 test classes + 7 support files (95 files)
│
├── pad_liveness_backend/                   # Python FastAPI ORIGINAL reference backend
│   ├── app/                                # FastAPI application (35 .py files)
│   ├── alembic/                            # DB migrations (Alembic)
│   ├── requirements.txt                    # Python dependencies
│   ├── Dockerfile                          # Python container
│   └── docker-compose.yml                  # Python service stack
│
├── docs/                                   # Documentation
│   ├── design.md                           # Architecture diagrams + sequence flows
│   ├── status-report.md                    # Project status and feasibility findings
│   ├── backend-swapping.md                 # How to swap liveness/PAD backends
│   └── configuration.md                    # Configuration reference
│
├── pom.xml                                 # Maven build (Java backend)
├── Dockerfile                              # Java container (Eclipse Temurin 17)
├── docker-compose.yml                      # Java service stack (PostgreSQL + API)
├── .env.example                            # Environment variable template
├── start.sh / start.bat                    # Cross-platform one-command launchers
├── mvnw / mvnw.cmd / .mvn/                 # Maven wrapper (no local Maven required)
└── brain.md                                # ← YOU ARE HERE
```

---

## 3. Programming Languages Used

| Language | Where | Lines of Code | Purpose |
|----------|-------|---------------|---------|
| **Java 17** | `src/main/java` (190 files) | ~24,100 | Main backend: engine, API, device adapters, backends, services, models |
| **Java 17** | `src/test/java` (95 files) | ~21,700 | Unit + integration tests and test utilities |
| **Python 3.11+** | `pad_liveness_backend/` (53 files) | ~3,840 | Original FastAPI reference implementation (kept for comparison) |
| **SQL** | `V1__init_schema.sql` | ~85 | PostgreSQL schema (Flyway migration) |
| **YAML** | `application.yml`, `application-dev.yml` | ~60 | Spring Boot configuration |
| **JavaScript/HTML** | `static/index.html`, `static/console.css`, `static/console.js` | ~1,340 | Single-page browser test console (external assets so CSP stays strict) |
| **Dockerfile** | 2 files | ~20 | Containerization (Java + Python) |

---

## 4. Tools & Technologies

### Core Framework
| Tool | Version | Purpose |
|------|---------|---------|
| **Java** | 17 | Runtime language |
| **Spring Boot** | 4.1.1 | Web framework, DI, JPA, validation |
| **Spring Data JPA** | (via starter) | ORM / database access |
| **Hibernate** | (via starter) | JPA implementation |
| **Flyway** | (via starter) | Database migration management |
| **Lombok** | (latest) | Boilerplate reduction (@Data, @Builder, etc.) |
| **SpringDoc OpenAPI** | 3.1.1 | Swagger UI / API documentation |

### Computer Vision & ML
| Tool | Version | Purpose |
|------|---------|---------|
| **OpenCV** (org.openpnp) | 4.9.0-0 | Face detection (Haar cascades), image processing, FFT analysis |
| **TensorFlow Lite** | (reflection-loaded) | ML inference for PAD model (MiniFASNet) and FaceMesh landmarks |
| **MediaPipe FaceMesh** | (TFLite model) | 468-point 3D facial landmark extraction |

### Database
| Tool | Version | Purpose |
|------|---------|---------|
| **PostgreSQL** | 16-alpine | Production database |
| **H2** | (dev profile) | In-memory database for local development |

### Testing
| Tool | Version | Purpose |
|------|---------|---------|
| **JUnit Jupiter** | 6.0.3 | Unit and integration testing |
| **Spring Boot Test** | (via starter) | MockMvc, @WebMvcTest, test context |
| **Maven Surefire** | 3.6.0 | Test runner |

### DevOps
| Tool | Version | Purpose |
|------|---------|---------|
| **Docker** | — | Containerization |
| **Docker Compose** | 3.9 | Multi-service orchestration |
| **Eclipse Temurin** | 17-jre-alpine | JRE base image |
| **Maven** | — | Build tool |

### Python Backend (reference)
| Tool | Version | Purpose |
|------|---------|---------|
| **FastAPI** | — | Python REST framework (original reference) |
| **SQLAlchemy** | — | Python ORM |
| **Alembic** | — | Python DB migrations |
| **Uvicorn** | — | Python ASGI server |

---

## 5. API Endpoints (Java Spring Boot Backend)

Base URL: `http://localhost:8000`

### Health Check
```
GET /health
→ { "status": "ok",
    "service": "MOSIP Face Liveness & PAD Service",
    "engine": "available" | "unavailable" }
```

`engine` reports whether the OpenCV native library loaded. When it is `unavailable`
the service still starts and serves `/health` and `/api/v1/config`, but every
frame/challenge endpoint that needs image processing returns **503**.

### Session ownership (`X-Subject-Ref`)

`POST /api/v1/sessions` takes an optional `subjectRef` (an opaque resident /
operator / supervisor reference). A session created **with** one is owned: every
session-scoped route (`GET`/`close`/`frames`/`challenges/validate`/
`active-liveness`/`audit`) then requires the same value in the `X-Subject-Ref`
header and answers **403** when it is missing or different. The rule is
`UserSessionGuard.refusal` (a constant-time compare) and the enforcement point is
`SessionOwnershipFilter`, which refuses **before** the handler runs, so no frame
is decoded and no challenge is consumed on a refused call. A session created
**without** one has no owner to protect and stays reachable to anyone who can
reach the port — the same caller could create their own session anyway — which
keeps the console and the two-argument `createSession` working unchanged.

Which routes are guarded is `RouteInventory.SESSION_SCOPED`, not a call inside
each handler — the call site was what a new session-scoped endpoint could forget,
leaving an owned session open with nothing to notice. `ApiRouteInventoryTest`
fails a route that carries a `{sessionId}` without being listed there, and fails
a listed route the matcher no longer reads the id off. The refusal body is the
service's usual error shape (`error`/`message`/`status`/`timestamp`), written by
`FilterRefusal` because a filter runs outside Spring MVC's exception handling.

On the client side, `LivenessHttpClient.createSession(workflow, device, subjectRef)`
claims the owner and the client then repeats the header on every session-scoped call
it makes for that session (the owner is remembered per session and released by
`closeSession`); an unknown session — one this client did not create — is sent
without the header, and the service's refusal arrives as a `LivenessClientException`
carrying the status and the service's own message rather than as a parse failure on
the error body.

This is resource-level isolation, not caller authentication: `subjectRef` is not a
wire credential, and a caller who learns a reference holds it. The account system
(`/api/v1/auth/*`, `auth_users` and `auth_tokens` in `V12`) authenticates
supervisors and operators for the privileged surface only — it does not change who
may open or drive a client-facing session. MOSIP subject references are
predictable, so treat the port binding (`SERVER_ADDRESS`, loopback by default) as
the real access boundary and this as the check that keeps honest clients from
reaching each other's sessions.

### Session Lifecycle
```
POST /api/v1/sessions
  Body: { "workflowType": "RESIDENT|OPERATOR|SUPERVISOR", "deviceId": "string", "subjectRef": "string?", "online": true }
  → 201: { id, workflowType, deviceId, status, currentStage, retryCount, ... }

GET /api/v1/sessions/{sessionId}
  → 200: SessionResponse (full session state)

POST /api/v1/sessions/{sessionId}/close
  → 200: SessionSummary { id, status, finalResult, totalFramesEvaluated, challengesIssued, durationMs, ... }

POST /api/v1/sessions/{sessionId}/active-liveness
  → 201: ChallengeResponse { id, sessionId, challengeType, status, attemptNumber,
                             timeoutMs, manual: true, issuedAt }
  → 409: { error: "CONFLICT", message: "..." }
```

**Manual active liveness (extra check).** Above the passive threshold the session
passes and the engine issues no challenge — by design. This endpoint asks for one
anyway, on a session that already passed (`status = PASSED`, `finalResult = true`),
and is what the console's **Run active liveness** button drives. The issued
challenge is marked `manual = true` (`V11__challenge_manual_flag.sql`) and is
validated as an *extra* check: the result is stored on its challenge row and
written to the audit trail (`CHALLENGE_ISSUED` / `CHALLENGE_PASSED` /
`CHALLENGE_FAILED` with `manual: true`, PAD hits with `stage: active_manual`), but
it **never rewrites the session verdict** — a missed action leaves the session
PASSED and consumes no retry budget. Refusals are fail-closed 409s: a session that
has not passed, a workflow with `activeLivenessEnabled: false`, or a workflow with
no challenge types. While a manual challenge is still `ISSUED`, a repeat call
returns that same challenge instead of issuing a second one. The route is
client-facing (no session) and shares the per-session rate-limit budget with
frame and challenge submissions.

### Client Evidence (`/api/v1/sessions/{sessionId}/evidence`)
```
POST /api/v1/sessions/{sessionId}/evidence
  Body: { sessionId, nonceHex, role, userId, policyVersion, engineId, modelVersion,
          engineCertified, passiveScore, challenges[], challengeResults[],
          startedEpochMs, completedEpochMs, bestFrameSha256, deviceId, bypassed,
          signatureHex }
  → 200: { verified: true/false, sessionId, failureReason, warnings[] }
```

`LivenessEvidenceVerifier` checks a `LivenessEvidence` payload produced by an
online client gate against the session the server already holds. It **rejects**
(`verified: false`) when the session is not `ACTIVE`/`PASSED`, when `nonceHex`
does not equal the session nonce minted at creation (`V13`), when
`completedEpochMs` precedes `startedEpochMs`, or when any entry in
`challengeResults` is `false`. It only **warns** — the evidence still verifies —
for a completion timestamp more than 5 s before the session was created, for
`bypassed: true`, and for a missing `signatureHex`; the endpoint checks that a
signature is present, not that it verifies against the client's key. Both
outcomes are audited (`EVIDENCE_REJECTED` with the reason / `EVIDENCE_VERIFIED`
with the engine, model and score) and the response is always `200`, so the caller
reads the reason from the body rather than an HTTP status. The route is
session-scoped, so `SessionOwnershipFilter` refuses a stranger first.

> Evidence signing and verification are implemented on the client side
> (`LivenessEvidenceBinding`, `SignedCapture`, `EvidenceSigner`) and exercised by
> `LivenessEvidenceBindingTest`. Server-side cryptographic verification is not
> wired into this endpoint yet — treat `verified: true` as "the evidence is
> internally consistent with this session", not "the client proved possession of
> its key".

### Frame Submission
```
POST /api/v1/sessions/{sessionId}/frames
  Body: { "frameBase64": "<base64-encoded JPEG>" }
  → FrameProcessResult {
      sessionId, stage, faceDetected, multipleFaces, faceQuality,
      livenessScore, padFlag, padAttackType,
      action: "proceed" | "escalate_to_active" | "reject" | "retry_passive",
      challenge: { challengeId, challengeType, timeoutMs, attemptNumber },
      message: "generic user-facing message"
    }
```

**Frame submission flow:**
1. Decodes base64 → OpenCV Mat
2. **Challenge lock**: if the session stage is `ACTIVE`, re-serve the open
   challenge (`escalate_to_active`, idempotent) — no scoring, no pass, no
   duplicate challenge (pessimistic row lock on `liveness_sessions`)
3. Runs face detection (Haar cascade)
4. Runs PAD check = FFT/texture heuristics **OR** MiniFASNet print/replay
   verdict. The model must clear `PAD_ATTACK_MIN_CONFIDENCE` (0.60) and beat the
   live class by `PAD_ATTACK_MARGIN` (0.15) — a near-tie softmax is ranked
   noise. A hit counts once confirmed on `PAD_CONFIRM_FRAMES` (3) consecutive
   frames (a real replay is consistent; a single noisy frame must not fail an
   honest session), and then **escalates to the active challenge** instead of
   ending the session: the challenge is the stronger check and PAD keeps running
   on every one of its frames, where it is terminal. The passive stage rejects
   outright only when the workflow has no challenge to hand the verdict to
   (`activeLivenessEnabled=false`, or liveness disabled)
5. If no face / multiple faces → retry
6. Computes the passive score for this frame — MiniFASNet live-class
   probability when the model is loaded, the OpenCV quality heuristic otherwise
   (`PassiveScoringService`, mode from `mosip.liveness.backend`). The model's
   probability is de-saturated above the default threshold by
   `mosip.liveness.score-temperature` (`ProbabilityCalibration`) so a genuine
   face reads a varied 0.88–0.97 instead of a flat `1.000`; `1` reports the raw
   confidence
7. Reads the session's scored frames and runs the **median-window decision**
   (`LivenessDecisionLogic.decidePassiveWindow`): until `passiveMinFrames` (5)
   scores exist the window is not decidable → `retry_passive`
   ("Checking face liveness...")
8. Once warm: median of the last `passiveWindowFrames` (7) scores ≥
   `passiveThreshold` → `proceed` (session PASSED); below → `escalate_to_active`
   + issue a challenge (or `reject` when active liveness is disabled). A
   confirmed PAD hit from step 4 escalates by the same route

> **Median over a window, not a single frame.** The HTTP path decides on the
> median of the last `passiveWindowFrames` scores, and only once at least
> `passiveMinFrames` scores exist (the same rule the embedded engine applies;
> both share `LivenessDecisionLogic`). One blurry frame can no longer escalate
> a session and one lucky frame can no longer pass it. Frame events persist
> every score, which is also the bona-fide dataset for calibration below.

### Challenge Validation
```
POST /api/v1/sessions/{sessionId}/challenges/validate
  Body: { "challengeId": "UUID", "framesBase64": ["<f1>", "<f2>", "<f3>"] }
  → ChallengeValidationResult {
      sessionId, challenge: { id, challengeType, status, ... },
      passed: true/false,
      action: "proceed" | "continue" | "retry_challenge" | "reject"
            | "locked" | "escalate_to_operator" | "failed"
            | "manual_check_passed" | "manual_check_failed",
      mayRetrySession: true/false,   // only on a terminal *failure* action
      message: "generic user-facing message"
    }
```

**Challenge validation flow:**
1. Re-run PAD on all challenge frames (attack = immediate reject)
2. Evaluate the specific challenge type across the frame sequence
3. If passed → resolve immediately:
   - enough challenges completed → `proceed` (PASSED)
   - more challenges needed → `retry_challenge` (a new challenge is issued)
4. If **not yet** passed and the window is still open → `continue` — the *same*
   challenge stays `ISSUED`, the session stays `ACTIVE`, and **no retry is consumed**
5. If the window elapsed without a pass → retry with a different challenge
   (up to `maxRetryCount`)
6. If retries exhausted → the frozen policy's repeated-failure action decides the
   terminal signal: `locked` (`LOCK_OUT`), `escalate_to_operator`
   (`ESCALATE_TO_OPERATOR`) or `failed` (`FALLBACK`, seeded as `ALLOW_RETRY`),
   each with its own `failureReason` and its own `mayRetrySession`. None of the
   three issues another challenge — extra attempts belong in `maxRetryCount`
   (see `RepeatedFailureAction`, and §10 for the per-workflow values)
7. A `manual` challenge (the extra check above) short-circuits steps 3–6: pass →
   `manual_check_passed`, window elapsed without the action → `manual_check_failed`,
   both with the session left untouched

**Challenge window.** A challenge stays open for `challengeTimeoutMs`, with a
**15-second floor** enforced by the engine regardless of the stored value
(shortened from 60s at the product's request). The floor is
`mosip.liveness.min-challenge-window-ms` (default 15 000, hard minimum 1 000 —
`DecisionEngineService.ABSOLUTE_MIN_CHALLENGE_WINDOW_MS`); lowering it is a test
seam, production keeps 15s. A client
should keep polling `/challenges/validate` with the same `challengeId` while the
response is `continue`, and stop the moment it changes — `static/console.js`
(loaded by `static/index.html`) is a reference implementation of exactly that loop.

### Authentication (`/api/v1/auth/*`)
```
POST /api/v1/auth/login
  Body:   { "username": "...", "password": "..." }
  → 200:  { username, role, mustChangePassword, disabled, createdAt, passwordChangedAt }
          Set-Cookie: MOSIP_SESSION=<token>; Path=/; Max-Age=...; HttpOnly; SameSite=Lax
  → 400:  { error, message, status, timestamp }   (a missing or malformed field)
  → 401:  { error, message, status, timestamp }   (every credential failure, one sentence)

POST /api/v1/auth/logout
  → 204, and a Set-Cookie that clears the session cookie   (idempotent)

GET  /api/v1/auth/me      → 200: the account, as above     (401 without a session)
POST /api/v1/auth/change-password
  Body:   { "currentPassword": "...", "newPassword": "..." }
  → 200:  the account, and a replacement Set-Cookie (every other session revoked)
  → 401:  a wrong current password — holding a session is not proof of knowing it
```

Accounts and sessions replace the shared admin API key. The privileged surface
(`PUT /api/v1/config/*`, `GET /api/v1/eval/threshold-sweep`, everything under
`/api/v1/users`) now needs an account whose session proves `SUPERVISOR`; the
operator API key remains for the read-only feeds and the role probe.

**Why the key had to go:** a shared secret names nobody in the audit trail,
cannot be revoked for one holder without rotating it for everyone, and — having no
expiry — stays good forever once a `.env` leaks. A policy write is the
highest-consequence call here (`passiveThreshold` decides who passes), so it is the
last route that should be reachable by a value sitting in a file.

**The token is never in a body.** It is 256 bits of CSPRNG output, returned exactly
once in an `HttpOnly` `Set-Cookie` header, stored server-side only as a SHA-256
digest, and accepted afterwards either as that cookie or as `Authorization: Bearer
<token>` (a cookie jar for a browser, a header for a command line). The short
digest — rather than BCrypt — is deliberate: the token is not a guessable human
secret, so a slow hash would defend nothing while turning every request's lookup
into a table scan.

**Machinery.** `PasswordHasher` is BCrypt (`at.favre.lib:bcrypt`, cost 12 ≈ 300 ms
on the 2-core target, refused below 10 outside `dev`). `AuthService` is the only
place a password is checked and the only place a session begins or ends;
`SessionCookie` is the only place the cookie's name and flags are written;
`AuthContextFilter` resolves the token once per request and parks the
`AuthPrincipal` on the request (`AuthGate` reads it back); `AuthUserBootstrap`
creates the first supervisor at startup; `SupervisorGateFilter` refuses
the supervisor-only routes. Tables `auth_users` and `auth_tokens` come from
`V12__auth_users_and_tokens.sql`.

**Rows.** A failed sign-in is counted on the account and locks it for
`MOSIP_AUTH_LOCKOUT_SECONDS` after `MOSIP_AUTH_MAX_FAILED_ATTEMPTS`; the lock is a
stored timestamp, so it ends by itself and a restart cannot clear it, and a
success clears it. Every refusal — unknown username, disabled account, locked
account, wrong password — is one `401` with one sentence, and an unknown username
still pays a full BCrypt verification, so the endpoint is neither a username nor a
timing oracle. A newly created account (and any password a supervisor resets)
owes a change: until it is made, that session reaches `GET /auth/me`,
`POST /auth/change-password` and `POST /auth/logout` and is refused `403`
elsewhere, including the client-facing routes. A change revokes every session the
account held, including the one that made it, and mints a replacement.

### Roles (`GET /api/v1/auth/role`)
```
GET /api/v1/auth/role
  Headers: X-Operator-API-Key: <MOSIP_OPERATOR_API_KEY>   (or a session cookie / Bearer token)
  → 200: { "role": "SUPERVISOR" | "OPERATOR" }
  → 401: { error, message, status, timestamp }   (nothing presented, or something that matches nothing)
```

Which role the caller proved, for a console that has to pick its surface. A
session for a `SUPERVISOR` account is answered `SUPERVISOR`, one for an `OPERATOR`
account `OPERATOR`, and the operator API key `OPERATOR` — a key can never be
answered `SUPERVISOR`. Every comparison is constant time, and the response is the
role name alone: never a credential, and not a fingerprint of one. The gate is
**fail-closed**: a blank `MOSIP_OPERATOR_API_KEY` means no caller is answered
`OPERATOR` by a key (an empty header matches nothing), and with no session and no
usable key the probe refuses everyone. `MOSIP_OPERATOR_API_KEY` must be at least
16 characters when set, enforced at startup by `SecurityValidator`.

The route is listed in `RouteInventory.OPERATOR_KEYED`, and `RateLimitFilter`
meters its requests per client IP on the existing per-IP budget
(`SESSION_CREATE_LIMIT`), so an unauthenticated caller guessing the key is
throttled and the refusal is counted as `rateLimitedOperatorKey` rather than as a
refused session creation. Sign-in attempts are metered on that same budget under
their own `auth-login` bucket and counters, because a password guess and a key
guess are different incidents. The same list, filter and budget apply to the two
operator feeds below.

### Operator feeds (`GET /api/v1/sessions`, `GET /api/v1/rejections`)
```
GET /api/v1/sessions?workflowType=RESIDENT&status=FAILED&limit=50
  Headers: X-Operator-API-Key: <MOSIP_OPERATOR_API_KEY>   (or a session cookie / Bearer token)
  → 200: [ { id, workflowType, deviceId, status, currentStage, retryCount,
             finalResult, failureReason, createdAt, closedAt }, ... ]
  → 400: limit outside 1..200, or an unknown workflowType/status
  → 401: { error, message, status, timestamp }   (no role in the headers)

GET /api/v1/rejections?limit=50
  → 200: { limit,
           sessions: [ SessionListItem, ... ],       # status = FAILED, newest first
           events:   [ { id, eventType, sessionId, details, createdAt }, ... ] }
```

The two reads the operator surface exists for. Both are newest first and page
limited, and both sit behind the key gate above — there is no second authentication
path, and nothing is checked inside the handlers. `limit` is **1..200**; outside it
the request is `400` rather than silently clamped (a clamped page reads as a short
result set), and an absent or empty `limit` (`?limit=`) is the default **50**.

On the session feed `workflowType` and `status` are optional: they are applied in
SQL, together with the page size — `LivenessSessionRepository.findRecent` orders by
`created_at DESC` and the single-page `PageRequest` becomes its `LIMIT`, so the page
is the newest matching rows rather than a window cut out of a full table read. A
blank parameter (`?workflowType=`) means "no filter", which is what a console that
always renders the field sends; an unknown value is a `400`.

Rows are `SessionListItem` — id, workflow, device, status, stage, retry count, final
result, failure reason, timestamps — and nothing else: no `subjectRef` (a list is not
an identity lookup), no frozen policy snapshot, no frame data. `RejectionsController`
reuses that projection for its failed rows, so the two feeds cannot disagree about
what a session row exposes.

The rejections feed answers "which sessions failed, and what was rejected inside
them": the `FAILED` sessions plus the `PAD_REJECTED` and `CHALLENGE_FAILED` audit
events, each list capped at `limit` on its own (a rejection is often one session
*and* one or more events, so one shared cap would split arbitrarily between them).
The event payload is the stored `details` map unchanged — exactly what
`GET /api/v1/sessions/{id}/audit` already answers for that session, so the feed
exposes no attack detail the session's own trail does not. `PAD_REJECTED` is one of
the free-form names the HTTP API writes and is *not* in `AuditEventType`, so the two
names the feed queries are literals in `RejectionsController.REJECTION_EVENT_TYPES`.

Neither route carries a `{sessionId}`, so neither belongs in
`SESSION_SCOPED`: an ownership claim cannot guard a list of sessions the caller did
not create, and the key is the guard instead. `ApiRouteInventoryTest` asserts exactly
that — the feeds are operator-keyed, are not session-scoped, and creation
(`POST /api/v1/sessions`) stays open. Like the role probe, they are metered per
client IP on the session-creation budget, so a console polling them paces itself
against the same budget a key guesser spends.

### Configuration
```
GET /api/v1/config/{workflowType}
  → ConfigPolicyResponse { workflowType, livenessEnabled, passiveThreshold, ... }

GET /api/v1/config/{workflowType}/effective
  → EffectivePolicy (computed merge of DB config + engine defaults)

PUT /api/v1/config/{workflowType}
  Headers: session cookie (or Authorization: Bearer <token>) for a SUPERVISOR account
  Body: { "passiveThreshold": 0.85, "maxRetryCount": 3, ... }
  → ConfigPolicyResponse (updated)
  → 401: no session; 403: a session that is not a supervisor's (or still owes a
         password change). Reads above are open GETs; only writes need an account.
```

**Config fields:**
| Field | Type | Range | Default |
|-------|------|-------|---------|
| livenessEnabled | boolean | — | true |
| passiveThreshold | double | 0.0–1.0 | 0.80 (single source of truth — `LivenessConfig.DEFAULT_PASSIVE_THRESHOLD`, mirrored by the V1/V2 seed, `application.yml` and the DB-miss fallback; calibrate via the sweep endpoint below) |
| activeLivenessEnabled | boolean | — | true |
| minChallengeCount | int | ≥ 1 | 1 |
| challengeTypes | string[] | non-empty | ["blink","smile","turn_left","turn_right"] |
| challengeTimeoutMs | int | ≥ 1000 (floor from `min-challenge-window-ms`, default 15 000) | 15000 |
| maxRetryCount | int | ≥ 0 | 3 |
| onRepeatedFailure | string | LOCK\|ESCALATE\|ALLOW_RETRY | LOCK |

### User Management (`/api/v1/users`)
```
GET /api/v1/users
  Headers: session cookie (or Authorization: Bearer <token>) for a SUPERVISOR account
  → 200: [ { username, role, disabled, mustChangePassword, createdAt, passwordChangedAt }, ... ]
  → 401: no session; 403: session not SUPERVISOR

POST /api/v1/users
  Headers: session cookie (or Authorization: Bearer <token>) for a SUPERVISOR account
  Body: { "username": "...", "role": "OPERATOR|SUPERVISOR", "initialPassword": "..." }
  → 201: UserResponse (account created; owes password change)
  → 409: username already exists

PUT /api/v1/users/{username}
  Headers: session cookie (or Authorization: Bearer <token>) for a SUPERVISOR account
  Body: { "role": "...", "disabled": boolean, "newPassword": "..." }
  → 200: UserResponse (updated; new password revokes existing sessions)
  → 404: user not found; 409: cannot disable or demote the last active supervisor

DELETE /api/v1/users/{username}
  Headers: session cookie (or Authorization: Bearer <token>) for a SUPERVISOR account
  → 204
  → 404: user not found; 409: cannot delete the last active supervisor
```

### Metrics
```
GET /api/v1/metrics
  → OperationalMetrics { totalSessions, passedSessions, failedSessions, activeSessions, passRate, avgPassiveToActiveEscalationRate,
      avgFramesPerSession, avgChallengesPerSession, livenessRetryRate, livenessFailureRate, padRejectionRate,
      rateLimitAllowedRequests, rateLimitedRequests, rateLimitedSessionCreate, rateLimitedFrames, rateLimitedOperatorKey,
      rateLimitedAuthLogin, rateLimitRejectionRate }
```

The `rateLimit*` fields come from `RateLimitCounters`, **not** from the
database: they count what the limiter has admitted and refused per rule
(`session-create` per IP, `frames` per session, `operator-key` per IP, `auth-login`
per IP for sign-in attempts) since process start, so they reset on restart while
the session counters do not.
`rateLimitRejectionRate` is `rejected / (allowed + rejected)` over the limited
routes, and is `0.0` (never `NaN`) when there has been no traffic.

### Threshold Calibration
```
GET /api/v1/eval/threshold-sweep?targetBpcer=0.02
  → ThresholdSweep.Result {
      targetBpcer, minFrames, windowFrames, scorer, attackDataIsProxy, excludedWindows,
      recommendedThreshold,        // omitted (null) when nothing is recommended
      recommendationBasis, note,
      rows: [ { threshold, bonaFideWindows, bonaFidePassed, bpcer,
                attackWindows, apcer, acer } ]     // 0.05..0.95 step 0.05
    }
```

Sweeps the passive threshold over the **same median-window decision the live
path uses**, evaluated on bona-fide score windows from `frame_events` (real
captures) plus attack windows (recorded presentation-attack sessions when any
exist, else the labelled `ProxyPresentationCorpus`). APCER is reported as
`null` (never a fabricated zero) when no attack data exists. See
`docs/configuration.md` for how to read and apply the result.

### Audit Trail
```
GET /api/v1/sessions/{sessionId}/audit
  → [ { id, eventType, details: {key: value, ...}, createdAt }, ... ]

GET /api/v1/config/audit?limit=50      # operator view: policy edits, newest first
  → [ { id, eventType: "CONFIG_CHANGED", workflowType, details: {workflowType, action, actor, changes}, createdAt }, ... ]
GET /api/v1/config/audit?limit=50&workflowType=RESIDENT   # same, narrowed in SQL via audit_logs.workflow_type (V8)
```

`audit_logs.session_id` is nullable (migration V7): `NULL` means an
operator-level event — currently only `CONFIG_CHANGED`, written in the same
transaction as the policy `PUT`. A policy edit decides who passes liveness, so it
is recorded like any decision: the fields that actually moved with their old and
new values, and the username of the signed-in supervisor who made the edit (never
a credential).
Session trails query by session id, so they can never pick one up. See
`docs/configuration.md` for the payload and the open-read rationale.

**Audit event types written by the HTTP API** (free-form strings stored in
`audit_logs.event_type`, and what these endpoints actually return):
`PAD_REJECTED`, `SESSION_PASSED`, `CHALLENGE_ISSUED`, `CHALLENGE_PASSED`, `CHALLENGE_FAILED`, `CONFIG_CHANGED`.

**Additional `AuditEventType` enum values** (`audit/AuditEventType.java`) emitted by the
standalone engine's structured logger — *not* by the HTTP API:
`SESSION_STARTED`, `SESSION_CLOSED`, `SESSION_ABORTED`, `FRAME_REJECTED`, `FRAME_SCORED`,
`PASSIVE_PASSED`, `LIVENESS_FAILED`, `ESCALATED_TO_ACTIVE`, `CHALLENGE_TIMEOUT`,
`MAX_RETRIES_EXCEEDED`, `REPEATED_FAILURE_ACTION`, `PAD_BLOCKED`, `MODEL_UPDATED`,
`INTERNAL_ERROR`.

### Error Response Format
All errors return:
```json
{
  "error": "MACHINE_READABLE_CODE",
  "message": "Generic, safe user-facing message (never reveals detection internals)",
  "status": 422,
  "timestamp": "2026-08-27T12:00:00Z"
}
```

---

## 6. How the Engine Works Internally

> **Two execution paths share the domain types below.** Where they differ, this
> document says which one it means.
>
> 1. **HTTP service** — `api/` → `services/DecisionEngineService` → `LivenessEngineService`
>    / `PadEngineService`. This is what actually runs on port 8000. Sessions, frames,
>    challenges and audit rows are persisted in the database, each frame is scored
>    **individually**, and challenges are `challenges` table rows.
> 2. **Embedded engine library** — `engine/FaceLivenessEngine` behind `engine/LivenessPipeline`.
>    A standalone, in-memory state machine with temporal **median voting**, frame
>    sampling, combined passive/active scoring and a `client/` adapter layer, embedded
>    by Desktop/Android registration clients. It is exercised by the engine tests and
>    the attack-scenario harness.

### 6.1 The Core Pipeline (Passive → Active → PAD)

```
┌─────────────────────────────────────────────────────────────────┐
│                    FaceLivenessEngine                            │
│                                                                 │
│  ┌─────────┐    ┌───────────┐    ┌──────────────┐             │
│  │ PASSIVE  │───▶│  ACTIVE   │───▶│    FINAL     │             │
│  │  STAGE   │    │  STAGE    │    │   DECISION   │             │
│  └─────────┘    └───────────┘    └──────────────┘             │
│                                                                 │
│  Scoring window:    Challenge:        Pass/Fail/              │
│  N frames →         blink/smile/      PAD blocked              │
│  median →           turn/gaze         Retry policy             │
│  threshold          (randomized)      Lock out                 │
└─────────────────────────────────────────────────────────────────┘
```

### 6.2 Passive Liveness Stage

1. **Frame sampling**: Process every Nth frame (configurable `frameSamplingRate`, default 1 = every frame). Always checks session timeout even on skipped frames.
2. **Face detection**: OpenCV Haar cascade `haarcascade_frontalface_default.xml`. Rejects if no face or multiple faces.
3. **Face quality**: Rejects if quality < `minFaceQuality` (0.50 default).
4. **PAD check**: Runs on every frame. FFT energy analysis (screen replay proxy), texture variance (print attack proxy), brightness check (video replay proxy). Any attack = terminal session failure.
5. **Passive liveness scoring**: Backend-specific. Mock backend uses scriptable score. Real backends use temporal analysis (EAR variance, blink detection, pose micro-movements, smile variation, gaze variance).
6. **Temporal median voting**: Collects `passiveMinFrames` (5) scores in a sliding window of `passiveWindowFrames` (7). Computes **median** (not mean — rejects outliers).
7. **Decision**: Median ≥ `passiveThreshold` (0.80) → PASS. Below → escalate to active (or fail if active disabled).

**HTTP-path passive score** (what the running service computes for one frame):

```
score = PassiveScoringService.score(frame, observation, imageUtils)
        ├─ mosip.liveness.backend = auto (default): MiniFASNet-V2 live-class
        │   probability via OnnxMiniFasNetBackend (checksum-verified model),
        │   falling back to the heuristic below if the model cannot analyse
        │   the frame (or fails to load)
        ├─ mosip.liveness.backend = <explicit id> (mock | onnx-minifasnet-v2 |
        │   mediapipe-facemesh | tflite-minifasnet): that SPI backend scores
        │   this path AND the engine's SPI bean — one key, both wirings;
        │   unavailable = coded fail-closed error, never a silent fallback
        │   (interop report F5)
        └─ mosip.liveness.backend = heuristic: quality formula
            faceQuality = 0.5 × sharpness + 0.5 × brightness
            sharpness   = min(1, LaplacianVariance / 500)
            brightness  = 1 − |meanGray − 128| / 128
            eyeSymmetry = 1.0 if ≥ 2 eyes found in the face ROI, else 0.4
            score       = 0.4 × faceQuality + 0.4 × eyeSymmetry + 0.2 × sharpness
```

Frame scores accumulate in `frame_events`; the **median of the last
`passiveWindowFrames`** (once ≥ `passiveMinFrames` exist) is compared against
`passiveThreshold` — the same `LivenessDecisionLogic.decidePassiveWindow` rule
the engine-library path and the calibration sweep use.

> The old heuristic is kept only as an explicit fallback: its 0.4·eyeSymmetry
> term capped the score at **0.76 whenever fewer than 2 eyes were detected**,
> so at the old 0.80 default such a session could never pass passive — eye-
> cascade flakiness, not liveness, drove the escalation rate. Calibrate the
> threshold against the scorer actually in use (`GET /api/v1/eval/threshold-sweep`).

> Every score, threshold and formula in the project — including the PAD heuristics
> and each backend's passive-scoring maths — is catalogued in the separate reference
> document *MOSIP Liveness — Scores, Thresholds & Passing Criteria*, which is
> deliberately **not** part of this file.

### 6.3 Active Challenge Stage

1. **Challenge selection**: `ChallengeSelector` uses a shuffled-bag algorithm with `SecureRandom`. Every challenge type appears once before repeating. No back-to-back repeats while alternatives remain.
2. **Available challenges**: `BLINK`, `SMILE`, `TURN_HEAD_LEFT`, `TURN_HEAD_RIGHT`, `LOOK_DIRECTION` (with random direction parameters), `LOOK_UP`, `LOOK_DOWN`, `LOOK_LEFT`, `LOOK_RIGHT`
3. **Challenge evaluation** (`ChallengeEvaluators`):
   - **BLINK**: EAR (Eye Aspect Ratio) drops below 0.22, then recovers above 0.27
   - **SMILE**: smile score exceeds 0.55 at least once
   - **TURN_HEAD_LEFT/RIGHT**: yaw reaches ±12°
   - **LOOK_***: gaze vector within 0.35 tolerance of target for ≥50% of frames
4. **Real-time UI feedback** (v3): Frame-by-frame evaluation produces `ChallengeProgress`:
   - `AWAITING_ACTION` → UI shows "Please continue"
   - `ACTION_DETECTED` → UI shows "Action detected"
   - `HOLD_STILL` → UI shows "Hold still"
5. **Passive re-evaluation during active** (v3): During challenges, each frame is also scored by the passive liveness scorer. If the passive median drops below `passiveThresholdActive`, the session fails — catches presentation attacks that can simulate the challenge action.
6. **Combined scoring** (v3): Weighted blend of passive (60%) + active (40%) components:
   `combined = 0.6 × passiveScore + 0.4 × activeComponent`.

**HTTP-path challenge evaluation** (engine library aside, the running service has no
landmarks — it uses OpenCV bounding-box heuristics over the submitted burst):

| Challenge | Passes when |
|-----------|-------------|
| `TURN_LEFT` | peak horizontal face-box excursion from the baseline frame > `max(12px, 20% of face width)`, toward **larger x** |
| `TURN_RIGHT` | same measure, toward **smaller x** |
| `LOOK_LEFT` / `LOOK_RIGHT` | peak excursion > `max(8px, 12% of face width)`, same polarity |
| `LOOK_DIRECTION` | `abs(peak excursion)` > the gaze threshold |
| `LOOK_UP` / `LOOK_DOWN` | never — fails closed, because vertical gaze is not recoverable from horizontal box movement |
| `BLINK` | an eye-detection dropout in the face ROI followed by eyes detected again |
| `SMILE` | mouth-ROI Laplacian variance range across the burst > 50 |

Frames in which no face is detected are **skipped** rather than failing the burst
(at least 2 face-bearing frames are required), so losing Haar tracking mid-turn does
not reject a genuine turn. The excursion is measured against the first usable frame,
so a turn that returns to centre still counts.

### 6.4 Session Lifecycle

```
initSession(workflow)
  → state: SCORING
  → pushFrame() × N
     → state: SCORING (accumulating passive scores)
     → state: ESCALATED (passive below threshold)
        → requestChallenge()
        → state: IN_CHALLENGE
           → pushFrame() × M (frame-by-frame evaluation)
           → validateChallenge(frames) (batch evaluation)
           → CHALLENGE_PASSED → state: ESCALATED (if more needed)
           → CHALLENGE_FAILED → retry or hard fail
        → state: PASSED or FAILED
closeSession()
  → SessionSummary with audit data
```

**Terminal states**: `PASSED`, `FAILED`, `BYPASSED`

**Session timeout**: `maxSessionDurationMs` (30s default). Checked on every frame, even skipped ones.

> This state machine is the **embedded engine library**. In the HTTP path a session is
> simply a `liveness_sessions` row whose `status` moves `ACTIVE` → `PASSED` | `FAILED`
> | `EXPIRED`, driven by `SessionsController` and `DecisionEngineService`; there is no
> `maxSessionDurationMs` enforcement on that path.

### 6.5 Error Codes

| Code | Constant | User Message | Retryable? |
|------|----------|-------------|------------|
| E101 | DEVICE_UNAVAILABLE | "Biometric device is unavailable." | Yes |
| E102 | DEVICE_CONNECTION_FAILURE | "Could not connect to the biometric device." | Yes |
| E103 | CAMERA_UNAVAILABLE | "Camera is unavailable." | Yes |
| E201 | FACE_NOT_DETECTED | "No face detected. Please look at the camera." | Yes |
| E202 | MULTIPLE_FACES_DETECTED | "Multiple faces detected..." | Yes |
| E203 | POOR_FACE_QUALITY | "Face is not clear..." | Yes |
| E301 | LIVENESS_SCORE_BELOW_THRESHOLD | "Liveness check incomplete..." | Yes |
| E401 | PAD_FAILURE | "Face verification could not be completed..." | **No (terminal)** |
| E501 | ACTIVE_CHALLENGE_FAILURE | "Verification action was not completed..." | Yes |
| E502 | CHALLENGE_TIMEOUT | "Verification timed out..." | Yes |
| E503 | MAX_RETRIES_EXCEEDED | "Verification could not be completed..." | **No** |
| E504 | SESSION_TIMEOUT | "Face verification timed out..." | Yes |
| E505 | MODEL_INTEGRITY | "Model integrity check failed." | Yes |
| E506 | ACTIVE_REEVAL_FAILED | "Liveness score dropped during active challenge..." | Yes |
| E601 | INVALID_FRAME_DATA | "Invalid capture data received." | Yes |
| E602 | INVALID_SESSION | "Unknown or closed session." | Yes |
| E603 | INVALID_STATE | "Operation not allowed in the current state." | Yes |
| E604 | CONFIGURATION_INVALID | "Liveness configuration is invalid." | Yes |
| E901 | ENGINE_INTERNAL_ERROR | "An internal error occurred." | Yes |

"Retryable?" reflects `LivenessErrorCode.isRetryableByUser()`, which is `true` for
every code except `PAD_FAILURE` (E401) and `MAX_RETRIES_EXCEEDED` (E503).

---

## 7. Backend Architecture (Pluggable Liveness/PAD)

### 7.1 The `LivenessBackend` Interface

All inference is behind one interface. The engine never calls vendor SDKs directly:

```java
public interface LivenessBackend {
    String id();
    void initialize(Map<String, String> options);
    FaceSignals analyzeFrame(Frame frame);           // face count, quality, EAR, pose, gaze, smile
    double scorePassiveLiveness(Frame frame, FaceSignals signals);  // 0.0-1.0
    PadVerdict assessPad(Frame frame, FaceSignals signals);         // attack/bonaFide + type
    void shutdown();
}
```

### 7.2 Built-in Backends

| Backend | Class | Model | What It Does | When to Use |
|---------|-------|-------|-------------|-------------|
| **Mock** | `MockLivenessBackend` | None | Scriptable test double. `Subject` simulates face, blink, smile, head turn, PAD verdicts. | Unit tests, CI, attack-scenario harness |
| **TFLite MiniFASNet** | `TfLiteMiniFasNetBackend` | `minifasnet.tflite` (~1.5MB) | Real PAD: classifies live vs print/screen-replay. Uses OpenCV Haar cascades for face/eye detection. | Android/embedded builds only — the TFLite AAR has no desktop-JVM interpreter, so this backend cannot run here |
| **ONNX MiniFASNet-V2** | `OnnxMiniFasNetBackend` | `minifasnet_v2.onnx` (~1.7MB) | Real passive liveness + PAD via ONNX Runtime; **live = index 1** (index 0/2 = print/replay). 80×80 BGR crop (2.7× margin), **raw 0–255** pixels → softmax. | **Default scorer** in the HTTP path (`mosip.liveness.backend: auto`) |
| **MediaPipe FaceMesh** | `MediaPipeFaceMeshBackend` | `face_mesh.tflite` (~5MB) | 468-point 3D facial landmark extraction. Precise EAR, smile, head pose (solvePnP + Rodrigues), iris-based gaze. Temporal liveness scoring via rolling buffer. | High-fidelity active challenge evaluation |

### 7.3 Key Backend Details

**MockLivenessBackend:**
- `Subject` class is the test control surface: `blink()`, `smileBig()`, `turnLeft(18)`, `attack(PRINTED_PHOTO, 0.95)`
- `enqueueSignal(FaceSignals)` for per-frame signal override sequences
- Configurable `livenessScore`, `scoreNoise` (adds random jitter)

**TfLiteMiniFasNetBackend:**
- Loads TFLite via reflection (no compile-time TFLite dependency)
- OpenCV face detection via Haar cascade → crop → resize to 80×80 → TFLite inference
- Options: `modelPath`, `inputSize`, `threads`, `delegate` (cpu/nnapi/gpu), `faceCascadePath`, `eyeCascadePath`
- **Desktop-JVM caveat:** the TFLite runtime ships as an Android-only AAR with no `org.tensorflow.lite.Interpreter`, so on desktop this backend silently falls back to the quality heuristic. Use the ONNX backend instead.

**OnnxMiniFasNetBackend:**
- `com.microsoft.onnxruntime:onnxruntime` (desktop-native, bundled runtime), no reflection
- Bundled model `classpath:models/minifasnet_v2.onnx`, SHA-256 `d7b3cd9b…` verified on load (skipped only for a custom `modelPath` unless `expectedSha256` is supplied)
- Input is **raw 0–255 BGR** (upstream `ToTensor` has `.div(255)` commented out — the ONNX model card's `/255` claim is wrong). The live class is **index 1** (`label == 1 ? "Real" : "Fake"` in every reference implementation); the card's `[live, print, replay]` order is also wrong. Feeding `/255` and reading index 0 made every genuine face score ≈0.0004 and always fall back to the heuristic.
- Face detection params mirror `ImageUtils.detectFaces` (scale 1.1, neighbors 5, min 60×60)
- Face crop is a 2.7× margin around the face centre **clamped to the frame** (reference `scale = min((h-1)/box_h, (w-1)/box_w, 2.7)`). Black-padding an oversized crop added borders the model read as a presented image, which surfaced as device-specific `SCREEN_REPLAY` false positives on some cameras (e.g. a Mac webcam)
- Leaves action landmarks `null` (a classifier, not a mesh) — active challenges stay with the heuristic/MediaPipe path
- Options: `modelPath`, `modelResource`, `expectedSha256`

**MediaPipeFaceMeshBackend:**
- OpenCV face detection → crop + pad (30% expansion) → resize to 192×192 → FaceMesh TFLite inference
- 468 landmarks → EAR from 6 eye-contour points, smile from mouth width/face width ratio
- Head pose via `solvePnP` with 6 reference 3D model points (nose tip, chin, eye corners, temples)
- Gaze from iris center position (478-point model) or geometric fallback
- Temporal liveness scoring: rolling buffer of 15 frames, computing EAR variance, blink count, pose variance, smile variance, gaze variance — weighted blend (30% EAR + 25% blink + 20% pose + 15% smile + 10% gaze) × 40% + spatial quality × 60%

---

## 8. Device Adapter Layer

### 8.1 Interface

```java
public interface DeviceAdapter extends AutoCloseable {
    String id();
    DeviceCapabilities capabilities();
    void open();
    void startCapture(FrameListener listener);
    void stopCapture();
    boolean isCapturing();
    void close();
}
```

### 8.2 Implementations

| Adapter | What It Does |
|---------|-------------|
| `SbiStreamDeviceAdapter` | Consumes L1 device's MJPEG STREAM endpoint (`GET /stream`, multipart/x-mixed-replace). HTTP client with reconnect backoff (200ms→4s, max 5 attempts). Throttles to maxFps (15). |
| `BurstRCaptureDeviceAdapter` | Polls L0 devices via discrete `rCapture` calls at target FPS (10). For devices without STREAM support. |
| `MockL0L1Device` | In-process simulated device. Renders `SyntheticScene` (gradient background + face-like subject). Supports failure injection (`failOnOpen`, `corruptEveryNthFrame`, `disconnectAfterFrames`). Exposes built-in SBI HTTP server (`/info`, `/stream`). |

### 8.3 Supporting Classes

| Class | Purpose |
|-------|---------|
| `MjpegDecoder` | Incremental JPEG extractor from multipart byte stream. Scans for SOI (FFD8) / EOI (FFD9) markers. |
| `PixelFormats` | `BufferedImage` → `RGB_888` / `RGB_GRAY` / `NV21` / `YUV420` conversion |
| `SyntheticScene` | Deterministic Java2D scene: gradient background + face oval + eyes (open/closed) + mouth line. Scriptable via `setBlinking()`, `setBrightness()`. |
| `FrameListener` | Callback: `onFrame(Frame)` on capture thread, `onDeviceError(LivenessException)` |
| `CaptureSource` | Functional interface: `capture(sequenceNumber, timestampMillis) → Frame` |
| `DeviceCapabilities` | Record: deviceId, deviceSubType, streamingSupported, burstCaptureSupported, supportedFormats, maxFps |

---

## 9. Core Domain Types

### `Frame`
Immutable captured frame: `byte[] data`, `int width`, `int height`, `Format format` (RGB_GRAY/RGB_888/NV21/YUV420), `long timestampMillis`, `int sequenceNumber`.

### `FaceSignals`
Per-frame facial analysis: `int faceCount`, `double qualityScore`, `Double eyeAspectRatioLeft/Right`, `Double smileScore`, `Double yawDegrees`, `Double pitchDegrees`, `Double gazeX/gazeY`. Factory methods: `noFace(quality)`, `live(faceCount, quality, ear, smile, yaw, pitch, gazeX, gazeY)`.

### `PadVerdict`
`boolean attackDetected`, `PadAttackType attackType` (PRINTED_PHOTO/SCREEN_REPLAY/VIDEO_REPLAY/OTHER), `double confidence`. Factory: `bonaFide(confidence)`, `attack(type, confidence)`.

### `Challenge`
`ChallengeType type`, `long timeoutMs`, `Map<String, Double> parameters`. Types: BLINK, SMILE, TURN_HEAD_LEFT, TURN_HEAD_RIGHT, LOOK_DIRECTION, LOOK_UP, LOOK_DOWN, LOOK_LEFT, LOOK_RIGHT.

### `ChallengeProgress` (v3)
`AWAITING_ACTION`, `ACTION_DETECTED`, `HOLD_STILL` — real-time UI feedback during active challenges.

### `CombinedLivenessScore` (v3)
`double passiveComponent`, `double activeComponent`, `double combined` — per-frame dual score during active challenges.

### `ActiveFrameResult` (v3)
`boolean actionDetected`, `ChallengeProgress progress`, `double passiveLivenessScore`, `double combinedScore`, `PadVerdict padVerdict` — result of evaluating one frame during active challenge.

---

## 10. Configuration System

### `LivenessConfig` (Builder pattern, immutable)
Base configuration with all defaults. Created once at startup. Supports per-workflow overrides via `Map<WorkflowType, LivenessPolicy>`.

### `LivenessPolicy` (per-workflow overrides)
Every field is nullable (null = inherit from base). Applied via `LivenessConfig.effectivePolicy(workflow)`.

### `EffectivePolicy` (resolved for one session)
Record with all fields fully resolved (base + override merged). Resolved and validated once at
session creation (`ConfigService` + `EffectivePolicyValidator`), then **frozen on the session** as
`policy_snapshot` / `policy_snapshot_at`. The engine's `resolvePolicy(session)` prefers that
snapshot, so an admin edit to `config_policies` cannot move an in-flight session's operating
point; legacy rows created before `V4` have no snapshot and fall back to a live config read.

### `WorkflowPolicyDefaults` (per-workflow operating points)
The single source of truth for what each user type gets before an operator edits anything —
shared by the `V4` seed, the `ConfigService` DB-miss fallback and lazily-created rows in
`ConfigController`, so a missing `config_policies` row can never collapse the three workflows
onto one operating point:

| Workflow | passiveThreshold | minChallengeCount | challengeTimeoutMs | maxRetryCount | onRepeatedFailure |
|----------|------------------|-------------------|--------------------|---------------|-------------------|
| RESIDENT | 0.80 | 1 | 20,000 | 3 | ESCALATE_TO_OPERATOR |
| OPERATOR | 0.82 | 1 | 15,000 | 2 | FALLBACK |
| SUPERVISOR | 0.85 | 2 | 15,000 | 1 | LOCK_OUT |

The challenge-window floor is one knob everywhere — `mosip.liveness.min-challenge-window-ms`
(default 15,000, absolute clamp 1,000): the config API refuses less, `EffectivePolicyValidator`
refuses to freeze less, and the engine clamps stored values up to it.

**Key config constants:**
| Constant | Default | Description |
|----------|---------|-------------|
| `DEFAULT_PASSIVE_THRESHOLD` | 0.80 | Median score ≥ this → pass |
| `DEFAULT_MIN_FACE_QUALITY` | 0.50 | Minimum face quality |
| `DEFAULT_PASSIVE_MIN_FRAMES` | 5 | Min frames before passive decision |
| `DEFAULT_PASSIVE_WINDOW_FRAMES` | 7 | Sliding window size |
| `DEFAULT_MIN_CHALLENGE_COUNT` | 2 | Challenges required to pass |
| `DEFAULT_CHALLENGE_TIMEOUT_MS` | 15,000 | Per-challenge window (15s floor) |
| `DEFAULT_MAX_RETRIES` | 2 | Retries before hard fail |
| `DEFAULT_MAX_SESSION_DURATION_MS` | 30,000 | Total session timeout |
| `DEFAULT_FRAME_SAMPLING_RATE` | 1 | Process every frame (production: 2) |
| `DEFAULT_COMBINED_PASSIVE_WEIGHT` | 0.6 | Passive weight in combined score |
| `DEFAULT_COMBINED_ACTIVE_WEIGHT` | 0.4 | Active weight in combined score |
| `DEFAULT_PASSIVE_THRESHOLD_ACTIVE` | -1 (sentinel) | During active, defaults to passiveThreshold |

---

## 11. Database Schema

Eight tables across twelve Flyway migrations (`V1`–`V13`; one number in the range
was never used). `V1__init_schema.sql` creates the five core ones:

```sql
liveness_sessions     -- id(UUID), workflow_type, device_id, status, current_stage,
                       -- retry_count, online, final_result, failure_reason, timestamps,
                       -- policy_snapshot(TEXT), policy_snapshot_at   (V4)

frame_events          -- id(UUID), session_id(FK), stage, face_detected, multiple_faces,
                       -- face_quality, liveness_score, pad_flag, pad_attack_type, pad_confidence

challenges            -- id(UUID), session_id(FK), challenge_type, status, attempt_number,
                       -- timeout_ms, issued_at, completed_at

config_policies       -- id(UUID), workflow_type(UNIQUE), liveness_enabled, passive_threshold,
                       -- active_liveness_enabled, min_challenge_count, challenge_types(TEXT),
                       -- challenge_timeout_ms, max_retry_count, on_repeated_failure

audit_logs            -- id(UUID), session_id(FK, NULL for operator events), event_type,
                       -- details(TEXT), created_at
```

Seed data: default policies for RESIDENT, OPERATOR, SUPERVISOR workflows (explicit
literal UUIDs — no `uuid-ossp` extension is required, because Hibernate generates the
identifier client-side with `@GeneratedValue(strategy = GenerationType.UUID)`).

`V4__session_policy_snapshot.sql` adds `liveness_sessions.policy_snapshot` /
`policy_snapshot_at` and rewrites the three seeded rows to their distinct
`WorkflowPolicyDefaults` operating points (see §10). The local audit chain adds
`config_change_audit` (V7), `audit_logs.workflow_type` + its index (V8) and the
append-only `UPDATE`/`DELETE` guards on `audit_logs` (V9). `V10` drops the unused
singular `audit_log` table, `V11` adds `challenges.manual`,
`V12__auth_users_and_tokens.sql` creates `auth_users` (BCrypt hash, role, flags,
lockout and password timestamps) and `auth_tokens` (SHA-256 digest only), and `V13`
adds `liveness_sessions.nonce` + `last_frame_seq` for the anti-replay checks.

`challenge_types` and `details` are stored as **JSON text**, not `JSONB`: the entities
map them with explicit JPA `AttributeConverter`s (`models/converter/`) so H2 (dev) and
PostgreSQL behave identically, and neither column is ever queried with JSON operators.
The `config_policies` passive-threshold default was unified to `0.80` by
`V2__unify_passive_threshold.sql` (which also rewrites any seeded `0.75` rows), so the
DB now matches `LivenessConfig` / `application.yml`, and `V4` rewrites the seeded rows to
their distinct `WorkflowPolicyDefaults` operating points — so `min_challenge_count` /
`max_retry_count` / `challenge_timeout_ms` are per workflow, not one shared literal.

---

## 12. Audit & Metrics System

### `StructuredAuditLogger`
Writes key=value structured lines to stdout (or any `PrintStream`):
```
ts=2026-08-27T12:00:00Z event=FRAME_SCORED session=abc-123 workflow=RESIDENT_REGISTRATION median=0.85 threshold=0.80
```
Never emits raw frames or model internals — only decisions and scores.

### `MetricsCollector`
Thread-safe counters maintained **inside the embedded engine**: `framesProcessed`,
`totalProcessingMs`, `challengesCompleted`, `totalChallengeMs`, `retries`,
`sessionsEnded`, `sessionsFailed`, `escalations`, `padBlocks`. `snapshot()` derives
`avgProcessingMs`, `avgChallengeMs`, retry rate, failure rate and escalation rate —
rates are divided by `sessionsEnded` (1 minimum, to avoid divide-by-zero).

> The HTTP `GET /api/v1/metrics` endpoint does **not** read this collector. It
> aggregates from the database (`MetricsController`): `passRate = passed/total`,
> `livenessFailureRate = failed/total`, `avgFramesPerSession`, `avgChallengesPerSession`,
> `livenessRetryRate = ΣretryCount / total`, `padRejectionRate = sessions whose
> failure_reason starts with 'presentation_attack:' / total`, and
> `avgPassiveToActiveEscalationRate = distinct sessions with an ACTIVE-stage frame / total`.
> Each ratio is rounded to 4 decimal places.

### `AttackScenarioHarness`
Automated evaluation framework for ISO/IEC 30107-3 style testing:
- Runs labeled presentations (BONA_FIDE, PRINTED_PHOTO, SCREEN_REPLAY, VIDEO_REPLAY)
- Through the real engine pipeline
- Reports APCER, BPCER, ACER, FAR, FRR, escalation rate
- Configurable attack-miss rate (3%) and bona-fide FP rate (1%)

---

## 13. The Python Reference Backend

`pad_liveness_backend/` contains the **original FastAPI reference implementation** that preceded the Java rewrite. It implements the same API contract and decision flow:

| Component | Python | Java Equivalent |
|-----------|--------|----------------|
| `app/main.py` | FastAPI app entry | `PadLivenessApplication.java` |
| `app/api/routes/` | 6 route files | 13 controller classes |
| `app/services/decision_engine.py` | Orchestrator | `DecisionEngineService.java` |
| `app/services/liveness_engine.py` | Passive + active scoring | `LivenessEngineService.java` |
| `app/services/pad_engine.py` | PAD heuristics | `PadEngineService.java` |
| `app/services/challenge_selector.py` | Challenge selection | `ChallengeSelectorService.java` |
| `app/schemas/` | Pydantic DTOs | `dto/` package |
| `app/crud/` | DB access | `crud/` package |
| `alembic/` | Migrations | `V1__init_schema.sql` (Flyway) |

The Java version is a **complete superset** of the Python version — it adds the standalone engine library, device adapters, pluggable backends, v3 features (passive re-evaluation during active, real-time challenge progress, session timeout, combined scoring), and the evaluation harness.

---

## 14. Test Suite

**88 test classes + 7 support files (95 files), 751 tests, all green.** The
cross-backend contract test runs in its own CI job (it needs both runtimes), so
it is excluded from the unit matrix rather than skipping there.

The table is the decision-and-API core, not the full list: the rest is
per-component (audit chain and key rotation, threshold calibration, rate
limiting, the MediaPipe/ONNX/TFLite backends, the Android orchestrator and model
store, the client adapters and overlay) plus the PostgreSQL schema gate and the
end-to-end `LivenessPipelineIntegrationTest`.

| Test | What It Validates |
|------|-------------------|
| `EngineFlowTest` | Full passive→pass workflow |
| `EngineEscalationTest` | Passive→active escalation, multi-challenge flow, retry exhaustion, challenge timeout |
| `PadBlockingTest` | PAD attack detection blocks session immediately |
| `ErrorConditionsTest` | No face, multiple faces, bad quality, invalid state |
| `LivenessDecisionLogicTest` | Median calculation, threshold decisions |
| `ChallengeEvaluatorsTest` | All 9 challenge types (batch + frame-by-frame) |
| `ChallengeSelectorTest` | No back-to-back repeats, shuffled bag, parameters |
| `AttackScenarioHarnessTest` | 240 presentations, APCER/BPCER/ACER computation |
| `ConfigOverrideTest` | Per-workflow policy merging |
| `EntityPersistenceTest` | JPA entities + JSON attribute converters round-trip on H2 |
| `ConfigServiceTest` | DB ↔ engine policy/enum conversion, 60s sentinel resolution, round-trips |
| `DecisionEngineServiceTest` | Challenge keep-open window, retry accounting, immediate PAD rejection |
| `LivenessEngineServiceTest` | Turn/gaze polarity, width-scaled thresholds, face-dropout tolerance |
| `ImageUtilsCascadeTest` | The vendored Haar cascades are present and loadable |
| `SessionsControllerTest` | Session CRUD endpoints |
| `FramesControllerTest` | Frame submission + decision flow |
| `ChallengesControllerTest` | Challenge validation endpoint, `challenge_id` validation, retry semantics |
| `ConfigControllerTest` | Config read/update |
| `MetricsControllerTest` | Metrics aggregation |
| `AuditControllerTest` | Audit trail retrieval |
| `HealthControllerTest` | Health check incl. the `engine` flag |
| `GlobalExceptionHandlerTest` | Error response formatting |
| `DeviceAdapterTest` | All device adapter components |

Supporting infrastructure: `MutableClock` (deterministic time), `RecordingAuditLogger`
(captures audit events), `TestConfig` (`@TestConfiguration` with mocked repositories).

---

## 15. Docker Deployment

### `docker-compose.yml`
```yaml
services:
  postgres:        # PostgreSQL 16-alpine on port 5432
  api:             # Java backend on port 8000, depends on postgres
```

### `Dockerfile` (Java)
```dockerfile
FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache libstdc++
COPY target/pad-liveness-backend-*.jar app.jar
EXPOSE 8000
CMD ["java", "-jar", "app.jar"]
```

### Running
```bash
cp .env.example .env    # Set POSTGRES_USER, POSTGRES_PASSWORD, POSTGRES_DB
docker compose up --build
# API: http://localhost:8000
# Swagger UI: http://localhost:8000/swagger-ui/index.html
```

### Running locally without Docker (cross-platform)

```bash
./start.sh          # macOS / Linux
start.bat           # Windows
```

The launchers build the jar with the bundled Maven wrapper (`./mvnw`, so no local
Maven install is needed) and run it on the **`dev` profile** (in-memory H2, Flyway
disabled, schema generated by Hibernate) with the browser console opened at
`http://localhost:8000`. Options: `--no-build`, `--rebuild`, `--test`, `--port N`,
`--profile NAME`, `--no-browser`, `--help`. `start.bat` also takes `--doctor`, which reports the JDK, port and jar and
the OpenCV engine if it can (the live /health endpoint when a service is already
on the port, otherwise by checking the jar for the Windows-native entries).
It exits without building or starting anything (exit 1 when something would block
a start).

Both launchers stop cleanly on Ctrl+C, and both refuse to start if the port is
already in use.

---

## 16. Key Design Decisions

1. **Client-side engine (not delegated to MDS)** — liveness/PAD runs inside the Registration Client. The biometric device vendor's MDS is treated as a dumb frame source.

2. **Zero SBI/MDS protocol changes** — no existing device DTOs are modified. Every certified L0/L1 device works unmodified.

3. **No mandatory cloud call** — all inference is on-device using bundled/local models.

4. **One engine, two platforms** — the same Java engine JAR is consumed by both Desktop (Spring DI) and Android (Pigeon bridge). Identical decision logic guarantees consistent behavior.

5. **Pluggable backend SPI** — swap `MockLivenessBackend` for a real model or vendor SDK without touching engine, UI, or config code.

6. **PAD is terminal** — a detected presentation attack immediately fails the session. No retry, distinct audit event. This is a security requirement.

7. **Median, not mean** for temporal scoring — rejects outlier frames (motion blur, momentary occlusion) without a second neural net.

8. **ISO/IEC 30107 taxonomy-aligned** — attack types map to the standard's species. Structured audit telemetry enables APCER/BPCER computation. The engine is certification-ready, not self-certifying.

9. **User messages are generic** — never reveal whether the system detected a screen replay, video replay, etc. This prevents attackers from learning what the system looks for.

10. **Per-workflow policy overrides** — resident capture, operator auth, and supervisor auth can each have different thresholds, challenge counts, and retry policies.

11. **Two configuration sources, one of which wins at runtime** — `application.yml`
    (`mosip.liveness.*`, bound by `AppConfig` into the engine's `LivenessConfig`) versus the
    `config_policies` table (read by `ConfigService` on every request). Sessions served by
    the HTTP API use the **database** path; when no row exists for a workflow the
    `ConfigService` fallback applies — and the fallback is now pinned to the
    `LivenessConfig` constants rather than ad-hoc literals. The passive threshold is
    reconciled: `V2__unify_passive_threshold.sql` sets the column default and existing
    rows to `0.80`, matching `application.yml`, `LivenessConfig` and the fallback. The
    remaining defaults still disagree (`min_challenge_count 1 / max_retry_count 3` in the
    DB vs `2 / 2` in `LivenessConfig`); reconcile those before relying on either.

12. **Timestamps are normalized, and the dialect is not pinned** — `spring.jpa.properties.hibernate`
    deliberately does *not* set a dialect, so Hibernate derives it from the actual JDBC
    connection. Pinning `PostgreSQLDialect` while the `dev` profile ran H2 made every
    persisted `OffsetDateTime` read back shifted by the JVM's offset (a row written at
    20:26:54Z read as 14:56:54Z on a +05:30 machine), which silently corrupted audit times
    and challenge-window arithmetic.

---

## 17. File Inventory

### Java Source (156 files, ~19,400 LOC)

**Entry Point (1)**
- `PadLivenessApplication.java`

**API Layer — Controllers (9)**
- `SessionsController.java` — POST/GET sessions, POST close
- `FramesController.java` — POST frames
- `ChallengesController.java` — POST validate
- `ConfigController.java` — GET/PUT config
- `CalibrationController.java` — GET threshold-sweep (BPCER/APCER table + recommendation)
- `MetricsController.java` — GET metrics
- `AuditController.java` — GET audit trail
- `HealthController.java` — GET health
- `GlobalExceptionHandler.java` — @RestControllerAdvice for all error types

**DTOs (12)**
- `SessionCreateRequest`, `SessionResponse`, `SessionSummary`
- `FrameSubmitRequest`, `FrameProcessResult`
- `ChallengeValidateRequest`, `ChallengeValidationResult`, `ChallengeResponse`
- `ConfigPolicyUpdate`, `ConfigPolicyResponse`
- `OperationalMetrics`, `AuditLogEntry`

**Services (8)**
- `DecisionEngineService` — orchestrates passive→active→accept/reject (challenge lock, median-window decision)
- `LivenessEngineService` — face observation, passive scoring (heuristic), active challenge validation (OpenCV-based)
- `PassiveScoringService` — scorer façade: MiniFASNet ONNX model or heuristic fallback (`mosip.liveness.backend`)
- `ThresholdCalibrationService` — assembles bona-fide windows from `frame_events` + proxy attack corpus, runs the sweep
- `PadEngineService` — PAD via FFT + texture + brightness heuristics
- `ChallengeSelectorService` — dynamic challenge selection with SecureRandom
- `ConfigService` — bridges DB config with engine EffectivePolicy
- `ImageUtils` — frame decode (base64→Mat), face/eye detection, sharpness/brightness scoring

**CRUD Repositories (5)**
- `LivenessSessionRepository`, `FrameEventRepository`, `ChallengeRepository`, `AuditLogRepository`, `ConfigPolicyRepository`

**Core Domain Types (13)**
- `Frame`, `FaceSignals`, `Challenge`, `ChallengeType`, `ChallengeProgress`
- `PadVerdict`, `PadAttackType`
- `CombinedLivenessScore`, `ActiveFrameResult`
- `WorkflowType`, `RepeatedFailureAction`
- `LivenessException`, `LivenessErrorCode`

**Engine (8)**
- `FaceLivenessEngine` — main engine: session lifecycle, frame processing, challenge management
- `LivenessPipeline` — interface exposed to host applications
- `LivenessSession` — mutable session state (single-thread)
- `LivenessDecisionLogic` — pure functions: median, threshold decision
- `FrameAssessment` — frame processing result
- `ValidationResult` — challenge validation result
- `SessionSummary` — close-session audit record
- `AssessmentStatus` — enum of frame assessment states

**Backends (5)**
- `LivenessBackend` — interface
- `MockLivenessBackend` — scriptable test double
- `OnnxMiniFasNetBackend` — real passive liveness + PAD via ONNX Runtime on the bundled `minifasnet_v2.onnx` (checksum-verified)
- `TfLiteMiniFasNetBackend` — real PAD via TFLite + OpenCV
- `MediaPipeFaceMeshBackend` — 468-point FaceMesh landmarks via TFLite + OpenCV

**Challenge (2)**
- `ChallengeEvaluators` — rule-based evaluators for all 9 challenge types (batch + frame-by-frame)
- `ChallengeSelector` — shuffled-bag selection with SecureRandom

**Config (3)**
- `LivenessConfig` — full config with builder
- `EffectivePolicy` — resolved policy record
- `LivenessPolicy` — per-workflow override (all fields nullable)

**Audit (6)**
- `AuditEvent`, `AuditEventType`, `AuditLogger`, `StructuredAuditLogger`
- `MetricsCollector`, `MetricsSnapshot`

**Device Adapters (10)**
- `DeviceAdapter` — interface
- `SbiStreamDeviceAdapter` — L1 STREAM consumer
- `BurstRCaptureDeviceAdapter` — L0 burst capture
- `MockL0L1Device` — simulated device + HTTP server
- `FrameListener` — callback interface
- `CaptureSource` — functional interface
- `DeviceCapabilities` — record
- `MjpegDecoder` — incremental JPEG extractor
- `PixelFormats` — buffer conversion utilities
- `SyntheticScene` — deterministic scene renderer

**Evaluation (6)**
- `AttackScenarioHarness` — automated attack-scenario test runner (accepts an injected `LivenessBackend` factory)
- `ThresholdSweep` — BPCER/APCER/ACER table + recommended operating point
- `ProxyPresentationCorpus` — labelled simulated print/screen degradations (proxy only)
- `PadMetrics` — APCER/BPCER/ACER/FAR/FRR computation
- `ScenarioReport` — aggregated evaluation report
- `PresentationLabel` — BONA_FIDE/PRINTED_PHOTO/SCREEN_REPLAY/VIDEO_REPLAY

**App Config (1)**
- `AppConfig` — Spring @Configuration: bean wiring, OpenCV init (catches `Throwable`,
  including `LinkageError`, and degrades instead of aborting startup), CORS

**Client (5)** — integration surface for host applications
- `LivenessClient` — platform-neutral client interface + result records
- `LivenessHttpClient` — HTTP client for this service's REST API, including session
  ownership (`X-Subject-Ref`) for the sessions it creates
- `LivenessClientException` — client-side error type
- `DesktopLivenessAdapter` — Desktop (JavaFX/Java) adapter
- `AndroidLivenessAdapter` — Android (Flutter / Pigeon bridge) adapter

**Models (13)** — API/DB layer, kept deliberately separate from `core/`
- `entity/` — `LivenessSession`, `FrameEvent`, `ChallengeEntity`, `AuditLog`, `ConfigPolicy`
- `enums/` — `WorkflowType`, `SessionStatus`, `LivenessStage`, `ChallengeStatus`,
  `ChallengeType`, `FailurePolicy`
- `converter/` — `StringListJsonConverter`, `StringObjectMapJsonConverter`
  (JSON text ↔ Java collections, so H2 and PostgreSQL behave identically)

### Java Tests (95 files, 88 test classes, 751 tests)
- Engine, challenge, config, API (one class per controller plus the fail-closed
  and route-inventory gates), device, evaluation, model, service and audit tests
- Support files: `TestConfig`, `MutableClock`, `RecordingAuditLogger`,
  `AuthTestFixture`, `RawHttpSupport`, `HeadlessFx`, `AndroidOrchestratorSupport`

### Python Reference Backend (50 files, ~3,390 LOC)
- FastAPI app with identical API contract
- SQLAlchemy + Alembic for DB
- Same decision flow, pluggable engines, audit logging

### SQL (12 files, ~470 lines)
- `V1`–`V13` (one number in the range is unused) — schema and seed data, unified passive threshold, the
  15 s challenge window, the frozen session policy snapshot, the audit table and
  chain immutability triggers, config-change auditing, workflow typing,
  the manual-challenge flag, `auth_users`/`auth_tokens` (V12) and the session
  anti-replay nonce (V13)

### Documentation
- `docs/PRD.md` — product requirements + full system reference: goals, repository contents,
  decision flow, API, data model, configuration, security, evaluation, tests, and known gaps
- `docs/design.md`, `docs/status-report.md`, `docs/backend-swapping.md`,
  `docs/configuration.md`, `docs/client-integration-guide.md`
- `README.md` (repo root) — quick start, cross-platform launchers, architecture summary

---

## 18. How to Extend This Codebase

### Add a New Backend
1. Implement `LivenessBackend` interface
2. Pass it to `FaceLivenessEngine` constructor
3. Done — no engine/UI/config changes needed

### Add a New Challenge Type
1. Add the value to **both** `core.ChallengeType` and `models.enums.ChallengeType`
   (they are separate families; `models` is the API/DB-facing one)
2. Add it to `ConfigService.toCoreChallenge` **and** `ConfigService.toDbChallenge`, or the
   DB/engine mapping will silently fall back to `BLINK` / collapse the value
3. Add an evaluator in `ChallengeEvaluators.evaluate()` and `evaluateFrame()` (engine path)
4. Add a case to `LivenessEngineService.validateActive()` (HTTP path)
5. Add parameter generation in `ChallengeSelector.parametersFor()` if the type is parameterised

### Add a New Workflow
1. Add the value to `WorkflowType` (core + `models.enums`) and to `ConfigService`'s
   `toCoreWorkflow` / `toDbWorkflow` switches
2. Add its default operating point to `WorkflowPolicyDefaults.forWorkflow` — the `V4`
   seed, the DB-miss fallback and lazily-created rows all read it
3. Add a `config_policies` row — either as a migration `INSERT` or by writing to
   `PUT /api/v1/config/{workflowType}` (the controller creates the row on first use)

### Integrate with Real Device
1. Implement `DeviceAdapter` for the vendor SDK
2. Or use `SbiStreamDeviceAdapter` / `BurstRCaptureDeviceAdapter` for SBI-compliant devices
3. Feed frames into `FaceLivenessEngine.pushFrame()`

---

*Last updated: 2026-10-17*
*Branch: main*
*Artifact: `io.mosip.liveness:pad-liveness-backend:1.0.0-SNAPSHOT`*
*Verified against: 190 main source files / 95 test files / 751 passing tests*
