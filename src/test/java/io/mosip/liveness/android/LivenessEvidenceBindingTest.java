package io.mosip.liveness.android;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.mosip.liveness.audit.AuditLogger;
import io.mosip.liveness.backend.MockLivenessBackend;

/**
 * End-to-end binding between signed liveness evidence and the downstream
 * signed capture (orchestration spec §8 consume gate:
 * {@code bindingOk = isGateValid && consistency(bestFrame, signedCapture)};
 * analyse.md §5.4). The capture payload carries the captured frame's sha256
 * and the gate's nonce; the binding check accepts only a capture whose signed
 * fields match the evidence the orchestrator signed, and fails closed on any
 * tampering: foreign frame pixels, altered nonce, foreign session, forged
 * signatures, edited evidence, or an expired gate window.
 */
class LivenessEvidenceBindingTest {

    /** Downstream capture payload (spec §5.3 {@code D-->>UI: signed face}). */
    record SignedCapture(String sessionId, String nonceHex, String frameSha256,
                         long capturedAtEpochMs, String signatureHex) {
        String canonicalPayload() {
            return String.join("|", sessionId, nonceHex, frameSha256,
                    String.valueOf(capturedAtEpochMs));
        }
    }

    /** Pixels every frame of the mock source carries — the subject the gate saw. */
    private static final byte[] SUBJECT_FRAME = {1, 2, 3, 4};
    /** A different face's pixels — same session, different subject. */
    private static final byte[] OTHER_FRAME = {9, 9, 9, 9};

    private MockLivenessBackend backend;
    private InMemoryModelStore modelStore;
    private LivenessEvidenceSigner evidenceSigner;   // gate's evidence-signing key
    private LivenessEvidenceSigner captureSigner;    // capture path's separate key
    private AndroidLivenessOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        backend = new MockLivenessBackend();
        modelStore = new InMemoryModelStore();
        modelStore.activate("minifasnet", "2026.10-test",
                "test-model-payload".getBytes(StandardCharsets.UTF_8));
        evidenceSigner = new LivenessEvidenceSigner.InProcessRsaSigner();
        captureSigner = new LivenessEvidenceSigner.InProcessRsaSigner();
        orchestrator = new AndroidLivenessOrchestrator(
                AndroidOrchestratorSupport.engine(backend), backend,
                AndroidOrchestratorSupport.policyProvider(),
                AuditLogger.noop(), null, evidenceSigner, null, modelStore,
                new AndroidOrchestratorSupport.DirectExecutorService(), false);
    }

    @AfterEach
    void tearDown() {
        orchestrator.close();
    }

    // ------------------------------------------------------------------ flow helpers

    /** Drive a gate to PASSED and hand back the signed evidence it emitted. */
    private LivenessEvidence passGate() {
        AndroidOrchestratorSupport.RecordingListener listener =
                new AndroidOrchestratorSupport.RecordingListener();
        orchestrator.setListener(listener);
        MockFaceFrameSource source = AndroidOrchestratorSupport.source(10);
        orchestrator.start(LivenessRole.RESIDENT, "user-42", source,
                new FaceFrameSource.SourceConfig(640, 480, 15, true));
        while (source.tick()) {
            // drain queued frames inline
        }
        assertTrue(listener.sawState(LivenessState.PASSED), () -> "states: " + listener.states);
        return orchestrator.currentEvidence().orElseThrow(
                () -> new AssertionError("PASSED gate must emit signed evidence"));
    }

    /** Build the downstream capture over the given pixels, signed by the capture key. */
    private SignedCapture signCapture(String sessionId, String nonceHex, byte[] pixels)
            throws Exception {
        String frameSha256 = sha256Hex(pixels);
        long capturedAt = System.currentTimeMillis();
        SignedCapture unsigned = new SignedCapture(sessionId, nonceHex, frameSha256,
                capturedAt, null);
        return new SignedCapture(sessionId, nonceHex, frameSha256, capturedAt,
                captureSigner.sign(unsigned.canonicalPayload()));
    }

    /**
     * Spec §8 consume-gate equation over the deterministic parts of
     * {@code consistency(bestFrame, signedCapture)}: the gate must still be
     * alive, both signatures must verify, and the capture's signed fields must
     * equal the evidence the orchestrator signed.
     */
    private boolean bindingOk(AndroidLivenessOrchestrator gate, LivenessEvidence evidence,
                              SignedCapture capture) {
        if (!gate.isGateValid(evidence.sessionId())) {
            return false;
        }
        if (!evidenceSigner.verify(evidence.canonicalPayload(), evidence.signatureHex())) {
            return false;
        }
        if (!captureSigner.verify(capture.canonicalPayload(), capture.signatureHex())) {
            return false;
        }
        return Objects.equals(evidence.sessionId(), capture.sessionId())
                && Objects.equals(evidence.nonceHex(), capture.nonceHex())
                && Objects.equals(evidence.bestFrameSha256(), capture.frameSha256());
    }

    /** Independent digest (raw MessageDigest), never the production helper. */
    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Evidence copy with a swapped frame hash / nonce — signature left untouched. */
    private static LivenessEvidence withFields(LivenessEvidence e, String frameSha256,
                                               String nonceHex) {
        return new LivenessEvidence(e.sessionId(), nonceHex, e.role(), e.userId(),
                e.policyVersion(), e.engineId(), e.modelVersion(), e.engineCertified(),
                e.passiveScore(), e.challenges(), e.challengeResults(), e.startedEpochMs(),
                e.completedEpochMs(), frameSha256, e.deviceId(), e.bypassed(),
                e.signatureHex());
    }

    /** Flip the first hex char — deterministically different, same length. */
    private static String flipFirstHexChar(String hex) {
        return (hex.charAt(0) == '0' ? '1' : '0') + hex.substring(1);
    }

    // ------------------------------------------------------------------ binding tests

    @Test
    void evidenceBindsToDownstreamSignedCapture() throws Exception {
        LivenessEvidence evidence = passGate();

        // The evidence hash IS the sha256 of the best frame's pixels —
        // recomputed here with an independent digest, not the production helper.
        assertNotNull(evidence.bestFrameSha256(), "evidence must carry the best-frame hash");
        assertTrue(evidence.bestFrameSha256().matches("[0-9a-f]{64}"),
                () -> "not a sha256 hex digest: " + evidence.bestFrameSha256());
        assertEquals(sha256Hex(SUBJECT_FRAME), evidence.bestFrameSha256(),
                "hash must be of the frame pixels the gate judged");
        assertTrue(evidenceSigner.verify(evidence.canonicalPayload(), evidence.signatureHex()),
                "evidence signature must verify over the canonical payload");

        SignedCapture capture = signCapture(evidence.sessionId(), evidence.nonceHex(),
                SUBJECT_FRAME);
        assertTrue(bindingOk(orchestrator, evidence, capture),
                "matching signed capture must bind to the evidence");
        // Distinct keys per path: the evidence signature must not verify
        // under the capture key (and vice versa — see the tamper tests).
        assertFalse(captureSigner.verify(evidence.canonicalPayload(), evidence.signatureHex()),
                "capture key must not verify evidence signatures");
    }

    @Test
    void captureOfDifferentFrameIsRejected() throws Exception {
        LivenessEvidence evidence = passGate();
        // Attacker holds the capture signing key and signs a capture of their
        // own face: the signature is perfectly valid, only the pixels differ.
        SignedCapture foreign = signCapture(evidence.sessionId(), evidence.nonceHex(),
                OTHER_FRAME);
        assertTrue(captureSigner.verify(foreign.canonicalPayload(), foreign.signatureHex()),
                "precondition: capture signature itself is valid");
        assertFalse(bindingOk(orchestrator, evidence, foreign),
                "frame-hash mismatch must break the binding");
    }

    @Test
    void tamperedCaptureSignatureIsRejected() throws Exception {
        LivenessEvidence evidence = passGate();
        SignedCapture good = signCapture(evidence.sessionId(), evidence.nonceHex(),
                SUBJECT_FRAME);
        SignedCapture tampered = new SignedCapture(good.sessionId(), good.nonceHex(),
                good.frameSha256(), good.capturedAtEpochMs(),
                flipFirstHexChar(good.signatureHex()));
        assertFalse(captureSigner.verify(tampered.canonicalPayload(), tampered.signatureHex()));
        assertFalse(bindingOk(orchestrator, evidence, tampered),
                "forged capture signature must break the binding");
    }

    @Test
    void tamperedEvidenceIsRejected() throws Exception {
        LivenessEvidence evidence = passGate();
        SignedCapture capture = signCapture(evidence.sessionId(), evidence.nonceHex(),
                SUBJECT_FRAME);
        // Baseline: untouched evidence binds.
        assertTrue(bindingOk(orchestrator, evidence, capture),
                "precondition: untampered evidence binds");

        // Swap the frame hash after signing: the canonical payload changes and
        // the signature no longer covers it.
        LivenessEvidence wrongFrame = withFields(evidence, sha256Hex(OTHER_FRAME),
                evidence.nonceHex());
        assertFalse(evidenceSigner.verify(wrongFrame.canonicalPayload(), wrongFrame.signatureHex()),
                "evidence signature must cover bestFrameSha256");
        assertFalse(bindingOk(orchestrator, wrongFrame, capture),
                "evidence with a swapped frame hash must never bind");

        // Swap the nonce after signing.
        LivenessEvidence wrongNonce = withFields(evidence, evidence.bestFrameSha256(),
                flipFirstHexChar(evidence.nonceHex()));
        assertFalse(evidenceSigner.verify(wrongNonce.canonicalPayload(), wrongNonce.signatureHex()),
                "evidence signature must cover the nonce");
        assertFalse(bindingOk(orchestrator, wrongNonce, capture),
                "evidence with a swapped nonce must never bind, even to a valid capture");
    }

    @Test
    void replayedCaptureWithStaleNonceIsRejected() throws Exception {
        LivenessEvidence evidence = passGate();
        // Same session and pixels, but a stale nonce — the attacker re-signs
        // (capture key compromised) hoping the frame-hash check alone carries
        // the binding.
        SignedCapture replay = signCapture(evidence.sessionId(),
                flipFirstHexChar(evidence.nonceHex()), SUBJECT_FRAME);
        assertTrue(captureSigner.verify(replay.canonicalPayload(), replay.signatureHex()),
                "precondition: capture signature itself is valid");
        assertFalse(bindingOk(orchestrator, evidence, replay),
                "stale nonce must break the binding");
    }

    @Test
    void captureBoundToDifferentSessionIsRejected() throws Exception {
        LivenessEvidence evidence = passGate();
        SignedCapture foreign = signCapture("some-other-gate-session",
                evidence.nonceHex(), SUBJECT_FRAME);
        assertFalse(bindingOk(orchestrator, evidence, foreign),
                "capture from another session must never bind");
    }

    @Test
    void expiredGateRejectsBindingEvenWithMatchingCapture() throws Exception {
        var clock = new io.mosip.liveness.testing.MutableClock(System.currentTimeMillis());
        AndroidLivenessOrchestrator timed = new AndroidLivenessOrchestrator(
                AndroidOrchestratorSupport.engine(backend), backend,
                AndroidOrchestratorSupport.policyProvider(), AuditLogger.noop(), clock,
                evidenceSigner, null, modelStore,
                new AndroidOrchestratorSupport.DirectExecutorService(), false);
        try {
            AndroidOrchestratorSupport.RecordingListener listener =
                    new AndroidOrchestratorSupport.RecordingListener();
            timed.setListener(listener);
            MockFaceFrameSource source = AndroidOrchestratorSupport.source(10);
            timed.start(LivenessRole.RESIDENT, "user-42", source,
                    new FaceFrameSource.SourceConfig(640, 480, 15, true));
            while (source.tick()) { /* drain */ }
            assertTrue(listener.sawState(LivenessState.PASSED), () -> "states: " + listener.states);

            LivenessEvidence evidence = timed.currentEvidence().orElseThrow();
            SignedCapture capture = signCapture(evidence.sessionId(), evidence.nonceHex(),
                    SUBJECT_FRAME);
            assertTrue(bindingOk(timed, evidence, capture), "fresh gate must bind");

            clock.advanceMillis(31_000);
            assertFalse(timed.isGateValid(evidence.sessionId()),
                    "gate must expire after validitySec");
            assertFalse(bindingOk(timed, evidence, capture),
                    "expired gate must fail closed even when the capture still matches");
        } finally {
            timed.close();
        }
    }
}
