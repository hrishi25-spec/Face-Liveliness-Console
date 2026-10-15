package io.mosip.liveness.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.mosip.liveness.app.config.AppConfig;
import io.mosip.liveness.shared.definitions.RepeatedFailureAction;
import io.mosip.liveness.testing.RawHttpSupport;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * One scenario, both backends: the active-challenge retry budget is spent on the
 * Spring service (real HTTP, real OpenCV, real scorer) and on the FastAPI backend
 * ({@code pad_liveness_backend/tests/cross_backend_scenario.py}, driven over a
 * subprocess because it is a different runtime), and the two terminal responses
 * are compared field by field.
 *
 * <p>This is the test the two implementations did not have, and the gap it closes
 * was real: the Python backend answered a spent budget with {@code reject} and a
 * private {@code max_retries_exceeded_locked} / {@code _escalated} vocabulary
 * while the Java service answered {@code locked} / {@code escalate_to_operator} /
 * {@code failed} with {@code max_retries_exceeded:…} reasons, so a client written
 * against one could not read the other. Both suites asserted their own strings
 * against their own expectations, which is exactly the shape of assertion that
 * cannot notice two implementations disagreeing.</p>
 *
 * <p><b>What "agree" means here.</b> The values must match; the JSON spelling does
 * not, and must not be papered over: this service is camelCase
 * ({@code mayRetrySession}, {@code failureReason}) and the Python backend is
 * snake_case throughout its API ({@code may_retry_session}, {@code failure_reason}).
 * The comparison names each side's own spelling, so a rename on either side fails
 * here rather than being absorbed by a lookup that accepts both. Enum casing
 * differs for the same reason — Java reports {@code FAILED}, Python
 * {@code failed} — and is compared case-insensitively.</p>
 *
 * <p><b>Why the Java side waits.</b> The two backends reach the same verdict by
 * different mechanics: the Python service fails a challenge the moment the frames
 * do not contain the action, while this service fails it when the challenge window
 * elapses. The window is therefore shortened to {@link #WINDOW_MS} — the
 * deployment floor itself, so the policy is accepted as written — and the scenario
 * sleeps it out, following {@code LivenessPipelineIntegrationTest}. What is
 * compared is the terminal shape, not the number of wall-clock seconds.</p>
 *
 * <p><b>Running it.</b> Needs a Python interpreter with this backend's
 * dependencies ({@code pad_liveness_backend/requirements-dev.txt}), named by
 * {@code PAD_LIVENESS_PYTHON} or defaulting to {@code python3}. Without one the
 * test skips with the reason, so a workstation without the Python environment is
 * not blocked; the CI job that exists to run it sets
 * {@code PAD_LIVENESS_REQUIRE_PYTHON=1}, which turns that skip into a failure
 * (the same convention the PostgreSQL integration tests use).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "mosip.security.audit-hmac-secret=" + CrossBackendContractTest.AUDIT_SECRET,
        "mosip.liveness.min-challenge-window-ms=" + CrossBackendContractTest.WINDOW_MS,
        // The scenario creates a session and posts frames from 127.0.0.1 like
        // every other test in this suite; the production budgets stay pinned by
        // RateLimitFilterTest.
        "mosip.security.rate-limit.session-create-limit=100000",
        "mosip.security.rate-limit.frame-limit=100000",
        "spring.jpa.show-sql=false"
})
class CrossBackendContractTest extends RawHttpSupport {

    /**
     * The supervisor account this class signs in as; see
     * {@code LivenessPipelineIntegrationTest} for the flow. The policy write needs a
     * supervisor session now that the shared admin key is gone.
     */
    static final String SUPERVISOR_USERNAME = "cross-backend-supervisor";
    static final String AUDIT_SECRET = "cross-backend-audit-hmac-secret";

    /**
     * One challenge window, and the deployment floor for it: 3s keeps the two
     * sleeps under ten seconds instead of ~32s of wall clock, and still leaves
     * headroom before a probe could stray past the window.
     */
    static final long WINDOW_MS = 3_000L;
    /** Slack over {@link #WINDOW_MS} before asserting the window has elapsed. */
    private static final long ELAPSED_MARGIN_MS = 700L;
    /** Two attempts: the first spends a retry, the second spends the budget. */
    private static final int MAX_RETRIES = 2;
    /** passive-min-frames is 5, so the escalation can never come later than this. */
    private static final int MAX_FRAMES = 12;

    private static final String PYTHON_ENV = "PAD_LIVENESS_PYTHON";
    private static final String REQUIRE_PYTHON_ENV = "PAD_LIVENESS_REQUIRE_PYTHON";

    /**
     * The admin key the Python backend's policy write requires, set for the
     * scenario subprocess. This service no longer has such a key of its own —
     * the privileged surface here is a supervisor session — so the constant
     * exists only to configure the other backend, and any value works.
     */
    static final String PYTHON_ADMIN_KEY_ENV = "MOSIP_ADMIN_API_KEY";
    private static final String PYTHON_ADMIN_KEY = "cross-backend-admin-key-0123456789abcdef";
    private static final Path BACKEND_DIR = Path.of("pad_liveness_backend");

    private static String frameB64;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void loadFixture() throws Exception {
        frameB64 = fixtureB64("fixtures/real-face.jpg");
    }

    /**
     * Skip (or, under {@code PAD_LIVENESS_REQUIRE_PYTHON=1}, fail) when the
     * Python backend cannot be driven at all. A missing interpreter is an
     * environment gap, not a contract violation, but it must not be able to hide
     * the comparison in the job that exists to run it.
     */
    @BeforeAll
    static void requireThePythonBackend() {
        String reason = pythonUnavailableReason();
        if (reason == null) {
            return;
        }
        if ("1".equals(System.getenv(REQUIRE_PYTHON_ENV))) {
            fail(REQUIRE_PYTHON_ENV + " is set, but the Python backend cannot be driven: " + reason);
        }
        Assumptions.assumeTrue(false,
                "cross-backend contract needs the Python backend: " + reason);
    }

    @BeforeEach
    void requireOpenCv() {
        // The scenario only reaches a challenge if a face is actually detected,
        // so without the natives the Java half cannot run at all.
        AppConfig.ensureOpenCvLoaded();
        Assumptions.assumeTrue(AppConfig.isOpenCvAvailable(),
                "OpenCV native library unavailable on this platform");
    }

    @Test
    @Timeout(180)
    void oneSpentRetryBudgetLooksTheSameOnBothBackends() throws Exception {
        // Test all three repeated-failure policies
        for (RepeatedFailureAction policy : RepeatedFailureAction.values()) {
            TerminalShape java = spendTheJavaRetryBudget(policy);
            JsonNode python = runPythonScenario(policy);

            // The Java shape first, against the documented vocabulary: a failure here
            // is a Java regression, not a difference between the backends.
            assertEquals(policy.action(), java.action(), "Wrong action for policy " + policy);
            assertEquals(policy.failureReason(), java.failureReason(), "Wrong failure reason for policy " + policy);
            assertEquals(policy.mayRetrySession(), java.mayRetrySession(), "Wrong mayRetrySession for policy " + policy);
            assertEquals("FAILED", java.sessionStatus(), "Wrong session status for policy " + policy);
            assertEquals(MAX_RETRIES, java.challengesIssued(), "Wrong challenges issued for policy " + policy);
            assertEquals(List.of("retry_challenge"), java.intermediateActions(),
                    "only the verdict that spends the last retry is terminal for policy " + policy);

            // Then the comparison. Each side is read under its own documented
            // spelling, so a rename on either side fails rather than being absorbed.
            assertEquals(java.action(), python.get("action").asText(),
                    "the terminal action is the client-visible contract: " + python);
            assertEquals(java.failureReason(), python.get("failure_reason").asText(),
                    "the session's failure_reason must mean the same thing on both sides: " + python);
            assertEquals(java.mayRetrySession(), python.get("may_retry_session").asBoolean(),
                    "retryability must agree: " + python);
            assertEquals(java.message(), python.get("message").asText(),
                    "the user-facing message is part of the shared vocabulary: " + python);
            assertEquals(java.sessionStatus(), python.get("session_status").asText().toUpperCase(Locale.ROOT),
                    "the session ends in the same state, in each API's enum casing: " + python);
            assertEquals(java.challengeStatus(), python.get("challenge_status").asText().toUpperCase(Locale.ROOT),
                    "the failed challenge is reported the same way: " + python);
            assertEquals(java.intermediateActions(), textValues(python.get("intermediate_actions")),
                    "both must retry before they give up: " + python);
            assertEquals(java.challengesIssued(), python.get("challenges_issued").asInt(),
                    "the budget is spent the same way: " + python);

            // The spelling difference is deliberate, not an accident this test walks
            // around: the Python API is snake_case throughout.
            assertFalse(python.has("mayRetrySession"), "the Python API is snake_case: " + python);
            assertFalse(python.has("failureReason"), "the Python API is snake_case: " + python);
            assertEquals(policyValue(policy), python.get("policy").asText(),
                    "both halves must have configured the same repeated-failure policy: " + python);
        }
    }

    /**
     * The policy's value on the wire, which is what both halves are configured
     * with and what the scenario echoes back.
     *
     * <p>Deliberately not {@code policy.name()}: {@link RepeatedFailureAction}
     * names the <em>terminal action</em> a spent budget reports
     * ({@code LOCK_OUT} &rarr; {@code locked}), while the config field and the
     * scenario's {@code --policy} both take the {@code FailurePolicy} spelling
     * ({@code LOCK} / {@code ESCALATE} / {@code ALLOW_RETRY}). Comparing the two
     * vocabularies to each other is a comparison that can never pass — the old
     * {@code policy.name().toLowerCase()} asserted {@code lock_out} against
     * {@code LOCK} — so the mapping lives in one helper and the assertion reads
     * the value that was actually configured on both sides.</p>
     */
    private static String policyValue(RepeatedFailureAction policy) {
        return switch (policy) {
            case LOCK_OUT -> "LOCK";
            case FALLBACK -> "ALLOW_RETRY";
            case ESCALATE_TO_OPERATOR -> "ESCALATE";
        };
    }

    // ------------------------------------------------------------ the Java half

    /** The terminal shape this service reports, in this API's own spelling. */
    private record TerminalShape(
            String action,
            Boolean mayRetrySession,
            String challengeStatus,
            String message,
            String failureReason,
            String sessionStatus,
            int challengesIssued,
            List<String> intermediateActions) {}

    private TerminalShape spendTheJavaRetryBudget(RepeatedFailureAction policy) throws Exception {
        String apiPolicyValue = policyValue(policy);

        // SUPERVISOR is seeded LOCK_OUT; the policy is written explicitly so the
        // scenario does not depend on which workflow carries which seed.
        expect(HttpStatus.OK, send(HttpMethod.PUT, "/api/v1/config/SUPERVISOR",
                "{\"passiveThreshold\":1.0,\"challengeTimeoutMs\":" + WINDOW_MS
                        + ",\"maxRetryCount\":" + MAX_RETRIES
                        + ",\"onRepeatedFailure\":\"" + apiPolicyValue + "\"}",  // LOCK, ALLOW_RETRY, or ESCALATE
                adminHeaders()));

        ObjectNode session = mapper.createObjectNode();
        session.put("workflowType", "SUPERVISOR");
        session.put("deviceId", "cross-backend");
        UUID sessionId = UUID.fromString(expect(HttpStatus.CREATED,
                send(HttpMethod.POST, "/api/v1/sessions", session.toString(), jsonHeaders()))
                .get("id").asText());

        // passiveThreshold 1.0 can never be cleared, so the pipeline escalates.
        JsonNode escalated = null;
        for (int sent = 0; sent < MAX_FRAMES && escalated == null; sent++) {
            JsonNode frame = postFrame(sessionId);
            if ("escalate_to_active".equals(frame.get("action").asText())) {
                escalated = frame;
            }
        }
        assertNotNull(escalated, "the real pipeline must escalate below the threshold");
        assertEquals("escalate_to_active", escalated.get("action").asText(), escalated.toString());

        List<String> intermediate = new ArrayList<>();
        JsonNode challenge = escalated.get("challenge");
        JsonNode terminal = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            UUID challengeId = UUID.fromString(challengeIdOf(challenge));
            long windowStart = System.currentTimeMillis();
            sleepPast(windowStart + WINDOW_MS + ELAPSED_MARGIN_MS);
            JsonNode verdict = validateChallenge(sessionId, challengeId);
            if (attempt < MAX_RETRIES) {
                assertEquals("retry_challenge", verdict.get("action").asText(), verdict.toString());
                intermediate.add(verdict.get("action").asText());
                // This service issues the follow-up with the verdict; the Python
                // backend issues it on the next frame. The verdict is the same.
                challenge = verdict.get("challenge");
                assertNotNull(challenge, "a retry must carry the fresh challenge: " + verdict);
            } else {
                terminal = verdict;
            }
        }
        assertNotNull(terminal, "the last attempt must be terminal");

        JsonNode sessionAfter = expect(HttpStatus.OK,
                send(HttpMethod.GET, "/api/v1/sessions/" + sessionId, null, jsonHeaders()));
        JsonNode audit = expect(HttpStatus.OK,
                send(HttpMethod.GET, "/api/v1/sessions/" + sessionId + "/audit", null, jsonHeaders()));

        return new TerminalShape(
                terminal.get("action").asText(),
                terminal.get("mayRetrySession").asBoolean(),
                terminal.get("challenge").get("status").asText(),
                terminal.get("message").asText(),
                sessionAfter.get("failureReason").asText(),
                sessionAfter.get("status").asText(),
                countEvents(audit, "CHALLENGE_ISSUED"),
                intermediate);
    }

    /**
     * The challenge's id as each payload spells it: a frame response nests a
     * {@code challengeId}, a validation response a {@code ChallengeResponse} whose
     * field is {@code id} (the same two shapes {@code LivenessHttpClient.parseChallenge}
     * accepts). The follow-up challenge arrives with the retry verdict, so the
     * second attempt reads the second spelling.
     */
    private static String challengeIdOf(JsonNode challenge) {
        assertNotNull(challenge, "a challenge payload is required");
        return challenge.has("challengeId")
                ? challenge.get("challengeId").asText()
                : challenge.get("id").asText();
    }

    private JsonNode postFrame(UUID sessionId) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("frameBase64", frameB64);
        return expect(HttpStatus.OK,
                send(HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/frames",
                        body.toString(), jsonHeaders()));
    }

    private JsonNode validateChallenge(UUID sessionId, UUID challengeId) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("challengeId", challengeId.toString());
        ArrayNode frames = body.putArray("framesBase64");
        frames.add(frameB64).add(frameB64).add(frameB64);
        return expect(HttpStatus.OK,
                send(HttpMethod.POST, "/api/v1/sessions/" + sessionId + "/challenges/validate",
                        body.toString(), jsonHeaders()));
    }

    // ---------------------------------------------------------- the Python half

    private JsonNode runPythonScenario(RepeatedFailureAction policy) throws Exception {
        String apiPolicyValue = policyValue(policy);
        Path stderr = Files.createTempFile("cross-backend-scenario", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    pythonInterpreter(), "tests/cross_backend_scenario.py",
                    "--policy", apiPolicyValue, "--max-retries", String.valueOf(MAX_RETRIES));
            builder.directory(BACKEND_DIR.toFile());
            // The scenario configures its policy over PUT /api/v1/config/resident,
            // and that route is fail-closed in the Python backend: it is refused
            // unless MOSIP_ADMIN_API_KEY is both configured on the service and
            // presented as X-Admin-API-Key. Handing the key to *this* child rather
            // than relying on the runner's environment is what keeps the gate
            // honest — without it the scenario would fail on a machine that never
            // exported the variable.
            builder.environment().put(PYTHON_ADMIN_KEY_ENV, PYTHON_ADMIN_KEY);
            // Captured to a file: the script logs every HTTP call to stderr, and
            // reading two pipes in sequence is how a process ends up blocked.
            builder.redirectError(ProcessBuilder.Redirect.to(stderr.toFile()));

            Process process = builder.start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("the Python scenario did not finish within 120s");
            }
            String log = Files.readString(stderr);
            assertEquals(0, process.exitValue(),
                    () -> "the Python scenario exited " + process.exitValue() + ":\n" + log);
            assertFalse(stdout.isBlank(), () -> "the Python scenario printed nothing. Log:\n" + log);
            return mapper.readTree(stdout);
        } finally {
            Files.deleteIfExists(stderr);
        }
    }

    private static String pythonInterpreter() {
        String configured = System.getenv(PYTHON_ENV);
        return configured == null || configured.isBlank() ? "python3" : configured;
    }

    /**
     * Why the Python backend cannot be driven, or {@code null} when it can: the
     * project directory must be here and the interpreter must import the app's
     * dependencies, since importing {@code app.main} pulls in FastAPI, SQLAlchemy,
     * numpy and OpenCV.
     */
    private static String pythonUnavailableReason() {
        if (!Files.isDirectory(BACKEND_DIR)) {
            return BACKEND_DIR + " is not in the working directory";
        }
        ProcessBuilder probe = new ProcessBuilder(pythonInterpreter(), "-c",
                "import fastapi, sqlalchemy, numpy, cv2");
        probe.directory(BACKEND_DIR.toFile());
        probe.redirectErrorStream(true);
        try {
            Process process = probe.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return pythonInterpreter() + " did not answer within 120s";
            }
            return process.exitValue() == 0 ? null
                    : pythonInterpreter() + " cannot import the backend's dependencies: " + output;
        } catch (IOException e) {
            return pythonInterpreter() + " is not runnable: " + e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted while probing " + pythonInterpreter();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * Headers a policy write is authorized with.
     *
     * <p>{@code Authorization: Bearer} rather than the {@code X-Admin-API-Key} this
     * used to set: the privileged routes are supervisor-only and the shared key is
     * gone. Signed in through the real flow, so the session is one the service
     * actually issued and can revoke.</p>
     */
    private HttpHeaders adminHeaders() {
        HttpHeaders headers = jsonHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, supervisorAuth());
        return headers;
    }

    /** Memoised supervisor bearer value; one sign-in per class. */
    private String supervisorToken;

    @org.springframework.beans.factory.annotation.Autowired
    private io.mosip.liveness.auth.AuthService authService;

    /**
     * A supervisor session whose password has already been replaced, so the
     * owed-password-change confinement does not apply — a fresh account starts
     * flagged and would be refused 403 on the policy write.
     */
    private synchronized String supervisorAuth() {
        if (supervisorToken == null) {
            String initial = "cross-backend-initial-1";
            authService.createUser(SUPERVISOR_USERNAME, "SUPERVISOR", initial);
            var firstLogin = authService.login(SUPERVISOR_USERNAME, initial)
                    .orElseThrow(() -> new IllegalStateException(
                            "the account just created must be able to sign in"));
            var ready = authService
                    .changePassword(firstLogin.principal(), initial, "cross-backend-final-12")
                    .orElseThrow(() -> new IllegalStateException(
                            "the initial password must verify for the change to succeed"));
            supervisorToken = "Bearer " + ready.token();
        }
        return supervisorToken;
    }

    private JsonNode expect(HttpStatus expected, RawResponse response) throws IOException {
        assertEquals(expected.value(), response.status(),
                () -> "unexpected status, body: " + response.body());
        return mapper.readTree(response.body());
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null) {
            array.forEach(node -> values.add(node.asText()));
        }
        return values;
    }

    private static int countEvents(JsonNode audit, String eventType) {
        int count = 0;
        for (JsonNode event : audit) {
            if (eventType.equals(event.get("eventType").asText())) {
                count++;
            }
        }
        return count;
    }

    /** Sleeps until {@code deadlineMillis}, as {@code LivenessPipelineIntegrationTest} does. */
    private static void sleepPast(long deadlineMillis) throws InterruptedException {
        long remaining = deadlineMillis - System.currentTimeMillis();
        if (remaining > 0) {
            Thread.sleep(remaining);
        }
    }

    private static String fixtureB64(String resource) throws Exception {
        try (InputStream in = CrossBackendContractTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must be on the test classpath");
            return Base64.getEncoder().encodeToString(in.readAllBytes());
        }
    }
}
