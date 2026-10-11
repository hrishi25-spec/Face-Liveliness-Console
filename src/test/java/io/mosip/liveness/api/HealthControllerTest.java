package io.mosip.liveness.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The health endpoint in isolation. {@code TestConfig} is imported because a
 * slice builds every {@code Filter} bean, and {@code SessionOwnershipFilter}
 * needs the session repository even for a request it never looks at — the same
 * reason every other slice here imports it.
 */
@WebMvcTest(HealthController.class)
@Import(TestConfig.class)
class HealthControllerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void healthCheck_returnsOkWithServiceName() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service").value("MOSIP Face Liveness & PAD Service"));
    }
}
