package io.mosip.liveness.models;

import io.mosip.liveness.crud.AuditLogRepository;
import io.mosip.liveness.crud.ConfigPolicyRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.models.entity.AuditLog;
import io.mosip.liveness.models.entity.ConfigPolicy;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.FailurePolicy;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence-level regression guard.
 *
 * <p>The controller tests mock the repositories, so they cannot catch a broken
 * column mapping. These tests write through JPA, clear the persistence context,
 * and read the rows back — which is what the API does on a real request.</p>
 *
 * <p>Flyway is disabled here so Hibernate generates the schema from the entity
 * mappings; the Flyway migration is PostgreSQL-targeted and exercised
 * separately.</p>
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class EntityPersistenceTest {

    @Autowired private TestEntityManager em;
    @Autowired private ConfigPolicyRepository configRepo;
    @Autowired private AuditLogRepository auditRepo;
    @Autowired private LivenessSessionRepository sessionRepo;

    @Test
    void configPolicy_challengeTypes_surviveARoundTrip() {
        configRepo.saveAndFlush(ConfigPolicy.builder()
                .workflowType(WorkflowType.RESIDENT)
                .challengeTypes(List.of("blink", "turn_left"))
                .onRepeatedFailure(FailurePolicy.ESCALATE)
                .maxRetryCount(5)
                .build());

        em.clear(); // force a real read rather than a persistence-context hit

        ConfigPolicy reloaded = configRepo.findByWorkflowType(WorkflowType.RESIDENT).orElseThrow();
        assertThat(reloaded.getChallengeTypes()).containsExactly("blink", "turn_left");
        assertThat(reloaded.getOnRepeatedFailure()).isEqualTo(FailurePolicy.ESCALATE);
        assertThat(reloaded.getMaxRetryCount()).isEqualTo(5);
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void session_timestampsAndDefaults_areApplied() {
        LivenessSession saved = sessionRepo.saveAndFlush(LivenessSession.builder()
                .workflowType(WorkflowType.OPERATOR)
                .deviceId("L1-CAM-01")
                .build());

        em.clear();

        LivenessSession reloaded = sessionRepo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(reloaded.getCurrentStage()).isEqualTo(LivenessStage.PASSIVE);
        assertThat(reloaded.getRetryCount()).isZero();
        assertThat(reloaded.getOnline()).isTrue();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void auditLog_details_surviveARoundTrip() {
        LivenessSession session = sessionRepo.saveAndFlush(
                LivenessSession.builder()
                        .workflowType(WorkflowType.SUPERVISOR)
                        .deviceId("L0-CAM-07")
                        .closedAt(OffsetDateTime.now())
                        .build());

        em.clear();

        AuditLog saved = auditRepo.saveAndFlush(AuditLog.builder()
                .session(session)
                .eventType("PAD_REJECTED")
                .details(Map.of("attackType", "PRINTED_PHOTO", "confidence", 0.87))
                .build());

        em.clear();

        AuditLog reloaded = auditRepo.findBySessionIdOrderByCreatedAtAsc(session.getId()).get(0);
        assertThat(reloaded.getEventType()).isEqualTo("PAD_REJECTED");
        assertThat(reloaded.getDetails())
                .containsEntry("attackType", "PRINTED_PHOTO")
                .containsEntry("confidence", 0.87);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getId()).isEqualTo(saved.getId());
    }

    // ------------------------------------------------- the operator feeds' queries

    /**
     * The session feed's ordering, filtering and limit are the query's, so they
     * are asserted against the database rather than against a mocked repository:
     * "newest first" is only true if SQL says so, and the limit only bounds the
     * result if it is part of the query instead of applied to a table read.
     */
    @Test
    void sessionFeed_isNewestFirst_filteredAndLimited() {
        OffsetDateTime now = OffsetDateTime.now();
        LivenessSession oldest = session(now.minusMinutes(3), WorkflowType.RESIDENT, SessionStatus.PASSED);
        LivenessSession middle = session(now.minusMinutes(2), WorkflowType.RESIDENT, SessionStatus.FAILED);
        LivenessSession newest = session(now.minusMinutes(1), WorkflowType.OPERATOR, SessionStatus.FAILED);
        em.clear();

        // Newest first, and the page size is what the limit asked for: the newest
        // two, not the first two rows the table happened to hand back.
        List<LivenessSession> page = sessionRepo.findRecent(null, null, PageRequest.of(0, 2));
        assertThat(page).extracting(LivenessSession::getId)
                .containsExactly(newest.getId(), middle.getId());

        // Each filter is applied in SQL, independently of the other.
        assertThat(sessionRepo.findRecent(WorkflowType.RESIDENT, null, PageRequest.of(0, 50)))
                .extracting(LivenessSession::getId)
                .containsExactly(middle.getId(), oldest.getId());
        assertThat(sessionRepo.findRecent(null, SessionStatus.FAILED, PageRequest.of(0, 50)))
                .extracting(LivenessSession::getId)
                .containsExactly(newest.getId(), middle.getId());
        assertThat(sessionRepo.findRecent(WorkflowType.OPERATOR, SessionStatus.PASSED,
                PageRequest.of(0, 50))).isEmpty();
        assertThat(sessionRepo.findRecent(WorkflowType.RESIDENT, SessionStatus.PASSED,
                PageRequest.of(0, 50))).extracting(LivenessSession::getId)
                .containsExactly(oldest.getId());
    }

    /**
     * The rejection feed's query: only the two rejection event types, newest
     * first, bounded, and with the rejected session readable from the row without
     * a second query — the controller maps that id after the transaction.
     */
    @Test
    void rejectionEvents_areTheTwoRejectionTypesNewestFirstAndBounded() {
        OffsetDateTime now = OffsetDateTime.now();
        LivenessSession firstSession = session(now.minusMinutes(9), WorkflowType.RESIDENT,
                SessionStatus.FAILED);
        LivenessSession secondSession = session(now.minusMinutes(8), WorkflowType.OPERATOR,
                SessionStatus.FAILED);

        event(firstSession, "PAD_REJECTED", now.minusMinutes(3));
        event(secondSession, "CHALLENGE_FAILED", now.minusMinutes(2));
        // Newer than both, and deliberately not a rejection: the feed must not
        // pick it up just because it is the most recent thing in the table.
        event(firstSession, "SESSION_PASSED", now.minusMinutes(1));
        em.clear();

        List<LivenessSession> page = sessionRepo.findRecent(null, SessionStatus.FAILED,
                PageRequest.of(0, 50));
        assertThat(page).extracting(LivenessSession::getId)
                .containsExactlyInAnyOrder(firstSession.getId(), secondSession.getId());

        List<AuditLog> events = auditRepo.findRecentByEventTypes(
                List.of("PAD_REJECTED", "CHALLENGE_FAILED"), PageRequest.of(0, 50));
        assertThat(events).extracting(AuditLog::getEventType)
                .containsExactly("CHALLENGE_FAILED", "PAD_REJECTED");
        assertThat(events).extracting(e -> e.getSession().getId())
                .containsExactly(secondSession.getId(), firstSession.getId());

        // The limit is the newest rows, on this query too.
        assertThat(auditRepo.findRecentByEventTypes(
                List.of("PAD_REJECTED", "CHALLENGE_FAILED"), PageRequest.of(0, 1)))
                .extracting(AuditLog::getEventType)
                .containsExactly("CHALLENGE_FAILED");
    }

    private LivenessSession session(OffsetDateTime createdAt, WorkflowType workflowType,
                                    SessionStatus status) {
        return sessionRepo.saveAndFlush(LivenessSession.builder()
                .workflowType(workflowType)
                .deviceId("L1-CAM-01")
                .status(status)
                .createdAt(createdAt)
                .build());
    }

    private AuditLog event(LivenessSession session, String eventType, OffsetDateTime createdAt) {
        return auditRepo.saveAndFlush(AuditLog.builder()
                .session(session)
                .eventType(eventType)
                .createdAt(createdAt)
                .details(Map.of("stage", "passive"))
                .build());
    }
}
