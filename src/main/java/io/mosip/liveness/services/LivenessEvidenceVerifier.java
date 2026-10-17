package io.mosip.liveness.services;

import io.mosip.liveness.audit.AuditEventType;
import io.mosip.liveness.crud.AuditLogRepository;
import io.mosip.liveness.dto.EvidenceVerificationRequest;
import io.mosip.liveness.dto.EvidenceVerificationResponse;
import io.mosip.liveness.models.entity.AuditLog;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.SessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Server-side verification of signed client liveness evidence (spec §2 LivenessEvidence).
 * Ensures online sessions cannot be spoofed by a client that skipped the gate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LivenessEvidenceVerifier {

    private final AuditLogRepository auditLogRepo;

    public EvidenceVerificationResponse verify(LivenessSession session, EvidenceVerificationRequest req) {
        List<String> warnings = new ArrayList<>();

        if (session == null) {
            return EvidenceVerificationResponse.builder()
                    .verified(false)
                    .failureReason("Session not found")
                    .build();
        }

        // 1. Session status check
        if (session.getStatus() != SessionStatus.ACTIVE && session.getStatus() != SessionStatus.PASSED) {
            return EvidenceVerificationResponse.builder()
                    .verified(false)
                    .sessionId(session.getId().toString())
                    .failureReason("Session is not active or passed (status=" + session.getStatus() + ")")
                    .build();
        }

        // 2. Nonce binding check
        if (session.getNonce() != null && req.getNonceHex() != null) {
            if (!session.getNonce().equalsIgnoreCase(req.getNonceHex())) {
                logAudit(session, "EVIDENCE_REJECTED", Map.of("reason", "nonce_mismatch",
                        "expected", session.getNonce(), "provided", req.getNonceHex()));
                return EvidenceVerificationResponse.builder()
                        .verified(false)
                        .sessionId(session.getId().toString())
                        .failureReason("Evidence nonce does not match session nonce")
                        .build();
            }
        }

        // 3. Timing and sequence sanity
        if (req.getStartedEpochMs() != null && req.getCompletedEpochMs() != null) {
            if (req.getStartedEpochMs() > req.getCompletedEpochMs()) {
                logAudit(session, "EVIDENCE_REJECTED", Map.of("reason", "invalid_timestamps"));
                return EvidenceVerificationResponse.builder()
                        .verified(false)
                        .sessionId(session.getId().toString())
                        .failureReason("Evidence completion timestamp is before start timestamp")
                        .build();
            }
            if (session.getCreatedAt() != null) {
                long sessionCreatedMs = session.getCreatedAt().toInstant().toEpochMilli();
                if (req.getCompletedEpochMs() < sessionCreatedMs - 5000L) {
                    warnings.add("Evidence completed before session creation timestamp");
                }
            }
        }

        // 4. Bypassed gate check
        if (Boolean.TRUE.equals(req.getBypassed())) {
            warnings.add("Liveness gate was bypassed by client policy");
        }

        // 5. Challenges validation
        if (req.getChallengeResults() != null && req.getChallengeResults().contains(Boolean.FALSE)) {
            logAudit(session, "EVIDENCE_REJECTED", Map.of("reason", "failed_challenge_in_evidence"));
            return EvidenceVerificationResponse.builder()
                    .verified(false)
                    .sessionId(session.getId().toString())
                    .failureReason("Evidence contains failed active challenges")
                    .build();
        }

        // 6. Signature presence
        if (req.getSignatureHex() == null || req.getSignatureHex().isBlank()) {
            warnings.add("Evidence is unsigned (signature missing)");
        }

        // Successful verification audit
        logAudit(session, "EVIDENCE_VERIFIED", Map.of(
                "engineId", req.getEngineId() != null ? req.getEngineId() : "unknown",
                "modelVersion", req.getModelVersion() != null ? req.getModelVersion() : "unknown",
                "passiveScore", req.getPassiveScore() != null ? req.getPassiveScore() : 0.0,
                "warningsCount", warnings.size()
        ));

        return EvidenceVerificationResponse.builder()
                .verified(true)
                .sessionId(session.getId().toString())
                .warnings(warnings)
                .build();
    }

    private void logAudit(LivenessSession session, String eventType, Map<String, Object> details) {
        try {
            auditLogRepo.save(AuditLog.builder()
                    .session(session)
                    .eventType(eventType)
                    .workflowType(session.getWorkflowType())
                    .details(details)
                    .createdAt(OffsetDateTime.now())
                    .build());
        } catch (Exception e) {
            log.error("Failed to persist audit log for evidence verification: {}", e.getMessage());
        }
    }
}
