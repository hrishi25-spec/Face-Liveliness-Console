package io.mosip.liveness.client;

import java.util.List;
import java.util.UUID;

/**
 * Platform-agnostic interface for Registration Client integration.
 *
 * <p>Both Desktop (JavaFX) and Android (Flutter via Pigeon bridge) use this
 * interface to drive the liveness/PAD pipeline. The concrete implementation
 * is either an HTTP client (calling this Spring Boot service) or a direct
 * in-process engine call (Option A from the integration plan).</p>
 *
 * <h3>Typical call sequence:</h3>
 * <pre>
 *   UUID sessionId = client.createSession("RESIDENT", "L1-CAM-01");
 *   FrameResult result = client.submitFrame(sessionId, frameJpeg);
 *   // if result.action == "escalate_to_active":
 *   //   show prompt from result.challengeType to the user
 *   //   capture N frames during the challenge
 *   ChallengeResult cResult = client.validateChallenge(sessionId, challengeId, challengeFrames);
 *   // if cResult.action == "retry_challenge": repeat
 *   // if cResult.action == "proceed": capture accepted
 *   SessionSummary summary = client.closeSession(sessionId);
 * </pre>
 */
public interface LivenessClient {

    // ---- Session lifecycle ----

    /**
     * Start a new liveness verification session owned by {@code subjectRef}.
     *
     * <p>An owned session may only be acted on by a caller presenting the same
     * reference in the {@code X-Subject-Ref} header — see
     * {@code docs/client-integration-guide.md} §5.5 — so the session flow after
     * this call (frames, challenge validation, close) is the owning subject's
     * alone. An implementation that talks to the service is expected to carry the
     * reference on those calls itself rather than make every caller repeat it.
     *
     * @param workflowType one of "RESIDENT", "OPERATOR", "SUPERVISOR"
     * @param deviceId     identifier of the capture device (e.g. "L1-CAM-01")
     * @param subjectRef   the resident/operator/supervisor this session belongs to,
     *                     or {@code null} for an anonymous session (no header, no
     *                     ownership check — the pre-existing behaviour)
     * @return session response with the new session ID
     */
    SessionInfo createSession(String workflowType, String deviceId, String subjectRef);

    /**
     * Start an anonymous liveness verification session (no owner).
     *
     * @param workflowType one of "RESIDENT", "OPERATOR", "SUPERVISOR"
     * @param deviceId     identifier of the capture device (e.g. "L1-CAM-01")
     * @return session response with the new session ID
     */
    default SessionInfo createSession(String workflowType, String deviceId) {
        return createSession(workflowType, deviceId, null);
    }

    /**
     * Close a session and get the audit summary.
     */
    SessionSummary closeSession(UUID sessionId);

    // ---- Frame submission ----

    /**
     * Submit a single JPEG frame for passive liveness + PAD evaluation.
     *
     * @param sessionId  active session ID
     * @param frameJpeg  raw JPEG bytes of the face frame
     * @return processing result — check {@code action} field
     */
    FrameResult submitFrame(UUID sessionId, byte[] frameJpeg);

    // ---- Challenge validation ----

    /**
     * Validate challenge frames captured during an active liveness challenge.
     *
     * @param sessionId     active session ID
     * @param challengeId   challenge ID from the escalation response
     * @param challengeJpegFrames  list of JPEG frames captured during the challenge window
     * @return validation result — check {@code action} field
     */
    ChallengeResult validateChallenge(UUID sessionId, UUID challengeId, List<byte[]> challengeJpegFrames);

    // ---- Config (read-only for clients) ----

    /**
     * Check whether liveness is enabled for a given workflow.
     */
    boolean isLivenessEnabled(String workflowType);

    // ---- Response DTOs (inner records) ----

    record SessionInfo(
            UUID id,
            String workflowType,
            String status,
            Policy policy           // null on sessions created before the policy was frozen
    ) {}

    /**
     * The policy the service froze on the session at creation. The client reads it
     * for the two things it cannot infer from the responses — how many challenges
     * the session needs (the overlay's "Challenge 1 / N" counter) and how large the
     * retry budget is — plus the repeated-failure action that decides the terminal
     * outcome. Fields are 0/absent when the service did not signal them.
     *
     * @param onRepeatedFailure LOCK_OUT | FALLBACK | ESCALATE_TO_OPERATOR
     */
    record Policy(
            int minChallengeCount,
            int maxRetries,
            long challengeTimeoutMs,
            String onRepeatedFailure
    ) {}

    /** A challenge the service issued, as carried by frame and validation responses. */
    record Challenge(
            String challengeId,
            String challengeType,   // BLINK | SMILE | TURN_HEAD_LEFT | TURN_HEAD_RIGHT | LOOK_DIRECTION
            Integer timeoutMs,
            Integer attemptNumber
    ) {}

    record FrameResult(
            UUID sessionId,
            String stage,
            boolean faceDetected,
            boolean multipleFaces,
            Double faceQuality,
            Double livenessScore,
            boolean padFlag,
            String padAttackType,
            String action,          // proceed | escalate_to_active | reject | retry_passive
                                    // | locked | escalate_to_operator | failed
            Challenge challenge,    // non-null when action == escalate_to_active
            String message
    ) {}

    record ChallengeResult(
            UUID sessionId,
            boolean passed,
            String action,          // proceed | retry_challenge | continue | reject
            String message,
            Challenge challenge     // the next challenge when the service issued one, else null
    ) {}

    record SessionSummary(
            UUID id,
            String workflowType,
            String status,
            Boolean finalResult,
            String failureReason,
            Integer totalFramesEvaluated,
            Integer totalChallengesIssued,
            Integer retryCount,
            Long durationMs
    ) {}
}
