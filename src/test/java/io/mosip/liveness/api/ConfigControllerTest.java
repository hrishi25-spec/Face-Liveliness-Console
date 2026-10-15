package io.mosip.liveness.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.liveness.audit.AuditChainKey;
import io.mosip.liveness.auth.AuthService;
import io.mosip.liveness.testing.AuthTestFixture;
import io.mosip.liveness.audit.AuditEventType;
import io.mosip.liveness.dto.ConfigPolicyUpdate;
import io.mosip.liveness.models.entity.AuditLog;
import io.mosip.liveness.models.entity.ConfigPolicy;
import io.mosip.liveness.models.enums.FailurePolicy;
import io.mosip.liveness.audit.AuditChain;
import io.mosip.liveness.models.enums.WorkflowType;
import io.mosip.liveness.crud.AuditLogRepository;
import io.mosip.liveness.crud.ConfigPolicyRepository;
import io.mosip.liveness.services.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ConfigController.class)
@Import(TestConfig.class)
@TestPropertySource(properties = {
        // Trusted so the forwarded-client test can name a real client; the
        // resolver ignores these headers when nothing is trusted.
        "mosip.security.rate-limit.trusted-proxies=10.0.0.0/8"})
class ConfigControllerTest {

    /**
     * The session a policy write is authorized with. A bearer token the stubbed
     * service resolves to a supervisor, rather than the shared admin key this
     * used to carry — the key is gone, and the audit trail now records the
     * account name behind the edit.
     */
    private static final String SUPERVISOR_AUTH = "Bearer " + AuthTestFixture.SUPERVISOR_TOKEN;

    /**
     * The key the controller hashes with: this slice leaves the secret unset,
     * which is the documented SHA-256 fallback (TestConfig binds the same
     * property the controller sees). AuditChainTamperTest covers the keyed mode.
     */
    private static final AuditChainKey CHAIN_KEY = new AuditChainKey("");


    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ConfigPolicyRepository configRepo;
    @Autowired private AuditLogRepository auditLogRepo;
    @Autowired private ConfigService configService;
    @Autowired private AuthService authService;

    private ConfigPolicy policy;

    @BeforeEach
    void setUp() {
        reset(configRepo, auditLogRepo, configService);
        AuthTestFixture.stub(authService);
        // ConfigController delegates enum conversion and effective-policy mapping
        // to ConfigService; run the real (dependency-free) methods so lazily-seeded
        // defaults and the merged-policy validation behave as in production.
        when(configService.toCoreWorkflow(any())).thenCallRealMethod();
        when(configService.toDbWorkflow(any())).thenCallRealMethod();
        when(configService.toDbChallenge(any())).thenCallRealMethod();
        when(configService.toDbFailure(any())).thenCallRealMethod();
        when(configService.mapToEffectivePolicy(any())).thenCallRealMethod();
        policy = ConfigPolicy.builder()
                .id(UUID.randomUUID()).workflowType(WorkflowType.RESIDENT)
                .livenessEnabled(true).passiveThreshold(0.75).activeLivenessEnabled(true)
                .minChallengeCount(1).challengeTypes(List.of("blink","smile","turn_left","turn_right"))
                .challengeTimeoutMs(15000).maxRetryCount(3).onRepeatedFailure(FailurePolicy.LOCK)
                .updatedAt(OffsetDateTime.now()).build();
    }

    @Test
    void getPolicy_existingPolicy_returns200() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        mockMvc.perform(get("/api/v1/config/RESIDENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passiveThreshold").value(0.75))
                .andExpect(jsonPath("$.maxRetryCount").value(3));
    }

    @Test
    void getPolicy_noExistingPolicy_seedsDefaults() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.OPERATOR)).thenReturn(Optional.empty());
        when(configRepo.save(any())).thenAnswer(inv -> {
            ConfigPolicy p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        mockMvc.perform(get("/api/v1/config/OPERATOR"))
                .andExpect(status().isOk())
                // A lazily-seeded row carries the workflow's own default
                // (WorkflowPolicyDefaults), not one shared operating point:
                // OPERATOR = 0.82 / ALLOW_RETRY, RESIDENT = 0.80 / ESCALATE.
                .andExpect(jsonPath("$.passiveThreshold").value(0.82))
                .andExpect(jsonPath("$.onRepeatedFailure").value("ALLOW_RETRY"));
        verify(configRepo).save(any());
    }

    @Test
    void setPolicy_updateThreshold_returns200() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ConfigPolicyUpdate.builder().passiveThreshold(0.85).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passiveThreshold").value(0.85));
    }

    @Test
    void setPolicy_updateChallengeTypes_returns200() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.SUPERVISOR)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mockMvc.perform(put("/api/v1/config/SUPERVISOR")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfigPolicyUpdate.builder().challengeTypes(List.of("blink","turn_left")).maxRetryCount(5).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challengeTypes.length()").value(2))
                .andExpect(jsonPath("$.maxRetryCount").value(5));
    }

    @Test
    void setPolicy_updateFailurePolicy_returns200() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ConfigPolicyUpdate.builder().onRepeatedFailure("ESCALATE").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onRepeatedFailure").value("ESCALATE"));
    }

    @Test
    void setPolicy_disableLiveness_returns200() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ConfigPolicyUpdate.builder().livenessEnabled(false).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.livenessEnabled").value(false));
    }

    @Test
    void setPolicy_noSession_returns401() throws Exception {
        // A caller that has not signed in is unauthenticated, not forbidden: 401
        // is what a client retries with credentials.
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfigPolicyUpdate.builder().passiveThreshold(0.0).build())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        verify(configRepo, never()).save(any());
    }

    @Test
    void setPolicy_unknownSession_returns401() throws Exception {
        // An expired or revoked token is refused exactly like an absent one, with
        // the same body: a caller learns only that it is not authenticated.
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfigPolicyUpdate.builder().passiveThreshold(0.0).build())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        verify(configRepo, never()).save(any());
    }

    @Test
    void getPolicy_stillOpenWithoutSession() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        mockMvc.perform(get("/api/v1/config/RESIDENT"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------ config change audit trail

    @Test
    void setPolicy_recordsWhatChangedAndWho() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .remoteAddress("203.0.113.10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ConfigPolicyUpdate.builder()
                                .passiveThreshold(0.90).maxRetryCount(1).build())))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepo).save(saved.capture());
        AuditLog entry = saved.getValue();

        assertEquals(AuditEventType.CONFIG_CHANGED.name(), entry.getEventType());
        assertNull(entry.getSession(), "a config change has no session");
        Map<String, Object> details = entry.getDetails();
        assertEquals("RESIDENT", details.get("workflowType"));
        assertEquals("UPDATED", details.get("action"));

        // Only the fields that actually moved, with their previous values: a
        // threshold move and a no-op PUT must not read alike.
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> changes =
                (Map<String, Map<String, Object>>) details.get("changes");
        assertEquals(Set.of("passiveThreshold", "maxRetryCount"), changes.keySet());
        assertEquals(0.75, changes.get("passiveThreshold").get("from"));
        assertEquals(0.90, changes.get("passiveThreshold").get("to"));
        assertEquals(3, changes.get("maxRetryCount").get("from"));
        assertEquals(1, changes.get("maxRetryCount").get("to"));

        // The actor is the accountable account name. This used to be a
        // fingerprint of a shared key, which answered "someone holding that key"
        // and could not distinguish a contractor who left from a leaked .env.
        assertEquals(AuthTestFixture.SUPERVISOR_USERNAME, details.get("actor"));

        // …and the address it came from. The name says who; the source IP is what
        // narrows that to a host.
        assertEquals("203.0.113.10", details.get("sourceIp"));
    }

    // ------------------------------------------------ risk classification

    @Test
    void setPolicy_loweringThePassiveThreshold_isHighRisk() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ConfigPolicyUpdate.builder()
                                .passiveThreshold(0.55).build())))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepo).save(saved.capture());
        // A lower threshold lets more spoofed frames pass, so the trail
        // itself carries the warning — an operator must not have to
        // diff the numbers to notice.
        @SuppressWarnings("unchecked")
        Map<String, Object> risk =
                (Map<String, Object>) saved.getValue().getDetails().get("risk");
        assertEquals("HIGH", risk.get("level"));
        assertEquals(List.of("passiveThreshold lowered"), risk.get("reasons"));
    }

    @Test
    void setPolicy_disablingActiveLiveness_isHighRisk() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ConfigPolicyUpdate.builder()
                                .activeLivenessEnabled(false).build())))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepo).save(saved.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> risk =
                (Map<String, Object>) saved.getValue().getDetails().get("risk");
        assertEquals("HIGH", risk.get("level"));
        assertEquals(List.of("active liveness disabled"), risk.get("reasons"));
    }

    @Test
    void setPolicy_disablingLivenessEntirely_isHighRisk() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ConfigPolicyUpdate.builder()
                                .livenessEnabled(false).build())))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepo).save(saved.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> risk =
                (Map<String, Object>) saved.getValue().getDetails().get("risk");
        assertEquals("HIGH", risk.get("level"));
        assertEquals(List.of("liveness disabled"), risk.get("reasons"));
    }

    @Test
    void setPolicy_strengtheningEdits_areLowRisk() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        // Raised threshold, fewer retries: stricter than
                        // the loaded policy, so nothing weakened.
                        .content(objectMapper.writeValueAsString(ConfigPolicyUpdate.builder()
                                .passiveThreshold(0.90).maxRetryCount(1).build())))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepo).save(saved.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> risk =
                (Map<String, Object>) saved.getValue().getDetails().get("risk");
        assertEquals("LOW", risk.get("level"));
        assertEquals(List.of(), risk.get("reasons"));
    }

    @Test
    void setPolicy_recordsTheForwardedClientIpNotTheProxy() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)        // The socket peer (10.0.0.7) is a trusted proxy; the
        // caller sits at the right end of the forwarded chain.
                        .remoteAddress("10.0.0.7")
                        .header("X-Forwarded-For", "198.51.100.23, 10.0.0.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ConfigPolicyUpdate.builder()
                                .passiveThreshold(0.90).build())))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepo).save(saved.capture());
        assertEquals("198.51.100.23", saved.getValue().getDetails().get("sourceIp"));
    }

    @Test
    void aJunkForwardedValueIsNotRecordedVerbatim() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // The socket peer (10.0.0.7) is NOT in the trusted list, so
        // the forwarded headers are untrusted and the raw socket address
        // is recorded instead of the caller-supplied text.
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .remoteAddress("10.0.0.7")
                        .header("X-Forwarded-For", "\" onload=alert(1) x=\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ConfigPolicyUpdate.builder()
                                .passiveThreshold(0.90).build())))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepo).save(saved.capture());
        assertEquals("unknown", saved.getValue().getDetails().get("sourceIp"));
    }

    @Test
    void aRejectedEditRecordsNoAddressAndLeavesThePolicyAlone() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));

        // No session at all: the caller is refused, so there is no edit to
        // attribute — and the address must not become a way to probe who calls.
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ConfigPolicyUpdate.builder()
                                .passiveThreshold(0.10).build())))
                .andExpect(status().isUnauthorized());

        verify(auditLogRepo, never()).save(any());
        verify(configRepo, never()).save(any());
    }

    @Test
    void verifyChain_reportsIntactAndBrokenStates() throws Exception {
        AuditLog first = AuditLog.builder()
                .id(UUID.randomUUID())
                .eventType(AuditEventType.CONFIG_CHANGED.name())
                .workflowType(WorkflowType.RESIDENT)
                .details(Map.of("workflowType", "RESIDENT"))
                .createdAt(OffsetDateTime.parse("2026-10-04T09:00:00Z"))
                .prevHash(AuditChain.GENESIS)
                .build();
        first.setEntryHash(AuditChain.hashOf(first, CHAIN_KEY));

        AuditLog second = AuditLog.builder()
                .id(UUID.randomUUID())
                .eventType(AuditEventType.CONFIG_CHANGED.name())
                .workflowType(WorkflowType.RESIDENT)
                .details(Map.of("workflowType", "RESIDENT"))
                .createdAt(OffsetDateTime.parse("2026-10-04T09:05:00Z"))
                .prevHash(first.getEntryHash())
                .build();
        second.setEntryHash(AuditChain.hashOf(second, CHAIN_KEY));

        when(auditLogRepo.findByEventTypeAndEntryHashIsNotNullOrderByCreatedAtAsc(
                eq(AuditEventType.CONFIG_CHANGED.name()))).thenReturn(List.of(first, second));

        mockMvc.perform(get("/api/v1/config/audit/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true))
                .andExpect(jsonPath("$.rotationWindowOpen").value(false))
                .andExpect(jsonPath("$.retiredKeyHashes").value(false))
                // The mode is reported so a fallback chain cannot be mistaken
                // for a keyed one: "intact" alone would overclaim.
                .andExpect(jsonPath("$.hmac").value(false))
                .andExpect(jsonPath("$.chainedEntries").value(2))
                .andExpect(jsonPath("$.headHash").value(second.getEntryHash()));

        // Now break the second entry's content, the way a tampered row looks.
        second.setDetails(Map.of("workflowType", "RESIDENT", "tampered", true));
        mockMvc.perform(get("/api/v1/config/audit/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(false))
                .andExpect(jsonPath("$.breakIndex").value(1))
                .andExpect(jsonPath("$.breakEntryId").value(second.getId().toString()))
                .andExpect(jsonPath("$.contentChanged").value(true));
    }

    @Test
    void setPolicy_chainsTheNewEntryOntoTheCurrentHead() throws Exception {
        // The tamper-evidence is only real if the write path actually links
        // entries — AuditChainTamperTest covers the chain logic in isolation,
        // this covers the wiring that produces it.
        AuditLog head = AuditLog.builder()
                .id(UUID.randomUUID())
                .eventType(AuditEventType.CONFIG_CHANGED.name())
                .workflowType(WorkflowType.RESIDENT)
                .details(Map.of("workflowType", "RESIDENT"))
                .createdAt(OffsetDateTime.parse("2026-10-04T09:00:00Z"))
                .entryHash("a".repeat(64))
                .build();
        when(auditLogRepo.findFirstByEventTypeAndEntryHashIsNotNullOrderByCreatedAtDesc(
                eq(AuditEventType.CONFIG_CHANGED.name()))).thenReturn(Optional.of(head));

        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfigPolicyUpdate.builder().passiveThreshold(0.62).build())))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepo).save(saved.capture());
        AuditLog entry = saved.getValue();

        assertEquals("a".repeat(64), entry.getPrevHash(), "must chain onto the existing head");
        assertEquals(AuditChain.hashOf(entry, CHAIN_KEY), entry.getEntryHash(),
                "the stored hash must match the canonical form of what was written");
        assertNotNull(entry.getCreatedAt(), "the hash covers createdAt, so it is set before hashing");
        assertTrue(entry.getCreatedAt().isAfter(head.getCreatedAt()),
                "timestamps must increase, or the created_at-ordered walk is ambiguous");
    }

    @Test
    void setPolicy_firstEverEntry_anchorsAtGenesis() throws Exception {
        when(auditLogRepo.findFirstByEventTypeAndEntryHashIsNotNullOrderByCreatedAtDesc(
                eq(AuditEventType.CONFIG_CHANGED.name()))).thenReturn(Optional.empty());

        when(configRepo.findByWorkflowType(WorkflowType.SUPERVISOR)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/api/v1/config/SUPERVISOR")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfigPolicyUpdate.builder().passiveThreshold(0.55).build())))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepo).save(saved.capture());
        assertEquals(AuditChain.GENESIS, saved.getValue().getPrevHash());
    }

    @Test
    void setPolicy_sameValues_stillRecordedWithNoChange() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.RESIDENT)).thenReturn(Optional.of(policy));
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfigPolicyUpdate.builder().passiveThreshold(0.75).build())))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepo).save(saved.capture());
        assertEquals(Map.of(), saved.getValue().getDetails().get("changes"));
    }

    @Test
    void setPolicy_firstRowForWorkflow_recordsCreated() throws Exception {
        when(configRepo.findByWorkflowType(WorkflowType.SUPERVISOR)).thenReturn(Optional.empty());
        when(configRepo.save(any())).thenAnswer(inv -> {
            ConfigPolicy p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        mockMvc.perform(put("/api/v1/config/SUPERVISOR")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfigPolicyUpdate.builder().passiveThreshold(0.5).build())))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepo).save(saved.capture());
        // The row's defaults come from LivenessConfig, so the diff must show the
        // move away from the engine default, not from null.
        assertEquals("CREATED", saved.getValue().getDetails().get("action"));
        assertNotNull(((Map<?, ?>) saved.getValue().getDetails().get("changes")).get("passiveThreshold"));
    }

    @Test
    void setPolicy_rejectedUpdate_writesNoAuditEntry() throws Exception {
        // passiveThreshold 5.0 fails the controller's own range check — which
        // runs only after the admin key is accepted.
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passiveThreshold\":5.0}"))
                .andExpect(status().isBadRequest());

        verify(auditLogRepo, never()).save(any());
    }

    @Test
    void setPolicy_windowBelowTheConfiguredFloor_isRejected() throws Exception {
        // 1,000ms clears the engine's absolute clamp but not this deployment's
        // floor (mosip.liveness.min-challenge-window-ms, 15s by default), so
        // accepting it would save a policy no session could ever freeze.
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeTimeoutMs\":1000}"))
                .andExpect(status().isBadRequest());

        verify(auditLogRepo, never()).save(any());
    }

    @Test
    void setPolicy_unknownSession_writesNoAuditEntry() throws Exception {
        mockMvc.perform(put("/api/v1/config/RESIDENT")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                ConfigPolicyUpdate.builder().passiveThreshold(0.0).build())))
                .andExpect(status().isUnauthorized());

        verify(auditLogRepo, never()).save(any());
    }

    @Test
    void configAudit_returnsNewestFirstAndCapsTheLimit() throws Exception {
        AuditLog newer = AuditLog.builder().id(UUID.randomUUID())
                .eventType(AuditEventType.CONFIG_CHANGED.name())
                .workflowType(WorkflowType.RESIDENT)
                .details(Map.of("workflowType", "RESIDENT", "action", "UPDATED"))
                .createdAt(OffsetDateTime.parse("2026-10-04T10:00:00Z"))
                .build();
        AuditLog older = AuditLog.builder().id(UUID.randomUUID())
                .eventType(AuditEventType.CONFIG_CHANGED.name())
                .workflowType(WorkflowType.OPERATOR)
                .details(Map.of("workflowType", "OPERATOR", "action", "CREATED"))
                .createdAt(OffsetDateTime.parse("2026-10-04T09:00:00Z"))
                .build();
        when(auditLogRepo.findByEventTypeAndSessionIsNullOrderByCreatedAtDesc(
                eq(AuditEventType.CONFIG_CHANGED.name()), any(Pageable.class)))
                // Honour the page size the controller asked for, so the test
                // fails if the limit stops being pushed into the query.
                .thenAnswer(inv -> {
                    Pageable page = inv.getArgument(1);
                    return List.of(newer, older).stream().limit(page.getPageSize()).toList();
                });

        // limit=1 must not silently return the whole history.
        mockMvc.perform(get("/api/v1/config/audit").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventType").value(AuditEventType.CONFIG_CHANGED.name()))
                .andExpect(jsonPath("$[0].details.workflowType").value("RESIDENT"))
                .andExpect(jsonPath("$[0].workflowType").value("RESIDENT"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-10-04T10:00Z"));

        mockMvc.perform(get("/api/v1/config/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].details.workflowType").value("OPERATOR"));

        // An absurd limit is clamped rather than trusted.
        mockMvc.perform(get("/api/v1/config/audit").param("limit", "100000"))
                .andExpect(status().isOk());
    }

    @Test
    void configAudit_filtersByWorkflowInTheQuery_notInTheBrowser() throws Exception {
        AuditLog resident = AuditLog.builder().id(UUID.randomUUID())
                .eventType(AuditEventType.CONFIG_CHANGED.name())
                .workflowType(WorkflowType.RESIDENT)
                .details(Map.of("workflowType", "RESIDENT", "action", "UPDATED"))
                .createdAt(OffsetDateTime.parse("2026-10-04T10:00:00Z"))
                .build();
        when(auditLogRepo.findByEventTypeAndWorkflowTypeAndSessionIsNullOrderByCreatedAtDesc(
                eq(AuditEventType.CONFIG_CHANGED.name()), eq(WorkflowType.RESIDENT), any(Pageable.class)))
                .thenReturn(List.of(resident));

        mockMvc.perform(get("/api/v1/config/audit").param("workflowType", "RESIDENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                // workflowType is a first-class field now, not only buried in details.
                .andExpect(jsonPath("$[0].workflowType").value("RESIDENT"));

        // The whole point: the filtered finder is used and the unfiltered one is
        // NOT. If this regressed to filtering in the browser, the assertion
        // still pass while every audit row came back from the database.
        verify(auditLogRepo).findByEventTypeAndWorkflowTypeAndSessionIsNullOrderByCreatedAtDesc(
                eq(AuditEventType.CONFIG_CHANGED.name()), eq(WorkflowType.RESIDENT), any(Pageable.class));
        verify(auditLogRepo, never())
                .findByEventTypeAndSessionIsNullOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void configAudit_rejectsAnUnknownWorkflow() throws Exception {
        // A typo must not silently return the unfiltered feed, which
        // like "this workflow has no history" instead of "you asked wrongly".
        mockMvc.perform(get("/api/v1/config/audit").param("workflowType", "RESIDANT"))
                .andExpect(status().isBadRequest());
        verify(auditLogRepo, never())
                .findByEventTypeAndSessionIsNullOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void configAudit_clampsAnAbsurdLimitInTheQuery() throws Exception {
        when(auditLogRepo.findByEventTypeAndSessionIsNullOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/config/audit").param("limit", "100000"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/config/audit").param("limit", "0"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogRepo, times(2))
                .findByEventTypeAndSessionIsNullOrderByCreatedAtDesc(
                        eq(AuditEventType.CONFIG_CHANGED.name()), page.capture());
        // 100000 clamps to the 500 ceiling, 0 to 1 — both reach the query as the
        // page size, so a hostile limit can't turn into a full-table read.
        assertEquals(List.of(500, 1), page.getAllValues().stream()
                .map(Pageable::getPageSize).toList());
    }

    @Test
    void configAudit_isOpenLikeOtherConfigReads() throws Exception {
        when(auditLogRepo.findByEventTypeAndSessionIsNullOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/config/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
