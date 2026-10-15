package io.mosip.liveness.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The wire contract the service-mediated path depends on: the frames the frozen
 * policy and the issued challenge arrive in, pinned from JSON literals of the
 * real response DTOs so a server-side field rename shows up here (and not as a
 * silently missing challenge counter on the overlay).
 */
class LivenessHttpClientParseTest {

    private static final String SESSION = "11111111-2222-3333-4444-555555555555";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ----------------------------------------------------------- create session

    @Test
    @DisplayName("a created session carries the policy the service froze")
    void aCreatedSessionCarriesTheFrozenPolicy() throws Exception {
        LivenessClient.SessionInfo info = LivenessHttpClient.parseSessionInfo(json("""
                {
                  "id": "11111111-2222-3333-4444-555555555555",
                  "workflowType": "SUPERVISOR",
                  "status": "ACTIVE",
                  "deviceId": "L1-CAM-01",
                  "policy": {
                    "livenessEnabled": true,
                    "activeLivenessEnabled": true,
                    "passiveThreshold": 0.85,
                    "minChallengeCount": 2,
                    "maxRetries": 1,
                    "challengeTimeoutMs": 15000,
                    "allowedChallenges": ["BLINK", "SMILE"],
                    "onRepeatedFailure": "LOCK_OUT",
                    "maxSessionDurationMs": 60000,
                    "frameSamplingRate": 3
                  }
                }
                """));

        assertEquals(UUID.fromString(SESSION), info.id());
        assertEquals("SUPERVISOR", info.workflowType());
        assertNotNull(info.policy());
        assertEquals(2, info.policy().minChallengeCount(),
                "the overlay's \"Challenge 1 / N\" counter comes from here");
        assertEquals(1, info.policy().maxRetries());
        assertEquals(15_000L, info.policy().challengeTimeoutMs());
        assertEquals("LOCK_OUT", info.policy().onRepeatedFailure(),
                "the frozen repeated-failure mode decides the terminal next action");
    }

    @Test
    void aLegacySessionWithoutAPolicyStillParses() throws Exception {
        assertNull(LivenessHttpClient.parseSessionInfo(json("""
                {"id": "11111111-2222-3333-4444-555555555555", "workflowType": "RESIDENT", "status": "ACTIVE"}
                """)).policy());
        assertNull(LivenessHttpClient.parseSessionInfo(json("""
                {"id": "11111111-2222-3333-4444-555555555555", "workflowType": "RESIDENT",
                 "status": "ACTIVE", "policy": null}
                """)).policy());
    }

    @Test
    void aPolicyWithMissingFieldsDoesNotBlowUp() throws Exception {
        LivenessClient.Policy policy = LivenessHttpClient.parsePolicy(json("{}"));
        assertEquals(0, policy.minChallengeCount(), "0 = the service signalled nothing");
        assertEquals(0, policy.maxRetries());
        assertEquals(0L, policy.challengeTimeoutMs());
        assertNull(policy.onRepeatedFailure());
    }

    // -------------------------------------------------------------- frame results

    @Test
    @DisplayName("a frame response carries the issued challenge")
    void aFrameResponseCarriesTheIssuedChallenge() throws Exception {
        LivenessClient.FrameResult result = LivenessHttpClient.parseFrameResult(json("""
                {
                  "sessionId": "11111111-2222-3333-4444-555555555555",
                  "stage": "ACTIVE",
                  "faceDetected": true,
                  "multipleFaces": false,
                  "faceQuality": 0.91,
                  "livenessScore": 0.42,
                  "padFlag": false,
                  "action": "escalate_to_active",
                  "challenge": {
                    "challengeId": "aaaaaaaa-0000-0000-0000-000000000001",
                    "challengeType": "TURN_HEAD_LEFT",
                    "timeoutMs": 15000,
                    "attemptNumber": 1
                  },
                  "message": "Please turn head left."
                }
                """));

        assertEquals("escalate_to_active", result.action());
        assertTrue(result.faceDetected());
        assertFalse(result.multipleFaces());
        assertEquals(0.42, result.livenessScore(), 1e-9);
        assertNull(result.padAttackType());
        assertNotNull(result.challenge(), "the prompt cannot be rendered without it");
        assertEquals("aaaaaaaa-0000-0000-0000-000000000001", result.challenge().challengeId());
        assertEquals("TURN_HEAD_LEFT", result.challenge().challengeType());
        assertEquals(15_000, result.challenge().timeoutMs());
        assertEquals(1, result.challenge().attemptNumber());
    }

    @Test
    void aMinimalFrameResponseParses() throws Exception {
        LivenessClient.FrameResult result = LivenessHttpClient.parseFrameResult(json("""
                {"sessionId": "11111111-2222-3333-4444-555555555555", "stage": "PASSIVE",
                 "action": "retry_passive"}
                """));
        assertFalse(result.faceDetected());
        assertFalse(result.multipleFaces());
        assertNull(result.challenge(), "no challenge on a scoring frame");
        assertNull(result.faceQuality());
        assertNull(result.padAttackType());
    }

    @Test
    void explicitJsonNullsStayNull() throws Exception {
        LivenessClient.FrameResult result = LivenessHttpClient.parseFrameResult(json("""
                {"sessionId": "11111111-2222-3333-4444-555555555555", "stage": "COMPLETED",
                 "action": "reject", "padAttackType": null, "challenge": null, "message": null,
                 "livenessScore": null}
                """));
        assertNull(result.padAttackType(), "\"null\" must not become the string \"null\"");
        assertNull(result.challenge());
        assertNull(result.message());
        assertNull(result.livenessScore());
    }

    // --------------------------------------------------------- challenge results

    @Test
    @DisplayName("a validation response carries the follow-up challenge the service issued")
    void aValidationResponseCarriesTheFollowUpChallenge() throws Exception {
        // The nested object on this endpoint is a ChallengeResponse, which names
        // the id "id" — not the frame endpoint's "challengeId".
        LivenessClient.ChallengeResult result = LivenessHttpClient.parseChallengeResult(
                UUID.fromString(SESSION), json("""
                        {
                          "sessionId": "11111111-2222-3333-4444-555555555555",
                          "passed": true,
                          "action": "retry_challenge",
                          "message": "Action detected. One more check required.",
                          "challenge": {
                            "id": "aaaaaaaa-0000-0000-0000-000000000002",
                            "sessionId": "11111111-2222-3333-4444-555555555555",
                            "challengeType": "SMILE",
                            "status": "ISSUED",
                            "attemptNumber": 1,
                            "timeoutMs": 15000
                          }
                        }
                        """));

        assertTrue(result.passed());
        assertEquals("retry_challenge", result.action());
        assertEquals("aaaaaaaa-0000-0000-0000-000000000002", result.challenge().challengeId());
        assertEquals("SMILE", result.challenge().challengeType(),
                "the follow-up prompt is rendered from this type");
    }

    @Test
    void aValidationWithoutAChallengeOrVerdictIsSafe() throws Exception {
        LivenessClient.ChallengeResult result = LivenessHttpClient.parseChallengeResult(
                UUID.fromString(SESSION), json("""
                        {"sessionId": "11111111-2222-3333-4444-555555555555",
                         "action": "continue", "message": "Not detected yet."}
                        """));
        assertFalse(result.passed(), "an absent verdict is never a pass");
        assertEquals("continue", result.action());
        assertNull(result.challenge(), "the window is still open on the current challenge");
    }

    private static JsonNode json(String literal) throws Exception {
        return MAPPER.readTree(literal);
    }
}
