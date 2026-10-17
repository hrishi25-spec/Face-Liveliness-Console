# Face Liveness & PAD Service (FastAPI + PostgreSQL)

Backend service implementing the Face Liveness Detection and Presentation
Attack Detection (PAD) requirements for the MOSIP Registration Client
(Desktop and Android), covering resident registration, operator
authentication, and supervisor authentication.

## Architecture

```
app/
  core/config.py         # env-driven settings
  db/                     # SQLAlchemy engine/session, Alembic base
  models/                 # ORM models: sessions, frame events, challenges,
                           # per-workflow config policy, audit log
  schemas/                 # Pydantic request/response contracts
  services/
    image_utils.py        # frame decode, face detect, quality scoring
    pad_engine.py          # BasePADEngine + pluggable implementation
    liveness_engine.py     # BaseLivenessEngine (passive score + active
                            # challenge validation) + pluggable implementation
    challenge_selector.py  # dynamic/unpredictable challenge selection
    decision_engine.py     # orchestrates passive -> active -> accept/reject
  crud/                    # DB access functions
  api/routes/               # HTTP endpoints
alembic/                   # migrations
```

### Pluggable liveness/PAD engines

`BaseLivenessEngine` and `BasePADEngine` (in `app/services/`) are the
contracts the rest of the system depends on. Ship-provided implementations
(`MockLivenessEngine`, `MockPADEngine`) use cheap OpenCV heuristics
(sharpness, frequency-domain analysis, motion/eye detection) as
**placeholders only** — replace them with a real, ISO/IEC 30107-3 evaluated
model or vendor SDK before production use. Nothing else in the codebase
needs to change: implement the base class and update `get_liveness_engine()`
/ `get_pad_engine()`.

### Decision flow (matches spec)

1. `POST /sessions` — start a session for a workflow (`resident` |
   `operator` | `supervisor`).
2. `POST /sessions/{id}/frames` — push a frame. Runs passive liveness + PAD.
   - PAD attack detected → immediate reject, session `FAILED`.
   - Score ≥ configured threshold → `proceed`, session `PASSED`.
   - Score < threshold → `escalate_to_active`, returns a dynamically
     selected `challenge` (blink / smile / turn / look direction).
3. `POST /sessions/{id}/challenges/validate` — submit the frame stream
   captured during the challenge. Returns `proceed`, `retry_challenge` (the
   next frame issues a new challenge), or — once `max_retry_count` is spent —
   the terminal action the workflow's `on_repeated_failure` policy selects
   (see *Repeated failure actions* below).
4. `POST /sessions/{id}/close` — explicit close + summary.
5. `GET /sessions/{id}/audit` — full structured audit trail.
6. `GET/PUT /config/{workflow_type}` — read/update per-workflow policy
   (threshold, challenge types, timeout, retry count, failure policy) —
   no redeploy required. The **PUT requires the admin key** (see
   [Security](#security)); the GET stays open.
7. `GET /metrics` — anonymized operational metrics (pass rate, escalation
   rate, retry rate, PAD rejection rate, etc.).

### Repeated failure actions

A challenge that is missed consumes one retry. When the budget (`max_retry_count`)
is spent the session is closed and the terminal action comes from the workflow's
`on_repeated_failure` policy. The vocabulary is the one the Spring service uses
(`RepeatedFailureAction`, tabled in `docs/configuration.md`), so the same client
can read either backend:

| `on_repeated_failure` | Session | `action` | `failure_reason` | `may_retry_session` |
|-----------------------|---------|----------|------------------|---------------------|
| `LOCK` | `failed` | `locked` | `max_retries_exceeded:locked_out` | `false` |
| `ESCALATE` | `failed` | `escalate_to_operator` | `max_retries_exceeded:escalation_required` | `false` |
| `ALLOW_RETRY` | `failed` | `failed` | `max_retries_exceeded` | `true` |

The first column is the policy the `PUT` accepts (`FailurePolicy`, upper snake
case in both backends); the `action` and `failure_reason` columns are the
lower-case, user-facing half of the same table. `SessionStatus` is lower case
and `ChallengeStatus` is upper snake case — each service reports its own enum
casing, and a client reads them as written here.

All three are terminal and none issues another challenge — extra attempts belong
in `max_retry_count`, not in the failure action. `allow_retry` is the only mode
that lets the client open a *fresh* session, and `may_retry_session` is present
only on that terminal verdict. Every reason
keeps the `max_retries_exceeded` prefix, and the messages are the generic,
user-facing strings — they name no detection method.

All decisions are logged to the `audit_logs` table (session created, each
frame's verdict is in `frame_events`, challenges issued/passed/failed,
PAD rejections, final outcome) without exposing model internals to the
end-user response (per the spec's UI/UX guidance — generic failure
messages only).

## Running locally

### 1. With Docker (recommended)

```bash
cp .env.example .env      # edit credentials as needed
docker compose up --build
```

API available at `http://localhost:8000`, docs at `http://localhost:8000/docs`.

### 2. Without Docker

```bash
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt

# start a local Postgres however you prefer, then:
cp .env.example .env      # point POSTGRES_HOST etc. at your instance

# apply the schema migrations (the baseline revision ships with the repo)
alembic upgrade head

# optional dev shortcut: create tables directly and seed default policies
# python -m app.db.init_db

uvicorn app.main:app --reload
```

Alembic is the source of truth for the schema. A database that was bootstrapped
with `app.db.init_db` has the tables but no `alembic_version` row, so adopt it
with `alembic stamp head` before running further migrations:

```bash
alembic stamp head    # only for databases created by app.db.init_db
```

## Running tests

The suite has five parts:

- **API behaviour** (`tests/test_liveness_api.py`) uses an isolated in-memory
  SQLite database and stubs the model engines, so it needs neither PostgreSQL
  nor camera hardware.
- **Liveness/PAD heuristics** (`tests/test_opencv_heuristics.py`) drives the real
  OpenCV engines over procedurally generated image fixtures — no real faces, no
  downloads — and asserts branch behaviour and run-to-run determinism, not model
  quality.
- **PostgreSQL integration** (`tests/test_postgres_migrations.py`,
  `tests/test_postgres_persistence.py`) starts a throwaway `postgres:16-alpine`
  container, applies the migrations to it with the real `alembic` CLI, and then
  checks the migrated schema and session persistence against it. These need a
  Docker daemon; without one they skip. Each module gets its own database inside
  the one container, because the migration tests downgrade the schema.
- **Application imports** (`tests/test_app_imports.py`) imports the documented
  entry points in a fresh interpreter, which is what `uvicorn app.main:app` and
  `python -m app.db.init_db` do.
- **Cross-backend contract** (`tests/cross_backend_scenario.py` with
  `tests/test_cross_backend_scenario.py`) drives one retry-exhaustion scenario
  through the real API and prints the terminal verdict as JSON. The Spring
  project's `CrossBackendContractTest` runs this same script and compares the two
  services' answers, so it is the Python half of a contract check that no single
  test suite here could make. It needs no PostgreSQL, no server and no camera —
  `TestClient` with the same in-memory SQLite and stub engines the API tests use.

Install the pinned test dependencies and run them from this directory:

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt
python -m pytest -q
```

Set `PAD_LIVENESS_REQUIRE_POSTGRES=1` to make an unavailable Docker daemon fail
the run instead of skipping the PostgreSQL tests. CI sets it, so those tests
cannot disappear silently.

The heuristic thresholds are placeholder calibrations measured on those
synthetic fixtures (see `app/services/pad_engine.py`); recalibrate them against
real capture hardware before reading anything into a verdict.

## Example flow (curl)

```bash
# 1. Create a session
SESSION_ID=$(curl -s -X POST localhost:8000/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"workflow_type":"resident","device_id":"L1-CAM-01"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")

# 2. Submit a frame (base64 JPEG)
curl -s -X POST localhost:8000/api/v1/sessions/$SESSION_ID/frames \
  -H "Content-Type: application/json" \
  -d "{\"frame_base64\":\"$(base64 -w0 face.jpg)\"}"

# If action == "escalate_to_active", prompt the user with `message`,
# capture a short frame burst, then:
curl -s -X POST localhost:8000/api/v1/sessions/$SESSION_ID/challenges/validate \
  -H "Content-Type: application/json" \
  -d "{\"challenge_id\":\"<from previous response>\",\"frames_base64\":[\"<f1>\",\"<f2>\",\"<f3>\"]}"

# 3. Check the audit trail
curl -s localhost:8000/api/v1/sessions/$SESSION_ID/audit
```

## Security

Hardening built into the service (no extra dependencies):

- **Admin key on policy updates** — `PUT /api/v1/config/{workflow_type}`
  requires the `X-Admin-API-Key` header matching `MOSIP_ADMIN_API_KEY`. The check
  is **fail-closed**: when the key is not configured the update is refused (403),
  so an unauthenticated caller can never lower `passive_threshold` or switch
  liveness off. Reads stay open, because a client has to be able to read the
  policy it is judged against. The comparison is constant-time
  (`hmac.compare_digest`), and neither refusal mentions the header, the key, or
  anything derived from one — a wrong key and an absent one are the same answer.
  ```bash
  export MOSIP_ADMIN_API_KEY=$(openssl rand -hex 32)

  curl -X PUT localhost:8000/api/v1/config/resident \
    -H "Content-Type: application/json" \
    -H "X-Admin-API-Key: $MOSIP_ADMIN_API_KEY" \
    -d '{"passive_threshold": 0.85}'
  ```
  The Spring service's identical route is stricter still: it requires a
  *supervisor session* rather than a shared key, so a policy write there is
  attributable to an account. This backend keeps the key as its simpler guard,
  and what the two must agree on is the shared contract — the fields, the
  repeated-failure vocabulary and the terminal verdicts — which
  `tests/cross_backend_scenario.py` and the Spring project's
  `CrossBackendContractTest` compare on every CI run.
- **CORS is an allow-list** (`CORS_ORIGINS`, default the local Registration
  Client origins) with `allow_credentials=False`; the wildcard-plus-credentials
  combination is deliberately absent.
- **Security headers on every response** (`harden_requests`):
  `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer`, `Cross-Origin-Opener-Policy`, a
  `Permissions-Policy` that allows the camera to this origin only, and a strict
  `Content-Security-Policy`. FastAPI's own `/docs`, `/redoc` and `/openapi.json`
  are exempt from CSP only, because their webjar boots inline.
- **Request size cap** — POST/PUT bodies over `MAX_REQUEST_BODY_BYTES`
  (default 24 MB) are refused with 413 before they are parsed, and frames are
  decoded with an isolated `InvalidFrameError` handler that answers 422 rather
  than leaking a decoder message or a 500.
- **Error responses** carry no stack trace and no model internals: liveness and
  PAD failures stay the generic, user-facing strings.

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
  evaluated model, and use `app/services/*_engine.py` as the seam for
  reporting APCER/BPCER/ACER during evaluation.
