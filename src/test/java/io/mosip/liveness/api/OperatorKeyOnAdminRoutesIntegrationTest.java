package io.mosip.liveness.api;

import io.mosip.liveness.audit.AuditEventType;
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
 * A valid operator key is not an admin key, over real HTTP.
 *
 * <p>The slice tests cover each half of this in isolation:
 * {@code AuthControllerTest} shows the operator key proving {@code OPERATOR},
 * and the {@code *FailClosedTest} classes show the keyed routes refusing when no
 * admin key is <em>configured</em>. Neither asks the question this class asks,
 * which is the one that matters operationally: with both keys configured and the
 * operator key genuinely valid elsewhere, does presenting it on an admin-keyed
 * route get refused — and is the refusal free of side effects?</p>
 *
 * <p>"Free of side effects" is the part a status assertion cannot show, so it is
 * checked three ways: the policy row in the database keeps its old threshold, the
 * open policy read answers byte-identically to the pre-attempt snapshot, and the
 * audit chain gains no {@code CONFIG_CHANGED} entry. A policy edit that was half
 * applied — refused after the row moved, or refused after it was audited — would
 * pass a status-only test and fail these.</p>
 *
 * <p>Runs the real application (dev profile, H2, real filters and repositories) on
 * a random port, because the refusal comes from {@link AdminKeyFilter} ahead of
 * the handler: a slice with mocked services would prove the handler's own check,
 * which is the second line of defence rather than the one an operator actually
 * hits.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "mosip.security.admin-api-key=" + OperatorKeyOnAdminRoutesIntegrationTest.ADMIN_KEY,
        "mosip.security.operator-api-key=" + OperatorKeyOnAdminRoutesIntegrationTest.OPERATOR_KEY
})
class OperatorKeyOnAdminRoutesIntegrationTest extends RawHttpSupport {

    /** At least 32 characters: {@code SecurityValidator} rejects a shorter admin key on boot. */
    static final String ADMIN_KEY = "integration-admin-key-0123456789abcdef";
    /** At least 16 characters, and different from the admin key — both enforced on boot. */
    static final String OPERATOR_KEY = "integration-operator-key-0001";

    /** The only workflow whose policy this class touches. */
    private static final String POLICY_PATH = "/api/v1/config/RESIDENT";

    @Autowired private ConfigPolicyRepository configRepo;
    @Autowired private AuditLogRepository auditRepo;

    /**
     * The operator key on the policy write: refused, with nothing written — and
     * the same call with the admin key accepted, which is what makes the refusal
     * an authorization decision rather than a malformed request.
     */
    @Test
    void anOperatorKeyCannotWritePolicyAndChangesNothing() {
        // Stage a known value with the key that may write, so the refused attempt
        // has a concrete value to fail to change.
        assertEquals(200, putAsAdmin(0.80).status());

        String policyBefore = policyRead();
        long auditBefore = configChangeCount();

        // The attack: a real operator key, correct header, well-formed body, valid
        // value inside the policy's range.
        RawResponse refused = putAsOperator(0.91);

        assertEquals(403, refused.status(),
                "an operator key must not write policy: " + refused.status() + " " + refused.body());
        assertTrue(refused.body().contains("FORBIDDEN"), refused.body());
        assertEquals(0.80, storedThreshold(), 1e-9,
                "a refused write must leave the config row exactly as it was");
        assertEquals(policyBefore, policyRead(),
                "the open policy read must answer identically after a refused write");
        assertEquals(auditBefore, configChangeCount(),
                "a refused write must not be recorded as a policy edit");

        // The control: the same body, same route, with the admin key.
        RawResponse accepted = putAsAdmin(0.91);
        assertEquals(200, accepted.status(), accepted.body());
        assertEquals(0.91, storedThreshold(), 1e-9,
                "the admin write must actually reach the database");
        assertEquals(auditBefore + 1, configChangeCount(),
                "the accepted edit must be the one on the audit trail");

        // Leave the shared dev database as this class found it.
        assertEquals(200, putAsAdmin(0.80).status());
    }

    /**
     * The operator key on every route that is admin-only today. The positive
     * control comes first: the same key is accepted on an operator route, so a
     * 403 below is about <em>where</em> the key is presented, not about the key
     * being unknown or the service having no operator key configured.
     */
    @Test
    void theOperatorKeyIsNeverAcceptedOnAnAdminKeyedRoute() {
        RawResponse role = get("/api/v1/auth/role", OperatorKeyGuard.HEADER, OPERATOR_KEY);
        assertEquals(200, role.status(), "the operator key must be a real credential: " + role.body());
        assertTrue(role.body().contains("OPERATOR"), role.body());

        // The open read first: on a fresh dev database the RESIDENT row does not
        // exist until something reads or writes it, and this is what stages it.
        policyRead();
        double before = storedThreshold();

        // 1. The policy write.
        RawResponse write = putAsOperator(0.42);
        assertEquals(403, write.status(), write.body());
        assertEquals(before, storedThreshold(), 1e-9,
                "the sweep-worthy point is that nothing moved, not just that the status was 403");

        // 2. The calibration sweep: admin-keyed, because it is expensive per call
        //    and reads back the operating point the threshold is meant to hold.
        RawResponse sweep = get("/api/v1/eval/threshold-sweep", OperatorKeyGuard.HEADER, OPERATOR_KEY);
        assertEquals(403, sweep.status(), sweep.body());
        assertTrue(sweep.body().contains("FORBIDDEN"), sweep.body());
        assertFalse(sweep.body().contains("recommendedThreshold"),
                "a refused sweep must answer no calibration data: " + sweep.body());

        // 3. The gate runs before the body is read, not inside the handler: an
        //    unparseable body answered 403 (not 400) is the discriminator, because
        //    a handler that had been reached would have failed on parsing first.
        RawResponse beforeTheBody = send(HttpMethod.PUT, POLICY_PATH, "{",
                jsonHeader(OperatorKeyGuard.HEADER, OPERATOR_KEY));
        assertEquals(403, beforeTheBody.status(),
                "the key must be refused ahead of the request body: "
                        + beforeTheBody.status() + " " + beforeTheBody.body());

        // 4. The admin header is not a free pass either: the operator key in it is
        //    compared like any other value and refused like any other stranger.
        HttpHeaders asAdminHeader = new HttpHeaders();
        asAdminHeader.setContentType(MediaType.APPLICATION_JSON);
        asAdminHeader.set(AdminKeyGuard.HEADER, OPERATOR_KEY);
        RawResponse inTheWrongHeader = send(HttpMethod.PUT, POLICY_PATH, thresholdBody(0.42), asAdminHeader);
        assertEquals(403, inTheWrongHeader.status(),
                "the operator key placed in the admin header must not be accepted");
        assertEquals(before, storedThreshold(), 1e-9);

        // The routes are alive: the admin key opens both of them. Without this,
        // the 403s above would also be explained by a route that refuses everyone.
        assertEquals(200, get("/api/v1/eval/threshold-sweep", AdminKeyGuard.HEADER, ADMIN_KEY).status(),
                "the admin key must open the sweep, or the refusals prove nothing");
        assertEquals(200, putAsAdmin(before).status(),
                "the admin key must open the policy write");
    }

    // ------------------------------------------------------------------ helpers

    private RawResponse putAsOperator(double threshold) {
        return send(HttpMethod.PUT, POLICY_PATH, thresholdBody(threshold),
                jsonHeader(OperatorKeyGuard.HEADER, OPERATOR_KEY));
    }

    private RawResponse putAsAdmin(double threshold) {
        return send(HttpMethod.PUT, POLICY_PATH, thresholdBody(threshold),
                jsonHeader(AdminKeyGuard.HEADER, ADMIN_KEY));
    }

    /** The open policy read — no key, the way a console renders the current point. */
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
}
