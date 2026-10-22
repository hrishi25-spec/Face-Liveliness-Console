package io.mosip.liveness.api;

import io.mosip.liveness.auth.AuthService;
import io.mosip.liveness.testing.AuthTestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail-closed guard for the operator-key path.
 *
 * <p>With {@code MOSIP_OPERATOR_API_KEY} unset, the key must prove nothing — not
 * even to a caller that sends an empty header, which is the shape that would leave
 * every operator route open on a deployment that never configured one. That
 * property is unchanged by the move to accounts, and it still matters: a blank
 * configured key compared against a blank presented value is exactly the equality
 * bug.</p>
 *
 * <p>What changed is the second half. This class used to pin the asymmetry that a
 * blank operator key left the <em>admin</em> key working, which is what kept an
 * existing deployment running. There is no admin key now, and the corresponding
 * guarantee is that a blank operator key does not close a signed-in caller out:
 * a supervisor session still reaches the probe. So the two tests below are the two
 * halves of the same rule — the key closes, the session does not.</p>
 */
@WebMvcTest(AuthController.class)
@Import(TestConfig.class)
class AuthControllerFailClosedTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthService authService;

    @BeforeEach
    void signIn() {
        AuthTestFixture.stub(authService);
    }

    @Test
    void keyAttemptWithNoOperatorKeyConfigured_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/role")
                        .header(OperatorKeyGuard.HEADER, "synthetic-operator-key-0001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void anEmptyOperatorHeaderCannotMatchAnUnconfiguredKey() throws Exception {
        // The failing shape: blank configured key compared against a blank header.
        mockMvc.perform(get("/api/v1/auth/role").header(OperatorKeyGuard.HEADER, ""))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/role"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The other half: a blank operator key is not "everything is closed". A
     * supervisor session reaches the probe and is answered as the supervisor, so a
     * deployment that never configured a key still has a working privileged
     * surface.
     */
    @Test
    void aSupervisorSessionStillAnswersWithNoOperatorKeyConfigured() throws Exception {
        mockMvc.perform(get("/api/v1/auth/role").with(AuthTestFixture.asSupervisor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPERVISOR"));
    }
}
