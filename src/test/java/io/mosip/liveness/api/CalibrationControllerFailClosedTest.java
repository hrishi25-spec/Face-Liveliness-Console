package io.mosip.liveness.api;

import io.mosip.liveness.auth.AuthService;
import io.mosip.liveness.eval.ThresholdSweep;
import io.mosip.liveness.services.ThresholdCalibrationService;
import io.mosip.liveness.testing.AuthTestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two ways the calibration sweep refuses, mirroring
 * {@code ConfigControllerFailClosedTest}.
 *
 * <p>The sweep replays every session's frame scores through the scorer, so an
 * open route is both unbounded work per request and a read-out of the operating
 * point the configured threshold is meant to hold. It used to be guarded by a
 * shared admin key and refused with 403 whenever that key was unconfigured; it is
 * now guarded by the supervisor role, which splits the refusals in two: 401 for a
 * caller that proved nothing (absent or unknown session), and 403 for an operator
 * — a genuinely authenticated caller that still must not read the operating point
 * or spend the CPU. The second case is the one the cutover introduced, and the
 * one a status-only 401 check would have missed.</p>
 *
 * <p>Every refusal asserts the service was never called, because a refusal that
 * had already run the sweep would satisfy a status assertion.</p>
 */
@WebMvcTest(CalibrationController.class)
@Import(TestConfig.class)
class CalibrationControllerFailClosedTest {

    private static final String SUPERVISOR_AUTH = "Bearer " + AuthTestFixture.SUPERVISOR_TOKEN;

    @Autowired private MockMvc mockMvc;
    @Autowired private ThresholdCalibrationService calibration;
    @Autowired private AuthService authService;

    @BeforeEach
    void signIn() {
        AuthTestFixture.stub(authService);
    }

    @Test
    void sweep_withNoSession_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/eval/threshold-sweep"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        verify(calibration, never()).run(anyDouble());
    }

    @Test
    void sweep_withAnUnknownSession_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/eval/threshold-sweep").with(AuthTestFixture.asUnknownSession()))
                .andExpect(status().isUnauthorized());
        verify(calibration, never()).run(anyDouble());
    }

    /**
     * The role wall on this route: authenticated, and still refused. 403 rather
     * than 401 because the caller is known and re-authenticating would not help.
     */
    @Test
    void sweep_withAnOperatorSession_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/eval/threshold-sweep").with(AuthTestFixture.asOperator()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        verify(calibration, never()).run(anyDouble());
    }

    /** The control: a supervisor session gets through, so the refusals above are about the credential. */
    @Test
    void sweep_withASupervisorSession_reachesTheService() throws Exception {
        when(calibration.run(anyDouble())).thenReturn(new ThresholdSweep.Result(
                0.02, 5, 7, "onnx-minifasnet-v2", true, 0,
                0.90, "Highest threshold keeping BPCER <= 2% with APCER = 0",
                "Attack windows are SIMULATED.",
                List.of(new ThresholdSweep.Row(0.90, 10, 10, 0.0, 5L, 0.0, 0.0))));

        mockMvc.perform(get("/api/v1/eval/threshold-sweep")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedThreshold").value(0.90));
        verify(calibration, atLeastOnce()).run(anyDouble());
    }
}
