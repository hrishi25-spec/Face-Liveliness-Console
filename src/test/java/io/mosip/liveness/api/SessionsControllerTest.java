package io.mosip.liveness.api;

import tools.jackson.databind.ObjectMapper;
import io.mosip.liveness.auth.AuthPrincipal;
import io.mosip.liveness.auth.AuthRefusals;
import io.mosip.liveness.dto.SessionCreateRequest;
import io.mosip.liveness.models.enums.AuthRole;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.config.WorkflowPolicyDefaults;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.FrameEventRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.services.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SessionsController.class)
@Import(TestConfig.class)
class SessionsControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LivenessSessionRepository sessionRepo;
    @Autowired private FrameEventRepository frameEventRepo;
    @Autowired private ChallengeRepository challengeRepo;
    @Autowired private ConfigService configService;

    private UUID sessionId;
    private LivenessSession session;

    @BeforeEach
    void setUp() {
        reset(sessionRepo, frameEventRepo, challengeRepo, configService);
        // The real conversion + per-workflow defaults: createSession resolves the
        // user type's policy, so the controller needs both to behave as in prod.
        when(configService.toCoreWorkflow(any())).thenCallRealMethod();
        when(configService.getEffectivePolicy(any()))
                .thenAnswer(inv -> WorkflowPolicyDefaults.forWorkflow(inv.getArgument(0)));
        sessionId = UUID.randomUUID();
        session = LivenessSession.builder()
                .id(sessionId).workflowType(WorkflowType.RESIDENT).deviceId("L1-CAM-01")
                .status(SessionStatus.ACTIVE).currentStage(LivenessStage.PASSIVE)
                .retryCount(0).online(true)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now())
                .build();
    }

    @Test
    void createSession_returns201() throws Exception {
        when(sessionRepo.save(any())).thenAnswer(inv -> {
            LivenessSession s = inv.getArgument(0);
            s.setId(sessionId); s.setCreatedAt(OffsetDateTime.now()); s.setUpdatedAt(OffsetDateTime.now());
            return s;
        });
        mockMvc.perform(post("/api/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                SessionCreateRequest.builder().workflowType(WorkflowType.RESIDENT).deviceId("L1-CAM-01").online(true).build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workflowType").value("RESIDENT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                // The resolved policy is returned and differs by user type.
                .andExpect(jsonPath("$.policy.passiveThreshold").value(0.80))
                .andExpect(jsonPath("$.policy.onRepeatedFailure").value("ESCALATE_TO_OPERATOR"));
        verify(sessionRepo).save(any());
    }

    @Test
    void createSession_freezesADistinctPolicyPerUserType() throws Exception {
        when(sessionRepo.save(any())).thenAnswer(inv -> {
            LivenessSession s = inv.getArgument(0);
            s.setId(UUID.randomUUID()); s.setCreatedAt(OffsetDateTime.now()); s.setUpdatedAt(OffsetDateTime.now());
            return s;
        });

        mockMvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                SessionCreateRequest.builder().workflowType(WorkflowType.OPERATOR).deviceId("D").build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policy.passiveThreshold").value(0.82))
                .andExpect(jsonPath("$.policy.onRepeatedFailure").value("FALLBACK"))
                .andExpect(jsonPath("$.policy.minChallengeCount").value(1));

        mockMvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                SessionCreateRequest.builder().workflowType(WorkflowType.SUPERVISOR).deviceId("D").build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policy.passiveThreshold").value(0.85))
                .andExpect(jsonPath("$.policy.onRepeatedFailure").value("LOCK_OUT"))
                .andExpect(jsonPath("$.policy.minChallengeCount").value(2));
    }

    @Test
    void createSession_persistsTheResolvedPolicySnapshot() throws Exception {
        when(sessionRepo.save(any())).thenAnswer(inv -> {
            LivenessSession s = inv.getArgument(0);
            s.setId(sessionId); s.setCreatedAt(OffsetDateTime.now()); s.setUpdatedAt(OffsetDateTime.now());
            return s;
        });

        mockMvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                SessionCreateRequest.builder().workflowType(WorkflowType.SUPERVISOR).deviceId("D").build())))
                .andExpect(status().isCreated());

        org.mockito.ArgumentCaptor<LivenessSession> saved =
                org.mockito.ArgumentCaptor.forClass(LivenessSession.class);
        verify(sessionRepo).save(saved.capture());
        assertNotNull(saved.getValue().getPolicySnapshot(),
                "the effective policy must be frozen on the session at creation");
        assertEquals(0.85, saved.getValue().getPolicySnapshot().passiveThreshold(), 1e-9);
        assertNotNull(saved.getValue().getPolicySnapshotAt());
    }

    /**
     * The user type is the signed-in account's, not a client choice: a supervisor
     * session that asks for the resident workflow is refused before anything is
     * persisted, because that would measure a staff authentication against the
     * loosest policy the service has.
     */
    @Test
    void createSession_signedInSupervisorClaimingResident_isRefused() throws Exception {
        mockMvc.perform(post("/api/v1/sessions")
                        .requestAttr(AuthGate.ATTRIBUTE, principal(AuthRole.SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                SessionCreateRequest.builder().workflowType(WorkflowType.RESIDENT)
                                        .deviceId("L1-CAM-01").build())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(AuthRefusals.USER_TYPE_FROM_ACCOUNT));

        verify(sessionRepo, never()).save(any());
    }

    @Test
    void createSession_signedInOperatorClaimingSupervisor_isRefused() throws Exception {
        mockMvc.perform(post("/api/v1/sessions")
                        .requestAttr(AuthGate.ATTRIBUTE, principal(AuthRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                SessionCreateRequest.builder().workflowType(WorkflowType.SUPERVISOR)
                                        .deviceId("L1-CAM-01").build())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(AuthRefusals.USER_TYPE_FROM_ACCOUNT));

        verify(sessionRepo, never()).save(any());
    }

    /**
     * The workflow a signed-in caller gets is the one its role runs, with that
     * role's frozen policy — never the value the body carried.
     */
    @Test
    void createSession_signedInSupervisorRunsTheSupervisorWorkflow() throws Exception {
        when(sessionRepo.save(any())).thenAnswer(inv -> {
            LivenessSession s = inv.getArgument(0);
            s.setId(sessionId); s.setCreatedAt(OffsetDateTime.now()); s.setUpdatedAt(OffsetDateTime.now());
            return s;
        });

        mockMvc.perform(post("/api/v1/sessions")
                        .requestAttr(AuthGate.ATTRIBUTE, principal(AuthRole.SUPERVISOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                SessionCreateRequest.builder().workflowType(WorkflowType.SUPERVISOR)
                                        .deviceId("L1-CAM-01").build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workflowType").value("SUPERVISOR"))
                .andExpect(jsonPath("$.policy.passiveThreshold").value(0.85))
                .andExpect(jsonPath("$.policy.minChallengeCount").value(2))
                .andExpect(jsonPath("$.policy.onRepeatedFailure").value("LOCK_OUT"));
    }

    @Test
    void createSession_signedInOperatorRunsTheOperatorWorkflow() throws Exception {
        when(sessionRepo.save(any())).thenAnswer(inv -> {
            LivenessSession s = inv.getArgument(0);
            s.setId(sessionId); s.setCreatedAt(OffsetDateTime.now()); s.setUpdatedAt(OffsetDateTime.now());
            return s;
        });

        mockMvc.perform(post("/api/v1/sessions")
                        .requestAttr(AuthGate.ATTRIBUTE, principal(AuthRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                SessionCreateRequest.builder().workflowType(WorkflowType.OPERATOR)
                                        .deviceId("L1-CAM-01").build())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workflowType").value("OPERATOR"))
                .andExpect(jsonPath("$.policy.passiveThreshold").value(0.82))
                .andExpect(jsonPath("$.policy.onRepeatedFailure").value("FALLBACK"));
    }

    /** The resolved principal an {@code AuthContextFilter} run would have parked. */
    private static AuthPrincipal principal(AuthRole role) {
        return new AuthPrincipal(UUID.randomUUID(), role.name().toLowerCase(), role, false);
    }

    @Test
    void createSession_missingWorkflowType_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"deviceId\":\"L1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void getSession_existingSession_returns200() throws Exception {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        mockMvc.perform(get("/api/v1/sessions/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sessionId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getSession_nonExistentSession_returns404() throws Exception {
        when(sessionRepo.findById(any(UUID.class))).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/v1/sessions/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    /**
     * The ownership rule on a session that declares an owner. It is checked on
     * reads too: the response names the workflow, the stage and the failure
     * reason, which is the owner's business and nobody else's.
     */
    @Test
    void aSessionOwnedByASubjectRefIsOnlyReachableWithIt() throws Exception {
        session.setSubjectRef("resident-42");
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));

        mockMvc.perform(get("/api/v1/sessions/" + sessionId))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/sessions/" + sessionId).header("X-Subject-Ref", "resident-43"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/sessions/" + sessionId).header("X-Subject-Ref", "resident-42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sessionId.toString()));
    }

    @Test
    void closingSomebodyElsesSessionIsRefused() throws Exception {
        session.setSubjectRef("resident-42");
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));

        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close")
                        .header("X-Subject-Ref", "resident-43"))
                .andExpect(status().isForbidden());
        verify(sessionRepo, never()).save(any());
        assertEquals(SessionStatus.ACTIVE, session.getStatus(), "a refused close must not expire it");
    }

    /**
     * Creation is what decides ownership: a session with no subjectRef has no
     * owner to protect, so every existing client (the console, the shipped
     * {@code LivenessHttpClient}) keeps working without a new header.
     */
    @Test
    void anAnonymousSessionStaysReachableWithoutTheHeader() throws Exception {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));

        mockMvc.perform(get("/api/v1/sessions/" + sessionId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/sessions/" + sessionId).header("X-Subject-Ref", "anyone"))
                .andExpect(status().isOk());
    }

    @Test
    void closeSession_activeSession_returns200() throws Exception {
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(frameEventRepo.countBySessionId(sessionId)).thenReturn(10L);
        when(challengeRepo.countBySessionId(sessionId)).thenReturn(2L);
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFramesEvaluated").value(10))
                .andExpect(jsonPath("$.totalChallengesIssued").value(2));
    }

    @Test
    void closeSession_alreadyClosed_doesNotUpdate() throws Exception {
        session.setStatus(SessionStatus.PASSED);
        session.setClosedAt(OffsetDateTime.now().minusMinutes(5));
        when(sessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(frameEventRepo.countBySessionId(sessionId)).thenReturn(5L);
        when(challengeRepo.countBySessionId(sessionId)).thenReturn(1L);
        mockMvc.perform(post("/api/v1/sessions/" + sessionId + "/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"));
        verify(sessionRepo, never()).save(any());
    }
}
