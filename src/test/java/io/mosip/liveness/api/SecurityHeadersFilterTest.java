package io.mosip.liveness.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards {@link SecurityHeadersFilter}: every response must carry the hardening
 * headers, and oversized POST bodies must be rejected before reaching a
 * controller. Body cap is lowered via property so the test stays fast.
 */
@WebMvcTest(HealthController.class)
@Import(TestConfig.class)
@TestPropertySource(properties = "mosip.security.max-request-body-bytes=1024")
class SecurityHeadersFilterTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void healthResponse_carriesSecurityHeaders() throws Exception {
        MvcResult result = mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andReturn();
        var response = result.getResponse();
        assertTrue("nosniff".equals(response.getHeader("X-Content-Type-Options")),
                "X-Content-Type-Options must be nosniff");
        assertTrue("DENY".equals(response.getHeader("X-Frame-Options")),
                "X-Frame-Options must be DENY");
        assertTrue(response.getHeader("Referrer-Policy") != null,
                "Referrer-Policy must be set");
        String csp = response.getHeader("Content-Security-Policy");
        assertTrue(csp != null && csp.contains("script-src 'self'"),
                "CSP must restrict scripts to 'self'");
        assertFalse(csp != null && csp.contains("unsafe-inline"),
                "CSP must not allow unsafe-inline");
    }

    @Test
    void swaggerPaths_areExemptFromCspButKeepOtherHeaders() throws Exception {
        MvcResult result = mockMvc.perform(get("/swagger-ui/index.html"))
                .andReturn();
        var response = result.getResponse();
        assertTrue(response.getHeader("Content-Security-Policy") == null,
                "CSP must be exempt for swagger-ui (its webjar boots inline)");
        assertTrue("nosniff".equals(response.getHeader("X-Content-Type-Options")),
                "other headers must still apply on swagger paths");
    }

    @Test
    void oversizedPost_isRejectedWith413BeforeController() throws Exception {
        String body = "{\"frameBase64\":\"" + "a".repeat(2048) + "\"}";
        mockMvc.perform(post("/api/v1/sessions/00000000-0000-0000-0000-000000000000/frames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("PAYLOAD_TOO_LARGE"));
    }
}
