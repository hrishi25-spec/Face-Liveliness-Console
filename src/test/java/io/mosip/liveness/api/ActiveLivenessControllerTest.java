package io.mosip.liveness.api;

import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.models.entity.ChallengeEntity;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.ChallengeStatus;
import io.mosip.liveness.models.enums.ChallengeType;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import io.mosip.liveness.services.DecisionEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers {@code POST /api/v1/sessions/{id}/active-liveness} — the manual extra
 * check.
 *
 * <p>The refusals are the contract: only a session that already passed can ask
 * for one (the service owns that rule), and an already-open challenge is handed
 * back rather than duplicated. The body carries {@code manual: true} so a client
 * can tell an extra check apart from an engine-issued challenge.</p>
 */
@WebMvcTest(ActiveLivenessController.class)
@Import(TestConfig.class)
class ActiveLivenessControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private LivenessSessionRepository sessionRepo;
    @Autowired private DecisionEngineService decisionEngine;

    private UUID sessionId;
    private LivenessSession session;

    @BeforeEach
    void setUp() {
        reset(sessionRepo, decisionEngine);
        sessionId = UUID.randomUUID();
        session = LivenessSession.builder()
                .id(sessionId).workflowType(WorkflowType.RESIDENT).deviceId("WEB-CAM")
                .status(SessionStatus.PASSED).currentStage(LivenessStage.COMPLETED)
                .retryCount(0).online(true).finalResult(true)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now())
                .build();
    }

    private String url() {
        return "/api/v1/sessions/" + sessionId + "/active-liveness";
    }

    @Test
    void unknownSession_returns404() throws Exception {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.empty());

        mockMvc.perform(post(url()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void passedSession_returnsTheManualChallengeAsCreated() throws Exception {
        ChallengeEntity challenge = ChallengeEntity.builder()
                .id(UUID.randomUUID()).session(session)
                .challengeType(ChallengeType.BLINK).status(ChallengeStatus.ISSUED)
                .attemptNumber(3).timeoutMs(15_000).manual(true)
                .issuedAt(OffsetDateTime.now())
                .build();
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(decisionEngine.startActiveLiveness(session)).thenReturn(challenge);

        mockMvc.perform(post(url()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(challenge.getId().toString()))
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.challengeType").value("BLINK"))
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.manual").value(true));
    }

    /**
     * An extra check is an action on somebody's session like any other: an owned
     * session answers only its owner, and no challenge is issued on a refusal.
     */
    @Test
    void anOwnedSessionRequiresItsSubjectRef() throws Exception {
        session.setSubjectRef("resident-42");
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));

        mockMvc.perform(post(url()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(url()).header("X-Subject-Ref", "resident-43"))
                .andExpect(status().isForbidden());
        verify(decisionEngine, never()).startActiveLiveness(any());
    }

    /**
     * The service's refusals (not passed, active liveness disabled, no challenge
     * types) must reach the client as structured 409s, not opaque 500s.
     */
    @Test
    void refusedRequest_returnsStructuredConflict() throws Exception {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(decisionEngine.startActiveLiveness(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                        "Active liveness is not enabled for this workflow"));

        mockMvc.perform(post(url()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value("Active liveness is not enabled for this workflow"));
    }
}
