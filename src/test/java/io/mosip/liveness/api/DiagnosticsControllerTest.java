package io.mosip.liveness.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import io.mosip.liveness.diagnostics.DiagnosticsService;
import io.mosip.liveness.diagnostics.DiagnosticsSnapshot;

/**
 * Diagnostic mode's local-only read side: loopback + opted-in gets the raw
 * snapshot with {@code no-store}; disabled and remote get the *same* empty
 * 404, so probing reveals neither the mode's existence nor its state.
 */
@WebMvcTest(DiagnosticsController.class)
@Import(TestConfig.class)
class DiagnosticsControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DiagnosticsService diagnostics; // TestConfig's @Primary mock

    private DiagnosticsSnapshot sample;

    @BeforeEach
    void setUp() {
        // The slice shares one mock across test methods; count only this run
        // so a "never" assertion cannot trip on an earlier test's legitimate call.
        clearInvocations(diagnostics);
        sample = new DiagnosticsSnapshot(true, "minifasnet-v2", 4.0, 12L,
                0.81, 0.79, 18.5, 32.0,
                List.of(new DiagnosticsSnapshot.FrameSample(
                        Instant.ofEpochMilli(1_000), 0.81, 0.77, false, 18L, "retry_passive")),
                Instant.ofEpochMilli(1_000));
    }

    @Test
    void loopbackCallerWithModeEnabledGetsTheSnapshotUncached() throws Exception {
        when(diagnostics.isEnabled()).thenReturn(true);
        when(diagnostics.snapshot()).thenReturn(sample);

        // MockMvc's default remote address is 127.0.0.1 — a local client.
        mockMvc.perform(get("/api/v1/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.scorer").value("minifasnet-v2"))
                .andExpect(jsonPath("$.fps").value(4.0))
                .andExpect(jsonPath("$.frameCount").value(12))
                .andExpect(jsonPath("$.lastScore").value(0.81))
                .andExpect(jsonPath("$.recentFrames[0].score").value(0.81))
                .andExpect(jsonPath("$.recentFrames[0].frameMs").value(18));
    }

    @Test
    void remoteCallerGetsTheSameEmpty404EvenWithModeEnabled() throws Exception {
        when(diagnostics.isEnabled()).thenReturn(true);

        mockMvc.perform(fromRemote("198.51.100.7"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
        verify(diagnostics, never()).snapshot();
    }

    @Test
    void modeOffIs404EvenOnLoopback() throws Exception {
        when(diagnostics.isEnabled()).thenReturn(false);

        mockMvc.perform(get("/api/v1/diagnostics"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
        verify(diagnostics, never()).snapshot();
    }

    @Test
    void aTrustedProxysForwardedClientDoesNotBecomeLocal() throws Exception {
        // The service trusts the local proxy's peer address as a proxy: the
        // peer may be loopback (a proxy on this box) but the forwarded client
        // is remote — judged as the remote caller it is, so tunnelling through
        // a local proxy must not launder a remote caller into loopback.
        ClientIpResolver trusting = new ClientIpResolver();
        ReflectionTestUtils.setField(trusting, "trustedProxies", List.of("127.0.0.1"));
        MockHttpServletRequest proxied = new MockHttpServletRequest("GET", "/api/v1/diagnostics");
        proxied.setRemoteAddr("127.0.0.1");
        proxied.addHeader("X-Forwarded-For", "198.51.100.7");
        assertEquals("198.51.100.7", trusting.resolve(proxied),
                "the forwarded remote client, not the local proxy, is the answer");
        assertFalse(ClientIpResolver.isLoopback(trusting.resolve(proxied)),
                "a local proxy must not launder a remote caller into loopback");

        // Through the endpoint with the slice's trust-nobody resolver, the raw
        // socket peer decides: the same forwarded header cannot open the panel.
        when(diagnostics.isEnabled()).thenReturn(true);
        mockMvc.perform(get("/api/v1/diagnostics")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.7");
                            request.addHeader("X-Forwarded-For", "203.0.113.9");
                            return request;
                        }))
                .andExpect(status().isNotFound());
        verify(diagnostics, never()).snapshot();
    }

    // ------------------------------------------------------------ helpers

    private static MockHttpServletRequestBuilder fromRemote(String address) {
        return get("/api/v1/diagnostics").with(request -> {
            request.setRemoteAddr(address);
            return request;
        });
    }
}
