# Face Liveness & PAD Service (Spring Boot)

Backend service implementing the Face Liveness Detection and Presentation
Attack Detection (PAD) requirements for the MOSIP Registration Client
(Desktop and Android), covering resident registration, operator
authentication, and supervisor authentication.

## Architecture

```
src/main/java/io/mosip/liveness/
  app/
    PadLivenessApplication.java     # Spring Boot entry point
    config/AppConfig.java           # Bean config, CORS, OpenCV init
  api/                              # REST controllers (HTTP endpoints)
    SessionsController.java         # POST /sessions, GET /sessions/{id}, POST /close
    FramesController.java           # POST /sessions/{id}/frames
    ChallengesController.java       # POST /sessions/{id}/challenges/validate
    ConfigController.java           # GET/PUT /config/{workflow_type}
    MetricsController.java          # GET /metrics
    AuditController.java            # GET /sessions/{id}/audit
    HealthController.java           # GET /health
  dto/                              # Request/response schemas (JSON DTOs)
    SessionCreateRequest.java
    SessionResponse.java
    FrameSubmitRequest.java
    FrameProcessResult.java
    ChallengeValidateRequest.java
    ChallengeValidationResult.java
    ConfigPolicyUpdate.java
    ConfigPolicyResponse.java
    OperationalMetrics.java
    AuditLogEntry.java
  models/
    enums/                          # Database enums
      WorkflowType.java             # RESIDENT | OPERATOR | SUPERVISOR
      SessionStatus.java            # ACTIVE | PASSED | FAILED | EXPIRED
      LivenessStage.java            # PASSIVE | ACTIVE | COMPLETED
      ChallengeType.java            # BLINK | SMILE | TURN_LEFT | TURN_RIGHT
      ChallengeStatus.java          # ISSUED | PASSED | FAILED | TIMEOUT
      FailurePolicy.java            # LOCK | ESCALATE | ALLOW_RETRY
    entity/                         # JPA ORM models
      LivenessSession.java
      FrameEvent.java
      ChallengeEntity.java
      AuditLog.java
      ConfigPolicy.java
  crud/                             # JPA repositories (DB access)
    LivenessSessionRepository.java
    FrameEventRepository.java
    ChallengeRepository.java
    AuditLogRepository.java
    ConfigPolicyRepository.java
  services/                         # Business logic
    DecisionEngineService.java      # Orchestrates passive -> active -> accept/reject
    LivenessEngineService.java      # Passive scoring + active challenge validation
    PadEngineService.java           # Presentation attack detection
    ChallengeSelectorService.java   # Dynamic/unpredictable challenge selection
    ImageUtils.java                 # Frame decode, face detect, quality scoring
  core/                             # (existing) Engine types, config, backends
    ...
  engine/                           # (existing) FaceLivenessEngine, LivenessSession
    ...
  backend/                          # (existing) LivenessBackend interface + impls
    ...
  audit/                            # (existing) StructuredAuditLogger, MetricsCollector
    ...
  device/                           # (existing) Device adapter layer
    ...
src/main/resources/
  application.yml                   # Spring Boot config (DB, liveness params)
  application-dev.yml               # Dev profile (H2 in-memory DB)
  db/migration/V1__init_schema.sql  # Flyway migration (applied at startup)
```

### Pluggable liveness/PAD engines

`io.mosip.liveness.backend.LivenessBackend` is the SPI everything above depends
on — `id()`, `initialize()`, `analyzeFrame()`, `scorePassiveLiveness()`,
`assessPad()` and `shutdown()`. Four implementations ship here (`mock`,
`onnx-minifasnet-v2`, `mediapipe-facemesh`, `tflite-minifasnet`), and the
`mosip.liveness.backend` property selects one for **both** scoring paths: the
HTTP path (`PassiveScoringService`) and the SPI bean
(`AppConfig.livenessBackend`). `LivenessBackendSelection` owns that vocabulary
and parses it once for both paths: `auto` and `heuristic` preserve the earlier
behaviour (bundled ONNX model on the HTTP path, scripted mock on the SPI path);
an explicitly named backend is honoured strictly — no silent fallback if it
cannot load — and an unknown value fails at startup instead of quietly picking
a different scorer.

The `mock` backend is a scripted placeholder and the model-backed backends are
reference integrations: replace them with a real, ISO/IEC 30107-3 evaluated
model or vendor SDK before production use, by implementing `LivenessBackend`
and naming it in `mosip.liveness.backend`. Per-backend runtime dependencies,
determinism and observed behaviour are measured in
[docs/backend-interoperability-report.md](docs/backend-interoperability-report.md).

### Decision flow (matches spec)

1. `POST /api/v1/sessions` — start a session for a workflow (`resident` |
   `operator` | `supervisor`).
2. `POST /api/v1/sessions/{id}/frames` — push a frame. Runs passive liveness + PAD.
   - PAD attack detected → immediate reject, session `FAILED`.
   - Score ≥ configured threshold → `proceed`, session `PASSED`.
   - Score < threshold → `escalate_to_active`, returns a dynamically
     selected `challenge` (blink / smile / turn / look direction).
3. `POST /api/v1/sessions/{id}/challenges/validate` — submit the frame stream
   captured during the challenge. Returns `proceed` / `retry_challenge`
   (new challenge issued) / `reject` (after `max_retry_count` exceeded).
   A challenge stays open for `challengeTimeoutMs`, raised to a **15 s floor**
   (`mosip.liveness.min-challenge-window-ms`, hard minimum 1 s) so a shortened
   policy row cannot fail someone who just needed a moment; polls return
   `continue` until the window truly elapses, and only then is a retry spent.
4. `POST /api/v1/sessions/{id}/close` — explicit close + summary.
5. `GET /api/v1/sessions/{id}/audit` — full structured audit trail.
6. `GET/PUT /api/v1/config/{workflow_type}` — read/update per-workflow policy
   (threshold, challenge types, timeout, retry count, failure policy) —
   no redeploy required. **PUT requires the admin API key** (see
   [Security](#security)); reads are open. Every successful `PUT` is recorded in
   the audit trail: `GET /api/v1/config/audit` returns who changed which
   workflow, which fields moved, and their old → new values.
7. `GET /api/v1/metrics` — anonymized operational metrics (pass rate, escalation
   rate, retry rate, PAD rejection rate, etc.) plus rate-limiter tallies:
   `rateLimitAllowedRequests`, `rateLimitedRequests` and the per-rule split
   `rateLimitedSessionCreate` / `rateLimitedFrames` /
   `rateLimitedOperatorKey`, with `rateLimitRejectionRate`. Those come from
   in-process counters, so they start at zero on restart while the session
   metrics come from the database.

All decisions are logged to the `audit_logs` table (session created, each
frame's verdict is in `frame_events`, challenges issued/passed/failed,
PAD rejections, final outcome) without exposing model internals to the
end-user response (per the spec's UI/UX guidance — generic failure
messages only).

## Running locally

### 1. One command — `start.sh` / `start.bat`

Builds if needed, starts the service, waits until it reports healthy, then opens
the browser console. Runs on **macOS, Linux and Windows** and needs only a
**JDK 25+** — Maven is not required, because the bundled Maven wrapper fetches it.
The service artifact stays Java 17 bytecode-compatible for the MOSIP Android host.

```bash
./start.sh                 # macOS / Linux / Git Bash
```

```bat
start.bat                  rem Windows
```

| Option | Effect |
|---|---|
| `--no-build` | skip Maven and relaunch the existing jar (fast restart) |
| `--rebuild` | clean build plus the full test suite |
| `--test` | run tests during the build |
| `--port N` | serve on port N (default 8000) |
| `--profile NAME` | Spring profile: `dev` (H2, default) or `default` (PostgreSQL) |
| `--lowmem` | tune for the 2-core / 2 GB target: capped heap, Serial GC, class-data-sharing archive, `lowmem` Spring profile (see [configuration.md](docs/configuration.md)) |
| `--no-browser` | do not open a browser |
| `--doctor` | `start.bat` only: report what would block a start — JDK, port and jar, plus the engine: the live `/health` of a service already on the port, or the jar's Windows-native entries when nothing is running. Never builds, starts or stops anything. Exits 1 when something would block a start. |
| `--help` | show the options and exit |

A successful launch looks like:

```
✓ JDK 25 → /opt/homebrew/Cellar/openjdk@25/.../bin/java
✓ Build complete
✓ Port 8000 is free
✓ Service is up (started in 5s)
  Console      http://localhost:8000/
  API docs     http://localhost:8000/swagger-ui/index.html
  Health       http://localhost:8000/health
  Engine       available (OpenCV native library loaded)
```

Press `Ctrl+C` (or any key in the Windows window) to stop the service cleanly.

On **Windows** the launcher to use is `start.bat`; the older minimal `run.bat` is
kept for compatibility only. It needs a stock JDK 25+, and the arrangement CI
tests is an install under `C:\Program Files\Eclipse Adoptium` — what
`winget install EclipseAdoptium.Temurin.25.JDK` gives you — because that space in
the path is what used to break `start.bat`'s version probe. See
[Tests and gates](#tests-and-gates) for the gate and how to run it locally.

`http://localhost:8000/` serves a **zero-dependency test console** that drives the
real API — no build step and no CDN, so it works fully offline. It creates a
session, captures webcam frames, runs passive liveness, performs the active
challenge, and shows the decision log, audit trail and operational metrics side
by side. A passive score above the threshold passes the session and skips the
challenge by design; **Run active liveness** then becomes available and starts
one extra challenge on request (`POST /api/v1/sessions/{id}/active-liveness`). The
extra check is marked `manual`: its result is recorded on the challenge row and
in the audit trail, and it **never changes the session verdict** — a missed
action leaves the session PASSED. The console also edits the **config policy**
per workflow: loads are open `GET`s,
saving issues a `PUT` with the `X-Admin-API-Key` header — paste
`MOSIP_ADMIN_API_KEY` into the admin-key field first (kept in the tab's
sessionStorage only, never logged). The key never sits in the page: the field is
cleared on blur and after every save, and a hint shows only a **partial mask**
(`••••••••••••-9f3`) so you can tell which key is loaded without exposing it to a
screen share or screenshot — **Forget key** drops it from the tab. The console
also shows the **Request budget** panel: live meters for both limiter budgets
(how many submissions remain and when the window resets, counting down each
second), turning amber near the limit and red on a 429 with its retry time — so
a tester can watch the limiter work instead of only seeing it fail. It also shows
the **policy change history** — who changed a workflow, which fields
moved, and their previous and new values — refreshed automatically after each
save. The policy form has a **dirty-form guard**: edits show an amber "unsaved
changes" marker, and switching workflow, pressing Load or closing the tab asks
before discarding them (the workflow select snaps back if you cancel), so a
tuned threshold can't vanish without a word.

It must be opened at `localhost`: browsers only expose the camera on a secure
origin, and `localhost` counts as one.

### 3. Without Docker — manual quick start (H2)

Requires only **JDK 25+**. No PostgreSQL, no Docker.

```bash
# Build the executable jar (also runs the full test suite)
./mvnw clean package

# Run with the dev profile: in-memory H2, schema created by Hibernate
java -jar target/pad-liveness-backend-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev
```

The default jar is ~209 MB because `org.openpnp:opencv` bundles natives for
eight platforms and `onnxruntime` bundles four (plus a 54 MB macOS `.dSYM`).
For linux x86_64 targets — CI runners and the Docker image — build the slim
variant, which keeps the Java bindings and the one native it actually loads:

```bash
./mvnw clean package -DskipTests -Pslim
# -> target/pad-liveness-backend-1.0.0-SNAPSHOT-slim.jar  (~92 MB)

java -jar target/pad-liveness-backend-1.0.0-SNAPSHOT-slim.jar --spring.profiles.active=dev
```

`clean` matters: `-Pslim` unpacks the filtered natives into `target/classes`, so
a slim build followed by a plain build would leave a linux-only `.so` ahead of
the full library on the classpath. The slim jar runs the real pipeline on linux
x86_64; on any other OS the natives are simply absent and the app degrades to
`engine: "unavailable"` rather than failing to start.

To reproduce what CI does before publishing, boot the jar and check it serves:

```bash
./scripts/smoke-jar.sh                 # the full jar (default)
SMOKE_JAR=slim ./scripts/smoke-jar.sh  # the slim jar — CI boots both
# {"status":"ok","service":"MOSIP Face Liveness & PAD Service","engine":"available"}
# packaged jar booted and reports the engine available

./scripts/check-jar-size.sh 'target/pad-liveness-backend-*-slim.jar' 110 'slim jar'
# slim jar: 89 MiB / 110 MiB budget
```

Both jars get the same `engine: available` assertion, because both are supposed
to have a working engine on linux x86_64. That is what catches a dependency bump
which quietly breaks the slim profile's native filter: the jar still builds, still
boots, and then rejects every frame.

**Artifacts from CI.** Every run publishes two, on the default branch and on `v*`
tags: `pad-liveness-backend-jar` — the slim linux-x86_64 build, which is the
primary download and the one the Docker image is built from — and
`pad-liveness-backend-full-jar` for macOS/Windows consumers. The run summary
reports each jar's size, and a jar that outgrows its budget fails the build rather
than quietly doubling artifact storage.

Then check it is up:

```bash
curl localhost:8000/health
# {"status":"ok","service":"MOSIP Face Liveness & PAD Service","engine":"available"}
```

OpenAPI UI: `http://localhost:8000/swagger-ui/index.html`

### 4. Against PostgreSQL (default profile)

Set the `POSTGRES_*` variables (or create `.env` from `.env.example`) and run
**without** `--spring.profiles.active=dev`. Flyway applies
`db/migration/V1__init_schema.sql` on startup and Hibernate validates the schema
(`ddl-auto: validate`) rather than creating it:

```bash
POSTGRES_HOST=localhost POSTGRES_USER=mosip POSTGRES_PASSWORD=... POSTGRES_DB=pad_liveness \
  java -jar target/pad-liveness-backend-1.0.0-SNAPSHOT.jar
```

There is no Flyway Maven plugin configured; migrations are applied by the
application at startup, and `spring.flyway.baseline-on-migrate: true` is set.

### 5. Haar cascade data (bundled)

Face detection needs OpenCV's `haarcascade_frontalface_default.xml` and
`haarcascade_eye.xml`. OpenCV's Java bindings do not ship them, so they are
**vendored in `src/main/resources/`** and loaded from the classpath (with a
filesystem fallback). Both files retain their original Intel/OpenCV BSD-3-Clause
licence header.

If they are ever removed, the service does not fail — it silently reports
`faceDetected: false` for every frame and no session can pass, logging a single
clear warning on the first detection attempt. `ImageUtilsCascadeTest` fails the
build in that case rather than letting it regress.

A real face passes the pipeline end to end:

```
{"faceDetected": true, "faceQuality": 0.87, "livenessScore": 0.90,
 "padFlag": false, "action": "proceed", "message": "Liveness verified."}
```

### 6. With Docker (alternative)

```bash
# The image is built from the slim profile, so package it first:
./mvnw clean package -DskipTests -Pslim

cp .env.example .env      # edit credentials as needed
docker compose up --build
```

The image drops the natives for platforms a container can never run, which takes
it from ~620 MB to ~294 MB of layers. The base is Alpine (musl) and the OpenCV
native is glibc-linked, so frame processing stays unavailable inside the container
— the API, config/audit, rate limiting and the console all serve normally. Use
the slim **jar** on a glibc host when you need the engine.

To check the image path the same way CI does:

```bash
./scripts/smoke-docker.sh
# health: {"status":"ok",...,"engine":"unavailable"}
# image size: 293 MiB of layers (runs as 'mosip')
```

API available at `http://localhost:8000`, docs at `http://localhost:8000/swagger-ui.html`.

## Tests and gates

CI runs every gate below on each push and pull request, and each one is
runnable locally — that is the point of listing them. Two of them live outside
Maven because Maven cannot reach what they check:

- `node src/test/js/console-guards.test.mjs` drives the real `index.html` and
  `console.js` in a DOM and exercises the dirty marker, the per-field diff, the
  discard guard and the unload warnings. The console is plain browser JS with no
  build step, so a green Java suite says nothing about whether it is still
  wired.
- `./scripts/boot-median.sh` boots the packaged jar five times, each from a cold
  OpenCV native cache, and fails if the median time to a serving `/health`
  exceeds `BOOT_MEDIAN_MAX_SECONDS`. A new eager bean or an extra migration
  ships green everywhere else and shows up only as "deployments are slow now".

| Gate | Run it locally | CI job |
| --- | --- | --- |
| Java unit + integration suites | `./mvnw --batch-mode test` | *Unit tests*, *Integration tests* |
| PostgreSQL schema gate (entities vs Flyway migrations) | `./mvnw --batch-mode test -Dtest='PostgresSchemaTest'` | *PostgreSQL schema gate* |
| Cross-backend contract (one spent retry budget, both services) | `./mvnw --batch-mode test -Dtest='CrossBackendContractTest'` | *Cross-backend contract* |
| Console DOM guards | `node src/test/js/console-guards.test.mjs` | *Console DOM tests* |
| Project boundaries (guidance, instruction scope, documented gates) | `node scripts/check-project-boundaries.mjs` | *Project boundaries* |
| Secret scan (committed credentials, keys, real biometrics) | `./scripts/check-secrets.sh` | *Secret scan* |
| Packaged jar boots and serves | `./scripts/smoke-jar.sh` | *Package … jar* |
| Windows launcher boots the full jar (JDK under a path with a space) | `pwsh -NoProfile -File ./scripts/smoke-windows-launcher.ps1` | *Windows launcher (full jar)* |
| Jar size ceiling | `./scripts/check-jar-size.sh 'target/pad-liveness-backend-*-slim.jar' 110 'slim jar'` | *Package … jar* |
| Cold boot time (median of 5) | `./scripts/boot-median.sh` | *Cold boot time (median)* |
| Docker image boots, non-root, `/health` ok | `./scripts/smoke-docker.sh` | *Docker image* |
| Python backend suite | see [pad_liveness_backend/README.md](pad_liveness_backend/README.md) | *Python liveness backend tests* |
| Android client build gates | see [android_client/README.md](android_client/README.md) | *Android/Flutter client build* |

The *Cross-backend contract* gate needs both toolchains on the machine running
it: the Java test boots this service (H2, real OpenCV) and drives
[pad_liveness_backend](pad_liveness_backend/)'s scenario script over a
subprocess. Point `PAD_LIVENESS_PYTHON` at an interpreter with
`pad_liveness_backend/requirements-dev.txt` installed (the default is
`python3`); without one the test skips with the reason, and
`PAD_LIVENESS_REQUIRE_PYTHON=1` — which CI sets — turns that skip into a
failure instead.

The *Project boundaries* gate keeps this table honest: it fails if a command
documented here or in a project README is not run by a workflow, and equally if
a workflow runs a gate that no document mentions. Adding a gate means writing it
down and wiring it up in the same change.

## Example flow (curl)

```bash
# 1. Create a session
SESSION_ID=$(curl -s -X POST localhost:8000/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"workflowType":"RESIDENT","deviceId":"L1-CAM-01"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")

# 2. Submit a frame (base64 JPEG)
curl -s -X POST localhost:8000/api/v1/sessions/$SESSION_ID/frames \
  -H "Content-Type: application/json" \
  -d "{\"frameBase64\":\"$(base64 -w0 face.jpg)\"}"

# If action == "escalate_to_active", prompt the user with `message`,
# capture a short frame burst, then:
curl -s -X POST localhost:8000/api/v1/sessions/$SESSION_ID/challenges/validate \
  -H "Content-Type: application/json" \
  -d "{\"challengeId\":\"<from previous response>\",\"framesBase64\":[\"<f1>\",\"<f2>\",\"<f3>\"]}"

# 3. Check the audit trail
curl -s localhost:8000/api/v1/sessions/$SESSION_ID/audit
```

## Security

Hardening built into the service (no extra dependencies):

- **Admin key on policy updates** — `PUT /api/v1/config/{workflow}` requires
  the `X-Admin-API-Key` header matching `MOSIP_ADMIN_API_KEY`. The check is
  **fail-closed**: if the key is not configured, config updates are refused
  (403), so an unauthenticated caller can never lower `passiveThreshold` and
  defeat PAD. Reads stay open for clients.
  ```bash
  curl -X PUT localhost:8000/api/v1/config/RESIDENT \
    -H "Content-Type: application/json" \
    -H "X-Admin-API-Key: $MOSIP_ADMIN_API_KEY" \
    -d '{"passiveThreshold": 0.85}'
  ```
- **Operator key and the role probe** — `GET /api/v1/auth/role` reports which
  role a key proves, so the console can choose its surface from the service's
  answer instead of guessing: `X-Admin-API-Key` → `{"role":"SUPERVISOR"}`,
  `X-Operator-API-Key` matching `MOSIP_OPERATOR_API_KEY` → `{"role":"OPERATOR"}`,
  anything else → **401**. The two keys are compared in constant time, the
  comparison **fails closed** (a blank operator key means nobody is ever answered
  `OPERATOR`, and an empty header never matches one), and the response holds the
  role and nothing else — no key, not even a fingerprint of one. Key attempts on
  operator routes spend the existing per-IP rate-limit budget, so guessing is
  throttled rather than free. See
  [docs/configuration.md](docs/configuration.md#the-two-api-keys-and-the-roles-they-prove).
- **Policy edits are audited** — the key alone must not be enough to weaken
  liveness silently. Each successful `PUT` writes a `CONFIG_CHANGED` entry in
  the *same transaction* as the policy update: which workflow, which fields
  actually moved with old → new values, `CREATED` vs `UPDATED`, a truncated
  SHA-256 fingerprint of the key (never the key), and a **risk classification**
  — an edit that lowers `passiveThreshold` or switches liveness (or active
  liveness) off is flagged `HIGH`, with the weakening move named, so the
  console shows the alert at a glance. Rejected requests record
  nothing. Read it back with `GET /api/v1/config/audit?limit=50`, or in the
  console's **Policy change history** panel.
  ```bash
  curl -s "localhost:8000/api/v1/config/audit?limit=5"
  # one workflow only — filtered in the database, not in the browser
  curl -s "localhost:8000/api/v1/config/audit?workflowType=OPERATOR"
  ```
- **The audit trail is tamper-evident, and optionally tamper-proof** — each
  `CONFIG_CHANGED` row is chained (`prev_hash` → `entry_hash`), and PostgreSQL
  rejects `UPDATE` / `DELETE` on `audit_logs` with triggers, so an edited row
  invalidates its own hash and a deleted one leaves every successor pointing at
  a hash that no longer follows. `GET /api/v1/config/audit/verify` walks the
  whole chain and reports the first break, distinguishing an **edited** row
  from a **deleted** one.
  An unkeyed hash only stops attackers who do not rebuild the chain — anyone
  with database write access can read the stored hashes and recompute them. Set
  `MOSIP_AUDIT_HMAC_SECRET` (≥ 16 characters; shorter values fail at startup,
  since a guessable key is no key) to key the hash with **HMAC-SHA-256** over
  the same canonical form: the secret lives on the app server, not in the
  database, so a rebuilt chain fails verification instead of verifying
  perfectly. Left unset, the hash falls back to plain SHA-256 — tamper-detecting
  only — and `/audit/verify` reports `"hmac": false` so the weaker mode is
  visible rather than assumed. Keep the secret **stable**: without an explicitly
  configured previous key, a rotated or missing secret reports old entries as
  broken (fail loudly, never accept the downgrade). During a keyed rotation,
  `MOSIP_AUDIT_HMAC_PREVIOUS_SECRET` lets verification accept old hashes while
  new writes use the current key; `/audit/verify` and the console badge report
  whether that key is configured and whether history still needs it. Rows are
  immutable, so the previous key cannot be retired until history is archived or
  re-keyed by a trusted process (not provided here); see `docs/configuration.md`.
  ```bash
  curl -s localhost:8000/api/v1/config/audit/verify
  # {"eventType":"CONFIG_CHANGED","chainedEntries":5,"headHash":"…",
  #  "hmac":true,"rotationWindowOpen":false,"retiredKeyHashes":false,
  #  "intact":true}
  ```
- **Security headers on every response** (`SecurityHeadersFilter`):
  `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer`, `Cross-Origin-Opener-Policy`,
  `Permissions-Policy` (camera self-only), and a strict
  `Content-Security-Policy` (`script-src 'self'`, `style-src 'self'`,
  `frame-ancestors 'none'`) — the browser console now loads its script/style
  from `static/console.js` / `static/console.css` so no `unsafe-inline` is
  needed. Swagger UI is exempt from CSP only (its webjar boots inline).
- **Request size limits** — POST/PUT bodies over `MAX_REQUEST_BODY_BYTES`
  (default 24 MB) are rejected with 413 before reaching a controller; frame
  DTOs cap each frame (`@Size`) and the decode step enforces payload and
  dimension caps (decompression-bomb guard) and downscales oversized frames
  to ≤1280 px before analysis.
- **Rate limiting** (`RateLimitFilter`, in-memory fixed windows, no extra
  dependency) — `POST /api/v1/sessions` is capped per client IP (default
  30/min) and `POST .../frames` + `POST .../challenges/validate` share a
  per-session budget (default 60 per 10 s), so one client or one session
  cannot flood the service. Over-limit requests get `429` + `Retry-After` in
  the standard error shape, before any body parsing or DB work. GETs are never
  limited. **Budget headers**: every limited route also returns
  `X-RateLimit-Bucket` (`session-create` / `frames`), `X-RateLimit-Limit`,
  `X-RateLimit-Remaining` and `X-RateLimit-Reset` (seconds into the window) —
  on *allowed* requests too, so a client can pace itself instead of only
  discovering the limiter through a failure (they are in CORS
  `Access-Control-Expose-Headers` for a console on another dev port). Tune via
  `mosip.security.rate-limit.*` (or `SESSION_CREATE_LIMIT`, `FRAME_LIMIT`,
  `RATE_LIMIT_ENABLED`).
  **Behind a proxy or Docker**: the per-IP budget keys on the socket address by
  default, so every client behind NAT collapses onto one budget. Set
  `TRUSTED_PROXIES` to the proxy's IP or CIDR (e.g. `172.16.0.0/12,127.0.0.1`)
  and the limiter reads `X-Forwarded-For` (right-to-left, skipping trusted hops)
  then `X-Real-IP` — **only** from those peers. Forwarded headers from anyone
  else are ignored, so a client cannot mint extra budgets by sending them; never
  list a range that includes untrusted addresses. Frame budgets are keyed by
  session id and are unaffected.
- **CORS** is an allow-list of `localhost` origins via `allowedOriginPatterns`
  (wildcard ports actually match now), methods limited to GET/POST/PUT,
  credentials off.
- **Error responses** never include stack traces or exception messages
  (`server.error.include-*=never` plus the structured `GlobalExceptionHandler`).
- **Containers**: the image runs as a non-root user, the JVM is capped with
  `-XX:MaxRAMPercentage=50 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError`, and
  PostgreSQL is published on `127.0.0.1` only.

Set a strong key before exposing the service:

```bash
export MOSIP_ADMIN_API_KEY=$(openssl rand -hex 32)
# keys the audit chain's hashes so a database-write attacker cannot rebuild it;
# keep it stable across restarts. For keyed rotations, see docs/configuration.md
# — immutable history may still require the previous key for verification.
export MOSIP_AUDIT_HMAC_SECRET=$(openssl rand -hex 32)
```

## Notes on the spec's non-functional requirements

- **Offline support**: this service is designed to run as a local process
  on the Desktop/Android Registration Client host (or an embedded service
  reachable without WAN connectivity) so liveness/PAD enforcement never
  depends on network availability; only policy/model updates require
  connectivity, and should be synced through a separate secure update
  channel (out of scope for this scaffold).
- **Vendor independence**: device integration is expected to sit upstream
  of this service (Common Face Capture Device Interface / Device Adapter
  pattern on the Registration Client side) — this service only consumes
  decoded frames, so it works with any compliant L0/L1 device.
- **ISO/IEC 30107 alignment**: replace the mock engines with a properly
  evaluated model, and use the `io.mosip.liveness.eval` harness
  (`AttackScenarioHarness`, `PadMetrics`, `ThresholdSweep`) over a labeled
  corpus — including the per-PAI-species APCER breakdown in `ScenarioReport` —
  to report APCER/BPCER/ACER during evaluation, as
  [docs/backend-interoperability-report.md](docs/backend-interoperability-report.md)
  does for the shipped backends.
