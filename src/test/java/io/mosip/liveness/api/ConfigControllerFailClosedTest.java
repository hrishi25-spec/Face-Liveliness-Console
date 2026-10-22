package io.mosip.liveness.api;

import tools.jackson.databind.ObjectMapper;
import io.mosip.liveness.auth.AuthService;
import io.mosip.liveness.dto.ConfigPolicyUpdate;
import io.mosip.liveness.crud.ConfigPolicyRepository;
import io.mosip.liveness.services.ConfigService;
import io.mosip.liveness.testing.AuthTestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail-closed guard: a policy write is refused unless the caller is a
 * supervisor.
 *
 * <p>This class used to assert 403 whenever {@code MOSIP_ADMIN_API_KEY} was
 * unconfigured — the route was disabled rather than open, which was the right
 * answer for a shared secret that a deployment either had or did not. With the
 * key retired the question is about the caller rather than the configuration, so
 * the same intent ("nothing unauthenticated writes a policy") is now asserted as
 * two refusals with different meanings:</p>
 *
 * <ul>
 *   <li><b>401</b> — no session, or a session the service does not know. The
 *       caller has not said who it is; a client answers by signing in.</li>
 *   <li><b>403</b> — an <em>operator</em> session. This is the case the cutover
 *       created and the one worth pinning: an operator API key is a real,
 *       working credential that can read the feeds, and it still must not be able
 *       to set {@code passiveThreshold=0} and defeat PAD for every session.</li>
 * </ul>
 *
 * <p>Every case asserts the repository was never touched, so a refusal that
 * happened after the write would not pass.</p>
 */
@WebMvcTest(ConfigController.class)
@Import(TestConfig.class)
class ConfigControllerFailClosedTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ConfigPolicyRepository configRepo;
    @Autowired private AuthService authService;
    @Autowired private ConfigService configService;

    @BeforeEach
    void signIn() {
        reset(configRepo, configService);
        AuthTestFixture.stub(authService);
        // The controller delegates workflow/enum conversion to ConfigService, which
        // this slice mocks. Its conversion methods are dependency-free, so the mock
        // is told to run the real ones — without that the accepted-path control
        // below would fail on a null conversion rather than on a refusal. Same
        // setup, and the same reasoning, as ConfigControllerTest.
        when(configService.toCoreWorkflow(any())).thenCallRealMethod();
        when(configService.toDbWorkflow(any())).thenCallRealMethod();
        when(configService.toDbChallenge(any())).thenCallRealMethod();
        when(configService.toDbFailure(any())).thenCallRealMethod();
        when(configService.mapToEffectivePolicy(any())).thenCallRealMethod();
        // The write path re-reads the saved row (ConfigController.snapshot), so the
        // repository mock has to hand back what it was given rather than null.
        when(configRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void setPolicy_withNoSession_returns401() throws Exception {
        putPolicy(null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.timestamp").exists());
        verify(configRepo, never()).save(any());
    }

    @Test
    void setPolicy_withAnUnknownSession_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .with(AuthTestFixture.asUnknownSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfigPolicyUpdate.builder().passiveThreshold(0.0).build())))
                .andExpect(status().isUnauthorized());
        verify(configRepo, never()).save(any());
    }

    /**
     * The role wall: an operator is authenticated, is refused, and nothing is
     * written. 403 rather than 401 — the caller is known, and the answer is not
     * "try again with credentials".
     */
    @Test
    void setPolicy_withAnOperatorSession_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .with(AuthTestFixture.asOperator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfigPolicyUpdate.builder().passiveThreshold(0.0).build())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(io.mosip.liveness.auth.AuthRefusals.FORBIDDEN));
        verify(configRepo, never()).save(any());
    }

    /**
     * The operator API key is refused here too, which is the property that made
     * keeping the key acceptable: it is a credential the service still accepts,
     * and it cannot reach a privileged route at all.
     */
    @Test
    void setPolicy_withTheOperatorApiKey_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(OperatorKeyGuard.HEADER, "synthetic-operator-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfigPolicyUpdate.builder().passiveThreshold(0.0).build())))
                .andExpect(status().isUnauthorized());
        verify(configRepo, never()).save(any());
    }

    /** The body is not even parsed on a refused call: an unparseable one is still 401, never 400. */
    @Test
    void setPolicy_refusesBeforeReadingTheBody() throws Exception {
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        verify(configRepo, never()).save(any());
    }

    /** The control: the same write with a supervisor session is accepted. */
    @Test
    void setPolicy_withASupervisorSession_isAccepted() throws Exception {
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + AuthTestFixture.SUPERVISOR_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfigPolicyUpdate.builder().passiveThreshold(0.9).build())))
                .andExpect(status().isOk());
        verify(configRepo, org.mockito.Mockito.atLeastOnce()).save(any());
    }

    /** The refusal body every other 4xx in this service uses. */
    private org.springframework.test.web.servlet.ResultActions putPolicy(String authorization)
            throws Exception {
        var request = put("/api/v1/config/RESIDENT")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        ConfigPolicyUpdate.builder().passiveThreshold(0.0).build()));
        if (authorization != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mockMvc.perform(request);
    }
}
