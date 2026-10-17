package io.mosip.liveness.security;

import io.mosip.liveness.dto.ChallengeValidateRequest;
import io.mosip.liveness.dto.EvidenceVerificationRequest;
import io.mosip.liveness.dto.EvidenceVerificationResponse;
import io.mosip.liveness.dto.FrameSubmitRequest;
import io.mosip.liveness.models.entity.ChallengeEntity;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.ChallengeType;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import io.mosip.liveness.services.LivenessEvidenceVerifier;
import io.mosip.liveness.crud.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

class AntiReplayValidatorTest {

    static {
        nu.pattern.OpenCV.loadLocally();
    }

    private AntiReplayValidator validator;
    private LivenessSession session;
    private AuditLogRepository auditLogRepo;
    private LivenessEvidenceVerifier evidenceVerifier;

    @BeforeEach
    void setUp() {
        validator = new AntiReplayValidator();
        session = LivenessSession.builder()
                .id(UUID.randomUUID())
                .workflowType(WorkflowType.RESIDENT)
                .deviceId("dev-01")
                .nonce("aabbccddeeff00112233445566778899")
                .lastFrameSeq(10L)
                .status(SessionStatus.ACTIVE)
                .createdAt(OffsetDateTime.now().minusSeconds(10))
                .build();
        auditLogRepo = mock(AuditLogRepository.class);
        evidenceVerifier = new LivenessEvidenceVerifier(auditLogRepo);
    }

    @Test
    @DisplayName("valid frame with fresh sequence, matching nonce and valid digest passes")
    void validFramePasses() {
        String base64 = "dGVzdC1mcmFtZQ==";
        String sha256 = AntiReplayValidator.sha256Hex(base64.getBytes(StandardCharsets.UTF_8));
        FrameSubmitRequest req = FrameSubmitRequest.builder()
                .frameBase64(base64)
                .nonce("aabbccddeeff00112233445566778899")
                .frameSeq(11L)
                .capturedAtEpochMs(System.currentTimeMillis() - 200)
                .frameSha256(sha256)
                .build();

        Mat mat = new Mat(20, 20, CvType.CV_8UC3, new Scalar(100, 100, 100));
        try {
            assertDoesNotThrow(() -> validator.validateFrame(session, req, mat));
            assertEquals(11L, session.getLastFrameSeq());
        } finally {
            mat.release();
        }
    }

    @Test
    @DisplayName("rejects frame when nonce does not match session nonce")
    void rejectsNonceMismatch() {
        FrameSubmitRequest req = FrameSubmitRequest.builder()
                .frameBase64("dGVzdA==")
                .nonce("wrong-nonce")
                .build();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> validator.validateFrame(session, req, null));
        assertTrue(ex.getReason().contains("Invalid frame nonce"));
    }

    @Test
    @DisplayName("rejects non-monotonic frame sequence")
    void rejectsNonMonotonicSequence() {
        FrameSubmitRequest req = FrameSubmitRequest.builder()
                .frameBase64("dGVzdA==")
                .frameSeq(10L) // <= current 10L
                .build();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> validator.validateFrame(session, req, null));
        assertTrue(ex.getReason().contains("Non-monotonic"));
    }

    @Test
    @DisplayName("rejects stale frame timestamp")
    void rejectsStaleTimestamp() {
        FrameSubmitRequest req = FrameSubmitRequest.builder()
                .frameBase64("dGVzdA==")
                .capturedAtEpochMs(System.currentTimeMillis() - 120_000L) // 2 minutes old
                .build();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> validator.validateFrame(session, req, null));
        assertTrue(ex.getReason().contains("Frame timestamp too old"));
    }

    @Test
    @DisplayName("rejects future frame timestamp")
    void rejectsFutureTimestamp() {
        FrameSubmitRequest req = FrameSubmitRequest.builder()
                .frameBase64("dGVzdA==")
                .capturedAtEpochMs(System.currentTimeMillis() + 60_000L) // 1 min into future
                .build();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> validator.validateFrame(session, req, null));
        assertTrue(ex.getReason().contains("future"));
    }

    @Test
    @DisplayName("rejects frame sha256 mismatch")
    void rejectsDigestMismatch() {
        FrameSubmitRequest req = FrameSubmitRequest.builder()
                .frameBase64("dGVzdA==")
                .frameSha256("badbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadb")
                .build();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> validator.validateFrame(session, req, null));
        assertTrue(ex.getReason().contains("digest mismatch"));
    }

    @Test
    @DisplayName("rejects exact and perceptual duplicate frames")
    void rejectsDuplicateFrames() {
        Mat mat1 = new Mat(30, 30, CvType.CV_8UC3, new Scalar(50, 120, 200));
        Mat mat2 = new Mat(30, 30, CvType.CV_8UC3, new Scalar(50, 120, 200));
        try {
            FrameSubmitRequest req1 = FrameSubmitRequest.builder()
                    .frameBase64("frame1==")
                    .nonce("aabbccddeeff00112233445566778899")
                    .frameSeq(11L)
                    .build();
            validator.validateFrame(session, req1, mat1);

            FrameSubmitRequest req2 = FrameSubmitRequest.builder()
                    .frameBase64("frame2==")
                    .nonce("aabbccddeeff00112233445566778899")
                    .frameSeq(12L)
                    .build();
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> validator.validateFrame(session, req2, mat2));
            assertTrue(ex.getReason().contains("Duplicate frame detected"));
        } finally {
            mat1.release();
            mat2.release();
        }
    }

    @Test
    @DisplayName("rejects cross-session frame replay")
    void rejectsCrossSessionReplay() {
        LivenessSession session2 = LivenessSession.builder()
                .id(UUID.randomUUID())
                .workflowType(WorkflowType.RESIDENT)
                .deviceId("dev-01")
                .nonce("11223344556677889900aabbccddeeff")
                .status(SessionStatus.ACTIVE)
                .createdAt(OffsetDateTime.now())
                .build();

        FrameSubmitRequest req1 = FrameSubmitRequest.builder()
                .frameBase64("replayed-frame-data==")
                .nonce("aabbccddeeff00112233445566778899")
                .frameSeq(11L)
                .build();
        validator.validateFrame(session, req1, null);

        FrameSubmitRequest req2 = FrameSubmitRequest.builder()
                .frameBase64("replayed-frame-data==")
                .nonce("11223344556677889900aabbccddeeff")
                .frameSeq(1L)
                .build();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> validator.validateFrame(session2, req2, null));
        assertTrue(ex.getReason().contains("cross-session replay attempt"));
    }

    @Test
    @DisplayName("rejects challenge frames captured before challenge was issued")
    void rejectsChallengeFramesBeforePrompt() {
        ChallengeEntity challenge = ChallengeEntity.builder()
                .id(UUID.randomUUID())
                .session(session)
                .challengeType(io.mosip.liveness.models.enums.ChallengeType.BLINK)
                .issuedAt(OffsetDateTime.now())
                .build();

        ChallengeValidateRequest req = ChallengeValidateRequest.builder()
                .challengeId(challenge.getId())
                .framesBase64(List.of("frame1"))
                .capturedAtEpochMs(List.of(System.currentTimeMillis() - 10_000L)) // 10s before prompt
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> validator.validateChallenge(session, challenge, req));
        assertTrue(ex.getReason().contains("before the challenge was issued"));
    }

    @Test
    @DisplayName("server-side evidence verification verifies nonce and timestamps")
    void verifyEvidenceSuccess() {
        long now = System.currentTimeMillis();
        EvidenceVerificationRequest req = EvidenceVerificationRequest.builder()
                .sessionId(session.getId().toString())
                .nonceHex(session.getNonce())
                .startedEpochMs(now - 4000)
                .completedEpochMs(now - 100)
                .passiveScore(0.92)
                .engineId("onnx-minifasnet-v2")
                .modelVersion("1.0")
                .challengeResults(List.of(true))
                .signatureHex("0123456789abcdef")
                .build();

        EvidenceVerificationResponse res = evidenceVerifier.verify(session, req);
        assertTrue(res.isVerified());
        assertNull(res.getFailureReason());
    }

    @Test
    @DisplayName("server-side evidence verification rejects wrong nonce")
    void verifyEvidenceNonceMismatch() {
        EvidenceVerificationRequest req = EvidenceVerificationRequest.builder()
                .sessionId(session.getId().toString())
                .nonceHex("wrong-nonce")
                .build();

        EvidenceVerificationResponse res = evidenceVerifier.verify(session, req);
        assertFalse(res.isVerified());
        assertTrue(res.getFailureReason().contains("nonce"));
    }
}
