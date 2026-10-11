package io.mosip.liveness.api;

import io.mosip.liveness.services.ThresholdCalibrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail-closed guard, mirroring {@code ConfigControllerFailClosedTest}: with
 * MOSIP_ADMIN_API_KEY unset, GET /api/v1/eval/threshold-sweep must be refused
 * rather than left open. The sweep replays every session's frame scores through
 * the scorer, so an open route is both unbounded work per request and a
 * read-out of the operating point the configured threshold is meant to hold.
 */
@WebMvcTest(CalibrationController.class)
@Import(TestConfig.class)
class CalibrationControllerFailClosedTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ThresholdCalibrationService calibration;

    @Test
    void sweep_withoutConfiguredKey_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/eval/threshold-sweep")
                        .header(AdminKeyGuard.HEADER, "anything"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        verify(calibration, never()).run(anyDouble());
    }

    @Test
    void sweep_withNoHeaderAtAll_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/eval/threshold-sweep"))
                .andExpect(status().isForbidden());
        verify(calibration, never()).run(anyDouble());
    }
}
