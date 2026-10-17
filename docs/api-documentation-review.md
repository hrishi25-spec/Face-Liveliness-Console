# Review — `API_Documentation.json` vs. the service that actually exists

**Reviewed artifact:** `API_Documentation.json` (MOSIP Face Liveness & PAD Service — API Documentation, v1.0.0-SNAPSHOT)
**Reviewed against:** this repository, branch `development` @ `1ed5eaa`
**Date:** 2026-10-10
**Corrected edition:** the rewritten, verified documentation is [docs/api-documentation.json](api-documentation.json) (revision 2).
**Method:** (1) source reading of every file the document names as authoritative sources; (2) the service started from `target/pad-liveness-backend-1.0.0-SNAPSHOT.jar` with `--spring.profiles.active=dev --server.port=8011` and exercised with curl; (3) the running service's own machine-readable surface from `GET /v3/api-docs`. Every "verified" line below is a command that was actually run against that instance.

---

## Verdict

The document is **not a description of this codebase**. The session/challenge/frame *lifecycle* is described accurately at a high level, but:

* **3 documented endpoints do not exist** (§1),
* **an entire security and operations layer is invented** — admin API key, rate limiting, security headers, request-body cap, config audit chain, HMAC verification, threshold sweep (§2–§5),
* **several core decision behaviours are wrong** — frozen per-session policy, median sliding-window passive voting and cold start, two-frame PAD confirmation, the `continue` challenge action, the challenge time window, the `livenessEnabled` bypass, per-workflow policy defaults, metrics fields (§6),
* and **the document's own `known_gaps` and `authoritative_sources` sections are themselves inaccurate** (§8).

If this file is handed to a client, a judge, or an integrator, it will cause concrete breakage: code will call `/api/v1/eval/threshold-sweep`, expect `403` on `PUT /api/v1/config/*`, branch on `action: "continue"`, and trust a request-body cap that does not exist.

---

## 1. Endpoints documented but not implemented

Verified against the running service. The service publishes **10** paths (`GET /v3/api-docs`), all listed in §9.

| Documented endpoint | Actual behaviour (verified) | Evidence |
|---|---|---|
| `GET /api/v1/config/audit` | **500** `INTERNAL_ERROR` — the path matches `GET /api/v1/config/{workflowType}` and `"audit"` fails enum conversion, landing in the generic handler | `curl -o /dev/null -w '%{http_code}' .../config/audit` → `500` |
| `GET /api/v1/config/audit?limit=5&workflowType=SUPERVISOR` | **500** `INTERNAL_ERROR`, same cause | same |
| `GET /api/v1/config/audit/verify` | **404** — no such route | `→ 404` |
| `GET /api/v1/eval/threshold-sweep?targetBpcer=0.02` | **404** — no such route | `→ 404` |

There is no `ConfigAuditController`, no `EvalController`, no `ThresholdSweepResult`, and no `ThresholdSweep`/`threshold-sweep` string anywhere in the repository (all branches checked).

---

## 2. Authentication section — the admin API key does not exist

The document's flagship security claim is `PUT /api/v1/config/{workflowType}` protected by a fail-closed `X-Admin-API-Key`.

* **`X-Admin-API-Key` appears nowhere in the repository** (`grep -r "X-Admin"` → 0 hits, checked on `development`, `main`, `master`, `stagging` and the sibling clone).
* `ConfigController.setPolicy` ([ConfigController.java:49-52](src/main/java/io/mosip/liveness/api/ConfigController.java#L49-L52)) performs **no authentication at all** — it validates, writes, and returns.
* **Verified:** `PUT /api/v1/config/RESIDENT` with **no** auth header and body `{"passiveThreshold":0.85}` → **`200`**, and the stored policy really changed to `0.85`. The document says this must be `403` and must change nothing.
* No `mosip.security.admin-api-key` property, no `MOSIP_ADMIN_API_KEY`, no `MessageDigest.isEqual` comparison, no key fingerprint, no `CONFIG_CHANGED` audit row.
* The document's claim that a weakening edit records `risk.level = HIGH` with reasons cannot happen: audit rows are session-scoped (`audit_logs.session_id` is `NOT NULL`, see [V1__init_schema.sql](src/main/resources/db/migration/V1__init_schema.sql)), so a config change **cannot** be recorded in the audit table even if someone wanted to.
* `FORBIDDEN` (403) is present in the error-code switch in [GlobalExceptionHandler.java:61](src/main/java/io/mosip/liveness/api/GlobalExceptionHandler.java#L61) but **nothing in the API throws it** — it is unreachable.

The document's `audit_chain_integrity` scheme (HMAC-SHA-256 keyed chain, `minimum_length: 16`, `GET …/audit/verify` reporting `hmac: true/false`) has no counterpart in the code: there are no `prev_hash`/`hash` columns and no chain verifier.

---

## 3. Rate limiting — not implemented

* No `RateLimitFilter`, no `mosip.security.rate-limit.*` property, no `RATE_LIMIT_ENABLED`, no `SESSION_CREATE_LIMIT`/`FRAME_LIMIT`, no `X-RateLimit-*` headers, and no `RATE_LIMITED` error code anywhere in the repository.
* **Verified:** 40 back-to-back `POST /api/v1/sessions` → **40 × `201`**, **zero `429`** (the document promises a 30-per-60s per-IP bucket).
* The documented 429 bodies, `Retry-After` semantics, trusted-proxy `X-Forwarded-For` handling and the "frames budget shared across both routes" rule are all fictional.

---

## 4. Security headers and CORS

**Security headers: nothing is set.** There is no `SecurityHeadersFilter`. Verified on `GET /health`:

```
curl -s -D - -o /dev/null .../health | grep -iE 'content-security|x-frame|x-content-type|referrer-policy|cross-origin|permissions-policy'
→ (no output)
```

So `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Cross-Origin-Opener-Policy`, `Permissions-Policy` and the strict CSP (with the "Swagger UI exempt from CSP only" carve-out) are all invented.

**CORS is documented wrong *and* is broken in the code.** [AppConfig.java:119](src/main/java/io/mosip/liveness/app/config/AppConfig.java#L119) uses `allowedOrigins("http://localhost:*", "http://127.0.0.1:*")`. In Spring, wildcard *patterns* require `allowedOriginPatterns`, so those are treated as two **literal** origins. Verified:

| Origin sent | Preflight `OPTIONS /api/v1/sessions` |
|---|---|
| `http://localhost:3000` | **403** (and the subsequent `POST` is also **403**) |
| `http://localhost:*` | `200` with `Access-Control-Allow-Origin: http://localhost:*` |
| `http://127.0.0.1:5173` | **403** |

Consequences: (a) the document's `allowed_origin_patterns` claim is false; (b) any browser client served from a different localhost port is refused by CORS — a real bug, not just a documentation error. Allowed methods are actually `GET, POST, PUT, DELETE, OPTIONS` and allowed headers are `Content-Type, Authorization`; there is no `exposedHeaders` list at all, so the documented exposure of `Retry-After`/`X-RateLimit-*` is meaningless.

---

## 5. Limits — the numbers exist, the enforcement does not

| Documented | Reality |
|---|---|
| `max_request_body_bytes: 25165824` rejected with **413 `PAYLOAD_TOO_LARGE`** by `SecurityHeadersFilter` | **No body-size filter and no 413 anywhere.** A 26 MB body produced **400** `VALIDATION_ERROR` ("Request body is malformed or unreadable"); 9 MB produced **422** `INVALID_FRAME` |
| `frame_payload.max_base64_chars_per_frame: 8000000`, `enforced_by: @Size`, violation **400** | `FrameSubmitRequest.frameBase64` carries only `@NotBlank` ([FrameSubmitRequest.java:19](src/main/java/io/mosip/liveness/dto/FrameSubmitRequest.java#L19)). The **real** ceiling is Jackson's `StreamReadConstraints` default max string length: verified 19,900,000 chars → `422` (reached the decoder) vs 21,000,000 chars → `400`. That is an undocumented ~20 M-char limit, nothing like 8 M |
| `challenge_validation_frame_count: max 60`, violation **400** | No `@Size` on `ChallengeValidateRequest.framesBase64` (only `@NotEmpty`, so `min: 1` is the one part that is real). Verified: a 61-element burst is **not** rejected on count — it passed validation and failed later with `404 NOT_FOUND` for the fake challenge id |
| `decoded_frame: max 6000000 bytes / 8192 px / 40000000 pixels`, violation **422 `INVALID_FRAME`** | No byte, dimension or pixel cap exists in `ImageUtils.decodeBase64Frame`; there is no `MAX_DIM`/`maxPixels` constant anywhere |
| "frames larger than 1280px on the long edge are downscaled before analysis" | No downscale in the request path at all. The only `resize` calls in the repo are inside the unused `backend/MediaPipeFaceMeshBackend` |

---

## 6. Decision-path behaviour — where the document is most misleading

These are the claims an integrator would actually build against.

| Documented behaviour | Code ([DecisionEngineService.java](src/main/java/io/mosip/liveness/services/DecisionEngineService.java)) |
|---|---|
| Session creation **freezes the workflow policy** and returns it as `SessionResponse.policy` | **No snapshot.** `SessionsController.createSession` stores only workflow/device/subjectRef/online. Verified: the created session body has no `policy` field. The policy is re-read from the DB on **every frame** ([line 55](src/main/java/io/mosip/liveness/services/DecisionEngineService.java#L55)), so a config edit **does** affect in-flight sessions — the opposite of the documented guarantee |
| Session creation validates cross-field invariants and returns **400 `BAD_REQUEST`** | No `EffectivePolicyValidator` exists; no invariant is checked at session creation |
| Passive decision uses the **median** of the last `passiveWindowFrames` scores after `passiveMinFrames` (cold start returns `retry_passive`) | **No median, no window, no cold start.** A **single** frame's score is compared to the threshold ([line 126](src/main/java/io/mosip/liveness/services/DecisionEngineService.java#L126)). `passiveMinFrames`/`passiveWindowFrames`/`minFaceQuality` are hard-coded to 5/7/0.50 in `ConfigService` and never consulted by the HTTP path. *The median-window logic does exist — in `io.mosip.liveness.engine`, which is referenced only by itself, the eval harness and tests, never by `api/` or `services/`* |
| PAD rejects only on a **confirmed** attack (current frame + preceding one); the first flagged frame returns `retry_passive` | **Single-frame reject** ([line 97](src/main/java/io/mosip/liveness/services/DecisionEngineService.java#L97)). No two-frame confirmation anywhere |
| PAD = "heuristics **OR'd with the ONNX model**" | The HTTP path runs `PadEngineService` only — pure OpenCV heuristics, no ONNX/TFLite model. (The model backends exist under `backend/` but `DecisionEngineService` never uses them.) Note `PadEngineService` line 57 is `double freqRatio = 0.1; // Placeholder`, below its own `0.35` threshold, so the **`SCREEN_REPLAY` branch is unreachable dead code** |
| Challenge validation returns `action: "continue"` (same challenge id, window still open, "…12s left for this action") | **No `continue` action exists.** The only literals emitted are `proceed`, `retry_challenge`, `reject` (challenges) and `proceed`, `escalate_to_active`, `retry_passive`, `reject` (frames). No challenge window is ever enforced; no `ChallengeStatus.TIMEOUT` is ever written by the HTTP path; the "seconds remaining" message string does not exist |
| A failed burst only consumes retry budget when the **window has elapsed** | The first `validate` after a non-detected action already fails the challenge and increments `retryCount` ([line 273](src/main/java/io/mosip/liveness/services/DecisionEngineService.java#L273)) |
| `livenessEnabled: false` skips passive scoring and challenges, and audits `LIVENESS_DISABLED` | `livenessEnabled` is **never read** in `services/` or `api/` (`grep` → only `ConfigController` copying it into the response). The bypass and its audit event do not exist |
| Per-workflow frozen policies: RESIDENT `0.80/1/20000/3/ESCALATE_TO_OPERATOR`, OPERATOR `0.82/1/15000/2/FALLBACK`, SUPERVISOR `0.85/2/15000/1/LOCK_OUT` | **All three workflows are seeded identically.** Verified `GET /api/v1/config/{RESIDENT,OPERATOR,SUPERVISOR}`: `0.75`, `minChallengeCount 1`, `challengeTimeoutMs 8000`, `maxRetryCount 3`, `LOCK`, `[blink,smile,turn_left,turn_right]` for all three. `ConfigController.getOrCreatePolicy()` builds **one** hard-coded default for any workflow; the document's own claim that "the three workflows never silently collapse onto one operating point" is exactly what happens |
| `frameSamplingRate` / `frameSamplingMinFps` / combined weights are part of the operating point | Carried in `EffectivePolicy` as constants (2 / 15 / 0.4 / 0.6) and never used for sampling or weighting |

### Retry exhaustion and the `known_gaps` section

The `known_gaps[0]` entry is **partly right and partly wrong**:

* Right: retry exhaustion always returns `action: "reject"` with `failureReason: "max_retries_exceeded"` and never emits `locked` / `escalate_to_operator` / `failed`.
* Wrong: `FrameProcessResult.mayRetrySession` is **not** "declared in the schema" — the field does not exist at all.
* Wrong: it says `docs/configuration.md` and `docs/client-integration-guide.md` describe a `LOCK_OUT → "locked"` matrix "as if it were live". Those wire strings appear in **neither** document. `docs/configuration.md` describes the repeated-failure actions conceptually (engine policy), and `docs/client-integration-guide.md` already documents `proceed/retry_challenge/reject` — i.e. the real vocabulary.

Also note the raw comparison is `retryCount >= maxRetry`, so with the seeded `maxRetryCount = 3` a session gets **3** failed challenge windows, and the documented per-workflow budgets (3/2/1) are not configured.

---

## 7. Error handling, data models and other corrections

**Error codes.** `VALIDATION_ERROR` 400, `BAD_REQUEST` 400, `NOT_FOUND` 404, `CONFLICT` 409, `INVALID_FRAME` 422, `INTERNAL_ERROR` 500, `ENGINE_UNAVAILABLE` 503 all exist and behave as documented. Corrections:

* **413 `PAYLOAD_TOO_LARGE` and 429 `RATE_LIMITED` do not exist.**
* "400 `VALIDATION_ERROR` … bad path/query parameter type" is wrong: verified `GET /api/v1/sessions/not-a-uuid` → **500** `{"error":"INTERNAL_ERROR",…}`. A `ResponseStatusException` is not raised for a failed path-variable conversion, and `GlobalExceptionHandler` has no handler for `MethodArgumentTypeMismatchException`, so the catch-all `Exception` handler wins. (The same gap is what turns `GET /api/v1/config/audit` into a 500.)
* "an invalid effective policy at session creation" as a 400 source: not implemented.

**`FrameProcessResult`** — the documented `mayRetrySession` field does not exist; every other field matches. Actions in the response are `String` values (not a Java enum), which is fine but worth noting for schema generation.

**`SessionResponse`** — matches except for the invented `policy` field. (It also has no `subjectRef`, so a client cannot read back what it sent.)

**`AuditLogEntry`** — the documented `workflowType` field does not exist in the DTO. The HTTP path writes only five event types: `PAD_REJECTED`, `SESSION_PASSED`, `CHALLENGE_ISSUED`, `CHALLENGE_PASSED`, `CHALLENGE_FAILED`. Documented `SESSION_CREATED`, `FRAME_SCORED`, `CONFIG_CHANGED`, `LIVENESS_DISABLED`, `MAX_RETRIES_EXCEEDED` are never written (verified: `GET /api/v1/sessions/{id}/audit` for a brand-new session returns `[]`). There is no `CONFIG_CHANGED` diff payload, actor fingerprint, source IP or risk classification.

**`OperationalMetrics`** — the real response has **11** fields:

```
totalSessions, passedSessions, failedSessions, activeSessions, passRate,
avgPassiveToActiveEscalationRate, avgFramesPerSession, avgChallengesPerSession,
livenessRetryRate, livenessFailureRate, padRejectionRate
```

The documented `pipelineTimings` block (with `decode/facedetect/onnxscore/heuristicscore/padheuristic/padonnx/total` phases) and the four `rateLimit*` counters do not exist.

**`ConfigPolicyUpdate`** — ranges are mostly right (`passiveThreshold` `[0,1]` via `@Min(0)@Max(1)`, `minChallengeCount [1,5]`, `challengeTimeoutMs [1000,60000]`, `maxRetryCount [0,10]`), but:

* `passiveThreshold: 0` is **accepted** (verified `200`), not refused as "(0, 1]".
* The `mosip.liveness.min-challenge-window-ms` floor (15000) does not exist. `challengeTimeoutMs: 1000` was **accepted** (verified `200`).
* "`minChallengeCount` must not exceed the configured challenge pool" is not enforced. `{"minChallengeCount":5,"challengeTypes":["blink"]}` was **accepted** (verified `200`).
* There is no merged/cross-field policy validation.

**`PadAttackType`** — the enum also has `OTHER`, so `padAttackType` and `presentation_attack:<TYPE>` can legitimately carry a fourth value the document omits.

**base64 convention** — the document says "no data-URI prefix"; `ImageUtils` actually *strips* a `data:…,` prefix if present ([ImageUtils.java:132](src/main/java/io/mosip/liveness/services/ImageUtils.java#L132)), so both forms are accepted.

---

## 8. Metadata errors

| Documented | Reality |
|---|---|
| `source_revision: development @ 05839c9` | **No such commit object** in this repository or the sibling clone (`git cat-file -t 05839c9` → fatal). `development` HEAD is `1ed5eaa` |
| `authoritative_sources` includes `api/RateLimitFilter.java`, `api/SecurityHeadersFilter.java` | **Neither file exists** in any branch |
| `start_service: "./start.sh"`, `port_configurable_via: "./start.sh --port N"` | The launcher is [`scripts/start.sh`](scripts/start.sh) (and `scripts/start.bat`); `--port` is real, the root path is not |
| `not_in_scope`: Python scaffold `pad_liveness_backend/`, client adapters, L0/L1 device layer | **Correct** — all three exist as described |
| `api_version 1.0.0-SNAPSHOT`, port 8000, `/swagger-ui/index.html`, `/` console, dev profile = H2 + Flyway off + `create-drop` | **Correct** (Swagger UI verified `200`; welcome page `static/index.html` logged at startup) |
| `generated_for: MOSIP Hackathon 2026 — PS 04, Team Cyber Hub` | Not verifiable from this repository |

---

## 9. What the document gets right (keep this)

* `GET /v3/api-docs` publishes exactly these paths, and the document describes each one's lifecycle correctly:

  ```
  /health
  /api/v1/sessions                                  (POST, 201)
  /api/v1/sessions/{sessionId}                      (GET)
  /api/v1/sessions/{sessionId}/close                (POST)
  /api/v1/sessions/{sessionId}/frames               (POST)
  /api/v1/sessions/{sessionId}/challenges/validate  (POST)
  /api/v1/sessions/{sessionId}/audit                (GET)
  /api/v1/config/{workflowType}                     (GET, PUT)
  /api/v1/config/{workflowType}/effective           (GET)
  /api/v1/metrics                                   (GET)
  ```
* Error envelope shape (`error` / `message` / `status` / `timestamp`), `non_null` omission of null fields, camelCase, ISO-8601-with-offset timestamps, and the "never leak stack traces / class names / PII" promise — all true.
* Bean-validation messages do use snake_case (`device_id is required`), matching the documented example.
* Enum vocabularies: `WorkflowType`, `SessionStatus`, `LivenessStage`, `ChallengeStatus`, `FailurePolicy` (LOCK/ESCALATE/ALLOW_RETRY ↔ LOCK_OUT/ESCALATE_TO_OPERATOR/FALLBACK) and the two `ChallengeType` wire spellings (`turn_left` ↔ `TURN_LEFT` ↔ core `TURN_HEAD_LEFT`) are all accurate, including the mapping note.
* Frame `action` vocabulary `proceed` / `escalate_to_active` / `retry_passive` / `reject` and its meanings (including "no challenge is ever issued for an attack") — accurate.
* Challenge validation: `409` when the challenge is not `ISSUED`, `404` when the challenge belongs to another session, and the client rule "always drive the next validation from the challenge object in the response" — accurate and useful.
* `passive_liveness_below_threshold` + `reject` when active liveness is disabled ([line 145](src/main/java/io/mosip/liveness/services/DecisionEngineService.java#L145)) — accurate.
* `422 INVALID_FRAME` on undecodable frames, `404` unknown session, `409` non-ACTIVE session, `503 ENGINE_UNAVAILABLE` when OpenCV fails to load, lazy config-row creation on first `GET /api/v1/config/{workflowType}` — accurate.
* `/api/v1/metrics` does return DB-derived rates with no PII, and rate/percentage rounding is to 4 decimals.

---

## 10. Recommended fixes

**If the document is meant to describe this service (documentation fix):** delete §2–§5 and §8 wholesale, replace the per-workflow policy table and the metrics/audit data models with the values in §6–§7, delete `policy` from `SessionResponse` and `workflowType` from `AuditLogEntry`, delete `mayRetrySession`, restate `POST /frames` as a single-frame decision (no median window, no cold start), and fix the metadata (real revision, real launcher path, real endpoint list).

**If the document is a specification the service is meant to meet (code fix), the order that pays off first:**

1. **CORS** — switch `allowedOrigins` → `allowedOriginPatterns` in [AppConfig.java:119](src/main/java/io/mosip/liveness/app/config/AppConfig.java#L119). One word; currently every cross-origin browser client gets 403.
2. **Admin key on `PUT /api/v1/config/*`** — fail closed, constant-time compare, and record the change (this needs a config-scoped audit table, since `audit_logs.session_id` is `NOT NULL`).
3. **Path-variable and unknown-path errors** — add handlers so a bad `{sessionId}` or an unknown `/api/v1/config/audit` returns 400/404 instead of 500 `INTERNAL_ERROR`.
4. **Request-size and frame caps** — if the documented numbers are wanted, they must be added (`@Size` on the frame fields, an explicit decode cap, and a real 413); today the only ceiling is Jackson's implicit ~20 M-char string limit.
5. **Challenge retry semantics** — enforce the configured challenge window and emit the documented `continue`/`TIMEOUT` path before burning retry budget, and wire `onRepeatedFailure` (this is the pre-existing `known_gaps[0]` item, and it is the one gap the document identified correctly).
6. **`livenessEnabled`** — either honour it in the decision path (with the audited bypass) or remove it from the config contract.
7. **Per-workflow defaults** — seed distinct rows per workflow if "one service, three risk profiles" is the headline claim.
8. **Median-window passive voting** — either route the HTTP path through `engine/FaceLivenessEngine` (which already implements it) or stop advertising it.

---

## Appendix — evidence commands

```bash
# verify the real endpoint surface
curl -s localhost:8000/v3/api-docs | tr ',' '\n' | grep -o '"/[^"]*"' | sort -u

# no admin gate
curl -s -o /dev/null -w '%{http_code}\n' -X PUT localhost:8000/api/v1/config/RESIDENT \
  -H 'Content-Type: application/json' -d '{"passiveThreshold":0.85}'      # → 200

# no rate limiting
for i in $(seq 1 40); do curl -s -o /dev/null -w '%{http_code} ' -X POST localhost:8000/api/v1/sessions \
  -H 'Content-Type: application/json' -d '{"workflowType":"RESIDENT","deviceId":"L1-CAM-01"}'; done   # → 40 × 201

# no security headers
curl -s -D - -o /dev/null localhost:8000/health | grep -iE 'x-frame|content-security|x-content-type'

# CORS: only the literal origin is allowed
curl -s -D - -o /dev/null -X OPTIONS localhost:8000/api/v1/sessions \
  -H 'Origin: http://localhost:3000' -H 'Access-Control-Request-Method: POST'   # → 403

# documented-but-missing endpoints
curl -s -o /dev/null -w '%{http_code}\n' localhost:8000/api/v1/config/audit        # → 500
curl -s -o /dev/null -w '%{http_code}\n' localhost:8000/api/v1/config/audit/verify  # → 404
curl -s -o /dev/null -w '%{http_code}\n' 'localhost:8000/api/v1/eval/threshold-sweep?targetBpcer=0.02'  # → 404

# repository-wide absence checks
grep -ril "X-Admin" . | grep -v '^./.git/'          # → nothing
grep -ril "rate.limit" . | grep -v '^./.git/'       # → nothing
grep -rn "mayRetrySession\|threshold-sweep\|CONFIG_CHANGED" src/   # → nothing
```

Startup logs used for this review: `target/api-doc-check.log`, `target/api-doc-check2.log` (git-ignored `target/`).
