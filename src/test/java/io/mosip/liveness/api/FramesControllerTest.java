package io.mosip.liveness.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.liveness.dto.FrameSubmitRequest;
import io.mosip.liveness.dto.FrameProcessResult;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.diagnostics.DiagnosticsService;
import io.mosip.liveness.services.DecisionEngineService;
import io.mosip.liveness.services.ImageUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencv.core.Mat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FramesController.class)
@Import(TestConfig.class)
class FramesControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LivenessSessionRepository sessionRepo;
    @Autowired private DecisionEngineService decisionEngine;
    @Autowired private ImageUtils imageUtils;
    @Autowired private DiagnosticsService diagnostics;

    private UUID sessionId;
    private LivenessSession session;

    @BeforeEach
    void setUp() {
        sessionId = UUID.randomUUID();
        session = LivenessSession.builder()
                .id(sessionId).workflowType(WorkflowType.RESIDENT).deviceId("L1-CAM-01")
                .status(SessionStatus.ACTIVE).currentStage(LivenessStage.PASSIVE)
                .retryCount(0).online(true)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now())
                .build();
    }

    /**
     * Frames are the session's biometrics: an owned session answers only its
     * owner, and the guard runs before the frame is decoded or scored.
     */
    @Test
    void framesOfAnOwnedSessionRequireItsSubjectRef() throws Exception {
        session.setSubjectRef("resident-42");
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/frames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frameBase64\":\"dGVzdA==\"}"))
                .andExpect(status().isForbidden());
        verify(decisionEngine, never()).processFrame(any(), any(), any());

        Mat frame = mock(Mat.class);
        when(imageUtils.decodeBase64Frame(anyString())).thenReturn(frame);
        when(decisionEngine.processFrame(eq(session), eq(frame), eq(imageUtils)))
                .thenReturn(FrameProcessResult.builder()
                        .sessionId(sessionId).stage(LivenessStage.PASSIVE)
                        .faceDetected(true).action("retry_passive").build());

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/frames")
                        .header("X-Subject-Ref", "resident-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frameBase64\":\"dGVzdA==\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("retry_passive"));
    }

    @Test
    void submitFrame_activeSession_returns200() throws Exception {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        Mat mockMat = mock(Mat.class);
        when(imageUtils.decodeBase64Frame(anyString())).thenReturn(mockMat);
        when(decisionEngine.processFrame(eq(session), eq(mockMat), eq(imageUtils)))
                .thenReturn(FrameProcessResult.builder()
                        .sessionId(sessionId).stage(LivenessStage.PASSIVE)
                        .faceDetected(true).action("retry_passive").message("No face detected.").build());

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/frames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                FrameSubmitRequest.builder().frameBase64("dGVzdA==").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("retry_passive"));
    }

    @Test
    void submitFrame_nonExistentSession_returns404() throws Exception {
        when(sessionRepo.findById(any(UUID.class))).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/v1/sessions/" + UUID.randomUUID() + "/frames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                FrameSubmitRequest.builder().frameBase64("dGVzdA==").build())))
                .andExpect(status().isNotFound());
    }

    @Test
    void submitFrame_inactiveSession_returns409() throws Exception {
        session.setStatus(SessionStatus.PASSED);
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/frames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                FrameSubmitRequest.builder().frameBase64("dGVzdA==").build())))
                .andExpect(status().isConflict());
    }

    @Test
    void submitFrame_emptyBase64_returns400() throws Exception {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/frames")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"frameBase64\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitFrame_invalidBase64_returns422() throws Exception {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(imageUtils.decodeBase64Frame("!!!bad!!!"))
                .thenThrow(new ImageUtils.InvalidFrameError("bad"));
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/frames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                FrameSubmitRequest.builder().frameBase64("!!!bad!!!").build())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_FRAME"));
    }

    @Test
    void submitFrame_escalatesToActive_returnsChallengeInfo() throws Exception {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        Mat mockMat = mock(Mat.class);
        when(imageUtils.decodeBase64Frame(anyString())).thenReturn(mockMat);
        UUID challengeId = UUID.randomUUID();
        when(decisionEngine.processFrame(eq(session), eq(mockMat), eq(imageUtils)))
                .thenReturn(FrameProcessResult.builder()
                        .sessionId(sessionId).stage(LivenessStage.ACTIVE).faceDetected(true)
                        .livenessScore(0.55).action("escalate_to_active").message("Please blink.")
                        .challenge(FrameProcessResult.ChallengeInfo.builder()
                                .challengeId(challengeId).challengeType("BLINK").timeoutMs(10000).attemptNumber(1).build())
                        .build());
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/frames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                FrameSubmitRequest.builder().frameBase64("dGVzdA==").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("escalate_to_active"))
                .andExpect(jsonPath("$.challenge.challengeType").value("BLINK"));
    }

    @Test
    void submitFrame_recordsADiagnosticSampleForTheLocalDebugPanel() throws Exception {
        // The slice shares one mock across test methods: count only this run.
        clearInvocations(diagnostics);
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        Mat mockMat = mock(Mat.class);
        when(imageUtils.decodeBase64Frame(anyString())).thenReturn(mockMat);
        FrameProcessResult processed = FrameProcessResult.builder()
                .sessionId(sessionId).stage(LivenessStage.PASSIVE)
                .faceDetected(true).livenessScore(0.87).faceQuality(0.7)
                .action("retry_passive").message("Checking face liveness...").build();
        when(decisionEngine.processFrame(eq(session), eq(mockMat), eq(imageUtils)))
                .thenReturn(processed);

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/frames")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                FrameSubmitRequest.builder().frameBase64("dGVzdA==").build())))
                .andExpect(status().isOk());

        ArgumentCaptor<FrameProcessResult> captor = ArgumentCaptor.forClass(FrameProcessResult.class);
        verify(diagnostics).recordFrame(captor.capture(), anyLong());
        assertEquals(0.87, captor.getValue().getLivenessScore(), 1e-9,
                "the sample carries the frame's raw score");
        assertEquals("retry_passive", captor.getValue().getAction());
    }
}
