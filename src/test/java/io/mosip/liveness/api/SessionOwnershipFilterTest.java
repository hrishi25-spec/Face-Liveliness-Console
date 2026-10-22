package io.mosip.liveness.api;

import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ownership filter, driven directly: what it refuses, what it lets through,
 * and the body it refuses in.
 *
 * <p>The per-route behaviour is covered against the controllers elsewhere
 * ({@code SessionsControllerTest}, {@code FramesControllerTest},
 * {@code ChallengesControllerTest}, {@code ActiveLivenessControllerTest},
 * {@code AuditControllerTest}) — those assert the status, because they go through
 * MockMvc and read what the dispatcher did. This test is the other half: the
 * refusal is now written by a filter rather than thrown into
 * {@code GlobalExceptionHandler}, so its shape and its CORS headers are asserted
 * here against the servlet objects it actually writes to.</p>
 */
class SessionOwnershipFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LivenessSessionRepository sessionRepo;
    private SessionOwnershipFilter filter;

    private UUID sessionId;
    private LivenessSession session;

    @BeforeEach
    void setUp() {
        sessionRepo = mock(LivenessSessionRepository.class);
        filter = new SessionOwnershipFilter(sessionRepo);
        sessionId = UUID.randomUUID();
        session = LivenessSession.builder()
                .id(sessionId).workflowType(WorkflowType.RESIDENT).deviceId("L1-CAM-01")
                .status(SessionStatus.ACTIVE).build();
    }

    @Test
    void anOwnedSessionIsRefusedWithoutTheHeader() throws Exception {
        session.setSubjectRef("resident-42");
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));

        MockHttpServletResponse response = run(framesRequest("POST"));

        assertEquals(403, response.getStatus());
        JsonNode body = MAPPER.readTree(response.getContentAsString());
        assertEquals("FORBIDDEN", body.get("error").asText());
        assertEquals(403, body.get("status").asInt());
        assertTrue(body.get("message").asText().contains(UserSessionGuard.HEADER),
                "the refusal should name the header to send: " + body);
        assertTrue(body.has("timestamp"), "the body is the shape every other error uses");
        assertEquals("application/json", response.getContentType());
    }

    /** A refusal that stops the chain: the request never reaches the dispatcher. */
    @Test
    void aRefusalStopsTheChain() throws Exception {
        session.setSubjectRef("resident-42");
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(framesRequest("POST"), new MockHttpServletResponse(), chain);

        assertNull(chain.getRequest(), "the request must not reach the dispatcher");
    }

    @Test
    void somebodyElsesHeaderIsRefusedWithTheMismatchMessage() throws Exception {
        session.setSubjectRef("resident-42");
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));

        MockHttpServletRequest request = framesRequest("POST");
        request.addHeader(UserSessionGuard.HEADER, "resident-43");

        MockHttpServletResponse response = run(request);

        assertEquals(403, response.getStatus());
        assertEquals("Invalid subject reference for this session.",
                MAPPER.readTree(response.getContentAsString()).get("message").asText());
    }

    @Test
    void theOwnersHeaderPasses() throws Exception {
        session.setSubjectRef("resident-42");
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));

        MockHttpServletRequest request = framesRequest("POST");
        request.addHeader(UserSessionGuard.HEADER, "resident-42");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "the owner's request reaches the handler");
        assertEquals(200, response.getStatus());
    }

    /** A session created without an owner has none to protect; the filter is not a lock. */
    @Test
    void anAnonymousSessionPasses() throws Exception {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(framesRequest("POST"), new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
    }

    /** An unknown session is the handler's answer (404, or an empty audit trail), not a 403. */
    @Test
    void anUnknownSessionIsLeftToTheHandler() throws Exception {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.empty());

        MockHttpServletResponse response = run(
                new MockHttpServletRequest("GET", "/api/v1/sessions/" + sessionId + "/audit"));

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().isEmpty(), "no refusal was written");
    }

    /**
     * A malformed id is a client mistake with a client's answer (400 from the
     * dispatcher). The filter must not refuse it as a stranger, and must not look
     * it up in the first place — the other way round is a 500 on a typo.
     */
    @Test
    void aMalformedSessionIdIsNotLookedUpAndNotRefused() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/sessions/not-a-uuid");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest(), "the dispatcher answers this one");
        verify(sessionRepo, never()).findById(any());
    }

    /** A refusal a browser host cannot read is a refusal it cannot act on. */
    @Test
    void aRefusalCarriesCorsHeadersForLoopbackHosts() throws Exception {
        session.setSubjectRef("resident-42");
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));

        MockHttpServletRequest request = framesRequest("POST");
        request.addHeader("Origin", "http://localhost:5173");

        MockHttpServletResponse response = run(request);

        assertEquals(403, response.getStatus());
        assertEquals("http://localhost:5173", response.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void aRefusalDoesNotWidenTheCorsAllowList() throws Exception {
        session.setSubjectRef("resident-42");
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));

        MockHttpServletRequest request = framesRequest("POST");
        request.addHeader("Origin", "https://evil.example");

        MockHttpServletResponse response = run(request);

        assertEquals(403, response.getStatus());
        assertNull(response.getHeader("Access-Control-Allow-Origin"),
                "only AppConfig's loopback origins are echoed");
    }

    /**
     * The gate itself, including the two shapes a handler-side check could not
     * have kept apart: a preflight (browsers send no custom headers on it) and a
     * path with no session in it. Both must reach the dispatcher untouched.
     */
    @Test
    void theGateOnlyCoversSessionScopedRequests() {
        assertFalse(filter.shouldNotFilter(framesRequest("POST")),
                "a session-scoped write is the filter's job");
        assertTrue(filter.shouldNotFilter(
                new MockHttpServletRequest("OPTIONS", framesRequest("POST").getRequestURI())),
                "a CORS preflight cannot carry the ownership claim");
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/config/RESIDENT")));
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/v1/sessions")));
    }

    private MockHttpServletRequest framesRequest(String method) {
        return new MockHttpServletRequest(method, "/api/v1/sessions/" + sessionId + "/frames");
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
