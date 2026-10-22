package io.mosip.liveness.client;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;

/**
 * HTTP client implementation of {@link LivenessClient}.
 *
 * <p>Calls the Spring Boot REST API endpoints. Suitable for both Desktop
 * (Java 11+ HttpClient) and Android (OkHttp or similar — adapt the
 * transport layer). The JSON mapping uses Jackson 3's {@link ObjectMapper}
 * ({@code tools.jackson}, the mapper Spring Boot 4 serves with).</p>
 *
 * <h3>Usage from Desktop (JavaFX):</h3>
 * <pre>
 *   LivenessClient client = new LivenessHttpClient("http://localhost:8000");
 *   SessionInfo session = client.createSession("RESIDENT", "L1-CAM-01", "resident-42");
 *   FrameResult result = client.submitFrame(session.id(), jpegBytes);
 * </pre>
 *
 * <p>Passing a subject reference makes the session <em>owned</em>: this client
 * then presents it in {@code X-Subject-Ref} on every session-scoped call it makes
 * for that session, and the service refuses the calls of any other caller. Omit
 * it for an anonymous session, which needs no header. See
 * {@code docs/client-integration-guide.md} §5.5.</p>
 *
 * <h3>Usage from Android (via Pigeon bridge):</h3>
 * <pre>
 *   // In HostApiModule.java (native Java side):
 *   LivenessClient client = new LivenessHttpClient("http://10.0.2.2:8000");
 *   // Feed results back to Dart via Pigeon response objects
 * </pre>
 *
 * <p>For environments requiring custom TLS configuration (e.g., certificate pinning),
 * use the {@link #LivenessHttpClient(String, SSLContext)} overload.</p>
 */
public class LivenessHttpClient implements LivenessClient {

    /**
     * The ownership header, as {@code UserSessionGuard.HEADER} spells it. Declared
     * here rather than imported so this package stays free of the web layer (it is
     * the transport hosts reuse), with {@code LivenessHttpClientWireTest} pinning
     * the two names together — the guard refuses the session if they ever differ.
     */
    static final String SUBJECT_REF_HEADER = "X-Subject-Ref";

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * The owner of each session this client created with one, so the header the
     * guard demands can be attached to the session flow without every caller
     * repeating it. A session created without a subject reference is absent here
     * and stays anonymous, which is the behaviour hosts had before.
     */
    private final Map<UUID, String> ownedSessions = new ConcurrentHashMap<>();

    /** Creates an instance with default JDK TLS settings. */
    public LivenessHttpClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = JsonMapper.shared();
    }

    /**
     * Creates an instance with the provided {@code SSLContext}, allowing custom
     * trust stores, certificate pinning, or other TLS adjustments.
     *
     * @param baseUrl   the base URL of the liveness service (trailing slash optional)
     * @param sslContext the SSLContext to use for HTTPS connections
     */
    public LivenessHttpClient(String baseUrl, SSLContext sslContext) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();
        this.objectMapper = JsonMapper.shared();
    }

    // ---- Session lifecycle ----

    @Override
    public SessionInfo createSession(String workflowType, String deviceId, String subjectRef) {
        try {
            String body = objectMapper.writeValueAsString(
                    new CreateSessionBody(workflowType, deviceId, subjectRef));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/sessions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            SessionInfo session = parseSessionInfo(send(request, "create session"));
            if (subjectRef != null && !subjectRef.isBlank()) {
                // Recorded only after the service accepted the session: the owner is
                // the caller's claim, and the service took it.
                ownedSessions.put(session.id(), subjectRef);
            }
            return session;
        } catch (IOException | InterruptedException e) {
            throw new LivenessClientException("Failed to create session", e);
        }
    }

    @Override
    public SessionSummary closeSession(UUID sessionId) {
        try {
            HttpRequest request = sessionRequest(sessionId,
                    "/api/v1/sessions/" + sessionId + "/close")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            JsonNode json = send(request, "close session");
            // The session is closed, so its owner is no longer needed: forgetting it
            // here is what keeps the map bounded by sessions in flight, not by
            // sessions ever created.
            ownedSessions.remove(sessionId);

            return new SessionSummary(
                    UUID.fromString(json.get("id").asText()),
                    json.get("workflowType").asText(),
                    json.get("status").asText(),
                    json.has("finalResult") && !json.get("finalResult").isNull()
                            ? json.get("finalResult").asBoolean() : null,
                    json.has("failureReason") && !json.get("failureReason").isNull()
                            ? json.get("failureReason").asText() : null,
                    json.has("totalFramesEvaluated") ? json.get("totalFramesEvaluated").asInt() : 0,
                    json.has("totalChallengesIssued") ? json.get("totalChallengesIssued").asInt() : 0,
                    json.has("retryCount") ? json.get("retryCount").asInt() : 0,
                    json.has("durationMs") ? json.get("durationMs").asLong() : 0L
            );
        } catch (IOException | InterruptedException e) {
            throw new LivenessClientException("Failed to close session", e);
        }
    }

    // ---- Frame submission ----

    @Override
    public FrameResult submitFrame(UUID sessionId, byte[] frameJpeg) {
        try {
            String frameBase64 = Base64.getEncoder().encodeToString(frameJpeg);
            String body = objectMapper.writeValueAsString(new SubmitFrameBody(frameBase64));

            HttpRequest request = sessionRequest(sessionId,
                    "/api/v1/sessions/" + sessionId + "/frames")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            return parseFrameResult(send(request, "submit frame"));
        } catch (IOException | InterruptedException e) {
            throw new LivenessClientException("Failed to submit frame", e);
        }
    }

    // ---- Challenge validation ----

    @Override
    public ChallengeResult validateChallenge(UUID sessionId, UUID challengeId, List<byte[]> challengeJpegFrames) {
        try {
            List<String> framesBase64 = challengeJpegFrames.stream()
                    .map(f -> Base64.getEncoder().encodeToString(f))
                    .toList();

            String body = objectMapper.writeValueAsString(
                    new ValidateChallengeBody(challengeId.toString(), framesBase64));

            HttpRequest request = sessionRequest(sessionId,
                    "/api/v1/sessions/" + sessionId + "/challenges/validate")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            return parseChallengeResult(sessionId, send(request, "validate challenge"));
        } catch (IOException | InterruptedException e) {
            throw new LivenessClientException("Failed to validate challenge", e);
        }
    }

    // ---- Config check ----

    @Override
    public boolean isLivenessEnabled(String workflowType) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/config/" + workflowType.toUpperCase()))
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            JsonNode json = send(request, "read config");
            return json.has("livenessEnabled") && json.get("livenessEnabled").asBoolean();
        } catch (IOException | InterruptedException | LivenessClientException e) {
            // Default to enabled when the config answer cannot be read at all —
            // including a 4xx, which used to surface as a missing field and the same
            // false. Never bypass liveness because the answer was unreadable.
            return true;
        }
    }

    // ---- Transport helpers ----

    /**
     * A session-scoped request builder: the JSON content type plus the session's
     * ownership header when this client is the session's owner.
     *
     * <p>An unknown session (one this client did not create) is sent without the
     * header on purpose. The client cannot invent a claim it was never given, and
     * the service answers 403 for an owned session either way — which
     * {@link #send} surfaces as a {@link LivenessClientException} rather than as a
     * response the JSON parser then has to make sense of.</p>
     */
    private HttpRequest.Builder sessionRequest(UUID sessionId, String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json");
        String subjectRef = ownedSessions.get(sessionId);
        if (subjectRef != null) {
            builder.header(SUBJECT_REF_HEADER, subjectRef);
        }
        return builder;
    }

    /**
     * Sends a request and decodes a JSON response, turning any HTTP error into a
     * {@link LivenessClientException}.
     *
     * <p>Before this existed each method parsed the body whatever the status was,
     * so a 4xx — an error object with no {@code id}, no {@code stage}, no
     * {@code action} — failed as a {@link NullPointerException} inside a parser or
     * as a silently defaulted field. The guard's 403 is the reachable case: a
     * mismatched subject reference has to read as a refusal, not as a bug in the
     * client.</p>
     */
    private JsonNode send(HttpRequest request, String action) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new LivenessClientException("Failed to " + action + ": HTTP "
                    + response.statusCode() + ": " + errorMessage(response.body()));
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * The service's {@code message} field when the error body carries one
     * ({@code GlobalExceptionHandler}'s shape), else the raw body — which is never
     * PII for an error response, and is the only thing left to say when the error
     * did not come from this service at all (a proxy, say).
     */
    private String errorMessage(String body) {
        if (body == null) {
            return "";
        }
        try {
            JsonNode message = objectMapper.readTree(body).get("message");
            if (message != null && !message.isNull()) {
                return message.asText();
            }
        } catch (JacksonException notJson) {
            // fall through to the raw body
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    // ---- JSON parsing helpers ----
    //
    // Package-private and static: the response shapes are the part of this client
    // worth pinning in a test, and they can be pinned from a JSON literal without
    // standing up a server.

    static SessionInfo parseSessionInfo(JsonNode json) {
        return new SessionInfo(
                UUID.fromString(json.get("id").asText()),
                json.get("workflowType").asText(),
                json.get("status").asText(),
                parsePolicy(json.get("policy"))
        );
    }

    /**
     * The frozen policy is {@code null} on legacy sessions; every field is read
     * defensively so a policy that later grows cannot break an older client.
     */
    static Policy parsePolicy(JsonNode policy) {
        if (policy == null || policy.isNull() || !policy.isObject()) {
            return null;
        }
        return new Policy(
                policy.has("minChallengeCount") ? policy.get("minChallengeCount").asInt() : 0,
                policy.has("maxRetries") ? policy.get("maxRetries").asInt() : 0,
                policy.has("challengeTimeoutMs") ? policy.get("challengeTimeoutMs").asLong() : 0L,
                policy.has("onRepeatedFailure") && !policy.get("onRepeatedFailure").isNull()
                        ? policy.get("onRepeatedFailure").asText() : null
        );
    }

    static FrameResult parseFrameResult(JsonNode json) {
        return new FrameResult(
                UUID.fromString(json.get("sessionId").asText()),
                json.get("stage").asText(),
                json.has("faceDetected") && json.get("faceDetected").asBoolean(),
                json.has("multipleFaces") && json.get("multipleFaces").asBoolean(),
                json.has("faceQuality") && !json.get("faceQuality").isNull()
                        ? json.get("faceQuality").asDouble() : null,
                json.has("livenessScore") && !json.get("livenessScore").isNull()
                        ? json.get("livenessScore").asDouble() : null,
                json.has("padFlag") && json.get("padFlag").asBoolean(),
                json.has("padAttackType") && !json.get("padAttackType").isNull()
                        ? json.get("padAttackType").asText() : null,
                json.get("action").asText(),
                parseChallenge(json.get("challenge")),
                json.has("message") && !json.get("message").isNull()
                        ? json.get("message").asText() : null
        );
    }

    static ChallengeResult parseChallengeResult(UUID sessionId, JsonNode json) {
        return new ChallengeResult(
                sessionId,
                json.has("passed") && json.get("passed").asBoolean(),
                json.get("action").asText(),
                json.has("message") && !json.get("message").isNull()
                        ? json.get("message").asText() : null,
                parseChallenge(json.get("challenge"))
        );
    }

    /**
     * The nested challenge object, or {@code null} when the response carries none.
     * Frame responses name the id {@code challengeId} and validation responses
     * ({@code ChallengeResponse}) name it {@code id}; both are accepted here so the
     * client does not depend on which endpoint produced the object.
     */
    static Challenge parseChallenge(JsonNode challenge) {
        if (challenge == null || challenge.isNull() || !challenge.isObject()) {
            return null;
        }
        return new Challenge(
                text(challenge, "challengeId", "id"),
                text(challenge, "challengeType"),
                integer(challenge, "timeoutMs"),
                integer(challenge, "attemptNumber")
        );
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            if (node.has(name) && !node.get(name).isNull() && !node.get(name).asText().isEmpty()) {
                return node.get(name).asText();
            }
        }
        return null;
    }

    private static Integer integer(JsonNode node, String name) {
        return node.has(name) && !node.get(name).isNull() ? node.get(name).asInt() : null;
    }

    // ---- Internal request body records ----

    // subjectRef is always written, null included: the service reads an absent and
    // a null reference alike (both mean "no owner"), so the anonymous case needs no
    // separate body shape.
    @SuppressWarnings("unused")
    private record CreateSessionBody(String workflowType, String deviceId, String subjectRef) {}
    @SuppressWarnings("unused")
    private record SubmitFrameBody(String frameBase64) {}
    @SuppressWarnings("unused")
    private record ValidateChallengeBody(String challengeId, List<String> framesBase64) {}
}
