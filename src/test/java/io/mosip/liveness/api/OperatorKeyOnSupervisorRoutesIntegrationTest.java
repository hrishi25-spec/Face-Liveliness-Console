package io.mosip.liveness.api;

import io.mosip.liveness.audit.AuditEventType;
import io.mosip.liveness.auth.AuthService;
import io.mosip.liveness.crud.AuditLogRepository;
import io.mosip.liveness.crud.ConfigPolicyRepository;
import io.mosip.liveness.models.enums.WorkflowType;
import io.mosip.liveness.testing.RawHttpSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operator key is not a supervisor credential, over real HTTP.
 *
 * <p>The slice tests cover each half of this apart: {@code AuthControllerTest}
 * shows the operator key proving {@code OPERATOR} on the probe, and the
 * {@code *FailClosedTest} classes show the supervisor routes refusing an absent or
 * unknown session. Neither asks the operational question this class asks, which is
 * the one that matters: with the operator key genuinely valid and accepted
 * elsewhere, is presenting it on a supervisor route refused, and is the refusal
 * free of side effects?</p>
 *
 * <p><b>What changed with the accounts cutover.</b> This used to assert 403 for
 * the operator key on the policy write, because {@code AdminKeyFilter} compared the
 * key it was handed and answered FORBIDDEN. Now the key is not read on these routes
 * at all — {@code OperatorKeyFilter} does not run there, by construction — so a
 * request carrying one is simply an unauthenticated request and is refused 401.
 * That is the stronger answer: the route no longer distinguishes "a valid operator
 * key" from "no credential", so the status cannot be used to learn whether a
 * guessed key was right.</p>
 *
 * <p>"Free of side effects" is the part a status assertion cannot show, so it is
 * checked three ways: the policy row keeps its old threshold, the open policy read
 * answers byte-identically to the pre-attempt snapshot, and the audit chain gains no
 * {@code CONFIG_CHANGED} entry. A policy edit that was half applied — refused after
 * the row moved, or refused after it was audited — would pass a status-only test
 * and fail these.</p>
 *
 * <p>Runs the real application (dev profile, H2, real filters and repositories) on a
 * random port, because the refusal comes from the filter ahead of the handler: a
 * slice with mocked services would prove the handler's own check, which is the
 * second line of defence rather than the one an operator actually hits.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "mosip.security.operator-api-key=" + OperatorKeyOnSupervisorRoutesIntegrationTest.OPERATOR_KEY
})
class OperatorKeyOnSupervisorRoutesIntegrationTest extends RawHttpSupport {

    /** At least 16 characters: {@code SecurityValidator} rejects a shorter key on boot. */
    static final String OPERATOR_KEY = "integration-operator-key-0001";

    /** The supervisor account this class signs in as. */
    private static final String SUPERVISOR_USERNAME = "routes-integration-supervisor";
    private static final String INITIAL_PASSWORD = "routes-initial-password-1";
    private static final String FINAL_PASSWORD = "routes-final-password-12";

    /** The only workflow whose policy this class touches. */
    private static final String POLICY_PATH = "/api/v1/config/RESIDENT";

    @Autowired private ConfigPolicyRepository configRepo;
    @Autowired private AuditLogRepository auditRepo;
    @Autowired private AuthService authService;

    private static String supervisorAuth;

    /**
     * The operator key on the policy write: refused, with nothing written — and the
     * same call with a supervisor session accepted, which is what makes the refusal
     * an authorization decision rather than a malformed request.
     */
    @Test
    void anOperatorKeyCannotWritePolicyAndChangesNothing() {
        // Stage a known value with the credential that may write, so the refused
        // attempt has a concrete value to fail to change.
        assertEquals(200, putAsSupervisor(0.80).status());

        String policyBefore = policyRead();
        long auditBefore = configChangeCount();

        // The attack: a real operator key, correct header, well-formed body, valid
        // value inside the policy's range.
        RawResponse refused = putAsOperatorKey(0.91);

        assertEquals(401, refused.status(),
                "the operator key must not write policy: " + refused.status() + " " + refused.body());
        assertTrue(refused.body().contains("UNAUTHORIZED"), refused.body());
        assertEquals(0.80, storedThreshold(), 1e-9,
                "a refused write must leave the config row exactly as it was");
        assertEquals(policyBefore, policyRead(),
                "the open policy read must answer identically after a refused write");
        assertEquals(auditBefore, configChangeCount(),
                "a refused write must not be recorded as a policy edit");

        // The control: the same body, same route, with a supervisor session.
        RawResponse accepted = putAsSupervisor(0.91);
        assertEquals(200, accepted.status(), accepted.body());
        assertEquals(0.91, storedThreshold(), 1e-9,
                "the supervisor write must actually reach the database");
        assertEquals(auditBefore + 1, configChangeCount(),
                "the accepted edit must be the one on the audit trail");

        // Leave the shared dev database as this class found it.
        assertEquals(200, putAsSupervisor(0.80).status());
    }

    /**
     * The operator key on every route that is supervisor-only. The positive control
     * comes first: the same key is accepted on an operator route, so a refusal below
     * is about <em>where</em> the key is presented, not about the key being unknown
     * or the service having no operator key configured.
     */
    @Test
    void theOperatorKeyIsNeverAcceptedOnASupervisorRoute() {
        RawResponse role = get("/api/v1/auth/role", OperatorKeyGuard.HEADER, OPERATOR_KEY);
        assertEquals(200, role.status(), "the operator key must be a real credential: " + role.body());
        assertTrue(role.body().contains("OPERATOR"), role.body());

        // The open read first: on a fresh dev database the RESIDENT row does not
        // exist until something reads or writes it, and this is what stages it.
        policyRead();
        double before = storedThreshold();

        // 1. The policy write.
        RawResponse write = putAsOperatorKey(0.42);
        assertEquals(401, write.status(), write.body());
        assertEquals(before, storedThreshold(), 1e-9,
                "the point is that nothing moved, not just that the status was a refusal");

        // 2. The calibration sweep: supervisor-only, because it is expensive per call
        //    and reads back the operating point the threshold is meant to hold.
        RawResponse sweep = get("/api/v1/eval/threshold-sweep", OperatorKeyGuard.HEADER, OPERATOR_KEY);
        assertEquals(401, sweep.status(), sweep.body());
        assertFalse(sweep.body().contains("recommendedThreshold"),
                "a refused sweep must answer no calibration data: " + sweep.body());

        // 3. The gate runs before the body is read, not inside the handler: an
        //    unparseable body answered 401 (not 400) is the discriminator, because a
        //    handler that had been reached would have failed on parsing first.
        RawResponse beforeTheBody = send(HttpMethod.PUT, POLICY_PATH, "{",
                jsonHeader(OperatorKeyGuard.HEADER, OPERATOR_KEY));
        assertEquals(401, beforeTheBody.status(),
                "the credential must be refused ahead of the request body: "
                        + beforeTheBody.status() + " " + beforeTheBody.body());

        // 4. The operator key in the Authorization header is not a session either: the
        //    header carries a token, and a key is not one.
        HttpHeaders asBearer = new HttpHeaders();
        asBearer.setContentType(MediaType.APPLICATION_JSON);
        asBearer.set(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR_KEY);
        RawResponse inTheTokenHeader = send(HttpMethod.PUT, POLICY_PATH, thresholdBody(0.42), asBearer);
        assertEquals(401, inTheTokenHeader.status(),
                "the operator key placed in the Authorization header must not be accepted");
        assertEquals(before, storedThreshold(), 1e-9);

        // The routes are alive: a supervisor session opens both of them. Without
        // this, the refusals above would also be explained by a route that refuses
        // everyone.
        assertEquals(200, get("/api/v1/eval/threshold-sweep", "Authorization", supervisorAuth()).status(),
                "a supervisor session must open the sweep, or the refusals prove nothing");
        assertEquals(200, putAsSupervisor(before).status(),
                "a supervisor session must open the policy write");
    }

    /**
     * The operator key cannot manage accounts either — the second privileged surface,
     * and the one where a leak would be worst (it could create a supervisor). Checked
     * on all four routes, because a gate that covers three of them covers none of
     * them.
     */
    @Test
    void theOperatorKeyCannotManageUsers() {
        assertEquals(401, send(HttpMethod.GET, "/api/v1/users", null,
                jsonHeader(OperatorKeyGuard.HEADER, OPERATOR_KEY)).status());
        assertEquals(401, send(HttpMethod.POST, "/api/v1/users", "{}",
                jsonHeader(OperatorKeyGuard.HEADER, OPERATOR_KEY)).status());
        assertEquals(401, send(HttpMethod.PUT, "/api/v1/users/somebody",
                "{\"role\":\"SUPERVISOR\"}", jsonHeader(OperatorKeyGuard.HEADER, OPERATOR_KEY)).status());
        assertEquals(401, send(HttpMethod.DELETE, "/api/v1/users/somebody", null,
                jsonHeader(OperatorKeyGuard.HEADER, OPERATOR_KEY)).status());

        // And the control: a supervisor session lists them.
        assertEquals(200, get("/api/v1/users", "Authorization", supervisorAuth()).status());
    }

    // ------------------------------------------------------------------ helpers

    private RawResponse putAsOperatorKey(double threshold) {
        return send(HttpMethod.PUT, POLICY_PATH, thresholdBody(threshold),
                jsonHeader(OperatorKeyGuard.HEADER, OPERATOR_KEY));
    }

    private RawResponse putAsSupervisor(double threshold) {
        return send(HttpMethod.PUT, POLICY_PATH, thresholdBody(threshold),
                jsonHeader("Authorization", supervisorAuth()));
    }

    /** The open policy read — no credential, the way a console renders the current point. */
    private String policyRead() {
        RawResponse response = get(POLICY_PATH, null, null);
        assertEquals(200, response.status(), response.body());
        return response.body();
    }

    private RawResponse get(String path, String header, String key) {
        HttpHeaders headers = new HttpHeaders();
        if (header != null) {
            headers.set(header, key);
        }
        return send(HttpMethod.GET, path, null, headers);
    }

    private static HttpHeaders jsonHeader(String name, String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(name, value);
        return headers;
    }

    private static String thresholdBody(double threshold) {
        return "{\"passiveThreshold\":" + threshold + "}";
    }

    /** The row as the database holds it, not as the response claims. */
    private double storedThreshold() {
        return configRepo.findByWorkflowType(WorkflowType.RESIDENT)
                .orElseThrow(() -> new AssertionError("the RESIDENT policy row must exist"))
                .getPassiveThreshold();
    }

    /** How many policy edits the (append-only) audit table holds. */
    private long configChangeCount() {
        return auditRepo.findByEventTypeAndSessionIsNullOrderByCreatedAtDesc(
                AuditEventType.CONFIG_CHANGED.name(), PageRequest.of(0, 500)).size();
    }

    /**
     * A supervisor session with its password already replaced.
     *
     * <p>Walks the real flow: create the account, sign in with the initial password,
     * replace it, use the token that replacement mints. The change is not incidental
     * — a fresh account has {@code mustChangePassword = true} and such a session is
     * confined to the three routes that fix it, so every supervisor call in this
     * class would be refused 403 until the flag is cleared.</p>
     */
    private String supervisorAuth() {
        synchronized (OperatorKeyOnSupervisorRoutesIntegrationTest.class) {
            if (supervisorAuth != null) {
                return supervisorAuth;
            }
            authService.createUser(SUPERVISOR_USERNAME, "SUPERVISOR", INITIAL_PASSWORD);
            var firstLogin = authService.login(SUPERVISOR_USERNAME, INITIAL_PASSWORD)
                    .orElseThrow(() -> new IllegalStateException(
                            "the account just created must be able to sign in"));
            var ready = authService
                    .changePassword(firstLogin.principal(), INITIAL_PASSWORD, FINAL_PASSWORD)
                    .orElseThrow(() -> new IllegalStateException(
                            "the initial password must verify for the change to succeed"));
            supervisorAuth = "Bearer " + ready.token();
            return supervisorAuth;
        }
    }
}
