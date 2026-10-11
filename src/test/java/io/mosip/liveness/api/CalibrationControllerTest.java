package io.mosip.liveness.api;

import io.mosip.liveness.eval.ThresholdSweep;
import io.mosip.liveness.services.ThresholdCalibrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CalibrationController.class)
@Import(TestConfig.class)
@TestPropertySource(properties = {
        "mosip.security.admin-api-key=" + CalibrationControllerTest.ADMIN_KEY
})
class CalibrationControllerTest {

    static final String ADMIN_KEY = "test-admin-key";

    @Autowired private MockMvc mockMvc;
    @Autowired private ThresholdCalibrationService calibration;

    private static ThresholdSweep.Result sample(double target) {
        return new ThresholdSweep.Result(
                target, 5, 7, "onnx-minifasnet-v2", true, 0,
                0.90, "Highest threshold keeping BPCER <= 2% with APCER = 0",
                "Attack windows are SIMULATED.",
                List.of(new ThresholdSweep.Row(0.90, 10, 10, 0.0, 5L, 0.0, 0.0)));
    }

    @Test
    void thresholdSweepReturnsTheTable() throws Exception {
        when(calibration.run(anyDouble())).thenReturn(sample(0.02));

        mockMvc.perform(get("/api/v1/eval/threshold-sweep")
                        .header(ConfigController.ADMIN_API_KEY_HEADER, ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetBpcer").value(0.02))
                .andExpect(jsonPath("$.scorer").value("onnx-minifasnet-v2"))
                .andExpect(jsonPath("$.attackDataIsProxy").value(true))
                .andExpect(jsonPath("$.recommendedThreshold").value(0.90))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].threshold").value(0.90))
                .andExpect(jsonPath("$.rows[0].bpcer").value(0.0))
                .andExpect(jsonPath("$.rows[0].apcer").value(0.0));
    }

    @Test
    void customTargetBpcerIsForwarded() throws Exception {
        when(calibration.run(0.05)).thenReturn(sample(0.05));

        mockMvc.perform(get("/api/v1/eval/threshold-sweep").param("targetBpcer", "0.05")
                        .header(ConfigController.ADMIN_API_KEY_HEADER, ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetBpcer").value(0.05));
    }

    @Test
    void outOfRangeTargetIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/eval/threshold-sweep").param("targetBpcer", "1.5")
                        .header(ConfigController.ADMIN_API_KEY_HEADER, ADMIN_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }
}
