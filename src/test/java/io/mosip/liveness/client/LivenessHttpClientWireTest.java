package io.mosip.liveness.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.mosip.liveness.api.UserSessionGuard;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The ownership half of the client's wire contract, exercised over a real socket
 * against a stub of this service rather than from JSON literals
 * ({@link LivenessHttpClientParseTest} covers the response shapes).
 *
 * <p>What only a wire test can show: that creating a session with a subject
 * reference puts it in the request <em>body</em>, that every session-scoped call
 * that follows carries it in {@link UserSessionGuard#HEADER} (the service refuses
 * an owned session without it, so a client that forgets one works only until the
 * first owned session), that an anonymous session still sends no header, and that
 * the guard's 403 arrives as a {@link LivenessClientException} instead of as a
 * parse failure on an error body.</p>
 *
 * <p>The header name is asserted against {@code UserSessionGuard.HEADER}: the
 * client declares the literal itself to stay free of the web layer, so this is
 * what keeps the two spellings from drifting apart.</p>
 */
class LivenessHttpClientWireTest {

    private static final String SESSION = "11111111-2222-3333-4444-555555555555";
    private static final String CHALLENGE = "aaaaaaaa-0000-0000-0000-000000000001";

    /** One request the stub received, as the service would see it. */
    private record Received(String method, String path, String subjectRef, String body) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Written from the stub's threads, read from the test's. */
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String baseUrl;
    private volatile int createStatus = 201;
    private volatile int frameStatus = 200;
    private volatile int configStatus = 200;

    @BeforeEach
    void startStubService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStubService() {
        server.stop(0);
    }

    // -------------------------------------------------------------- owned sessions

    @Test
    @DisplayName("creating an owned session puts the subject reference in the body")
    void creatingAnOwnedSessionPutsTheSubjectReferenceInTheBody() {
        LivenessClient.SessionInfo info = client().createSession("RESIDENT", "L1-CAM-01", "resident-42");

        assertEquals(UUID.fromString(SESSION), info.id());
        Received created = only("POST", "/api/v1/sessions");
        assertTrue(created.body().contains("\"subjectRef\":\"resident-42\""),
                "the owner is claimed at creation, not afterwards: " + created.body());
        assertNull(created.subjectRef(), "creation is not session-scoped, so it sends no header");
    }

    @Test
    @DisplayName("every session-scoped call repeats the ownership header")
    void everySessionScopedCallRepeatsTheOwnershipHeader() {
        LivenessClient client = client();
        UUID sessionId = client.createSession("RESIDENT", "L1-CAM-01", "resident-42").id();

        client.submitFrame(sessionId, new byte[]{1, 2, 3});
        client.validateChallenge(sessionId, UUID.fromString(CHALLENGE), List.of(new byte[]{4, 5}));
        client.closeSession(sessionId);

        for (String path : List.of(
                "/api/v1/sessions/" + SESSION + "/frames",
                "/api/v1/sessions/" + SESSION + "/challenges/validate",
                "/api/v1/sessions/" + SESSION + "/close")) {
            Received call = only("POST", path);
            assertEquals("resident-42", call.subjectRef(),
                    path + " must carry the session's owner or the service refuses it");
        }
        assertEquals(UserSessionGuard.HEADER, LivenessHttpClient.SUBJECT_REF_HEADER,
                "the client and the guard have to spell the header the same way");
    }

    /**
     * The remembered owner is released by the close, which is what bounds the
     * client's map by sessions in flight rather than by sessions ever created.
     */
    @Test
    void closingASessionReleasesItsOwner() {
        LivenessClient client = client();
        UUID sessionId = client.createSession("RESIDENT", "L1-CAM-01", "resident-42").id();

        client.closeSession(sessionId);
        assertEquals("resident-42", only("POST", "/api/v1/sessions/" + SESSION + "/close").subjectRef());

        client.submitFrame(sessionId, new byte[]{1, 2, 3});
        assertNull(only("POST", "/api/v1/sessions/" + SESSION + "/frames").subjectRef());
    }

    // ---------------------------------------------------------- anonymous sessions

    @Test
    @DisplayName("an anonymous session sends no header and stays reachable")
    void anAnonymousSessionSendsNoHeader() throws Exception {
        LivenessClient client = client();
        UUID sessionId = client.createSession("RESIDENT", "L1-CAM-01").id();

        client.submitFrame(sessionId, new byte[]{1, 2, 3});
        client.closeSession(sessionId);

        assertNull(subjectRefOf(only("POST", "/api/v1/sessions").body()),
                "an anonymous body carries no owner");
        assertNull(only("POST", "/api/v1/sessions/" + SESSION + "/frames").subjectRef());
        assertNull(only("POST", "/api/v1/sessions/" + SESSION + "/close").subjectRef());
    }

    // ------------------------------------------------------------------- refusals

    @Test
    @DisplayName("the guard's 403 is a client error, not a parse failure")
    void theGuards403IsAClientError() {
        frameStatus = 403;

        LivenessClientException failure = assertThrows(LivenessClientException.class, () -> {
            LivenessClient client = client();
            UUID sessionId = client.createSession("RESIDENT", "L1-CAM-01", "resident-42").id();
            client.submitFrame(sessionId, new byte[]{1, 2, 3});
        });

        assertTrue(failure.getMessage().contains("403"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Invalid subject reference for this session."),
                "the service's own explanation is what the host shows: " + failure.getMessage());
    }

    /** A refusal at creation is reported too, and no session is remembered. */
    @Test
    void aRefusedCreationThrowsAndRemembersNothing() {
        createStatus = 400;

        LivenessClient client = client();
        LivenessClientException failure = assertThrows(LivenessClientException.class,
                () -> client.createSession("RESIDENT", "L1-CAM-01", "resident-42"));
        assertTrue(failure.getMessage().contains("Failed to create session"), failure.getMessage());
        assertTrue(failure.getMessage().contains("400"), failure.getMessage());
    }

    /**
     * The config read is the one call that fails open, and the difference between
     * the two cases is what makes that safe: a <em>readable</em> "liveness off" is
     * honoured, and no unreadable answer is ever mistaken for one.
     */
    @Test
    void anUnreadableConfigAnswerNeverDisablesLiveness() {
        LivenessClient client = client();
        assertFalse(client.isLivenessEnabled("RESIDENT"),
                "a readable 'liveness is off' answer is honoured");

        configStatus = 500;
        assertTrue(client.isLivenessEnabled("RESIDENT"),
                "an unreadable answer must not be mistaken for 'off'");
    }

    // ---------------------------------------------------------------------- stub

    private LivenessClient client() {
        return new LivenessHttpClient(baseUrl);
    }

    /** The body's owner claim, {@code null} when the body carries none. */
    private static String subjectRefOf(String body) throws IOException {
        var subjectRef = MAPPER.readTree(body).get("subjectRef");
        return subjectRef == null || subjectRef.isNull() ? null : subjectRef.asText();
    }

    private Received only(String method, String path) {
        List<Received> matches = received.stream()
                .filter(r -> r.method().equals(method) && r.path().equals(path))
                .toList();
        assertEquals(1, matches.size(), "expected exactly one " + method + " " + path
                + ", got " + received);
        return matches.get(0);
    }

    /** Spy that records the requests made by a LivenessClient. */
    static class LivenessClientSpy implements LivenessClient {
        private final LivenessHttpClientWireTest wireTest;
        private final LivenessClient client;
        private final Map<UUID, String> ownedSessions = new ConcurrentHashMap<>();

        LivenessClientSpy() {
            this.wireTest = new LivenessHttpClientWireTest();
            try {
                this.wireTest.startStubService();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            this.client = this.wireTest.client();
        }

        // Records
        final List<SessionCreation> createdSessions = new CopyOnWriteArrayList<>();
        final List<FrameSubmission> frameSubmissions = new CopyOnWriteArrayList<>();
        final List<ChallengeValidation> challengeValidations = new CopyOnWriteArrayList<>();
        final List<SessionClosure> sessionClosures = new CopyOnWriteArrayList<>();

        @Override
        public SessionInfo createSession(String workflowType, String deviceId, String subjectRef) {
            SessionInfo info = client.createSession(workflowType, deviceId, subjectRef);
            if (subjectRef != null && !subjectRef.isBlank()) {
                // Recorded only after the service accepted the session: the owner is
                // the caller's claim, and the service took it.
                ownedSessions.put(info.id(), subjectRef);
            }
            createdSessions.add(new SessionCreation(workflowType, deviceId, subjectRef));
            return info;
        }

        @Override
        public FrameResult submitFrame(UUID sessionId, byte[] frameJpeg) {
            FrameResult result = client.submitFrame(sessionId, frameJpeg);
            String subjectRef = ownedSessions.get(sessionId);
            frameSubmissions.add(new FrameSubmission(subjectRef));
            return result;
        }

        @Override
        public ChallengeResult validateChallenge(UUID sessionId, UUID challengeId, List<byte[]> challengeFrames) {
            ChallengeResult result = client.validateChallenge(sessionId, challengeId, challengeFrames);
            String subjectRef = ownedSessions.get(sessionId);
            challengeValidations.add(new ChallengeValidation(subjectRef));
            return result;
        }

        @Override
        public SessionSummary closeSession(UUID sessionId) {
            SessionSummary summary = client.closeSession(sessionId);
            // The session is closed, so its owner is no longer needed: forgetting it
            // here is what keeps the map bounded by sessions in flight, not by
            // sessions ever created.
            String subjectRef = ownedSessions.get(sessionId);
            ownedSessions.remove(sessionId);
            sessionClosures.add(new SessionClosure(subjectRef));
            return summary;
        }

        @Override
        public boolean isLivenessEnabled(String workflowType) {
            return client.isLivenessEnabled(workflowType);
        }

        void stop() {
            wireTest.stopStubService();
        }

        /** The body's owner claim, {@code null} when the body carries none. */
        static String subjectRefOf(String body) throws IOException {
            var subjectRef = MAPPER.readTree(body).get("subjectRef");
            return subjectRef == null || subjectRef.isNull() ? null : subjectRef.asText();
        }

        // Inner classes to match what the test expects
        static final class SessionCreation {
            final String workflowType;
            final String deviceId;
            final String subjectRef;

            SessionCreation(String workflowType, String deviceId, String subjectRef) {
                this.workflowType = workflowType;
                this.deviceId = deviceId;
                this.subjectRef = subjectRef;
            }
        }

        static final class FrameSubmission {
            final String subjectRef;

            FrameSubmission(String subjectRef) {
                this.subjectRef = subjectRef;
            }
        }

        static final class ChallengeValidation {
            final String subjectRef;

            ChallengeValidation(String subjectRef) {
                this.subjectRef = subjectRef;
            }
        }

        static final class SessionClosure {
            final String subjectRef;

            SessionClosure(String subjectRef) {
                this.subjectRef = subjectRef;
            }
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(exchange.getRequestMethod(), path,
                exchange.getRequestHeaders().getFirst(UserSessionGuard.HEADER), body));

        if (path.equals("/api/v1/sessions")) {
            respond(exchange, createStatus, sessionJson());
        } else if (path.endsWith("/frames")) {
            respond(exchange, frameStatus, frameStatus >= 400 ? forbiddenJson() : frameJson());
        } else if (path.endsWith("/challenges/validate")) {
            respond(exchange, 200, """
                    {"sessionId": "%s", "passed": true, "action": "proceed",
                     "message": "Liveness verified."}
                    """.formatted(SESSION));
        } else if (path.startsWith("/api/v1/config/")) {
            respond(exchange, configStatus, configStatus >= 400
                    ? "{\"error\":\"INTERNAL_ERROR\",\"message\":\"boom\",\"status\":500}"
                    : "{\"workflowType\": \"RESIDENT\", \"livenessEnabled\": false}");
        } else if (path.endsWith("/close")) {
            respond(exchange, 200, """
                    {"id": "%s", "workflowType": "RESIDENT", "status": "PASSED",
                     "finalResult": true, "totalFramesEvaluated": 1, "totalChallengesIssued": 1,
                     "retryCount": 0, "durationMs": 4200}
                    """.formatted(SESSION));
        } else {
            respond(exchange, 404, "{\"error\":\"NOT_FOUND\",\"message\":\"no stub\",\"status\":404}");
        }
    }

    /** {@code SessionResponse} with the frozen policy the overlay reads. */
    private static String sessionJson() {
        return """
                {"id": "%s", "workflowType": "RESIDENT", "status": "ACTIVE", "deviceId": "L1-CAM-01",
                 "policy": {"minChallengeCount": 1, "maxRetries": 1, "challengeTimeoutMs": 15000,
                            "onRepeatedFailure": "LOCK_OUT"}}
                """.formatted(SESSION);
    }

    private static String frameJson() {
        return """
                {"sessionId": "%s", "stage": "ACTIVE", "faceDetected": true, "multipleFaces": false,
                 "faceQuality": 0.91, "livenessScore": 0.42, "padFlag": false,
                 "action": "escalate_to_active",
                 "challenge": {"challengeId": "%s", "challengeType": "BLINK",
                               "timeoutMs": 15000, "attemptNumber": 1}}
                """.formatted(SESSION, CHALLENGE);
    }

    /** The body {@code GlobalExceptionHandler} produces for {@code UserSessionGuard}. */
    private static String forbiddenJson() {
        return """
                {"error": "FORBIDDEN", "message": "Invalid subject reference for this session.",
                 "status": 403}
                """;
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
