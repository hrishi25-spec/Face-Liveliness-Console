package io.mosip.liveness.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail-closed guard, mirroring {@code ConfigControllerFailClosedTest}: with
 * {@code MOSIP_OPERATOR_API_KEY} unset, the operator surface must be closed
 * rather than open to whoever sends an empty header.
 *
 * <p>The admin key is configured here on purpose, so the class pins the one
 * deliberate asymmetry: a blank operator key refuses every operator attempt while
 * the admin key is still answered {@code SUPERVISOR}. That is what keeps an
 * existing deployment — which sets only {@code MOSIP_ADMIN_API_KEY} — working,
 * and why the second test exists rather than a bare "everything is 401"
 * assertion.</p>
 */
@WebMvcTest(AuthController.class)
@Import(TestConfig.class)
@TestPropertySource(properties = {
        "mosip.security.admin-api-key=" + AuthControllerFailClosedTest.ADMIN_KEY
})
class AuthControllerFailClosedTest {

    static final String ADMIN_KEY = "synthetic-admin-key-0000000000000000001";

    @Autowired private MockMvc mockMvc;

    @Test
    void operatorAttemptWithNoOperatorKeyConfigured_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/role")
                        .header(OperatorKeyGuard.HEADER, "synthetic-operator-key-0001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void anEmptyOperatorHeaderCannotMatchAnUnconfiguredKey() throws Exception {
        // The failing shape: blank configured key compared against a blank header.
        // Equality here would leave the route open on every deployment that never
        // set the variable.
        mockMvc.perform(get("/api/v1/auth/role").header(OperatorKeyGuard.HEADER, ""))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/role"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminKeyStillAnswersSupervisorWithNoOperatorKeyConfigured() throws Exception {
        mockMvc.perform(get("/api/v1/auth/role").header(AdminKeyGuard.HEADER, ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPERVISOR"));
    }
}
