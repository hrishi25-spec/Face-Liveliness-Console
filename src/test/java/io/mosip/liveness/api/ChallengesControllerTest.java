package io.mosip.liveness.api;

import tools.jackson.databind.ObjectMapper;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.dto.ChallengeValidateRequest;
import io.mosip.liveness.models.entity.ChallengeEntity;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.ChallengeStatus;
import io.mosip.liveness.models.enums.ChallengeType;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import io.mosip.liveness.services.DecisionEngineService;
import io.mosip.liveness.services.ImageUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers {@code POST /api/v1/sessions/{id}/challenges/validate}.
 *
 * <p>This endpoint previously returned HTTP 500 for <em>every</em> request:
 * {@code challengeId} was annotated {@code @NotBlank}, which only supports
 * {@code CharSequence}, so Hibernate Validator threw
 * {@code UnexpectedTypeException} before the handler ran. The
 * {@code missingChallengeId_returns400NotServerError} case is the regression
 * guard for that.</p>
 */
@WebMvcTest(ChallengesController.class)
@Import(TestConfig.class)
class ChallengesControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LivenessSessionRepository sessionRepo;
    @Autowired private ChallengeRepository challengeRepo;
    @Autowired private DecisionEngineService decisionEngine;
    @Autowired private ImageUtils imageUtils;

    private UUID sessionId;
    private LivenessSession session;
    private ChallengeEntity challenge;

    @BeforeEach
    void setUp() {
        reset(sessionRepo, challengeRepo, decisionEngine, imageUtils);
        sessionId = UUID.randomUUID();
        session = LivenessSession.builder()
                .id(sessionId).workflowType(WorkflowType.RESIDENT).deviceId("L1-CAM-01")
                .status(SessionStatus.ACTIVE).currentStage(LivenessStage.ACTIVE)
                .retryCount(0).online(true)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now())
                .build();
        challenge = ChallengeEntity.builder()
                .id(UUID.randomUUID()).session(session)
                .challengeType(ChallengeType.TURN_LEFT).status(ChallengeStatus.ISSUED)
                .attemptNumber(1).timeoutMs(8000).issuedAt(OffsetDateTime.now())
                .build();
    }

    private String url() {
        return "/api/v1/sessions/" + sessionId + "/challenges/validate";
    }

    @Test
    void missingChallengeId_returns400NotServerError() throws Exception {
        mockMvc.perform(post(url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"framesBase64\":[\"dGVzdA==\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void missingFrames_returns400() throws Exception {
        mockMvc.perform(post(url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ChallengeValidateRequest.builder()
                                        .challengeId(challenge.getId())
                                        .framesBase64(List.of())
                                        .build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    /**
     * Challenge frames are the session's own capture, so an owned session answers
     * only its owner — and the refusal happens before any frame is decoded.
     */
    @Test
    void validatingAnotherSubjectsSessionIsRefused() throws Exception {
        session.setSubjectRef("resident-42");
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(challengeRepo.findById(challenge.getId())).thenReturn(Optional.of(challenge));

        mockMvc.perform(post(url())
                        .header("X-Subject-Ref", "resident-43")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ChallengeValidateRequest.builder()
                                        .challengeId(challenge.getId())
                                        .framesBase64(List.of("dGVzdA=="))
                                        .build())))
                .andExpect(status().isForbidden());
        verify(decisionEngine, never()).processChallengeValidation(any(), any(), anyList(), any());
    }

    @Test
    void validRequest_passes_returns200() throws Exception {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(challengeRepo.findById(challenge.getId())).thenReturn(Optional.of(challenge));
        Mat frame = mock(Mat.class);
        when(imageUtils.decodeBase64Frame(anyString())).thenReturn(frame);
        when(decisionEngine.processChallengeValidation(any(), any(), anyList(), any()))
                .thenReturn(Map.of(
                        "passed", true,
                        "action", "proceed",
                        "message", "Liveness verified."));

        mockMvc.perform(post(url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ChallengeValidateRequest.builder()
                                        .challengeId(challenge.getId())
                                        .framesBase64(List.of("dGVzdA==", "dGVzdA=="))
                                        .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.action").value("proceed"))
                .andExpect(jsonPath("$.challenge.challengeType").value("TURN_LEFT"));
    }

    @Test
    void sessionNotFound_returns404() throws Exception {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.empty());
        mockMvc.perform(post(url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ChallengeValidateRequest.builder()
                                        .challengeId(challenge.getId())
                                        .framesBase64(List.of("dGVzdA=="))
                                        .build())))
                .andExpect(status().isNotFound());
    }

    /**
     * A session that is neither ACTIVE nor PASSED can never validate a challenge.
     * (A PASSED session may, but only for a manually requested extra check — see
     * {@link #engineChallengeOnAPassedSession_isRefused()}.)
     */
    @Test
    void sessionExpired_returns409() throws Exception {
        session.setStatus(SessionStatus.EXPIRED);
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        mockMvc.perform(post(url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ChallengeValidateRequest.builder()
                                        .challengeId(challenge.getId())
                                        .framesBase64(List.of("dGVzdA=="))
                                        .build())))
                .andExpect(status().isConflict());
    }

    /**
     * The manual extra check is validated on a session that already passed, so
     * PASSED sessions are accepted — but only for a challenge the manual
     * endpoint marked. An engine-issued challenge on a passed session stays
     * refused: its verdict could otherwise be replayed against a closed verdict.
     */
    @Test
    void engineChallengeOnAPassedSession_isRefused() throws Exception {
        session.setStatus(SessionStatus.PASSED);
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(challengeRepo.findById(challenge.getId())).thenReturn(Optional.of(challenge));

        mockMvc.perform(post(url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ChallengeValidateRequest.builder()
                                        .challengeId(challenge.getId())
                                        .framesBase64(List.of("dGVzdA=="))
                                        .build())))
                .andExpect(status().isConflict());
    }

    @Test
    void manualChallengeOnAPassedSession_isAccepted() throws Exception {
        session.setStatus(SessionStatus.PASSED);
        challenge.setManual(true);
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(challengeRepo.findById(challenge.getId())).thenReturn(Optional.of(challenge));
        Mat frame = mock(Mat.class);
        when(imageUtils.decodeBase64Frame(anyString())).thenReturn(frame);
        when(decisionEngine.processChallengeValidation(any(), any(), anyList(), any()))
                .thenReturn(Map.of(
                        "passed", true,
                        "action", "manual_check_passed",
                        "message", "Action detected. Extra check passed — the session result "
                                + "is unchanged."));

        mockMvc.perform(post(url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ChallengeValidateRequest.builder()
                                        .challengeId(challenge.getId())
                                        .framesBase64(List.of("dGVzdA=="))
                                        .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("manual_check_passed"))
                .andExpect(jsonPath("$.challenge.manual").value(true));
    }

    @Test
    void challengeBelongingToAnotherSession_returns404() throws Exception {
        ChallengeEntity foreign = ChallengeEntity.builder()
                .id(UUID.randomUUID())
                .session(LivenessSession.builder().id(UUID.randomUUID()).build())
                .challengeType(ChallengeType.BLINK).status(ChallengeStatus.ISSUED)
                .attemptNumber(1).timeoutMs(8000)
                .build();
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(challengeRepo.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        mockMvc.perform(post(url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ChallengeValidateRequest.builder()
                                        .challengeId(foreign.getId())
                                        .framesBase64(List.of("dGVzdA=="))
                                        .build())))
                .andExpect(status().isNotFound());
    }

    @Test
    void alreadyValidatedChallenge_returns409() throws Exception {
        challenge.setStatus(ChallengeStatus.PASSED);
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(challengeRepo.findById(challenge.getId())).thenReturn(Optional.of(challenge));

        mockMvc.perform(post(url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ChallengeValidateRequest.builder()
                                        .challengeId(challenge.getId())
                                        .framesBase64(List.of("dGVzdA=="))
                                        .build())))
                .andExpect(status().isConflict());
    }

    /**
     * A {@code retry_challenge} verdict must hand back a brand-new challenge.
     * Returning the just-resolved one made the client's next call fail with 409,
     * because that challenge was already PASSED or FAILED.
     */
    @Test
    void retryChallenge_returnsNewlyIssuedChallengeNotTheResolvedOne() throws Exception {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(challengeRepo.findById(challenge.getId())).thenReturn(Optional.of(challenge));
        Mat frame = mock(Mat.class);
        when(imageUtils.decodeBase64Frame(anyString())).thenReturn(frame);

        ChallengeEntity next = ChallengeEntity.builder()
                .id(UUID.randomUUID()).session(session)
                .challengeType(ChallengeType.SMILE).status(ChallengeStatus.ISSUED)
                .attemptNumber(2).timeoutMs(8000).issuedAt(OffsetDateTime.now())
                .build();
        when(decisionEngine.processChallengeValidation(any(), any(), anyList(), any()))
                .thenReturn(Map.of(
                        "passed", false,
                        "action", "retry_challenge",
                        "message", "We could not verify that action. Let's try a different one.",
                        "challenge", next));

        mockMvc.perform(post(url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ChallengeValidateRequest.builder()
                                        .challengeId(challenge.getId())
                                        .framesBase64(List.of("dGVzdA=="))
                                        .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("retry_challenge"))
                .andExpect(jsonPath("$.challenge.id").value(next.getId().toString()))
                .andExpect(jsonPath("$.challenge.status").value("ISSUED"))
                .andExpect(jsonPath("$.challenge.challengeType").value("SMILE"));
    }
}
