package io.mosip.liveness.api;

import io.mosip.liveness.testing.AuthTestFixture;
import io.mosip.liveness.crud.AuditLogRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.models.entity.AuditLog;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/rejections}: the operator's rejection feed.
 *
 * <p>It answers two things at once — the failed sessions and the audit events
 * recorded for them — so the tests pin both halves: each is asked for in SQL
 * with the caller's page size, each comes back newest first (whatever order the
 * query answered in), a failed row carries no owner reference, and the event
 * payload is the stored one rather than a rewritten summary. The key rules are
 * the same as the session feed's, because it is the same gate: no key is 401,
 * the operator key reads it, the admin key reads it too.</p>
 */
@WebMvcTest(RejectionsController.class)
@Import(TestConfig.class)
@TestPropertySource(properties = {
        "mosip.security.operator-api-key=" + RejectionsControllerTest.OPERATOR_KEY,
        "mosip.security.rate-limit.session-create-limit=1000"
})
class RejectionsControllerTest {

    /** Synthetic; long enough to satisfy the startup floor (operator ≥16). */
    static final String OPERATOR_KEY = "synthetic-operator-key-0001";

    private static final UUID SESSION_ID =
            UUID.fromString("5c8f1c9e-0000-4000-8000-000000000001");
    private static final UUID EVENT_ID =
            UUID.fromString("5c8f1c9e-0000-4000-8000-0000000000e1");

    @Autowired private MockMvc mockMvc;
    @Autowired private LivenessSessionRepository sessionRepo;
    @Autowired private AuditLogRepository auditLogRepo;
    @Autowired private io.mosip.liveness.auth.AuthService authService;

    @BeforeEach
    void setUp() {
        reset(sessionRepo, auditLogRepo);
        AuthTestFixture.stub(authService);
    }

    @Test
    void theFeedIsRefusedWithoutAKey() throws Exception {
        mockMvc.perform(get("/api/v1/rejections"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value(OperatorKeyGuard.REFUSAL))
                .andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(sessionRepo, auditLogRepo);
    }

    @Test
    void theOperatorKeyReadsBothHalves() throws Exception {
        when(sessionRepo.findRecent(any(), any(), any())).thenReturn(List.of(failedSession()));
        when(auditLogRepo.findRecentByEventTypes(any(), any())).thenReturn(List.of(padRejection()));

        mockMvc.perform(get("/api/v1/rejections").header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(50))
                // The failed session, with the reason an operator triages on.
                .andExpect(jsonPath("$.sessions.length()").value(1))
                .andExpect(jsonPath("$.sessions[0].id").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.sessions[0].status").value("FAILED"))
                .andExpect(jsonPath("$.sessions[0].failureReason")
                        .value("presentation_attack:print"))
                // The stored event, with the session it belongs to.
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].id").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.events[0].eventType").value("PAD_REJECTED"))
                .andExpect(jsonPath("$.events[0].sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.events[0].createdAt").exists());
    }

    /**
     * The gate accepts a session as well as the key, and a supervisor session reads
     * the feed: what replaced "the admin key reads the feed too".
     */
    @Test
    void aSupervisorSessionReadsTheFeed() throws Exception {
        when(sessionRepo.findRecent(any(), any(), any())).thenReturn(List.of());
        when(auditLogRepo.findRecentByEventTypes(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/rejections").with(AuthTestFixture.asSupervisor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(0))
                .andExpect(jsonPath("$.events.length()").value(0));
    }

    /**
     * Both halves are narrowed in SQL: the session query is asked for
     * {@code FAILED} (not for every session the deployment ever ran), and the
     * event query for the two rejection names the pipeline writes — one of which
     * is not in {@code AuditEventType}, so the literal is pinned here rather
     * than assumed from the enum.
     */
    @Test
    void bothHalvesAreNarrowedInTheQuery() throws Exception {
        when(sessionRepo.findRecent(any(), any(), any())).thenReturn(List.of());
        when(auditLogRepo.findRecentByEventTypes(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/rejections?limit=10").header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk());

        ArgumentCaptor<SessionStatus> status = ArgumentCaptor.forClass(SessionStatus.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(sessionRepo).findRecent(any(), status.capture(), page.capture());
        assertEquals(SessionStatus.FAILED, status.getValue(),
                "the feed is about failures; a status filter that is not FAILED would be a different feed");
        assertEquals(10, page.getValue().getPageSize());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> eventTypes = ArgumentCaptor.forClass(List.class);
        verify(auditLogRepo).findRecentByEventTypes(eventTypes.capture(), page.capture());
        assertEquals(List.of("PAD_REJECTED", "CHALLENGE_FAILED"), eventTypes.getValue());
        assertEquals(10, page.getAllValues().get(1).getPageSize(),
                "the caller's page size bounds both halves, not just one");
    }

    @Test
    void thePageSizeIsBounded() throws Exception {
        when(sessionRepo.findRecent(any(), any(), any())).thenReturn(List.of());
        when(auditLogRepo.findRecentByEventTypes(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/rejections?limit=200")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(200));
        // Absent and empty both mean the default page, and the answer says which
        // page size it actually used.
        mockMvc.perform(get("/api/v1/rejections?limit=")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(50));
        mockMvc.perform(get("/api/v1/rejections").header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(50));
        for (String limit : List.of("0", "-1", "201", "abc")) {
            mockMvc.perform(get("/api/v1/rejections?limit=" + limit)
                            .header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
        mockMvc.perform(get("/api/v1/rejections?limit=0")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 200"));

        verify(sessionRepo, times(3)).findRecent(any(), any(), any());
    }

    /**
     * The failed rows are the same projection as the session feed's, so the two
     * answers cannot disagree about what a session row exposes — and neither
     * exposes the owner reference.
     */
    @Test
    void aFailedRowNeverCarriesTheOwnerReference() throws Exception {
        LivenessSession owned = failedSession();
        owned.setSubjectRef("resident-42");
        when(sessionRepo.findRecent(any(), any(), any())).thenReturn(List.of(owned));
        when(auditLogRepo.findRecentByEventTypes(any(), any())).thenReturn(List.of());

        String body = mockMvc.perform(get("/api/v1/rejections")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("subjectRef"), body);
        assertFalse(body.contains("resident-42"), body);
        assertTrue(body.contains("presentation_attack:print"), body);
    }

    /**
     * The event payload is the one the pipeline stored, so this feed tells an
     * operator no more than {@code GET /api/v1/sessions/{id}/audit} already tells
     * the session's owner — and no less, or a rejection would read as a bare
     * event name.
     */
    @Test
    void anEventCarriesTheStoredPayloadUnchanged() throws Exception {
        when(sessionRepo.findRecent(any(), any(), any())).thenReturn(List.of());
        when(auditLogRepo.findRecentByEventTypes(any(), any())).thenReturn(List.of(padRejection()));

        mockMvc.perform(get("/api/v1/rejections").header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].details.attackType").value("PRINTED_PHOTO"))
                .andExpect(jsonPath("$.events[0].details.stage").value("passive"))
                .andExpect(jsonPath("$.events[0].details.confidence").value(0.87));
    }

    /** Failed sessions and events interleaved by time is the caller's job; each list is ordered. */
    @Test
    void eachHalfKeepsTheOrderTheQueryAnsweredIn() throws Exception {
        LivenessSession newer = failedSession();
        LivenessSession older = failedSession();
        older.setId(UUID.fromString("5c8f1c9e-0000-4000-8000-000000000002"));
        when(sessionRepo.findRecent(any(), any(), any())).thenReturn(List.of(newer, older));

        AuditLog newerEvent = padRejection();
        AuditLog olderEvent = padRejection();
        olderEvent.setId(UUID.fromString("5c8f1c9e-0000-4000-8000-0000000000e2"));
        when(auditLogRepo.findRecentByEventTypes(any(), any()))
                .thenReturn(List.of(newerEvent, olderEvent));

        mockMvc.perform(get("/api/v1/rejections").header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].id").value(newer.getId().toString()))
                .andExpect(jsonPath("$.sessions[1].id").value(older.getId().toString()))
                .andExpect(jsonPath("$.events[0].id").value(newerEvent.getId().toString()))
                .andExpect(jsonPath("$.events[1].id").value(olderEvent.getId().toString()));
    }

    private static LivenessSession failedSession() {
        return LivenessSession.builder()
                .id(SESSION_ID)
                .workflowType(WorkflowType.RESIDENT)
                .deviceId("L1-CAM-01")
                .status(SessionStatus.FAILED)
                .retryCount(0)
                .finalResult(false)
                .failureReason("presentation_attack:print")
                .createdAt(OffsetDateTime.now().minusMinutes(1))
                .closedAt(OffsetDateTime.now())
                .build();
    }

    private static AuditLog padRejection() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("attackType", "PRINTED_PHOTO");
        details.put("confidence", 0.87);
        details.put("stage", "passive");
        return AuditLog.builder()
                .id(EVENT_ID)
                .session(failedSession())
                .eventType("PAD_REJECTED")
                .details(details)
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
