package io.mosip.liveness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosip.liveness.app.config.AppConfig;
import io.mosip.liveness.testing.RawHttpSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpClientErrorException;

import java.io.InputStream;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the real application — full bean graph, H2 (dev profile), real OpenCV
 * face detection and the real ONNX MiniFASNet scorer — on a random port and
 * drives the frame pipeline over HTTP. Nothing is mocked.
 *
 * <p>Each test uses a different workflow type where config is mutated, so those
 * get independent config rows and the tests stay order-independent:</p>
 * <ul>
 *   <li>RESIDENT (default policy) — the real fixture face scores ~0.91 &gt;
 *       0.80 on the calibrated scale (raw ~0.99), so the median window must
 *       reach {@code proceed} → PASSED.</li>
 *   <li>SUPERVISOR with {@code passiveThreshold=1.0} — the median can never
 *       clear it, so the pipeline must escalate and open a challenge; static
 *       frames cannot perform the action, so validation stays {@code continue}.</li>
 *   <li>OPERATOR with {@code passiveThreshold=1.0} + active liveness off — the
 *       escalation branch must terminate as {@code reject} → FAILED.</li>
 *   <li>RESIDENT + {@code fixtures/print-attack.jpg} — two consecutive
 *       PAD-positive frames must terminally reject before scoring
 *       ({@code presentation_attack:PRINTED_PHOTO}). No config is mutated.</li>
 *   <li>SUPERVISOR with a 3s challenge window (the deployment floor itself) and
 *       {@code maxRetryCount=2} — a real-time test that waits out both windows:
 *       {@code continue} → {@code retry_challenge} → {@code reject}
 *       ({@code max_retries_exceeded}). Shares the SUPERVISOR config row with
 *       the escalation test; each test re-PUTs the fields it depends on.</li>
 *   <li>RESIDENT policy saved the way the browser console saves it (Chrome
 *       headers, CORS preflight, {@code X-Admin-API-Key}) — accepted and audited,
 *       refused without the key or from a foreign origin.</li>
 *   <li>Every mutating console call (sessions, frames, challenge
 *       validation, close, config PUT) answers its CORS preflight from
 *       an allowed cross-origin dev server, clearing the headers the
 *       console needs.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        // Keys the audit chain, the way MOSIP_AUDIT_HMAC_SECRET would in a
        // deployment: set here so the real boot path runs keyed rather than in
        // the fallback, and the verify endpoint can be seen reporting the mode.
        "mosip.security.audit-hmac-secret=" + LivenessPipelineIntegrationTest.AUDIT_SECRET,
        // Shortened only so the timeout path is exercised over real HTTP in
        // seconds rather than 2 × 15s of wall clock. Production keeps the 15s
        // default — DecisionEngineServiceTest pins the default and the 1s clamp.
        "mosip.liveness.min-challenge-window-ms=" + LivenessPipelineIntegrationTest.WINDOW_MS,
        // Test-only budget override. Every test in this class shares one process
        // and therefore one per-IP session-create bucket (all requests arrive
        // from 127.0.0.1), so the production 30/min is exhausted by the suite
        // itself and tests fail with 429s depending on execution order. Raised
        // high enough to be unreachable; the real defaults stay pinned by
        // RateLimitFilterTest, and rateLimitBudget_isPublishedOnLimitedPosts
        // asserts the *configured* limit rather than a literal so it keeps
        // working under the override.
        "mosip.security.rate-limit.session-create-limit=100000",
        "mosip.security.rate-limit.frame-limit=100000",
        "spring.jpa.show-sql=false"
})
/**
 * ponytail: NOT parallelised, and the reason is not the rate limiter. Nearly
 * every test here first PUTs the shared RESIDENT policy to stage its scenario
 * (passiveThreshold, activeLivenessEnabled, challengeTimeoutMs, maxRetryCount),
 * so concurrent methods overwrite each other's setup — running with
 * {@code @Execution(CONCURRENT)} fails deterministically in
 * {@code configChange_isTraceableInTheConfigAuditView} ("expected UPDATED but
 * was CREATED", because another test had just re-created the policy).
 *
 * <p>Measured before deciding: 3/3 runs failed, and the class took ~75 s
 * sequentially and ~75 s in parallel — no wall-clock win to trade the risk for.
 * Genuine parallelism needs per-test policy isolation (a database each, or
 * workflow types per test), which is a bigger change than this suite justifies.
 * The rate-limit budgets are raised below so that coupling is at least gone.</p>
 *
 * <p>Extends {@link RawHttpSupport} for the browser-shaped raw HTTP
 * calls (real {@code Origin} and preflight headers).</p>
 */
class LivenessPipelineIntegrationTest extends RawHttpSupport {

    /**
     * The supervisor account this class signs in as.
     *
     * <p>The policy write and the calibration sweep are supervisor-only routes
     * reachable with a session; the shared admin key they used to present is gone.
     * The helper below creates this account, replaces its initial password (so the
     * owed-password-change confinement does not apply) and mints a token.</p>
     */
    static final String SUPERVISOR_USERNAME = "e2e-integration-supervisor";
    /** Long enough to satisfy the key's minimum; the value itself is irrelevant. */
    static final String AUDIT_SECRET = "integration-test-audit-hmac-secret";

    /**
     * Challenge window the timeout test configures — and the deployment's floor
     * ({@code mosip.liveness.min-challenge-window-ms}, set to this value below),
     * so the policy is accepted as written and runs exactly as configured. Sized
     * so one window is 3s: the two windows plus two in-window probes cost ~8s
     * instead of ~32s, and still leave ~1s of headroom before a probe could
     * stray past the window.
     */
    static final long WINDOW_MS = 3_000L;
    /** Policy path used by the browser-style test; it only touches the timeout. */
    private static final String RESIDENT_CONFIG = "/api/v1/config/RESIDENT";
    /** Well inside {@link #WINDOW_MS}: this frame must not end the window early. */
    private static final long INSIDE_WINDOW_PROBE_MS = 1_300L;
    /** Slack over {@link #WINDOW_MS} before asserting the window has elapsed. */
    private static final long ELAPSED_MARGIN_MS = 700L;

    /**
     * Accounts and sessions, so this class can obtain the supervisor session the
     * privileged routes now require. The real service, against the real migrated
     * schema — the point of this class is that it runs the shipped code.
     */
    @org.springframework.beans.factory.annotation.Autowired
    private io.mosip.liveness.auth.AuthService authService;

    /**
     * The memoised bearer value; one sign-in per class, not one per request.
     *
     * <p>{@code static} because the field is per test <em>instance</em> otherwise,
     * and Spring builds a fresh instance for every method against the one cached
     * context. Each instance would therefore re-run the creation below against an
     * account the class already made, which the service correctly refuses
     * ("that username is already taken") — the account has to be created once for
     * the class, so the memo has to be the class's.</p>
     */
    private static String supervisorToken;

    /**
     * A supervisor session, created once and reused.
     *
     * <p>Walks the real flow rather than reaching past it: create the account, sign
     * in with the initial password, replace it, and use the token that replacement
     * mints. The change is not incidental — a freshly created account has
     * {@code mustChangePassword = true}, and the session that owes a password change
     * is confined to {@code /auth/me}, {@code /auth/change-password} and
     * {@code /auth/logout}. Every policy write in this class would be refused 403
     * until that flag is cleared, which is exactly the behaviour that confinement
     * exists to produce.</p>
     *
     * <p>Synchronised because a few tests share this class's single context and the
     * account must be created once, not once per caller.</p>
     */
    private String supervisorAuth() {
        synchronized (LivenessPipelineIntegrationTest.class) {
            if (supervisorToken != null) {
                return supervisorToken;
            }
            String initial = "e2e-initial-password-1";
            authService.createUser(SUPERVISOR_USERNAME, "SUPERVISOR", initial);
            var firstLogin = authService.login(SUPERVISOR_USERNAME, initial)
                    .orElseThrow(() -> new IllegalStateException(
                            "the account just created must be able to sign in"));
            var ready = authService
                    .changePassword(firstLogin.principal(), initial, "e2e-final-password-12")
                    .orElseThrow(() -> new IllegalStateException(
                            "the initial password must verify for the change to succeed"));
            supervisorToken = "Bearer " + ready.token();
            return supervisorToken;
        }
    }

    private static final Set<String> CHALLENGE_TYPES =
            Set.of("BLINK", "SMILE", "TURN_LEFT", "TURN_RIGHT");
    /** passive-min-frames is 5, so a terminal verdict can never come later. */
    private static final int MAX_FRAMES = 12;

    private static String frameB64;
    private static String attackB64;

    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper mapper;

    /**
     * The budgets this run was configured with. Injected rather than written as
     * literals because {@code @TestPropertySource} above raises them; asserting
     * against the configured value keeps the test honest under the override.
     */
    @Value("${mosip.security.rate-limit.session-create-limit}")
    private int sessionCreateLimit;
    @Value("${mosip.security.rate-limit.frame-limit}")
    private int frameLimit;

    @BeforeEach
    void requireOpenCv() {
        // The native load warms in the background during boot, so without a
        // barrier this assumption would test timing rather than availability:
        // a still-loading warm would skip every test below. ensureOpenCvLoaded
        // returns immediately once the attempt has settled.
        AppConfig.ensureOpenCvLoaded();
        // Same guard convention as ImageUtilsCascadeTest: without natives no
        // frame can detect a face and the pipeline cannot be exercised.
        Assumptions.assumeTrue(AppConfig.isOpenCvAvailable(),
                "OpenCV native library unavailable on this platform");
    }

    @org.junit.jupiter.api.BeforeAll
    static void loadFixtures() throws Exception {
        frameB64 = fixtureB64("fixtures/real-face.jpg");
        attackB64 = fixtureB64("fixtures/print-attack.jpg");
    }

    private static String fixtureB64(String resource) throws Exception {
        try (InputStream in = LivenessPipelineIntegrationTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must be on the test classpath");
            return Base64.getEncoder().encodeToString(in.readAllBytes());
        }
    }

    // ------------------------------------------------------------------ 1. passive pass

    @Test
    void residentSession_passivePipeline_proceedsAndPasses() throws Exception {
        ResponseEntity<String> created = exchange(HttpMethod.POST, "/api/v1/sessions",
                sessionBody("RESIDENT"), false);
        JsonNode session = expect(HttpStatus.CREATED, created);
        UUID sessionId = UUID.fromString(session.get("id").asText());
        // The security headers must survive all the way through real Tomcat.
        assertEquals("nosniff", created.getHeaders().getFirst("X-Content-Type-Options"));
        assertNotNull(created.getHeaders().getFirst("Content-Security-Policy"));

        // Cold start: real decode → face detect → PAD → ONNX score, but fewer
        // than passive-min-frames scores exist, so the answer is retry_passive.
        JsonNode first = postFrame(sessionId, frameB64);
        assertEquals("retry_passive", first.get("action").asText(), first.toString());
        assertTrue(first.get("faceDetected").asBoolean(), "fixture face must be detected");
        assertFalse(first.get("padFlag").asBoolean(), "fixture must not be flagged as an attack");
        assertNotNull(first.get("livenessScore"), "real scorer must produce a score");

        int sent = 1;
        JsonNode last = first;
        while (!terminal(last.get("action").asText()) && sent < MAX_FRAMES) {
            last = postFrame(sessionId, frameB64);
            sent++;
        }
        assertEquals("proceed", last.get("action").asText(), last.toString());
        assertEquals("COMPLETED", last.get("stage").asText());

        JsonNode after = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/sessions/" + sessionId, null, false));
        assertEquals("PASSED", after.get("status").asText());
        assertTrue(after.get("finalResult").asBoolean());

        JsonNode audit = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/sessions/" + sessionId + "/audit", null, false));
        assertTrue(hasEvent(audit, "SESSION_PASSED"), "audit must record the outcome: " + audit);

        JsonNode summary = expect(HttpStatus.OK,
                exchange(HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/close", null, false));
        assertEquals("PASSED", summary.get("status").asText());
        assertEquals(sent, summary.get("totalFramesEvaluated").asInt());
        assertEquals(0, summary.get("totalChallengesIssued").asInt());

        JsonNode metrics = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/metrics", null, false));
        assertTrue(metrics.get("totalSessions").asInt() >= 1);
    }

    // ------------------------------------------------------------------ 2. escalation + challenge

    @Test
    void supervisorSession_belowThreshold_escalatesAndChallengeStaysOpen() throws Exception {
        // Runtime policy change over the admin-key API — part of the pipeline.
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/v1/config/SUPERVISOR",
                "{\"passiveThreshold\":1.0}", true));

        UUID sessionId = UUID.fromString(expect(HttpStatus.CREATED,
                exchange(HttpMethod.POST, "/api/v1/sessions",
                        sessionBody("SUPERVISOR"), false)).get("id").asText());

        JsonNode last = null;
        int sent = 0;
        while ((last == null || !terminal(last.get("action").asText())) && sent < MAX_FRAMES) {
            last = postFrame(sessionId, frameB64);
            sent++;
        }
        assertEquals("escalate_to_active", last.get("action").asText(), last.toString());

        JsonNode challenge = last.get("challenge");
        assertNotNull(challenge, "escalation must carry a challenge");
        UUID challengeId = UUID.fromString(challenge.get("challengeId").asText());
        assertTrue(CHALLENGE_TYPES.contains(challenge.get("challengeType").asText()),
                "unexpected challenge type: " + challenge.get("challengeType").asText());

        JsonNode after = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/sessions/" + sessionId, null, false));
        assertEquals("ACTIVE", after.get("status").asText());
        assertEquals("ACTIVE", after.get("currentStage").asText());

        // Static frames cannot perform the action, and the 15s window is still
        // open, so validation must stay non-terminal rather than fail the try.
        ObjectNode validate = mapper.createObjectNode();
        validate.put("challengeId", challengeId.toString());
        ArrayNode frames = validate.putArray("framesBase64");
        frames.add(frameB64).add(frameB64).add(frameB64);
        JsonNode result = expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/v1/sessions/" + sessionId + "/challenges/validate",
                validate.toString(), false));
        assertEquals("continue", result.get("action").asText(), result.toString());
        assertFalse(result.get("passed").asBoolean());

        JsonNode audit = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/sessions/" + sessionId + "/audit", null, false));
        assertTrue(hasEvent(audit, "CHALLENGE_ISSUED"), "audit must record issuance: " + audit);

        JsonNode summary = expect(HttpStatus.OK,
                exchange(HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/close", null, false));
        assertEquals("EXPIRED", summary.get("status").asText());
        assertEquals(1, summary.get("totalChallengesIssued").asInt());
    }

    // ------------------------------------------------------------------ 3. reject below threshold

    @Test
    void operatorSession_activeDisabled_rejectsBelowThreshold() throws Exception {
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/v1/config/OPERATOR",
                "{\"passiveThreshold\":1.0,\"activeLivenessEnabled\":false}", true));

        UUID sessionId = UUID.fromString(expect(HttpStatus.CREATED,
                exchange(HttpMethod.POST, "/api/v1/sessions",
                        sessionBody("OPERATOR"), false)).get("id").asText());

        JsonNode last = null;
        int sent = 0;
        while ((last == null || !terminal(last.get("action").asText())) && sent < MAX_FRAMES) {
            last = postFrame(sessionId, frameB64);
            sent++;
        }
        assertEquals("reject", last.get("action").asText(), last.toString());

        JsonNode after = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/sessions/" + sessionId, null, false));
        assertEquals("FAILED", after.get("status").asText());
        assertEquals("passive_liveness_below_threshold", after.get("failureReason").asText());
        assertFalse(after.get("finalResult").asBoolean());

        JsonNode summary = expect(HttpStatus.OK,
                exchange(HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/close", null, false));
        assertEquals("FAILED", summary.get("status").asText());
    }

    // ------------------------------------------------------------------ 4. terminal PAD rejection

    @Test
    void printAttackFixture_isTerminallyRejectedByPad() throws Exception {
        UUID sessionId = UUID.fromString(expect(HttpStatus.CREATED,
                exchange(HttpMethod.POST, "/api/v1/sessions",
                        sessionBody("RESIDENT"), false)).get("id").asText());

        // Frame 1: PAD flags the printed presentation immediately, but the
        // verdict needs PAD_CONFIRM_FRAMES (2) consecutive attack frames
        // before it is terminal — one noisy frame must not kill a session.
        JsonNode first = postFrame(sessionId, attackB64);
        assertEquals("retry_passive", first.get("action").asText(), first.toString());
        assertTrue(first.get("padFlag").asBoolean(), "attack must be flagged on the first frame");
        assertEquals("PRINTED_PHOTO", first.get("padAttackType").asText());

        // Frame 2: confirmation — immediate terminal reject, before scoring.
        JsonNode second = postFrame(sessionId, attackB64);
        assertEquals("reject", second.get("action").asText(), second.toString());
        assertEquals("COMPLETED", second.get("stage").asText());

        JsonNode after = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/sessions/" + sessionId, null, false));
        assertEquals("FAILED", after.get("status").asText());
        assertEquals("presentation_attack:PRINTED_PHOTO", after.get("failureReason").asText());

        JsonNode audit = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/sessions/" + sessionId + "/audit", null, false));
        assertTrue(hasEvent(audit, "PAD_REJECTED"), "audit must record the attack: " + audit);

        // Terminal means terminal: the session left ACTIVE, so any further
        // frame is refused with 409 instead of being evaluated.
        ObjectNode body = mapper.createObjectNode();
        body.put("frameBase64", attackB64);
        JsonNode conflict = expect(HttpStatus.CONFLICT, exchange(HttpMethod.POST,
                "/api/v1/sessions/" + sessionId + "/frames", body.toString(), false));
        assertEquals("CONFLICT", conflict.get("error").asText());

        JsonNode summary = expect(HttpStatus.OK,
                exchange(HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/close", null, false));
        assertEquals("FAILED", summary.get("status").asText());
        assertEquals("presentation_attack:PRINTED_PHOTO", summary.get("failureReason").asText());
    }

    // ------------------------------------------------------------------ 5. error path, real decoder

    @Test
    void undecodableFrame_returns422ThroughRealImageUtils() throws Exception {
        UUID sessionId = UUID.fromString(expect(HttpStatus.CREATED,
                exchange(HttpMethod.POST, "/api/v1/sessions",
                        sessionBody("RESIDENT"), false)).get("id").asText());

        ObjectNode body = mapper.createObjectNode();
        body.put("frameBase64", "!!!not-base64!!!");
        JsonNode error = expect(HttpStatus.UNPROCESSABLE_ENTITY,
                exchange(HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/frames",
                        body.toString(), false));
        assertEquals("INVALID_FRAME", error.get("error").asText());
    }

    // ------------------------------------------------------------------ 6. window timeout → retry → max-retries reject

    /**
     * Real-time test of the challenge timeout path: forces the challenge window
     * to elapse twice with static frames that can never perform the action.
     *
     * <p>Covers all three branches in order: {@code continue} while the window
     * is open (a frame well inside it must not expire the challenge early),
     * {@code retry_challenge} with a brand-new challenge after the first window
     * elapses, and terminal {@code reject} with {@code max_retries_exceeded}
     * after the second.</p>
     *
     * <p>Window length comes from {@code mosip.liveness.min-challenge-window-ms}
     * (see the class javadoc): the same transitions, without 32s of sleeping.
     * The 15s production default and the 1s absolute clamp are pinned by
     * {@code DecisionEngineServiceTest}.</p>
     */
    @Test
    @Timeout(60)
    void supervisorChallengeWindowTimeout_retriesThenLocksOutAtMaxRetries() throws Exception {
        // The policy's window is the deployment floor itself, so the update is
        // accepted as written; maxRetryCount=2 → one retry, then the supervisor
        // workflow's terminal outcome: SUPERVISOR is seeded LOCK_OUT, so a spent
        // budget answers `locked` and refuses a fresh session (docs/configuration.md).
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/v1/config/SUPERVISOR",
                "{\"passiveThreshold\":1.0,\"challengeTimeoutMs\":" + WINDOW_MS
                        + ",\"maxRetryCount\":2}", true));

        UUID sessionId = UUID.fromString(expect(HttpStatus.CREATED,
                exchange(HttpMethod.POST, "/api/v1/sessions",
                        sessionBody("SUPERVISOR"), false)).get("id").asText());

        JsonNode last = null;
        int sent = 0;
        while ((last == null || !terminal(last.get("action").asText())) && sent < MAX_FRAMES) {
            last = postFrame(sessionId, frameB64);
            sent++;
        }
        assertEquals("escalate_to_active", last.get("action").asText(), last.toString());

        JsonNode challenge = last.get("challenge");
        UUID firstId = UUID.fromString(challenge.get("challengeId").asText());
        String firstType = challenge.get("challengeType").asText();
        // The challenge was issued while handling the escalation request, so this
        // is never earlier than issuedAt — a safe base for the window deadline.
        long firstWindowStart = System.currentTimeMillis();

        // Still inside the window the verdict must be continue: nothing may end
        // the challenge before its deadline.
        Thread.sleep(INSIDE_WINDOW_PROBE_MS);
        assertEquals("continue", validateChallenge(sessionId, firstId).get("action").asText());

        // Window elapsed → attempt failed, retry budget spent, a fresh challenge issued.
        sleepUntil(firstWindowStart + WINDOW_MS + ELAPSED_MARGIN_MS);
        JsonNode retry = validateChallenge(sessionId, firstId);
        assertEquals("retry_challenge", retry.get("action").asText(), retry.toString());
        UUID secondId = UUID.fromString(retry.get("challenge").get("id").asText());
        assertNotEquals(firstId, secondId, "retry must issue a brand-new challenge");
        assertEquals(2, retry.get("challenge").get("attemptNumber").asInt());
        assertEquals("ISSUED", retry.get("challenge").get("status").asText());
        String secondType = retry.get("challenge").get("challengeType").asText();
        assertNotEquals(firstType, secondType, "retry must not repeat the failed action");
        long secondWindowStart = System.currentTimeMillis();

        // The new challenge gets its own full window.
        Thread.sleep(INSIDE_WINDOW_PROBE_MS);
        assertEquals("continue", validateChallenge(sessionId, secondId).get("action").asText());

        // Second window elapsed and retryCount(2) >= maxRetry(2) → terminal lock-out.
        sleepUntil(secondWindowStart + WINDOW_MS + ELAPSED_MARGIN_MS);
        JsonNode rejected = validateChallenge(sessionId, secondId);
        assertEquals("locked", rejected.get("action").asText(), rejected.toString());
        assertFalse(rejected.get("passed").asBoolean());
        assertFalse(rejected.get("mayRetrySession").asBoolean(true),
                "a lock-out must not let the client open a fresh session: " + rejected);
        assertEquals("FAILED", rejected.get("challenge").get("status").asText());
        assertNotNull(rejected.get("challenge").get("completedAt"));

        JsonNode after = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/sessions/" + sessionId, null, false));
        assertEquals("FAILED", after.get("status").asText());
        assertEquals("max_retries_exceeded:locked_out", after.get("failureReason").asText());
        assertEquals("COMPLETED", after.get("currentStage").asText());
        // finalResult is only ever set on pass, so it is absent (NON_NULL) here.
        assertFalse(after.path("finalResult").asBoolean(false));

        JsonNode audit = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/sessions/" + sessionId + "/audit", null, false));
        assertEquals(2, countEvents(audit, "CHALLENGE_ISSUED"), audit.toString());
        assertEquals(2, countEvents(audit, "CHALLENGE_FAILED"), audit.toString());
        assertFalse(hasEvent(audit, "CHALLENGE_PASSED"), audit.toString());

        // Terminal means terminal: the failed challenge cannot be validated again.
        expect(HttpStatus.CONFLICT, exchange(HttpMethod.POST,
                "/api/v1/sessions/" + sessionId + "/challenges/validate",
                validateBody(secondId).toString(), false));

        JsonNode summary = expect(HttpStatus.OK,
                exchange(HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/close", null, false));
        assertEquals("FAILED", summary.get("status").asText());
        assertEquals("max_retries_exceeded:locked_out", summary.get("failureReason").asText());
        assertEquals(2, summary.get("totalChallengesIssued").asInt());
    }

    // ------------------------------------------------------------------ 7. config change audit

    @Test
    void configChange_isTraceableInTheConfigAuditView() throws Exception {
        // A policy edit decides who passes liveness, so it is recorded the same
        // way a decision is: which workflow, which fields moved, under which key.
        //
        // Stage a known prior state first: test 3 leaves OPERATOR at threshold
        // 1.0 with active liveness off, while an isolated run of this method
        // starts from the seeded 0.82. Pinning the row makes the audited
        // edit's direction — and therefore its risk level — the same either way.
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/v1/config/OPERATOR",
                "{\"passiveThreshold\":0.95,\"activeLivenessEnabled\":true}", true));
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, "/api/v1/config/OPERATOR",
                "{\"passiveThreshold\":0.93,\"maxRetryCount\":4}", true));

        JsonNode feed = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/config/audit?limit=50", null, false));
        JsonNode entry = firstChangeFor(feed, "OPERATOR");
        assertNotNull(entry, "the PUT must leave a trace in the audit view: " + feed);
        assertEquals("CONFIG_CHANGED", entry.get("eventType").asText());
        assertEquals("UPDATED", entry.get("details").get("action").asText());
        assertNotNull(entry.get("createdAt"), "an audit entry without a timestamp is not traceable");

        // The actor is the accountable account name. This used to be a fingerprint
        // of a shared key, which could not tell a departed contractor from a leaked
        // environment file.
        String actor = entry.get("details").get("actor").asText();
        assertEquals(SUPERVISOR_USERNAME, actor,
                "the audit trail must name the account behind the edit, got: " + actor);

        JsonNode changes = entry.get("details").get("changes");
        assertEquals(Set.of("passiveThreshold", "maxRetryCount"), fieldNames(changes),
                "only the fields that moved belong in the diff: " + changes);
        assertEquals(0.93, changes.get("passiveThreshold").get("to").asDouble(), 1e-9);
        assertEquals(4, changes.get("maxRetryCount").get("to").asInt());
        // …and the previous values, so the entry is self-contained.
        assertNotNull(changes.get("passiveThreshold").get("from"));
        assertNotNull(changes.get("maxRetryCount").get("from"));

        // The risk classification rides on every entry. Staged at 0.95, the
        // audited 0.93 lowers the threshold — the move that quietly defeats
        // PAD — so the entry itself carries the warning, with the move named.
        // (Active liveness was staged back on, so it is not part of the diff.)
        JsonNode risk = entry.get("details").get("risk");
        assertNotNull(risk, "every config change carries a risk classification");
        assertEquals("HIGH", risk.get("level").asText());
        assertEquals(1, risk.get("reasons").size());
        assertEquals("passiveThreshold lowered", risk.get("reasons").get(0).asText());

        // Session trails stay session-scoped: an operator-level event has no
        // session and must never surface inside one.
        UUID sessionId = UUID.fromString(expect(HttpStatus.CREATED,
                exchange(HttpMethod.POST, "/api/v1/sessions",
                        sessionBody("RESIDENT"), false)).get("id").asText());
        postFrame(sessionId, frameB64);
        JsonNode sessionAudit = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/sessions/" + sessionId + "/audit", null, false));
        for (JsonNode e : sessionAudit) {
            assertNotEquals("CONFIG_CHANGED", e.path("eventType").asText(),
                    "config events must not leak into a session trail: " + sessionAudit);
        }
    }

    // ------------------------------------------------------------------ 7b. the chain verifies end to end

    @Test
    void configAuditChain_verifiesKeyedEndToEnd() throws Exception {
        // One real edit, so there is a chain to walk — written by the real app
        // with the secret configured, the way a deployment that sets
        // MOSIP_AUDIT_HMAC_SECRET runs.
        expect(HttpStatus.OK, exchange(HttpMethod.PUT, RESIDENT_CONFIG,
                "{\"challengeTimeoutMs\":31000}", true));

        JsonNode verified = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/config/audit/verify", null, false));
        assertEquals("CONFIG_CHANGED", verified.get("eventType").asText());
        assertTrue(verified.get("chainedEntries").asInt() >= 1,
                "at least the entry just written: " + verified);
        assertNotNull(verified.get("headHash").asText());
        // The mode rides on the result: "intact" alone does not say whether a
        // database writer could have rebuilt the trail, and a fallback chain
        // would otherwise read exactly like a keyed one.
        assertTrue(verified.get("hmac").asBoolean(),
                "the secret is configured, so the keyed mode must be reported: " + verified);
        assertTrue(verified.get("intact").asBoolean(),
                "a chain this app wrote with this secret must verify: " + verified);
    }

    // ------------------------------------------------------------------ 8. browser-style config PUT

    @Test
    void browserStyleConfigPut_isAcceptedWithASupervisorSession() throws Exception {
        String sameOrigin = "http://localhost:" + port;
        String devServerOrigin = "http://localhost:5173";  // a console on another dev port

        // A browser can only send the Authorization header cross-origin after a
        // preflight that advertises it — if that fails, the console silently can't
        // save. (allowedOriginPatterns http://localhost:*, credentials off, so a
        // cross-origin caller presents a bearer token rather than the HttpOnly
        // session cookie.)
        HttpHeaders preflight = browserHeaders(devServerOrigin);
        preflight.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT");
        preflight.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type, authorization");
        RawResponse options = send(HttpMethod.OPTIONS, RESIDENT_CONFIG, null, preflight);
        assertEquals(200, options.status(), "preflight must be allowed");
        assertEquals(devServerOrigin, options.header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        String allowHeaders = options.header(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS);
        assertNotNull(allowHeaders, "preflight must allow the requested headers");
        assertTrue(allowHeaders.toLowerCase().contains("authorization"), allowHeaders);

        // The console's own call: browser headers + admin key. The value must
        // differ from the RESIDENT default V4 seeds (20s): a PUT that resubmits
        // the current policy is audited but records no field movement, and this
        // test asserts the movement.
        HttpHeaders save = browserHeaders(sameOrigin);
        save.add(HttpHeaders.AUTHORIZATION, supervisorAuth());
        RawResponse saved = send(HttpMethod.PUT, RESIDENT_CONFIG,
                "{\"challengeTimeoutMs\":25000}", save);
        assertEquals(200, saved.status(), saved.body());
        assertEquals("nosniff", saved.header("X-Content-Type-Options"));
        JsonNode applied = mapper.readTree(saved.body());
        assertEquals("RESIDENT", applied.get("workflowType").asText());
        assertEquals(25000, applied.get("challengeTimeoutMs").asInt());

        // Accepted and applied — so it must also be traceable.
        JsonNode feed = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/config/audit?limit=50", null, false));
        JsonNode entry = firstChangeFor(feed, "RESIDENT");
        assertNotNull(entry, "a browser-style PUT must leave an audit entry: " + feed);
        assertEquals(25000, entry.get("details").get("changes")
                .get("challengeTimeoutMs").get("to").asInt());

        // Browser headers are not a bypass: the same request with no session at all
        // is still refused, and writes nothing.
        RawResponse noKey = send(HttpMethod.PUT, RESIDENT_CONFIG,
                "{\"challengeTimeoutMs\":30000}", browserHeaders(sameOrigin));
        assertEquals(401, noKey.status(), noKey.body());
        assertEquals("UNAUTHORIZED", mapper.readTree(noKey.body()).get("error").asText());
        JsonNode after = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/config/RESIDENT", null, false));
        assertEquals(25000, after.get("challengeTimeoutMs").asInt(),
                "a rejected PUT must not change the policy");

        // A foreign origin is refused by the CORS processor before the session is
        // even considered.
        HttpHeaders foreign = browserHeaders("https://evil.example");
        foreign.add(HttpHeaders.AUTHORIZATION, supervisorAuth());
        RawResponse evil = send(HttpMethod.PUT, RESIDENT_CONFIG,
                "{\"challengeTimeoutMs\":30000}", foreign);
        assertEquals(403, evil.status(), evil.body());
        JsonNode unchanged = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/config/RESIDENT", null, false));
        assertEquals(25000, unchanged.get("challengeTimeoutMs").asInt(),
                "a cross-origin PUT must not change the policy either");
    }

    // ------------------------------------------------------------------ 8b. preflights for every mutating console call

    @Test
    void browserStylePreflights_coverEveryMutatingConsoleCall() throws Exception {
        String devServerOrigin = "http://localhost:5173";  // the console on another dev port

        // A real session, so the frame/challenge/close paths are the
        // ones the console actually calls. The preflight itself never
        // reaches the controller — the CORS processor answers it — but
        // the paths must still be routable for a future reader.
        UUID sessionId = UUID.fromString(expect(HttpStatus.CREATED,
                exchange(HttpMethod.POST, "/api/v1/sessions",
                        sessionBody("RESIDENT"), false)).get("id").asText());

        // The complete inventory of console calls a browser prefights
        // (anything non-simple: a JSON body, or the admin-key header).
        // The plain GETs (health, metrics, config, audit) are simple
        // requests — no preflight is sent for them, so there is nothing
        // to pin beyond the CORS mapping itself, which this test
        // exercises for every route family here.
        String[][] preflighted = {
                {"POST", "/api/v1/sessions", "content-type"},
                {"POST", "/api/v1/sessions/" + sessionId + "/frames", "content-type"},
                {"POST", "/api/v1/sessions/" + sessionId + "/challenges/validate", "content-type"},
                {"POST", "/api/v1/sessions/" + sessionId + "/close", "content-type"},
                {"PUT", RESIDENT_CONFIG, "content-type, authorization"},
        };
        for (String[] call : preflighted) {
            HttpHeaders preflight = browserHeaders(devServerOrigin);
            preflight.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, call[0]);
            preflight.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, call[2]);
            RawResponse options = send(HttpMethod.OPTIONS, call[1], null, preflight);

            String what = call[0] + " " + call[1];
            assertEquals(200, options.status(), "preflight must be allowed: " + what);
            assertEquals(devServerOrigin, options.header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN),
                    "the requesting origin must be echoed back, not a wildcard: " + what);
            String allowedMethods = options.header(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS);
            assertNotNull(allowedMethods, "preflight must advertise allowed methods: " + what);
            assertTrue(allowedMethods.contains(call[0]),
                    allowedMethods + " must include " + call[0] + " for " + what);
            String allowedHeaders = options.header(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS);
            assertNotNull(allowedHeaders, "preflight must allow the requested headers: " + what);
            // Every header the console needs must be cleared for use,
            // or the browser blocks the real request before it is sent.
            for (String header : call[2].split(", ")) {
                assertTrue(allowedHeaders.toLowerCase().contains(header),
                        allowedHeaders + " must clear " + header + " for " + what);
            }
        }
    }

    // ------------------------------------------------------------------ 8c. cross-origin write without a session

    @Test
    void crossOriginPutWithoutASession_isRefusedAndCredentialsNeverEchoed() throws Exception {
        String devServerOrigin = "http://localhost:5173";  // allowed by pattern, not this server

        // Stage a known policy so "unchanged" is a real assertion.
        HttpHeaders save = browserHeaders(devServerOrigin);
        save.add(HttpHeaders.AUTHORIZATION, supervisorAuth());
        RawResponse saved = send(HttpMethod.PUT, RESIDENT_CONFIG,
                "{\"challengeTimeoutMs\":26000}", save);
        assertEquals(200, saved.status(), saved.body());

        // The attacker's position is worse than "wrong origin": a page
        // on an origin the CORS processor *allows* still cannot write.
        // The origin allow-list is routing policy for the console, not
        // authorization — the session is. The sessionless preflight even
        // succeeds, so a browser would let the attacker page *read* the
        // refusal; only the authorization check stands between it and the
        // policy.
        HttpHeaders attackerPreflight = browserHeaders(devServerOrigin);
        attackerPreflight.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT");
        attackerPreflight.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type");
        RawResponse attackerOptions = send(HttpMethod.OPTIONS, RESIDENT_CONFIG, null, attackerPreflight);
        assertEquals(200, attackerOptions.status(),
                "a keyless preflight is allowed — CORS itself is not the barrier");

        RawResponse attacker = send(HttpMethod.PUT, RESIDENT_CONFIG,
                "{\"challengeTimeoutMs\":30000,\"passiveThreshold\":0.5}",
                browserHeaders(devServerOrigin));
        assertEquals(401, attacker.status(), attacker.body());
        assertEquals("UNAUTHORIZED", mapper.readTree(attacker.body()).get("error").asText());
        JsonNode after = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/config/RESIDENT", null, false));
        assertEquals(26000, after.get("challengeTimeoutMs").asInt(),
                "a sessionless cross-origin PUT must not change the policy");

        // Credentials are never echoed. A cross-origin caller presents the
        // session as an Authorization header — the cookie is not sent, because
        // credentialed CORS is off — and a policy PUT mints nothing, so no
        // Set-Cookie may appear on any of these responses. Credentialed CORS
        // must stay off on every response — preflight, success and refusal
        // alike — and the allow-origin header must stay the specific requesting
        // origin rather than a wildcard (the wildcard is only safe *because*
        // credentials are off; pinning both keeps them from drifting apart).
        assertNull(attackerOptions.header(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS),
                "a preflight must never advertise credentialed CORS");
        assertNull(saved.header(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS),
                "an accepted cross-origin call must never advertise credentialed CORS");
        assertNull(attacker.header(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS),
                "a refused cross-origin call must never advertise credentialed CORS");
        assertEquals(devServerOrigin, attackerOptions.header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN),
                "the allowed origin must be echoed specifically, never as a wildcard");
        assertEquals(devServerOrigin, attacker.header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN),
                "a refusal must still name the allowed origin specifically");
        assertNull(saved.header("Set-Cookie"),
                "a policy PUT mints no session, so it must set no cookie");
        assertNull(attacker.header("Set-Cookie"),
                "a refused PUT must set no cookie either");
    }

    // ------------------------------------------------------------------ 9. published rate-limit budget

    @Test
    void rateLimitBudget_isPublishedOnLimitedPosts() throws Exception {
        // What the console's Request budget panel renders: the limiter's state
        // must reach the client as headers, not just as a 429 after the fact.
        ResponseEntity<String> created = exchange(HttpMethod.POST, "/api/v1/sessions",
                sessionBody("RESIDENT"), false);
        JsonNode session = expect(HttpStatus.CREATED, created);
        assertEquals("session-create", created.getHeaders().getFirst("X-RateLimit-Bucket"));
        // The configured limit, not a literal: this class overrides it so the
        // suite cannot exhaust the shared per-IP bucket. What matters is that
        // the published value matches what the limiter was configured with —
        // RateLimitFilterTest is where 30/60 themselves are pinned.
        assertEquals(String.valueOf(sessionCreateLimit),
                created.getHeaders().getFirst("X-RateLimit-Limit"));
        assertNotNull(created.getHeaders().getFirst("X-RateLimit-Remaining"));
        assertNotNull(created.getHeaders().getFirst("X-RateLimit-Reset"));
        assertNull(created.getHeaders().getFirst("Retry-After"),
                "an allowed request must not tell the client to retry");

        UUID sessionId = UUID.fromString(session.get("id").asText());
        ObjectNode body = mapper.createObjectNode();
        body.put("frameBase64", frameB64);
        ResponseEntity<String> framed = exchange(HttpMethod.POST,
                "/api/v1/sessions/" + sessionId + "/frames", body.toString(), false);
        expect(HttpStatus.OK, framed);
        assertEquals("frames", framed.getHeaders().getFirst("X-RateLimit-Bucket"),
                "frame work has its own budget, separate from session creation");
        assertEquals(String.valueOf(frameLimit),
                framed.getHeaders().getFirst("X-RateLimit-Limit"));

        // Unmetered routes stay unmetered, or a client would show a budget for
        // work that is never limited.
        ResponseEntity<String> metrics = exchange(HttpMethod.GET, "/api/v1/metrics", null, false);
        expect(HttpStatus.OK, metrics);
        assertNull(metrics.getHeaders().getFirst("X-RateLimit-Bucket"));

        // The limiter's tallies must reach /metrics: the two limited posts above
        // each count as one admitted request (>= because other tests in this
        // class share the same process).
        JsonNode reported = expect(HttpStatus.OK,
                exchange(HttpMethod.GET, "/api/v1/metrics", null, false));
        assertTrue(reported.get("rateLimitAllowedRequests").asInt() >= 2, reported.toString());
        assertTrue(reported.get("rateLimitedRequests").asInt() >= 0);
        assertTrue(reported.get("rateLimitedSessionCreate").asInt() >= 0);
        assertTrue(reported.get("rateLimitedFrames").asInt() >= 0);
        assertTrue(reported.get("rateLimitRejectionRate").asDouble() >= 0.0);
    }

    @Test
    void forwardedHeadersCannotMintABudget_whenNoProxyIsTrusted() throws Exception {
        // trust-nobody is the shipped default, so X-Forwarded-For must be noise:
        // two requests claiming different clients share one per-IP budget. If a
        // proxy were trusted they would be separate buckets and the counter
        // would not move — that is exactly what this delta pins down.
        HttpHeaders spoofed = new HttpHeaders();
        spoofed.setContentType(MediaType.APPLICATION_JSON);
        spoofed.set("X-Forwarded-For", "203.0.113.1");
        RawResponse first = send(HttpMethod.POST, "/api/v1/sessions",
                sessionBody("RESIDENT"), spoofed);
        assertEquals(201, first.status(), first.body());

        spoofed.set("X-Forwarded-For", "203.0.113.2");   // a different "client"
        RawResponse second = send(HttpMethod.POST, "/api/v1/sessions",
                sessionBody("RESIDENT"), spoofed);
        assertEquals(201, second.status(), second.body());

        int before = Integer.parseInt(first.header("X-RateLimit-Remaining"));
        int after = Integer.parseInt(second.header("X-RateLimit-Remaining"));
        assertEquals(before - 1, after,
                "a spoofed X-Forwarded-For must not create a second budget: "
                        + before + " -> " + after);
    }

    // ------------------------------------------------------------------ helpers

    private static String sessionBody(String workflow) {
        return "{\"workflowType\":\"" + workflow + "\",\"deviceId\":\"E2E-CAM\"}";
    }

    private static boolean terminal(String action) {
        return "proceed".equals(action) || "reject".equals(action)
                || "escalate_to_active".equals(action);
    }

    private static JsonNode firstChangeFor(JsonNode feed, String workflowType) {
        for (JsonNode entry : feed) {
            if (workflowType.equals(entry.path("details").path("workflowType").asText())) {
                return entry;
            }
        }
        return null;
    }

    private static Set<String> fieldNames(JsonNode changes) {
        Set<String> names = new LinkedHashSet<>();
        changes.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static boolean hasEvent(JsonNode audit, String eventType) {
        return countEvents(audit, eventType) > 0;
    }

    private static int countEvents(JsonNode audit, String eventType) {
        int count = 0;
        for (JsonNode entry : audit) {
            if (eventType.equals(entry.path("eventType").asText())) count++;
        }
        return count;
    }

    private static void sleepUntil(long deadlineMs) throws InterruptedException {
        long remaining = deadlineMs - System.currentTimeMillis();
        if (remaining > 0) Thread.sleep(remaining);
    }

    private ObjectNode validateBody(UUID challengeId) {
        ObjectNode body = mapper.createObjectNode();
        body.put("challengeId", challengeId.toString());
        ArrayNode frames = body.putArray("framesBase64");
        frames.add(frameB64).add(frameB64).add(frameB64);
        return body;
    }

    private JsonNode validateChallenge(UUID sessionId, UUID challengeId) throws Exception {
        return expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/v1/sessions/" + sessionId + "/challenges/validate",
                validateBody(challengeId).toString(), false));
    }

    private JsonNode postFrame(UUID sessionId, String frame) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("frameBase64", frame);
        return expect(HttpStatus.OK, exchange(HttpMethod.POST,
                "/api/v1/sessions/" + sessionId + "/frames", body.toString(), false));
    }

    /**
     * HTTP call that tolerates both TestRestTemplates: the default one (returns
     * error responses as ResponseEntity) and one that throws on 4xx/5xx.
     */
    private ResponseEntity<String> exchange(HttpMethod method, String path,
                                            String body, boolean asSupervisor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (asSupervisor) headers.add(HttpHeaders.AUTHORIZATION, supervisorAuth());
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        try {
            return rest.exchange(url(path), method, entity, String.class);
        } catch (HttpClientErrorException e) {
            HttpHeaders errorHeaders = e.getResponseHeaders() != null
                    ? e.getResponseHeaders() : new HttpHeaders();
            return new ResponseEntity<>(e.getResponseBodyAsString(), errorHeaders, e.getStatusCode());
        }
    }

    private JsonNode expect(HttpStatus expected, ResponseEntity<String> response) throws Exception {
        // Every API response — including errors — must carry the hardening headers.
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"),
                "security headers missing on " + response);
        assertEquals(expected.value(), response.getStatusCode().value(),
                "unexpected status, body: " + response.getBody());
        return mapper.readTree(response.getBody());
    }
}
