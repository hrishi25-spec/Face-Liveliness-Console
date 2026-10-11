# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased] - 2026-10-11

### Changed — Session ownership moved into a filter, and a new session route cannot skip it

- Ownership used to be a `UserSessionGuard.require` call inside each of the six
  session-scoped handlers. That shape has the failure mode the admin key already
  had: a new session-scoped endpoint whose author forgets the call is simply
  open, and nothing notices — the omission is invisible to every test, because
  no route is missing a check that a *list* would have said it needed.
  `SessionOwnershipFilter` now decides for every route in the new
  `RouteInventory.SESSION_SCOPED` list — `GET /api/v1/sessions/{id}`,
  `POST …/close`, `POST …/frames`, `POST …/challenges/validate`,
  `POST …/active-liveness`, `GET …/audit` — before the handler runs, so there is
  no call site left to omit.
- `ApiRouteInventoryTest` fails a route that carries a `{sessionId}` without
  being in that list, a listed route that is no longer declared or no longer
  matched (the id has to be read off the path), and a session-scoped route that
  also lands in `ADMIN_KEYED`. A session endpoint added without the opt-in is a
  red test now instead of an unguarded route.
- The 403 is written by the filter, so it no longer travels through
  `GlobalExceptionHandler`, which resolves exceptions Spring MVC raised.
  `FilterRefusal` holds the one copy of that body
  (`error`/`message`/`status`/`timestamp`, loopback CORS headers included) and
  `AdminKeyFilter` and `RateLimitFilter` write through it too: three filters
  produce a shape the client guide documents and clients parse `message` out of,
  so a second copy of it was a contract change waiting to happen.
  `UserSessionGuard.refusal` returns the reason — the text that becomes
  `message` — instead of throwing a `ResponseStatusException`, and the
  handler-side `require` call is gone.
- Nothing a client sees changed: the same 403 with the same two messages, the
  same CORS treatment on the refusal, an unknown session id still answered by the
  handler (404, or the audit route's empty trail), a malformed id still a 400
  rather than a 500, and a browser's `OPTIONS` preflight still passing because it
  cannot carry the claim.
- Tests: `SessionOwnershipFilterTest` (10) drives the filter directly — the
  refusal body, the chain actually stopping, the shapes it must not refuse
  (anonymous session, unknown id, malformed id without a lookup, preflight) and
  that it does not widen the CORS origin list; `UserSessionGuardTest` now asserts
  the returned reasons, `ApiRouteInventoryTest` +3 pins the opt-in, the matcher
  and the non-session cases, and `HealthControllerTest` imports `TestConfig`
  because a slice builds every filter bean. Verified by falsification: adding an
  unlisted `GET /api/v1/sessions/{id}/ping` to `SessionsController` fails
  `everySessionScopedRouteOptsIntoOwnership` naming the route, and the probe was
  removed afterwards.

### Fixed — The documented `onRepeatedFailure` behaviour is now the actual behaviour

- `docs/configuration.md` (and the DTOs it mirrors) documented three terminal
  outcomes for a spent retry budget — `locked` / `escalate_to_operator` /
  `failed`, each with its own `failureReason` and `mayRetrySession` — and said
  they were "wired into the HTTP decision path, so a per-workflow value is real
  behaviour, not just stored config". They were not: the engine answered every
  spent budget with `reject` + `max_retries_exceeded`, so a per-workflow
  `onRepeatedFailure` was stored config, `mayRetrySession` was documented on four
  documents and set by nothing, and the client layer carried a workaround for the
  gap (`ServiceLivenessEventMapper` derived the outcome from the policy name
  because the action was always `reject`).
- Each `RepeatedFailureAction` now carries its own outcome — action,
  `failureReason`, retryability and the generic user-facing message — and the
  engine reads it when the last retry is spent, so `ESCALATE_TO_OPERATOR`
  escalates, `LOCK_OUT` locks and `FALLBACK` (= the seeded `ALLOW_RETRY`) fails
  with `mayRetrySession: true`. Every reason keeps the
  `max_retries_exceeded` prefix, so a client that only knows the old value still
  recognises the outcome.
- `ChallengeValidationResult` carries `mayRetrySession` (set only on a terminal
  budget-exhausted verdict), `FrameProcessResult` no longer advertises a field
  no code path could set (the frame path never spends the retry budget, so it
  cannot emit the terminal vocabulary), and `docs/PRD.md` now lists the two
  response shapes accordingly.
- The console treats the three outcomes as terminal and shows the server's own
  message: `LOCKED OUT` (not `REJECT`) with no "new session" hint for a lock-out,
  `ESCALATE TO OPERATOR`, and `FAILED` with the retry hint for a fallback. The
  passive loop now also stops on any non-`retry_passive` verdict instead of
  looping a closed session.
- The service-mediated client mapped the three actions only on **frame**
  responses, where they can never appear — `ServiceLivenessEventMapper.fromValidation`
  fell through to "verifying", so an overlay would keep waiting on a session the
  service had already closed. The terminal vocabulary now ends a validation too,
  and the attempt that spent the last retry counts as consumed (it was made).
- Tests: `DecisionEngineServiceTest` +3 (one per repeated-failure action, each
  pinning action, `failureReason`, retryability and that no further challenge is
  issued), `ServiceLivenessEventMapperTest` +1 (the three actions end a
  validation with the right next action, and each consumes the attempt) and the
  console DOM suite gained a budget-exhausted scenario asserting the headline,
  the server's message, the retry hint only for `failed`, and that the resolved
  challenge is never re-submitted.

### Fixed — The Python backend speaks the same repeated-failure vocabulary

- `pad_liveness_backend` answered every spent retry budget with `reject` and a
  reason of its own (`max_retries_exceeded` / `_locked` / `_escalated`), so a
  client written against the Java contract — or against the table in
  `docs/configuration.md` — could not tell a lock-out from an escalation, and the
  per-workflow `on_repeated_failure` was stored config there too. Each policy now
  carries the outcome it produces (`FailurePolicy.outcome` in
  `app/models/enums.py`, mirroring the Spring service's `RepeatedFailureAction`):
  `lock` → `locked` + `max_retries_exceeded:locked_out`, `escalate` →
  `escalate_to_operator` + `max_retries_exceeded:escalation_required`,
  `allow_retry` → `failed` + `max_retries_exceeded`, with the same generic
  user-facing messages and the same `max_retries_exceeded` prefix a client that
  only knows the older spelling still recognises.
- `ChallengeValidationResult` gained `may_retry_session`, set only on the terminal
  budget-exhausted verdict, and the action's documented domain now names all three
  terminal actions. Nothing else in the service keyed off the old reasons.
- Tests: +4 in `tests/test_liveness_api.py` — each policy driven through the real
  API (a low-score frame issues a challenge, the failing frame stream then spends
  three retries), asserting the terminal action, `failure_reason`,
  `may_retry_session`, the message, that only the last verdict is terminal, the
  audit trail, and that the closed session refuses further frames; plus the
  mapping table itself with its prefix invariant. The divergence had **no**
  coverage before — no Python test reached retry exhaustion at all. Restoring the
  old `reject` mapping fails the three API cases.
- Docs: the backend README tables the shared vocabulary, `docs/configuration.md`
  points at it, and `docs/PRD.md`'s companion-project and test-suite counts move
  to 42 tests.

### Added — One contract test, both backends

- Each implementation was only ever asserted against its own expectations, which
  is exactly why the Python backend could answer a spent retry budget with
  `reject` while the Java service answered `locked` (fixed above) without either
  suite noticing. `CrossBackendContractTest` drives one scenario on both — the
  Spring service over real HTTP (real OpenCV, real scorer, a 3s challenge window)
  and the FastAPI backend through `pad_liveness_backend/tests/cross_backend_scenario.py`
  over a subprocess, because it is a different runtime — and compares the terminal
  verdicts field by field: `action`, `failure_reason`, `may_retry_session`, the
  user-facing message, the session and challenge status, and the retry that
  precedes the outcome.
- Each side is read under its own documented spelling (camelCase here, snake_case
  there) and the spellings are asserted, not papered over by a lookup that accepts
  both, so a rename on either side fails. Enum casing (`FAILED` / `failed`) is the
  one deliberate difference the comparison normalises, and it says so.
- Tests: the scenario script is covered by the Python suite as well
  (`test_cross_backend_scenario.py`, +4: the documented shape for all three
  policies, plus a larger budget to show the outcome does not depend on its size),
  and the new Java test was verified by mutation — restoring the old Python
  vocabulary fails it with `expected: <locked> but was: <reject>`.
- CI: a new *Cross-backend contract* job sets up JDK 17 and Python 3.12, installs
  the backend's test dependencies and runs the test with
  `PAD_LIVENESS_REQUIRE_PYTHON=1`, so a runner without the Python environment fails
  the job instead of skipping the comparison inside it. The unit matrix excludes
  the class (it would only skip there, since that job installs no Python) and the
  README gate table lists the new job, which the boundaries gate checks from both
  sides.

### Fixed — Session ownership (`X-Subject-Ref`) is enforced, not just documented

- `UserSessionGuard` implemented the resource-level ownership rule — a session
  created with a `subjectRef` may only be acted on by a caller presenting it —
  and had a unit test, but **no route called it**, so an owned session was
  reachable by anyone who could reach the port. It is now invoked on every
  session-scoped endpoint: `GET /api/v1/sessions/{id}`,
  `POST …/close`, `POST …/frames`, `POST …/challenges/validate`,
  `POST …/active-liveness` and `GET …/audit`. A missing or mismatched header is
  403 and the refusal happens before any frame is decoded or scored.
- Anonymous sessions (no `subjectRef`) stay reachable without a header, which is
  what the console and the shipped `LivenessHttpClient` create — their behaviour
  is unchanged. `X-Subject-Ref` is allowed in the CORS headers so a browser host
  that creates an owned session can send it, and the header is documented in
  `docs/client-integration-guide.md` (§5.5) and `docs/brain.md`.
- Tests: +1 to each of `SessionsControllerTest` (read requires the header, close
  refuses another subject, anonymous sessions stay open), `FramesControllerTest`,
  `ChallengesControllerTest`, `ActiveLivenessControllerTest` and
  `AuditControllerTest`, each asserting the 403 and that no work was performed on
  the refused call.

### Added — `LivenessHttpClient` can create and drive an owned session

- The shipped client could only create **anonymous** sessions: `createSession` had
  no subject reference, so a host could not use the `X-Subject-Ref` ownership the
  service enforces. `LivenessClient` now declares
  `createSession(workflowType, deviceId, subjectRef)` — the two-argument form is a
  `default` delegating with no owner, so anonymous hosts and implementations are
  untouched — and `LivenessHttpClient` puts the reference in the create body,
  remembers it for that session, and repeats it in the `X-Subject-Ref` header on
  every session-scoped call it makes (`frames`, `challenges/validate`, `close`).
  The owner is released by `closeSession`, which bounds that bookkeeping by
  sessions in flight rather than by sessions ever created.
- A 4xx response used to be parsed as if it were the success shape, so the guard's
  refusal — an error object with no `id` and no `action` — failed as a
  `NullPointerException` inside a parser, or silently defaulted a field. Every call
  now goes through one transport helper that raises a `LivenessClientException`
  carrying the status and the service's own `message`, so a mismatched subject
  reference reads as a refusal. `isLivenessEnabled` keeps its documented fail-open
  default (never bypass liveness because the config answer was unreadable).
- Tests: `LivenessHttpClientWireTest` (7) drives the client over a real socket
  against a stub `HttpServer` — the only way to see the request body and headers —
  asserting the create body claims the owner, that every session-scoped call
  repeats it under the name `UserSessionGuard.HEADER` (the client declares the
  literal itself to stay free of the web layer, so this pins the two spellings
  together), that `close` releases it, that an anonymous session still sends no
  header, that a 403 arrives as a `LivenessClientException` rather than a parse
  failure, and that an unreadable config answer is not mistaken for `livenessEnabled:
  false`. Suppressing the header in the client fails two of the seven.

### Added — Secret scan gate

- `scripts/check-secrets.sh` runs gitleaks over the checkout **and its Git
  history**, so a credential that was committed and then removed still fails
  until the history is rewritten; `--redact` keeps the value out of CI logs and
  terminal scrollback. The image tag is pinned so a new gitleaks release cannot
  change what the gate accepts on its own schedule, and the CLI is used when one
  is on `PATH` (faster locally). Wired as the *Secret scan* CI job
  (`fetch-depth: 0`, since a shallow checkout would scan one commit) and listed
  in the README's gate table, which the *Project boundaries* gate checks from
  both sides.

### Changed — Dead code and stale artefacts removed

- 43 unused Java imports across 23 files, 7 unreferenced MediaPipe landmark
  constants (the right-iris ring and the unused lip/model-point tables), an
  unused `@Mock` field in `ApcerEndpointTest`, and the unreachable
  `FrameProcessResult.mayRetrySession` field.
- Deleted `docs/brain.md.backup` (a tracked, stale copy of `brain.md`) and
  `src/test/java/io/mosip/liveness/client/LivenessHttpClientTest.java.bak`, a
  scratch copy of a test that asserted a client API (`subjectRef` in
  `createSession`) which did not exist at the time and could not compile — that API
  now does exist, and is covered by `LivenessHttpClientWireTest` (above).

### Added — Manually requested active liveness (console extra check)

- Above the passive threshold a session passes and the engine issues no
  challenge — that part is right, but it left no way to ask for one anyway.
  Added `POST /api/v1/sessions/{sessionId}/active-liveness`: on a session that
  already passed (`status = PASSED`, `finalResult = true`) it issues a single
  active challenge marked `manual` and hands it back, and the console's new
  **Run active liveness** button drives it. Refusals are fail-closed 409s (not
  passed yet, active liveness disabled, no challenge types configured), and a
  repeat call while a manual challenge is still `ISSUED` returns that same
  challenge instead of issuing a second one.
- The extra check is *advisory*: its result is stored on the challenge row and
  audited (`CHALLENGE_PASSED` / `CHALLENGE_FAILED` with `manual: true`, PAD hits
  with `stage: active_manual`), but the session's verdict is never rewritten — a
  missed action leaves the session PASSED and consumes no retry budget. The
  engine's own escalation path is untouched, so an automatic challenge still
  decides its session exactly as before.
- Schema: `challenges.manual BOOLEAN NOT NULL DEFAULT FALSE`
  (`V11__challenge_manual_flag.sql`), so every pre-existing challenge stays
  engine-issued. The route is client-facing (no admin key) and shares the
  per-session rate-limit budget with frames and challenge validation.
- Tests: `DecisionEngineServiceTest` +6 (refused before a pass and when active
  liveness is disabled, issued and marked `manual`, idempotent while open, a
  passed and a missed extra check both leaving the verdict untouched, a PAD hit
  audited as `active_manual`), `ActiveLivenessControllerTest` (3 — 404, the
  created manual challenge, structured 409), `ChallengesControllerTest` +2 (a
  manual challenge accepted on a PASSED session, an engine challenge still
  refused there), `RateLimitFilterTest` +1 (the extra check shares the session
  budget), and the console DOM guard suite drives the button end to end
  (disabled until a pass, requests the challenge, shows that check's own
  verdict). The suite's challenge stub now carries the resolved challenge like
  the server does — without that shape the loop's `retry_challenge` fallback
  re-submitted a just-failed manual challenge and surfaced a 409 in the verdict
  box (caught by driving the real page, fixed by treating a manual verdict as
  terminal for its challenge).

### Fixed — Passive liveness score pinned at `1.000` on genuine faces

- The MiniFASNet-V2 live-class probability is over-confident: on an ordinary
  genuine webcam face it sits at 0.99–0.9999, so the console's decision log
  showed `score=1.000` on every frame and the number carried no information.
  Added `ProbabilityCalibration` — a log-odds temperature anchored at the
  default threshold — and wired it through `PassiveScoringService` /
  `OnnxMiniFasNetBackend` as `mosip.liveness.score-temperature` (default
  `4.0`). The map is continuous, strictly increasing and a fixed point at
  `0.80`, so every pass/fail verdict at `t <= 0.80` is unchanged while the
  genuine tail spreads into a readable band (the committed genuine fixture goes
  `0.99499` → `0.9139`, the print fixture's live probability stays `0.00055`).
  A per-workflow threshold above 0.80 is applied to the calibrated scale, so it
  demands a slightly higher raw confidence — the existing "recalibrate after a
  scorer change" rule applies. Set the key to `1` to report the raw model
  confidence. The PAD verdict deliberately keeps the raw probabilities (it
  decides by class ranking, which a monotonic temperature cannot change), so
  attack rejection — and the audit trail's PAD confidences — are untouched.
- Tests: `ProbabilityCalibrationTest` (10 — identity, below/at-pivot
  pass-through, threshold-verdict preservation, monotonicity, the spread of a
  saturated genuine score, attack passthrough, NaN, and constructor
  validation) and two `OnnxMiniFasNetBackendTest` cases pinning the calibrated
  genuine score above the threshold while staying below the raw one, plus a
  malformed-temperature configuration error.

### Added — One backend-selection key for both wirings (interop report F5)

- `mosip.liveness.backend` now selects the backend for **both** paths behind
  one vocabulary (`LivenessBackendSelection`): the HTTP scorer
  (`PassiveScoringService`) and the `LivenessBackend` SPI bean
  (`AppConfig.livenessBackend()`, consumed by `FaceLivenessEngine`). The
  default `auto` — and `heuristic` — keep each path byte-for-byte on today's
  behaviour (service: ONNX→heuristic; SPI bean: the scripted mock), so the
  key changes nothing until a deployment opts in. Explicit ids (`mock`,
  `onnx-minifasnet-v2`, `mediapipe-facemesh`, `tflite-minifasnet`) move both
  paths together, named by the same audit ids the interop contract pins.
- Explicit means strict: an id that cannot load fails closed with a coded
  `LivenessException` naming the key (service: cached, rethrown, never a
  silent swap to the heuristic; engine: the existing
  `DEVICE_CONNECTION_FAILURE` mapping), and an unknown value fails at bean
  construction — a typo must stop startup, not quietly pick another scorer
  (the divergence F5 is about). `model-path` applies to ONNX selections;
  construction loads nothing at boot (the engine calls `initialize()`).
- Tests: `LivenessBackendSelectionTest` (5 — vocabulary, fail-fast, SPI
  factory by id, mock default, the Spring bean mapping the same key),
  `PassiveScoringServiceTest` +4 (explicit mock/ONNX honoured, unavailable
  MediaPipe refuses closed with the key in the message — availability probed,
  not assumed — unknown mode fails at construction), and the interop suite +1
  constructing the engine **through the selection path** for every selectable
  id (the report's own recommendation on F5). F5 and recommendation 3 are
  marked resolved in the report; configuration docs and the yml comments
  document the shared semantics.

### Added — Measured per-frame latency and throughput for the mock and ONNX backends

- `BackendPerFrameLatencyTest` measures what one SPI conversation costs at
  the seam: the identical genuine-face fixture (256×320) through
  `analyzeFrame → assessPad → scorePassiveLiveness` in engine order,
  single-threaded, decode excluded, 10 warm-up frames then 100 measured
  frames per backend. Results land in
  `target/backend-latency-results.txt` and in the interoperability report's
  new section 10. Assertions are sanity-level only (every frame scored in
  `[0,1]`, positive latency, ≥ 1 fps floor) — timing is host-dependent, so
  the test guards the measurement, not a wall-clock target.
- **Numbers (one run, Linux x86_64, 4 × i3-1005G1, OpenJDK 17.0.20.1, ORT
  1.24.2 CPU):** mock **0.017 ms/frame mean** (p95 0.037, ≈59,515 fps);
  ONNX **156.768 ms/frame mean** (analyze 46.680 · pad 54.386 · score
  55.702; p50 148.039, p95 206.396, max 309.050; **6.4 fps**), genuine face
  scoring `[0.9950, 0.9950]` on every frame.
- The phase costs confirm at the seam what the HTTP stage timers found
  end-to-end: `scorePassiveLiveness` and `assessPad` each call
  `probabilitiesFor(frame)` independently, so one qualifying frame pays
  three Haar cascades and two inferences — and 6.4 fps single-threaded sits
  under the 10–15 fps analysis budget, so camera rate needs that duplication
  removed or scoring parallelised. The report gains the data (section 10),
  finding **F7**, and an amended limitation 4 (seam timing now measured;
  end-to-end/device timing stays out of scope).

### Added — MODEL_UPDATED audit events from the signed-manifest store

- `SignedManifestModelStore` now audits what spec §10 defines
  (`MODEL_UPDATED: old/new version, hash ok`) through an `AuditLogger`
  (no-op on the existing constructors, so every current caller is
  unchanged): each `activate` call and each `rollback` emits exactly one
  event. A swap carries `action=SWAP`, the old version (`none` for a first
  install), the new version and `hashOk=true`. A refusal carries
  `action=ROLLBACK` with `oldVersion == newVersion` (the previous model is
  kept) and a `reason` naming the failing check — `INPUT`, `SIGNATURE`,
  `HASH`, `MIN_APP_VERSION` or `UNSIGNED` — while `hashOk` stays
  independent of it: a payload whose digest matches but whose signature does
  not is recorded as `hashOk=true, reason=SIGNATURE`, a tampered payload as
  `hashOk=false, reason=HASH`. `rollback()` emits spec §13's
  `MODEL_UPDATED(rollback)` — `reason=HEALTH_CHECK`, the undone and restored
  versions, `hashOk=true` (both states were digest-verified when swapped in:
  what failed is the health check, not the hash).
- Events carry no session or workflow — a model update is not a liveness
  decision — and are emitted *before* any state change, so an audit sink
  that throws aborts the transition fail-closed instead of leaving a swap
  the trail never saw. The unsigned bare-interface path is audited too
  (`reason=UNSIGNED`) instead of silently returning false, and
  `AndroidModelStore` gains audit-forwarding constructors. The old "the
  caller audits MODEL_UPDATED(rollback)" contract in `ModelStore` now notes
  that audited stores discharge it themselves, so callers must not log it a
  second time.
- `SignedManifestModelStoreAuditTest` (9 tests) pins every field above plus
  the no-session/no-workflow shape and exactly-one-event-per-call;
  red-proved by forcing the emitted `hashOk` to `true`, which failed exactly
  the two refusal-field assertions while every decision stayed correct.

### Added — Key ids on signed model manifests (signing-key rotation)

- `SignedModelManifest` carries a `keyId` — the SHA-256 hex of the signing
  key's X.509 encoding, derivable from either half of the RSA pair and
  externally via `openssl pkey -pubin -in vendor-public.pem -outform DER |
  sha256sum`. `sign(...)` stamps it automatically; the canonical payload
  stays spec §13's four fields, so pre-keyid manifests verify
  byte-identically and old readers (which never load the property) keep
  verifying new ones. The id sits outside the signature but selects the
  verifying key, and `verifySignature` additionally requires a named id to
  match the key it is checked under — a rewritten id cannot hop to another
  trusted key.
- `SignedManifestModelStore` now verifies against a key *ring*:
  `new SignedManifestModelStore(appVersion, previousKey, currentKey)` covers
  the rotation window; an id present is authoritative (exactly that key or
  refuse — never a fallback), a blank id is a legacy manifest verified under
  any key still trusted, and revoking a key means constructing without it
  (everything it signed is refused, kid-less manifests included). The
  single-key constructors keep their exact behaviour as a ring of one, an
  empty ring throws at construction, and `AndroidModelStore` gains the same
  rotation constructor while reading the `keyId` property.
- The manifest tool writes the `keyId` line (always — same lesson as
  `minAppVersion`: a *missing* key loads as null) and self-checks it on
  reload. `SignedManifestRotationTest` (7 tests) pins derivation, the
  keyring, legacy fallback, revocation, id-hop and unknown-id refusal, and
  the construction guards; `ModelManifestToolTest` (now 10) proves rotation
  end-to-end through the written file plus a stripped pre-keyid file still
  installing. All pre-existing manifest tests pass unmodified — the
  five-field constructor and kid-less verification are unchanged.

### Added — Model-manifest generator tool (`ModelManifestTool`)

- `io.mosip.liveness.tools.ModelManifestTool` produces the vendor-signed
  properties manifest `AndroidModelStore.install(...)` reads (spec §13), so
  deployments can package a model release instead of hand-rolling the format:
  `sign` hashes the artifact with the same SHA-256 the store re-derives at
  activation, signs `modelId|version|sha256|minAppVersion` (SHA256withRSA) and
  writes the five keys deterministically (ASCII-escaped, so a hostile
  modelId/version can't forge a `key=value` line or a comment); `keygen`
  bootstraps a 2048-bit PKCS#8 vendor keypair, refusing to overwrite an
  existing key or emit a sub-2048 one. Legacy PKCS#1 PEM keys (`openssl
  genrsa`) are wrapped and accepted.
- The tool fails closed: usage errors exit 2, operational errors exit 1 with
  no manifest left behind — and before returning, `sign` re-reads the bytes it
  wrote and verifies them under the signing key, so an escaping or I/O defect
  fails the command instead of shipping a file the device would refuse.
  `ModelManifestToolTest` (8 tests) round-trips through the client's own
  `Properties.load` path and the engine's `SignedManifestModelStore`: install,
  tampered payload, rewritten field, hostile values, the `minAppVersion` gate,
  a PKCS#1 key, and every failure exit.

### Added — The desktop overlay now renders the service-mediated path too

- `DesktopLivenessAdapter` (the REST/session path, as opposed to the on-device
  orchestrator) emits the same `LivenessStateEvent` / `LivenessFinalResult` pair
  the  orchestrator emits, so one overlay renders both: point the adapter's listener
  at the overlay's `asListener()`, and the desktop client no longer needs the
  orchestrator in process. The pre-existing raw-DTO callback surface is untouched
  and still fires alongside the listener.
- `ServiceLivenessEventMapper` is the whole translation, as a pure function of
  one response plus the session's frozen policy and the client's counters —
  framing, challenge prompts, the challenge counter, attempt failures, the
  terminal vocabulary, transport failures and the host's cancel all map without
  a network, a clock or a node. Two rules carry over from the orchestrator: the
  client renders catalogue keys and never the service's English `message`, and
  it counts attempts where the budget lives (so a spent budget renders recovery
  guidance with no retry).
- The client now reads two things the service already returned and the client
  threw away: the policy frozen on the session at creation (`SessionInfo.policy`
  — minChallengeCount, maxRetries, onRepeatedFailure) and the follow-up challenge
  issued by a validation response. Without the policy the overlay cannot draw
  "Challenge 1 / N", and without the follow-up it cannot prompt the next
  challenge. The response parsers are static and pinned by JSON literals
  (`LivenessHttpClientParseTest`, 8 tests), including both id shapes the service
  uses for a challenge (`challengeId` on frames, `id` on validations).
- Details that only show up in a live session: a re-served challenge does not
  advance the counter (the service answers every frame with the same
  `escalate_to_active` while a challenge is open); a `reject` is retryable with
  Retry restarting the gate as a fresh session, while recovery guidance comes
  from the budget-exhausted vocabulary or a `reject` whose closed session reports
  `max_retries_exceeded`; and transport failures are split by entry point —
  `startSession` propagates, `onFrame`/`submitChallenge` render the recoverable
  `DEVICE_ERROR` and keep the session open, because frames arrive from the
  streamer's decode loop where throwing would kill it.
- Tests: `ServiceMediatedOverlayTest` (7) drives the adapter from off the FX
  thread — as the host's Streamer loop does — into the real overlay and asserts
  the rendered cards; `ServiceLivenessEventMapperTest` (22) pins the mapping
  table. The overlay tests now share one toolkit harness (`HeadlessFx`), since a
  test JVM can only start JavaFX once. Suite: 488 tests, 0 failures, exit 0.

### Added — Headless JavaFX render smoke test, and the two overlay defects it found

- `LivenessChallengeOverlaySmokeTest` (10 tests) builds the real overlay — real
  `FXMLLoader`, real `liveness-overlay.css`, real layout pass — and reads the
  rendered nodes back. JavaFX is pinned to Monocle's headless glass platform in
  the surefire configuration, so no display, window system or GPU is involved
  and the CI runner runs it unchanged. Every `LivenessState` is asserted
  against the card the design's state table gives it, together with the copy,
  the CSS-resolved white text, the laid-out card bounds, the Retry / Cancel
  affordances, the red failure tone and the green PASSED oval; the LOCK_OUT
  countdown, the button callbacks and a swapped i18n catalogue are covered
  too. The state→card expectations are spelled out in the test rather than
  read from the presenter, so one wrong mapping cannot pass twice.
- Two rendering defects the test found, both fatal to a path that had never
  been executed end to end:
  - `LivenessChallengeOverlay.fxml` declared a concrete `<StackPane>` root, so
    **every** `new LivenessChallengeOverlay()` threw `LoadException: Root
    value already specified.` — `FXMLLoader` only accepts a pre-set root for
    an `<fx:root>` document and otherwise insists on building the root itself.
    The file now declares `<fx:root>` whose `type` is `javafx.scene.layout.StackPane`,
    which is what makes `setRoot(this)` + `setController(this)` work at all;
    the DOM-level FXML test asserts the fx:root form so it cannot regress.
  - Retry and Cancel rendered as blank buttons: the FXML declares bare
    `<Button>`s and the renderer only ever toggled their visibility, never
    their text. Both labels now resolve through the catalogue
    (`liveness.action.retry` / `liveness.action.cancel`), so a deployment's
    swapped catalogue reaches them.
- `openjfx-monocle` is pinned to `${javafx.version}` (17.0.10) rather than the
  `jdk-12.0.1+2` build TestFX's own documentation points at: the old build
  dies with `AbstractMethodError` on `Window._updateViewSize` the moment the
  first window is created, and a mismatch there would have been read as "the
  smoke test is flaky".
- Suite: 451 tests, 0 failures, exit 0; the reference gate stays green (the new
  test file is scanned by it).

### Added — Config-key reference gate (`mosip.liveness.*`)

- `FlywayMigrationReferenceTest` gains a third rule: the build fails when any
  owned file — doc, comment, code string or annotation — cites a
  `mosip.liveness.*` config key that `application.yml` does not declare and
  no config class declares with `@Value`. The key set is parsed from
  application.yml at test time (SnakeYAML), so renaming or removing a key
  turns the gate red wherever the old name is still cited, and adding one
  needs no gate edit. A service's own `@Value` counts as a citation rather
  than a definition, which keeps every backend key declared in
  application.yml or a config class.
- The `mosip.liveness.android.*` build-flag namespace is discovered from its
  declaration site instead: quoted key constants in the Android gate-policy
  package. Worth knowing: `mosip.liveness.android.allow-disable` is declared
  (`LivenessGatePolicy.BUILD_FLAG_ALLOW_DISABLE`) and cited by
  `docs/resource-compliance.md`, but nothing in this repository reads it and
  `android_client/` never references it — the gate verifies declarations,
  not consumption.
- Self-validated in-file with a concatenated dangling key, and red-proved end
  to end: a planted undefined-key citation turns the scan red with the
  offending line and the full defined-key list; removal turns it green again.
  Gate: 4 tests, 0 failures.

### Added — Spec-reference gate: checked-in specs + §-anchor rule

- `docs/references/` now carries the two liveness specs the repository cites —
  `orchestration.auth` (state machine, sequence diagrams, adapter contracts)
  and `analyse.md` (requirements analysis) — so section citations resolve
  against a checked-in source of truth instead of a document nobody has.
- `FlywayMigrationReferenceTest` gains a third rule beside the migration ones:
  every cited spec section anchor must match a heading of the checked-in
  specs. File-qualified citations (`orchestration.auth §5.2`,
  `analyse.md §5.4`) are validated against that spec's own heading set,
  extracted from the file at test time; bare anchors (`spec §4`, and every
  `§N` in `android_client/*.md` markdown) are validated against the union of
  both specs. Doc-internal anchors — citations of this repository's own
  design, guide and resource-compliance documents — are deliberately out of
  scope. The rule is self-validated in-file with concatenated dangling tokens
  and was red-proved end to end: a planted dangling citation turns the scan
  red with the offending line and the known heading list, removing it turns
  the gate green again.

### Added — Backend interoperability report (mock / ONNX / MediaPipe)

- `docs/backend-interoperability-report.md` — formal comparison of the three
  `LivenessBackend` implementations across the full test suite (measured
  baseline: 439 tests, 0 failures, exit 0): SPI profiles, an observed
  conformance matrix, per-backend suite attribution, six findings (uniform
  fail-closed init mapping to `DEVICE_CONNECTION_FAILURE`; the engine's
  face-count gate absorbing backend convention drift; PAD fidelity *not*
  interchangeable though the seam is; the two wiring paths that must stay
  aligned) and honest limitations.
- New `LivenessBackendInteroperabilityTest` (12 tests) executes one identical
  parameterized contract against all three backends: audit-safe stable and
  distinct ids (pinned to the report's verbatim values), clean-start-or-coded-
  refusal initialization, the full SPI conversation (scores and PAD confidence
  in `[0,1]`, no fabricated attacks on no-face frames, idempotent shutdown),
  and the engine's accept-iff-available constructor contract — MediaPipe's
  missing-TFLite refusal lands on `DEVICE_CONNECTION_FAILURE` like any absent
  device. ONNX is additionally driven through `FaceLivenessEngine` end-to-end
  on the genuine-face fixture. Availability is probed, never assumed, so the
  same contract holds on a host where TFLite is installed.

### Added — Diagnostic opt-in mode with local-only debug panel

- Closes the acceptance audit's last 🟡 for diagnostic mode ("opt-in raw-score
  UI mode not fully surfaced"). `mosip.liveness.diagnostics-enabled`
  (default off, `LIVENESS_DIAGNOSTICS_ENABLED`) turns on a fail-closed
  collector that retains exactly what orchestration spec §10 allows — raw
  passive scores, per-frame timings, FPS and the scorer delegate — in a
  bounded 120-frame ring with a sliding 5 s FPS window and an injected clock.
  Disabled, nothing is retained at all: an opt-in that silently buffered
  would be indistinguishable from always-on.
- `GET /api/v1/diagnostics` is the panel's read side with two independent
  fail-closed gates: opted in **and** loopback (judged through
  `ClientIpResolver`, so a local reverse proxy cannot launder a remote caller
  into `local`). Both failures return the same empty 404 — remote probes learn
  neither the mode's existence nor its state — and 200s are `no-store`.
- The test console gains the debug panel: hidden until the endpoint answers
  200, polling only while visible, showing delegate, FPS, last/median raw
  scores, avg/max frame ms and the per-frame table. No pixels anywhere: the
  input is the decision DTO, and a test serialises the snapshot to prove no
  frame-shaped payload can appear (spec §10 "still no pixels").
- 15 new tests: collector aggregation/window/ring/no-pixels
  (`DiagnosticsServiceTest`), endpoint gating (`DiagnosticsControllerTest`),
  loopback judgement incl. trusted-proxy chains (`ClientIpResolverTest`), and
  the frames hook (`FramesControllerTest`).

### Added — Desktop JavaFX LivenessChallengeOverlay (ui-ux-design.md)

- `client/LivenessChallengeOverlay` + `fxml/LivenessChallengeOverlay.fxml`
  (with `liveness-overlay.css`) implement the Desktop surface the design doc
  pointed at a stub for: preview slot (JavaFX ImageView path), oval
  positioning guide, and status / challenge / failure cards driven by the
  shared orchestrator events (`LivenessStateEvent` / `LivenessFinalResult`
  via `overlay.asListener()`) — challenge index "Challenge 1 / N", normalised
  progress (indeterminate until the combined score is known), the live
  feedback cycle continue → detected → hold, context-sensitive Retry (only
  `ATTEMPT_FAILED` / `RETRY_WAIT` / recoverable `DEVICE_ERROR`, never on
  terminal failure) + Cancel, the green PASSED oval accent, and the LOCK_OUT
  "Try again in Ns" countdown (design §1/§3/§4 state table).
- Every decision lives in the pure `LivenessOverlayPresenter`, verified by 27
  headless tests that walk each row of the design's state table, plus 3 FXML
  contract tests (`fx:id` ↔ `@FXML` parity, card structure, stylesheet) — the
  suite never needs a JavaFX toolkit. The default catalogue mirrors the
  Flutter `LivenessView.defaultMessages` string-for-string and a test parses
  the Dart source, so Desktop and Android cannot drift apart (design §6: same
  states, same keys on every surface); Desktop adds only the documented
  action/template keys (`liveness.action.*`, `liveness.challenge.index`,
  `liveness.lockout.retry_in`, `liveness.cancelled`,
  `liveness.device.disconnected`).
- JavaFX enters the build as `provided` scope and is explicitly excluded from
  both the full and `-slim` service jars (verified by inspecting the
  repackage output): the overlay runs inside the MOSIP Registration Client
  host, never in the backend.

### Added — Signed-manifest model activation (Android ModelStore glue)

- `SignedModelManifest` + `SignedManifestModelStore` close the gap the
  `ModelStore` javadoc promised ("sha256 + signature at the glue layer") but
  no implementation shipped: activation now requires a spec §13 manifest —
  `{modelId, version, sha256, minAppVersion}` under a SHA256withRSA vendor
  signature — whose digest binds to the exact payload bytes, followed by an
  atomic swap that keeps the previous version for `rollback()` after a failed
  health check. The bare unsigned `activate(modelId, version, payload)` path
  fails closed (returns false), and a configured app version enforces the
  manifest's `minAppVersion` gate (malformed versions refused). Without the
  signature a stored sha256 proved nothing: whoever can rewrite the model
  file can rewrite its recorded hash — the vendor signature anchors it.
- `android_client` gains `AndroidModelStore`: file I/O glue that loads a
  properties-format manifest + payload and delegates all verification to the
  engine store (security logic stays pure-JVM, so it is engine-tested).
- `SignedManifestModelStoreTest` — 10 tamper tests: mutated model bytes
  (previous model kept), manifest digest rewritten to match tampered bytes,
  mutated modelId/version/minAppVersion fields, flipped/odd-length/empty
  signature hex, wrong signing key, unsigned/null/empty/blank refusals, the
  min-app-version gate (newer → refused, malformed → refused, unconfigured →
  no gate), and spec §13 rollback.

### Added — Per-PAI-species APCER reporting (eval)

- `ScenarioReport` now carries a **per-species APCER breakdown**:
  `apcerBySpecies` maps each attack `PresentationLabel` to an immutable
  `SpeciesApcer` row (presentations, accepted-as-bona-fide, that species'
  APCER); `BONA_FIDE` is excluded because its error rate is BPCER.
  `AttackScenarioHarness.report()` groups its already-labelled results into
  those rows, the aggregate `apcer` stays the presentation-weighted mean of
  the breakdown (asserted), and `toString` prints it — e.g.
  `apcerBySpecies={PRINTED_PHOTO=0.0167 (1/60), SCREEN_REPLAY=0.0000 (0/60),
  VIDEO_REPLAY=0.0333 (2/60)}`.
- Closes the ISO/IEC 30107-3 gap in `docs/resource-compliance.md` §02
  (APCER was aggregate-only) — tracker G1 done; now 30 ✅ · 8 🟡 · 1 🔴.
- Tests: three new cases in `AttackScenarioHarnessTest` — full-run grouping
  with weighted-mean consistency, exact hand-built per-label math (1/3 for
  one species vs a 1/5 aggregate), immutability of the map, and zero-count
  species rows rendering 0.0 rather than NaN.

### Documentation — Android RC version pins + auto-logout interplay

- `android_client/README.md` now pins the Android stack **and verifies it
  against the live RC repo** (`mosip/android-registration-client`, `main`
  v1.1.1): Flutter 3.10.4 / Dart 3.0.3 (docs Technology Stack), the
  docs-SDK-31 vs repo-`compileSdkVersion 34` drift flagged, **`pigeon:
  ^10.0.1`** (the repo's own pubspec pin, so one generator version),
  **CameraX ≥ 1.3.0** (the glue uses `ResolutionSelector`, which exists only
  from 1.3.0; an SDK-31 toolchain falls back to 1.2.x + legacy
  `setTargetResolution`), and the **Java-11-vs-17 question resolved**: 11 is
  the *backend platform* figure, the RC app builds at Java 21 with
  desugaring (so the `--release 17` engine jar links and dexes), and the
  Spring Boot service itself needs JDK 17+ regardless.
- Documents the **auto-logout ↔ liveness interplay**: worst-case gate bounds
  (≤ retries × `maxSessionDurationMs` ≈ 150 s by policy floor; typical pass
  < 10 s), the host contract (suppress the inactivity timer between
  `startSession` and `onFinal`, refresh on every `onState`, timeout floor
  above the worst case), and the fail-safe (logout anyway → view-model
  dispose → `cancelSession` → ABORTED → gate fails closed — a lost retry,
  never a bypass).
- Closes the §05 version-pinning gap (tracker G3 done) and moves auto-logout
  to partial (tracker G4 in progress, RC-side pause wiring pending) — now
  29 ✅ · 8 🟡 · 2 🔴.

### Documentation — ISO 30107-1 injection threat model

- `design.md` gains a threat-model section for **digital injection attacks**
  (virtual camera, hooked camera API, frame replay into the pipeline) — the
  class ISO/IEC 30107-1 explicitly scopes out. Written in Part-1 vocabulary
  (**PAI**, **attack presentation**, **bona fide presentation**, PAD
  subsystem), it maps each `PresentationLabel` species to its PAI class, lays
  out the layered countermeasures (signed capture binding, per-attempt
  session/nonce binding, fail-closed pipeline, session-scoped rate-limited
  frame ingestion, keyed audit chain, model integrity), and states the
  residual risk — endpoint integrity is assumed at this layer, not enforced.
  (Sequence-diagram and thread-safety sections renumbered to §7/§8; no
  cross-references pointed at them.)
- Closes two rows in `docs/resource-compliance.md` §01 — the scope-boundary
  partial and the PAI-terminology gap — and marks tracker item G2 done
  (now 28 ✅ · 7 🟡 · 4 🔴).

### Added — Measured frame-rate fallback

- `FrameRateMeter` measures the rate frames **actually arrive at** the
  frame-source boundary (a clock-injected, 5 s sliding window over a wrapped
  `FaceFrameSource.Listener`) instead of trusting the configured `maxFps`
  ceiling advertised in `SourceCapabilities`.
- The Android orchestrator consults the measured rate whenever the engine
  escalates: below 5 fps — and for any rate that cannot be proven — BLINK is
  excluded from the challenge draw, falling back to
  `SMILE` / `TURN_HEAD_LEFT` / `TURN_HEAD_RIGHT`.
  `FaceLivenessEngine.requestChallenge(sessionId, excludedTypes)` applies the
  exclusion (recording it in the `CHALLENGE_ISSUED` audit event) and never
  lets the pool go empty: excluding every allowed type falls back to the full
  pool. Closes the "3 fps minimum → adaptive fallback" gap in
  `docs/resource-compliance.md` §06 (now 26 ✅ · 8 🟡 · 5 🔴).
- Tests: `FrameRateMeterTest` (rate math, window pruning, listener wrap),
  `FrameRateFallbackTest` (a measured 4 fps stream excludes blink at issuance
  and never prompts it; a healthy burst keeps the full pool; boundary table
  incl. unmeasurable-rate-fails-closed), plus engine-level pool-exclusion and
  empty-pool-guard tests in `EngineEscalationTest`.

### Fixed — Liveness evidence binding

- `AndroidLivenessOrchestrator` now records the sha256 of each attempt's
  best-scoring frame into `LivenessEvidence.bestFrameSha256` (spec §8
  `pass()` evidence field, analyse.md §5.4 binding). The field was declared
  and signed but never assigned, so every evidence record carried a null
  hash and the downstream `consistency(bestFrame, signedCapture)` check had
  nothing to bind to. The hash resets with the nonce on every retry, so
  evidence can only ever reference frames from the attempt it certifies.
- `LivenessEvidenceBindingTest` (7 tests) proves the binding end to end: the
  evidence's `bestFrameSha256` + nonce verified against a downstream signed
  capture payload (spec §8 `bindingOk = isGateValid && consistency(...)`),
  with tampering rejected — foreign-frame capture under a valid signature,
  forged capture signature, evidence edited after signing (frame hash or
  nonce), stale-nonce replay, capture bound to a foreign session, and an
  expired gate window. The happy path recomputes the frame digest with an
  independent SHA-256 to pin the hash to actual pixel bytes.

### Added — Build hygiene

- `FlywayMigrationReferenceTest`, a self-maintaining gate that fails the suite
  when any owned file cites a Flyway migration that does not exist in
  `db/migration` — the class of drift where a comment keeps citing a migration
  the directory never had (the audit-chain javadoc and a test comment pointed
  at a number the migration set had left behind) while every compiler and test
  stays green. The gate discovers the migration set from the directory at
  runtime instead of pinning a list, recognizes exact-filename, javadoc
  code-span and prose citations, and self-validates by planting a dangling
  citation. Rides the Unit CI job unchanged.

### Added — Android liveness gate (Face Liveness + PAD orchestration)

- New `io.mosip.liveness.android` package implementing the Android
  Registration Client liveness orchestration on top of the shared engine
  pipeline, so Resident/Operator/Supervisor flows behave identically on
  Desktop and Android. The orchestrator owns the state machine
  (IDLE → INITIALIZING → POSITIONING → PASSIVE_EVALUATING → challenge states
  → PASSED / ATTEMPT_FAILED / RETRY_WAIT / TERMINAL_FAILURE / DEVICE_ERROR /
  ABORTED), attempt/retry counting, per-attempt nonces, gate validity windows,
  and signed evidence — while every frame is still scored by the same
  `FaceLivenessEngine` decision logic the REST service uses.
  Spec invariants pinned by tests: device errors and positioning hints never
  consume the retry budget; every retry gets a fresh engine session, nonce and
  challenge sequence; a PASSED gate is valid for a bounded window only
  (`isGateValid` fails closed after expiry); a missing/invalid model fails
  closed; an evidence-signing failure withholds evidence instead of handing
  out an unsigned record; PAD failures surface only the generic
  `liveness.pad.generic` key to the UI.
- `AndroidLivenessPolicyProvider` resolves per-role policy from the shared
  `WorkflowPolicyDefaults` table and then clamps to a hard security floor in
  code (threshold ≥ 0.60, retries ≤ 5, challenge window 3–20 s); disabling
  liveness via config is refused and audited unless the
  `mosip.liveness.android.allow-disable` build flag is set.
- Vendor-neutral `FaceFrameSource` SPI with a deterministic
  `MockFaceFrameSource` (spec §11 vendor independence; the same suite runs
  against mock + CameraX adapters).
- `android_client/` integration tree: Pigeon API definition
  (`pigeon/liveness.dart`), Flutter `LivenessViewModel`/`LivenessView`
  (i18n-key rendering, capture enabled only on PASSED), and the Android-only
  glue — `CameraXFaceSource` (keep-only-latest, ≤640x480 analysis),
  `AndroidKeystoreEvidenceSigner` (non-exportable RSA-2048 evidence key), and
  the `LivenessPigeonBridge` host-api sketch (frames never cross the channel —
  preview is a Flutter Texture).
- `AndroidLivenessOrchestratorTest` (13 tests) drives the full gate against
  the mock backend: passive pass, escalation to an engine-selected challenge
  (performed prompt-by-prompt, never user-chosen), PAD block, device-error
  neutrality, gate expiry, evidence signing and the policy floor.

### Added
- The config audit chain's hashes are now **keyed** (`AuditChainKey`), so a
  database writer without the active audit secret can no longer rebuild the trail: with
  `MOSIP_AUDIT_HMAC_SECRET` set, `entry_hash` becomes
  **HMAC-SHA-256** over the same canonical form instead of plain SHA-256, and
  the secret lives in the app server's environment rather than in the database.
  The canonical form is untouched, so this is a construction change, not a
  format change — which is what keeps the two modes comparable.
  Previously the chain stopped at the honest ceiling: anyone holding a database
  connection could read the stored hashes and recompute every link, producing a
  chain that verified perfectly on its own. `AuditChainTamperTest` now stages
  exactly that attack — edit a row, then recompute the entire trail publicly —
  and pins that it verifies under the fallback key yet breaks at the **first
  entry** under the real secret. Verified by mutation: ignoring the secret
  outright fails that test.
  Three decisions, each visible rather than silent:
  - **A secret under 16 characters fails at startup.** A guessable key
    produces keyed-looking hashes an attacker recomputes just as easily, so
    failing fast beats shipping a false sense of security. The exception names
    the setting to fix.
  - **No secret falls back to the original SHA-256**, so a fresh deployment
    boots with no configuration and chains written before the key existed stay
    verifiable. The fallback is *reported*, not implied:
    `GET /api/v1/config/audit/verify` gained an `hmac` field, so an operator
    reading `intact: true` can see whether that verdict came from a keyed chain
    (`hmac: true`) or a tamper-detecting one (`hmac: false`).
  - **Verification uses the same key as writing, and never falls back to
    accepting.** A different or missing secret reports every entry as broken
    at index 0 rather than quietly downgrading to a weaker check — unless the
    previous key is explicitly configured for a keyed rotation. A test pins the
    hard break with no overlap, with untouched rows still verifying under the
    original key.
- Keyed chains can verify historical rows with
  `MOSIP_AUDIT_HMAC_PREVIOUS_SECRET` while new application writes use the
  current `MOSIP_AUDIT_HMAC_SECRET`. `/api/v1/config/audit/verify` reports
  `rotationWindowOpen` (previous key configured) and `retiredKeyHashes`
  (historical entries still need it); the console shows intact, broken, and
  open-window states. The JPA round-trip test proves old and new hashes verify
  after reload. **Limit:** audit rows are immutable, so the app cannot re-key
  history; keep the previous key while any hashes need it. A trusted archival
  or re-key protocol is required to retire it.
- Every `CONFIG_CHANGED` audit entry now carries a **risk classification**
  (`details.risk`: `HIGH` or `LOW`, plus the named weakening moves).
  An edit that lowers `passiveThreshold` or switches `livenessEnabled`
  or `activeLivenessEnabled` off is flagged `HIGH`; everything else is
  `LOW`. The console's **Policy change history** panel shows the level
  on every entry and lists the reasons above the field diff, so a change
  that quietly weakens PAD reads as an alert rather than a diff to be
  hand-checked. Entries written before the classification have no `risk`
  key and render an em dash. Because the classification is computed over
  the diff and sealed inside the hashed `details` payload, it is covered
  by the tamper-evidence below.
- Config audit entries are now **immutable and tamper-evident**. Each
  `CONFIG_CHANGED` row stores `prev_hash` (the previous entry's hash, `GENESIS`
  for the first) and `entry_hash = SHA-256(prev_hash ‖ canonical(entry))`
  (`V9__audit_chain_immutability.sql`), so editing a row invalidates its own
  hash and deleting one leaves every successor pointing at a hash that no
  longer follows. Immutability is enforced by the **database**, not by Java:
  `BEFORE UPDATE` / `BEFORE DELETE` triggers that raise, because a Java-side
  rule is bypassed by anything holding a connection. Those triggers also fire
  for the `ON DELETE CASCADE` from `liveness_sessions`, so deleting a session
  that has audit rows now fails rather than silently erasing the evidence —
  nothing in the application deletes sessions, so that is now enforced instead
  of assumed.
  Three subtleties the implementation had to get right, each caught by a test
  rather than by reading:
  - `details` round-trips through a converter that rebuilds it as a plain
    `HashMap`, so hash order is **not** stable across a database round-trip.
    Hashing what came out of the map would fail every verification for reasons
    unrelated to tampering. The payload is canonicalised first — keys sorted
    recursively, every component **length-prefixed** so a value containing the
    separator cannot be re-split into different fields.
  - `id` is assigned by Hibernate at INSERT, so it is null at hash time and the
    canonical form would change the moment the row lands; it cannot be hashed
    afterwards either, because the new trigger rejects the UPDATE. `id` is
    therefore deliberately excluded — chain position plus `prev_hash` already
    binds an entry uniquely.
  - The chain is walked in `created_at` order, so two edits inside one
    millisecond would make a perfectly intact trail fail. Timestamps are
    nudged forward by 1 ms rather than left ambiguous: a false alarm on a
    security control is its own kind of bug.
  Read-head and write-head happen under one lock, so two concurrent admin
  requests cannot both chain onto the same predecessor and fork the trail.
  `GET /api/v1/config/audit/verify` walks the whole chain (never a page — a
  break past the page boundary would read as "intact") and reports the first
  break, distinguishing an **edited** row from a **deleted** one, since the
  remedy differs. Verified live: five consecutive PUTs chain and report
  `intact: true` with a head hash.
  **Ceiling, stated plainly:** this detects corruption and edits by anyone who
  does not rebuild the chain — including the admin-key holder working through
  the API, which is the realistic threat here. It does **not** stop an attacker
  with database write access, who can read the stored hashes and recompute the
  chain. Closing that means keying the hash with a secret the database does not
  hold (HMAC, secret from the environment) or anchoring the chain head where a
  database writer cannot reach. The hash is now **keyed** when
  `MOSIP_AUDIT_HMAC_SECRET` is set — see the entry below.
- `audit_logs` gained a `workflow_type` column (`V8__audit_logs_workflow_type.sql`),
  so `GET /api/v1/config/audit` can be filtered **in the database** with
  `?workflowType=`. The audit table grows one row per frame decision, so
  narrowing in the browser meant paging through the entire pipeline history to
  answer a policy question; the feed now has a purpose-built finder
  (`findByEventTypeAndWorkflowTypeAndSessionIsNullOrderByCreatedAtDesc`) served by
  a new `idx_audit_logs_config_feed(event_type, workflow_type, created_at DESC)`.
  The migration backfills existing rows twice — from `liveness_sessions` for
  pipeline events, and from the `details` JSON for session-less `CONFIG_CHANGED`
  rows, which is the only place the old history recorded the workflow — so
  nothing already logged becomes unfilterable. The column is nullable: an entry
  that cannot be resolved stays NULL rather than being guessed at.
  The value stays in `details.workflowType` as well; the column is for indexing,
  not a replacement for the audit payload. `DecisionEngineService` populates it
  too, so pipeline rows are not left NULL and any future "everything for
  OPERATOR" query covers the whole trail. `workflowType` is now a first-class
  field on `AuditLogEntry`. An unknown workflow is a **400**, so a typo cannot
  silently return the unfiltered feed and read as "this workflow has no
  history". The console's Policy change history panel gained a workflow dropdown
  that pushes the filter to the server and reloads on change.
- Fixed: the console's Operational metrics panel rendered the new
  `pipelineTimings` object as `[object Object]`, because the generic key/value
  renderer stringified it. Nested objects now render one indented line per
  phase (`decode: 21.7 ms mean · 159.7 ms max · n=12 (6.3%)`), with
  `white-space: pre-wrap` on the value cell so the lines survive.
- The rate limiter's clock is now injectable (`java.time.Clock`, via an
  `ObjectProvider` so a `@WebMvcTest` slice without `AppConfig` still gets real
  time), with a `rateLimitClock` bean in `AppConfig`. Window rollover can
  finally be tested by moving time instead of sleeping out a 60-second window:
  three new `RateLimitFilterTest` cases drive a mutable clock to prove the
  session-create window resets, the frame window resets on its own clock, and
  the one-arg constructor still uses real time.
- The integration suite no longer shares a rate-limit budget with itself.
  Every test in a class runs in one process from one address, so the production
  30 sessions/min was being consumed by the suite and failing tests with 429s
  depending on order. `@TestPropertySource` now raises both budgets
  (test-only), and `rateLimitBudget_isPublishedOnLimitedPosts` asserts the
  *configured* limit instead of the literal `"30"`/`"60"` — `RateLimitFilterTest`
  is where the real defaults stay pinned, which is the correct layering.
  **Parallel execution was attempted and reverted.** The blocker is not the
  limiter: nearly every integration test first PUTs the shared RESIDENT policy
  to stage its scenario, so concurrent methods overwrite each other's setup.
  `@Execution(CONCURRENT)` failed 3/3 runs —
  `configChange_isTraceableInTheConfigAuditView` saw `CREATED` where it expected
  `UPDATED` because another test had re-created the policy meanwhile — and the
  class took ~75 s sequentially and ~75 s in parallel, so there was no wall-clock
  win to trade the risk for. Real parallelism needs per-test policy isolation.
- A PostgreSQL schema gate (`PostgresSchemaTest` + a `schema` CI job) so
  entity/migration drift fails the build instead of hiding. Every other test
  runs on H2 with `ddl-auto: create-drop`, which means Hibernate builds the
  schema *from the entities* and Flyway never runs — so a column an entity
  declares but no migration creates, a column too narrow for its values, or an
  enum stored as a number would stay green in CI and break in production, which
  is PostgreSQL, the one database never exercised. The test boots the real
  application against a real PostgreSQL with the **production** datasource
  configuration, lets Flyway build the schema, and has
  Hibernate `validate` compare it against the entities, then round-trips a row
  to prove the mapping — including `OffsetDateTime` against `TIMESTAMPTZ`, the
  classic H2-passes/Postgres-fails difference.
  Two guards keep it honest: it asserts the JDBC product really is PostgreSQL
  and that `ddl-auto` really is `validate`, so it cannot silently pass on H2.
  Testcontainers starts an isolated PostgreSQL 16 locally and in CI; the schema
  suite is excluded from the H2 unit job and runs in its dedicated CI job. A
  local run passed all four schema checks. Testcontainers 1.21.4 is pinned
  because the Spring Boot 3.3.3 managed 1.19 client requests Docker API 1.32,
  which recent Docker daemons reject.
- Micrometer timers around every phase of the per-frame decision path
  (`decode`, `facedetect`, `onnxscore`, `heuristicscore`, `padheuristic`,
  `paddonx`, plus a wall-clock `total`), surfaced as a `pipelineTimings` block
  on the existing `GET /api/v1/metrics` with count / mean / max / p99 and each
  phase's share of the measured phases. Only `micrometer-core` was added
  (version managed by the Spring Boot parent, resolving to the same 1.13.3
  already on the classpath as `micrometer-observation`) — not
  `spring-boot-starter-actuator`, because the service already has its own
  metrics surface. Static holder by design: `ImageUtils` and the scoring
  services are constructed directly in unit tests as well as by Spring, so
  constructor injection would have meant threading a registry through every
  test for no benefit. Phases that never ran are **omitted** rather than
  reported as zero, so "fell back to the heuristic" is distinguishable from
  "never scored".
  **What it revealed** — measured over 12 frames of the real-face fixture,
  ONNX is 70% of pipeline time and the frame is detected *three* times:
  mean per frame is `decode` 21.7 ms, `facedetect` 74.5 ms, `onnxscore`
  126.5 ms, `paddonx` 116.9 ms, `padheuristic` 4.8 ms, request `total`
  442.0 ms. `PassiveScoringService.score()` and `assessPad()` each call
  `model.analyzeFrame(...)`, and that method runs its **own** Haar cascade
  internally (`OnnxMiniFasNetBackend.detectFaces`), so one frame pays for the
  HTTP face gate plus two more cascades and two BGR→RGB conversions on the same
  pixels. `paddonx` costing nearly as much as `onnxscore` is the duplicate work
  showing up in the numbers. The phases also do not sum to `total`: the ~98 ms
  gap is JSON, the session `findById`/`save` and the audit write
- `-Pslim` Maven profile producing a ~92 MB linux-x86_64 jar (down from
  ~209 MB) that still runs the full liveness/PAD pipeline. `org.openpnp:opencv`
  ships natives for eight platforms (109 MB) and `onnxruntime` for four plus a
  54 MB macOS `.dSYM`; a Linux runner and the Docker image load exactly one of
  each. The profile unpacks both jars into `BOOT-INF/classes` with the unused
  platform trees excluded, because spring-boot's `<excludes>` can only drop a
  whole dependency and cannot filter *inside* one. Note the trap it avoids:
  excluding the opencv dependency outright does **not** slim the app, it breaks
  it — the jar holds the Java bindings as well as the natives, so Spring cannot
  introspect beans whose signatures mention `Mat` and startup dies with
  `NoClassDefFoundError: org/opencv/core/Mat`. Removing natives while keeping
  the bindings is the only slimming that is actually safe. The profile also has
  to pin `<mainClass>`, because with a `<classifier>` set spring-boot 3.3.3
  auto-detects `Start-Class: ...loader.launch.JarLauncher`, making the loader
  re-launch itself until `StackOverflowError`. Opt-in only; the default build
  is byte-for-byte unchanged and still carries all seven natives
- Rate limiting (`RateLimitFilter`, no new dependency): session creation is
  capped per client IP (30/min) and frame + challenge-validate submissions
  share a per-session budget (60/10s) — over-limit POSTs get 429 + Retry-After
  before any body parsing or DB work; configurable via
  `mosip.security.rate-limit.*`
- Terminal PAD rejection proven end to end: new committed fixture
  `fixtures/print-attack.jpg` (15×15-blur degradation of `real-face.jpg` —
  one face still detectable, texture heuristic classifies it PRINTED_PHOTO and
  the MiniFASNet model independently flags it as an attack) plus
  `PrintAttackFixtureTest` (fast fixture contract) and a
  `LivenessPipelineIntegrationTest` case that pushes two attack frames over
  real HTTP and asserts the `retry_passive` → confirmed `reject` flow, the
  `presentation_attack:PRINTED_PHOTO` failure reason, the `PAD_REJECTED` audit
  entry, and terminality (a third frame gets 409)
- Browser console can now edit the config policy: a per-workflow policy editor
  (threshold, challenge timeout, min challenges, retries, failure policy,
  enabled flags, challenge types) that loads via the open `GET` and saves via
  admin-key `PUT` — the key lives in the tab's sessionStorage, is masked, and is
  never written to the decision log
- GitHub Actions CI (`.github/workflows/ci.yml`) — runs on every push as **two
  parallel jobs**: unit tests (253, surefire
  `!LivenessPipelineIntegrationTest,!PostgresSchemaTest` filter) and the
  full-app integration suite (10), each with Temurin JDK 17,
  Maven dependency cache, `fail-fast: false` and a least-privilege token; a
  gated third job then packages the Spring Boot executable jar (only when both
  suites pass) and uploads it as the `pad-liveness-backend-jar` artifact
  (7-day retention). The upload step is additionally gated on the push ref
  matching the repository's default branch, so feature-branch pushes still
  prove the jar packages but no longer spend the shared artifact quota on
  commits nobody downloads. The package job now also **boots the jar it just
  built** and asserts `GET /health` returns `engine: available` before anything
  is published — on every branch, not just the default one. The test jobs run
  against `target/classes` with the full `~/.m2` classpath, so nothing could
  catch a packaging-only regression (fat jar missing its OpenCV natives, wrong
  `Start-Class`, truncated repackage): those all shipped green and only broke
  for whoever ran the artifact. `/health` always answers `status: ok`, so the
  200 proves nothing on its own — the assertion is on `engine`, which reflects
  whether the natives actually loaded. Pushing a **version tag (`v*`) now also
  publishes that same jar to a GitHub Release**: the `release` job
  re-packages **with the tag's version stamped in** — `v1.0.1` builds
  as `1.0.1`, so the jar's manifest and the asset name match the
  release instead of reading `1.0.0-SNAPSHOT` — then smoke tests it
  and attaches it with `gh release create` (falling back to
  `gh release upload --clobber` when the release already exists, so a re-pushed
  tag converges instead of failing). It carries `contents: write` scoped to
  that one job — the rest of the workflow stays read-only — and authenticates
  with the automatic per-run `GITHUB_TOKEN`, so no secret must be configured.
  The per-push artifact upload still fires on tag pushes (its guard
  includes `v*`), and the release job builds its own stamped copy —
  CI jobs have no shared filesystem, so the two never exchange files.
  `--notes-start-tag` is deliberately omitted because it errors when there is
  no previous release, which would make the very first tag unpublishable.
  The smoke test moved
  from inline YAML into `scripts/smoke-jar.sh` so both jobs share it and it
  stays runnable locally
- `LivenessPipelineIntegrationTest` — boots the full app with H2 (dev profile)
  on a random port and drives the real pipeline over HTTP with no mocked beans:
  passive pass → PASSED, below-threshold escalation → challenge issued →
  validation stays `continue` inside the 15s window, active-disabled reject →
  FAILED, undecodable frame → 422, plus admin-key config updates and security
  headers on real responses
- The browser's policy-save path is now covered end to end:
  `browserStyleConfigPut_isAcceptedWithTheAdminKey` sends a real preflight
  (`Origin` + `Access-Control-Request-*`) and the console's own same-origin PUT
  with Chrome headers and `X-Admin-API-Key`, then asserts the preflight allows
  the admin header, the PUT is applied *and* audited, the same request without
  the key is 403, and a foreign origin is 403 with no policy change. It needed
  the JDK HTTP client rather than `TestRestTemplate`: `HttpURLConnection`
  silently strips `Origin` and `Access-Control-Request-*` as restricted
  headers, so a preflight written that way is indistinguishable from a plain
  same-origin OPTIONS and the test would pass without exercising CORS at all
- Config changes are now audited and reviewable: every successful
  `PUT /api/v1/config/{workflowType}` writes a `CONFIG_CHANGED` entry **in the
  same transaction as the policy update**, recording only the fields that
  actually moved with their old → new values, `CREATED` vs `UPDATED`, and a
  truncated SHA-256 fingerprint of the admin key (never the key itself).
  Rejected requests (no/invalid key, invalid value) record nothing, so a saved
  policy can never be silent and a rollback can never leave a phantom entry.
  Previously a policy edit was entirely untraceable — `audit_logs.session_id`
  was `NOT NULL`, so an operator-level event could not be recorded at all.
  New `GET /api/v1/config/audit?limit=50` (newest first, open read like the
  other config GETs) plus a **Policy change history** panel in the browser
  console, refreshed automatically after each save. Migration V4 makes
  `audit_logs.session_id` nullable (`NULL` = operator event, never a pipeline
  event) and indexes `created_at`; session trails query by session id, so they
  can never pick one up
- Challenge-window timeout proven end to end: a real-time
  `LivenessPipelineIntegrationTest` case configures a 1s challenge window with
  `maxRetryCount=2` (so the engine's floor is what must keep the challenge
  open), waits out both windows, and drives `continue` → `retry_challenge`
  (fresh challenge, attempt 2, different action) → terminal `reject` with
  `max_retries_exceeded`, asserting the paired CHALLENGE_ISSUED/
  CHALLENGE_FAILED audit entries, the FAILED session and close summary, and a
  409 when the failed challenge is re-validated

### Changed
- The console's **Policy change history** now loads with the page instead of
  only after clicking refresh, so the most recent edit — its key fingerprint,
  risk level and field diff — is on screen when the console opens. Previously
  the fingerprint and the risk badge were rendered but hidden behind a click,
  which meant the panel an operator was meant to check before saving was the
  one they had not yet loaded. The load is the same open `GET` as the policy
  read (no admin key needed) and a failure only writes the status line, so a
  history outage cannot block editing.
- **Release assets carry the tag version.** `v1.0.1` now builds as
  `1.0.1` — the pom switched to Maven's CI-friendly `${revision}`
  property (default `1.0.0-SNAPSHOT`, so every other build keeps its
  familiar artifact name), overridden only in the release job. The job
  asserts the stamp landed, because a silent miss would still build
  green and publish a SNAPSHOT-named asset. The attach step's
  `ls | grep` also became a nullglob loop, clearing the last
  actionlint warning
- **CI now builds, boots and publishes both jars.** The package job is a two-leg
  matrix: the full cross-platform jar (~200 MB) and the slim linux-x86_64 one
  (~94 MB). Both legs boot and must report `engine: available` — that is what
  catches a dependency bump which quietly breaks the slim profile's native
  filter, which otherwise ships a jar that boots fine and then rejects every
  frame. The run summary reports both sizes, and `scripts/check-jar-size.sh`
  fails the build when a jar outgrows its budget (245 MiB full, 110 MiB slim):
  a jar that quietly re-adds a platform breaks no test, it just doubles
  artifact storage.
- **Artifact names follow the primary download.** `pad-liveness-backend-jar` is
  now the **slim** jar — it is what CI runners, the Docker image and most
  deployments run, and it is the small one. The cross-platform jar moves to
  `pad-liveness-backend-full-jar`, one click away for macOS/Windows consumers.
  Each leg writes its own name because upload-artifact v4 treats a name as
  immutable across jobs: a shared name fails the second leg with 409.
- **Uploads also fire on `v*` tags** (the default branch used to be the only
  publisher), so a release tag gets a versioned artifact independent of which
  branch is the default. `pull_request` is now a trigger as well: branches run
  the identical gate before merging, and the upload guard keeps PRs — forks
  included — out of artifact storage.
- **The Docker image is built from the slim profile**, and the jar is copied
  with `--chown` rather than a `chown -R` layer that stored the same file twice:
  **620 MB → 294 MB** of layers, measured. The COPY is pinned to the `-slim`
  classifier because a slim build leaves the thin jar beside the repackaged one,
  which made the old `*.jar` glob ambiguous. `scripts/smoke-docker.sh` builds the
  image, boots the container and asserts `/health`, the unprivileged user, and
  reports the layer total — the image path had no coverage at all before, so a
  broken `COPY` or base image shipped green.
- `scripts/smoke-jar.sh` takes `SMOKE_JAR=full|slim` (default `full`), selects
  with globs instead of `ls | grep`, and says which jar it is about to boot.
  Its container sibling asserts `status: ok` rather than `engine: available`:
  Alpine is musl and the OpenCV native is glibc-linked, so a correct image serves
  the API with frame processing unavailable by design.
- Rate-limited requests are now counted per rule and reported by
  `GET /api/v1/metrics`: `rateLimitedRequests` (total refused) with the split
  `rateLimitedSessionCreate` / `rateLimitedFrames`, plus
  `rateLimitAllowedRequests` and `rateLimitRejectionRate`, so an operator can
  see the limiter biting (and which rule bites) from the API instead of container
  logs. The tallies live in a new `RateLimitCounters` component rather than on
  the filter, because a mocked `Filter` bean inside a `@WebMvcTest` slice is
  auto-registered as a filter and swallows every request before it reaches a
  handler — and because the metrics controller should not depend on servlet
  plumbing. These counters are per-process: they start at zero on restart,
  unlike the session metrics in the same payload, which are queried from the
  database; `rateLimitRejectionRate` is `0.0`, never `NaN`, with no traffic
- Rate limiting now works behind Docker port-mapping and reverse proxies:
  `mosip.security.rate-limit.trusted-proxies` (`TRUSTED_PROXIES`, comma-separated
  IPs/CIDRs, **default empty = trust nobody**) lists the peers whose
  `X-Forwarded-For` / `X-Real-IP` may be believed when keying the per-IP session
  budget. Forwarded headers are honoured *only* from a trusted peer, and the
  chain is walked right-to-left so the first untrusted hop is the client — a
  client cannot mint extra budgets (or poison someone else's) by sending the
  headers itself, which is why the default is empty and why listing a range
  containing untrusted addresses is called out as unsafe. Before this, every
  client behind NAT shared one budget, the ceiling documented on the filter.
  Hops may carry a port (`10.0.0.1:51234`) or bracketed IPv6 (`[::1]:443`); junk
  and hostname entries are ignored rather than resolved (no DNS in the request
  path). Frame budgets stay keyed by session id
- The rate limiter now **publishes its budget** instead of only failing: every
  limited route returns `X-RateLimit-Bucket` (`session-create` / `frames`),
  `-Limit`, `-Remaining` and `-Reset` (seconds into the window) on *allowed*
  requests as well as 429s, so a client can pace itself rather than discover the
  limiter through a failure. They are added to CORS
  `Access-Control-Expose-Headers` for a console on another dev port, and
  `Retry-After` stays exclusive to the 429
- The console gained a **Request budget** panel: a live meter per limiter budget
  showing how many submissions remain and when the window resets, counting down
  locally every second (no polling), going amber at ≤25% and red on a 429 with
  its retry time. Verified end to end in a browser against a running service,
  including burning the whole session-create budget to watch it trip
- The console's policy editor no longer loses unsaved edits silently. Loading a
  workflow replaced every field, so switching workflow (or pressing Load)
  threw away a threshold the operator had just tuned with no warning. The form
  is now compared against the last loaded/saved state: an amber "Unsaved changes
  to the \<workflow\> policy" marker appears while edits are pending (and clears
  again if a field is reverted by hand), and switching workflow, Load and
  closing/reloading the tab all confirm first — cancelling snaps the workflow
  select back so the UI matches what is actually loaded. The guard fails closed
  if `confirm()` is unavailable (embedded webviews that block dialogs): the edits
  are kept rather than dropped
- The console no longer leaves the admin key sitting in the page: it used to be
  written straight into the input's value on every load, so the browser's
  password masking was the only thing between the secret and a screenshot, a
  screen share or devtools. The key now lives only in this tab's sessionStorage;
  the field is a typing buffer that is cleared on blur and after every save, a
  hint shows a partial mask (`••••••••••••-9f3`, the same idea as the audit
  trail's key fingerprint) so the operator can still tell which key is loaded,
  and **Forget key** removes it from the tab. Saving is unaffected — the key is
  sent from the stored value, verified against a live server
- The challenge-window floor is now `mosip.liveness.min-challenge-window-ms`
  (default **15 000** — unchanged for production, and hard-clamped to 1 000 ms,
  the smallest `challengeTimeoutMs` the config API accepts). It is the guard
  against a shortened or legacy policy row failing someone who needed a moment,
  so raising it only ever makes the flow more patient. Lowering it is a test
  seam: the e2e timeout case now runs 3 s windows instead of 2 × 15 s, cutting  that test 34.4 s → 10.0 s and the integration class 54 s → 30 s
  (93 s → 70 s in a cold, isolated CI-job run) with identical
  assertions. The 15 s default and
  the 1 s clamp stay pinned by `DecisionEngineServiceTest` (3 new
  cases, no sleeps). Everything else the class spends is real work —
  ~15 s of OpenCV face detection and ONNX scoring across 25 frames
  plus a ~37–43 s Spring context load (per-phase breakdown in the
  Performance section below);
  Flyway cannot replace that DDL on H2 because the migrations are
  Postgres-specific (`TIMESTAMPTZ`), so dev/integration boots keep
  `ddl-auto: create-drop`
- OpenCV (and the MiniFASNet model) now load **lazily on first use** — no boot
  step waits for either. `PadLivenessApplication.main()` still starts an
  `opencv-warmup` daemon thread before `SpringApplication.run()` so the ~65 MB
  extraction overlaps context refresh, and `PassiveScoringService` resolves its
  scorer (natives + model) on its first caller instead of in its constructor;
  `ensureOpenCvLoaded()` remains the synchronous barrier — it loads at most once
  and blocks only if the background attempt has not settled by the time a frame
  arrives, which is the rare case (a boot that reaches a request takes longer
  than the load). `isOpenCvAvailable()` (a plain non-blocking read) is therefore
  eventually consistent at boot: `/health` reports the engine as unavailable
  until the warm settles — accurate, and what the smoke test waits for.
  `openCvAttempted` is volatile since the fast path is now read across threads.
  **Measured earlier for the overlap-only variant, and the headline claim did
  not hold.** Seven interleaved A/B boots per variant on the same box: baseline
  mean 46.47 s / median 46.02 s, warm-up mean 47.25 s / median 48.14 s —
  indistinguishable. The timeline shows the load *does* leave the critical path
  (baseline occupies +35.5 s → +41.5 s, warm +0 s → +4.8 s), but the warm
  thread costs ~4.5 s in CPU contention with the main thread on 4 cores, netting
  ~1.5 s in the best pair. The original "~6 s" figure came from a cold page
  cache; warm, the load is only ~3.8 s and there is nothing to hide. It may
  still pay off on cold-cache CI runners, which could not be verified here
  (dropping caches needs root). Kept because it is behaviour-neutral and correct,
  not because it is proven faster.
  Two findings worth keeping: logback **silently drops** events logged from the
  warm thread, because it starts before Spring Boot initialises its logging
  system — the JVM's own fat-jar class-loading can outlast the entire native
  load, so the attempt may settle before `run()` even begins. The outcome is
  therefore reported from the main thread: from `init()` once Spring is
  configuring beans (the common case — the line lands ~16 s before
  `Started PadLivenessApplication`), or, if the attempt is still in flight, by a
  short-lived daemon that joins the warm-up thread and reports the moment it
  settles; `ensureOpenCvLoaded()`'s caller is the last resort on the first
  frame. The warm thread itself never logs. The outcome line carries the
  measured millisecond cost.

### Security
- `PUT /api/v1/config/{workflowType}` now requires an `X-Admin-API-Key` header
  matching `MOSIP_ADMIN_API_KEY` — fail-closed: config updates are refused when
  no key is configured, so unauthenticated callers can no longer lower the
  liveness threshold and defeat PAD
- Added `SecurityHeadersFilter`: `X-Content-Type-Options`, `X-Frame-Options`,
  `Referrer-Policy`, `Cross-Origin-Opener-Policy`, `Permissions-Policy` and a
  strict `Content-Security-Policy` (`script-src 'self'`, `style-src 'self'`,
  `frame-ancestors 'none'`) on every response (Swagger UI exempt from CSP only)
- POST/PUT bodies over `MAX_REQUEST_BODY_BYTES` (default 24 MB) are rejected
  with 413 before reaching a controller; frame DTOs gained `@Size` caps
- Frame decoding now enforces payload and dimension caps (decompression-bomb
  guard) and releases leaked `Mat` buffers on the error paths
- Fixed CORS: `allowedOriginPatterns` (the previous `allowedOrigins` wildcard
  ports never matched any real origin), methods limited to GET/POST/PUT,
  credentials off
- Python service: replaced wildcard `allow_origins=["*"]` + credentials with an
  origin allow-list (`settings.CORS_ORIGINS`), added the same security headers
  and a body-size cap
- Containers: image runs as non-root, JVM capped (`MaxRAMPercentage=50`,
  `UseSerialGC`, `ExitOnOutOfMemoryError`), PostgreSQL published on loopback
  only; `server.error.include-stacktrace/message: never`

### Fixed
- Invalid path parameters (e.g. a non-UUID `sessionId`) returned 500 — now 400
  `VALIDATION_ERROR` via `MethodArgumentTypeMismatchException` handling
- `DeviceAdapterTest` race: `mockDeviceCorruptFrames` and
  `mockDeviceLifecycleAndFrameDelivery` asserted exact event counts after their
  latches opened while capture keeps producing events until `stopCapture()` —
  they now assert the guaranteed lower bound (flaked under load)

### Performance
- Console script/style moved to `static/console.js` / `static/console.css`
  (cacheable, enables the strict CSP; no inline scripts or styles remain)
- Frames larger than 1280 px are downscaled before analysis so 1080p/4K cameras
  cost the same CPU as 640x480 (low-end hosts)
- Console: single reused capture canvas instead of one per frame, bounded
  decision-log size, health polling paused while the tab is hidden
- Server: Hikari pool 10 → 5, Tomcat threads 200 → 50, response gzip enabled
- Startup cost breakdown, measured on the packaged jar (dev profile,
  cold native cache, `BufferingApplicationStartup` across 412 steps,
  three runs; wall clock 37–43 s, of which **~4 s is JVM launch and
  fat-jar class loading** before Spring's clock even starts — the
  "process running for" figure minus Spring's own seconds). The
  remaining ~30 s of `spring.context.refresh` is, by self time:
  Hibernate SessionFactory construction **~7.8 s** (probed with
  `ddl-auto=none`: unchanged at 7.76 s, so it is the metamodel
  build, **not** DDL — the old "Hibernate DDL 7.8 s" attribution
  was wrong), `@Configuration` class parsing **~6 s** (135 classes
  read through the jar-in-jar loader, ~45 ms each), Spring Data
  repository proxies **~4 s** (the first repository pays the shared
  JPA metamodel, ~2.4 s), the dev-only H2 console bean ~1.5 s,
  Tomcat creation ~1 s, and the rest spread over ~400 smaller steps
  (springdoc, AOP, handler mappings, post-processors). The slim jar
  (94 MB) shows the same distribution — artifact size is not a
  factor — and OpenCV natives no longer appear on the main-thread
  timeline at all: the warm-up thread overlaps completely. The real
  boot-time bottleneck is Hibernate's metamodel and Spring's own
  config parsing; candidates for future work are
  `hibernate.temp.use_jdbc_metadata_defaults=false` (skips JDBC
  metadata introspection), excluding unused auto-configurations
  (135 parsed classes), and an AppCDS archive for the pre-Spring
  class loading

## [Unreleased] - 2026-10-01

### Fixed
- Resolved intermittent test failures in DeviceAdapterTest
- All test suites now pass consistently
- Improved test reliability for device adapter components
