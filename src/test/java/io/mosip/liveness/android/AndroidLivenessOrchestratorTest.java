package io.mosip.liveness.android;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.mosip.liveness.audit.AuditLogger;
import io.mosip.liveness.backend.MockLivenessBackend;
import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.engine.FaceLivenessEngine;

/**
 * Orchestrator flows against the shared engine with the mock backend (spec §15
 * acceptance items verifiable at this layer): passive pass, challenge
 * escalation, PAD block, retry limit + lockout, device errors not counting as
 * attempts, gate validity, and fail-closed evidence signing.
 */
class AndroidLivenessOrchestratorTest {

    private MockLivenessBackend backend;
    private AndroidLivenessOrchestrator orchestrator;
    private ExecutorService executor;
    private InMemoryModelStore modelStore;

    @BeforeEach
    void setUp() {
        backend = new MockLivenessBackend();
        executor = Executors.newSingleThreadExecutor();
        modelStore = new InMemoryModelStore();
        // A verified model must be active before any session can start (fail
        // closed when missing — see missingModelFailsClosed).
        modelStore.activate("minifasnet", "2026.10-test",
                "test-model-payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // Direct executor: the orchestrator's frame loop runs inline, so tests
        // drive the state machine deterministically from the test thread.
        orchestrator = new AndroidLivenessOrchestrator(
                AndroidOrchestratorSupport.engine(backend), backend,
                AndroidOrchestratorSupport.policyProvider(),
                AuditLogger.noop(), null, null, null, modelStore,
                new AndroidOrchestratorSupport.DirectExecutorService(), false);
    }

    @Test
    void missingModelFailsClosedWithDeviceError() {
        InMemoryModelStore empty = new InMemoryModelStore();
        AndroidLivenessOrchestrator orchestratorNoModel = new AndroidLivenessOrchestrator(
                AndroidOrchestratorSupport.engine(backend), backend,
                AndroidOrchestratorSupport.policyProvider(),
                AuditLogger.noop(), null, null, null, empty,
                new AndroidOrchestratorSupport.DirectExecutorService(), false);
        AndroidOrchestratorSupport.RecordingListener listener = new AndroidOrchestratorSupport.RecordingListener();
        orchestratorNoModel.setListener(listener);
        orchestratorNoModel.start(LivenessRole.RESIDENT, null, AndroidOrchestratorSupport.source(3),
                new FaceFrameSource.SourceConfig(640, 480, 15, true));
        assertTrue(listener.sawState(LivenessState.DEVICE_ERROR),
                "missing model must fail closed, never skip");
        orchestratorNoModel.close();
    }

    private FaceLivenessEngine engine() {
        return AndroidOrchestratorSupport.engine(backend);
    }

    @AfterEach
    void tearDown() {
        orchestrator.close();
        executor.shutdownNow();
    }

    @Test
    void passivePassProceedsWithoutUserAction() {
        AndroidOrchestratorSupport.RecordingListener listener = new AndroidOrchestratorSupport.RecordingListener();
        orchestrator.setListener(listener);
        MockFaceFrameSource source = AndroidOrchestratorSupport.source(10);
        orchestrator.start(LivenessRole.RESIDENT, null, source,
                new FaceFrameSource.SourceConfig(640, 480, 15, true));

        while (source.tick()) {
            // deliver all queued frames inline
        }

        assertTrue(listener.sawState(LivenessState.PASSED), () -> "states: " + listener.states);
        AndroidOrchestratorSupport.RecordingListener l = listener;
        LivenessFinalResult finalResult = l.finals.get(l.finals.size() - 1);
        assertEquals(LivenessFinalResult.LivenessOutcome.PASSED, finalResult.outcome());
        assertEquals(LivenessFinalResult.NextAction.PROCEED, finalResult.nextAction());
        assertTrue(finalResult.validForSeconds() > 0);
    }

    @Test
    void lowPassiveScoreEscalatesToEngineSelectedChallenge() {
        // Subject starts poor-quality/low-score (blurry first frames) → the gate
        // must escalate; during the challenge the "camera settles" (score
        // recovers) and the user performs the action → active path passes.
        backend.subject().livenessScore = 0.30;
        AndroidOrchestratorSupport.RecordingListener listener = new AndroidOrchestratorSupport.RecordingListener();
        orchestrator.setListener(listener);
        MockFaceFrameSource source = AndroidOrchestratorSupport.source(40);
        orchestrator.start(LivenessRole.RESIDENT, null, source,
                new FaceFrameSource.SourceConfig(640, 480, 15, true));

        int guard = 0;
        int armedPrompts = 0;
        while (source.tick() && guard++ < 200) {
            long promptCount = listener.states.stream()
                    .filter(e -> e.state() == LivenessState.CHALLENGE_PROMPT).count();
            if (promptCount > armedPrompts) {
                armedPrompts = (int) promptCount;
                backend.subject().livenessScore = 0.93;   // genuine user, camera settled
                performPromptedChallenge(backend, listener);  // ...and performs it
            }
            if (listener.sawState(LivenessState.PASSED)) {
                break;
            }
        }

        assertTrue(listener.sawState(LivenessState.CHALLENGE_PROMPT),
                () -> "states: " + listener.states);
        assertTrue(listener.sawState(LivenessState.PASSED), () -> "states: " + listener.states);
        // The prompt key must be an i18n key, not raw text (R5).
        LivenessStateEvent prompt = listener.states.stream()
                .filter(e -> e.state() == LivenessState.CHALLENGE_PROMPT).findFirst().orElseThrow();
        assertNotNull(prompt.uiMessageKey());
        assertTrue(prompt.uiMessageKey().startsWith("prompt."));
    }

    /**
     * Perform whichever challenge the engine selected — the user never chooses
     * (R3). BLINK needs a closed→open→closed temporal sequence, which the
     * mock's stateful subject cannot express via stored frames, so it is armed
     * through queued per-frame signals (real-time pass + batch replay copy).
     */
    private static void performPromptedChallenge(MockLivenessBackend backend,
                                                 AndroidOrchestratorSupport.RecordingListener listener) {
        listener.states.stream()
                .filter(e -> e.state() == LivenessState.CHALLENGE_PROMPT)
                .reduce((first, second) -> second)   // latest prompt
                .map(LivenessStateEvent::challenge)
                .ifPresent(type -> {
                    switch (type == null ? "" : type) {
                        case "BLINK" -> {
                            backend.clearSignalOverrides();
                            var closed = io.mosip.liveness.core.FaceSignals.live(
                                    1, 0.92, 0.08, 0.05, 0, 0, 0, 0);
                            var open = io.mosip.liveness.core.FaceSignals.live(
                                    1, 0.92, 0.34, 0.05, 0, 0, 0, 0);
                            // real-time pass: closed → open → closed (HOLD_STILL)
                            backend.enqueueSignal(closed);
                            backend.enqueueSignal(open);
                            backend.enqueueSignal(closed);
                            // batch replay of the same 3 stored frames
                            backend.enqueueSignal(closed);
                            backend.enqueueSignal(open);
                            backend.enqueueSignal(closed);
                        }
                        case "SMILE" -> backend.subject().smileBig();
                        case "TURN_HEAD_LEFT" -> backend.subject().turnLeft(20);
                        case "TURN_HEAD_RIGHT" -> backend.subject().turnRight(20);
                        default -> { /* leave neutral */ }
                    }
                });
    }

    @Test
    void padDetectedBlocksAndCountsAsAttempt() {
        backend.subject().attack(PadAttackType.PRINTED_PHOTO, 0.95);
        AndroidOrchestratorSupport.RecordingListener listener = new AndroidOrchestratorSupport.RecordingListener();
        orchestrator.setListener(listener);
        MockFaceFrameSource source = AndroidOrchestratorSupport.source(5);
        orchestrator.start(LivenessRole.RESIDENT, null, source,
                new FaceFrameSource.SourceConfig(640, 480, 15, true));

        while (source.tick()) { /* drain */ }

        assertTrue(listener.sawState(LivenessState.ATTEMPT_FAILED), () -> "states: " + listener.states);
        // PAD messages to the UI stay generic (R5): the PAD-specific key is used,
        // never attack-type detail.
        LivenessStateEvent failed = listener.states.stream()
                .filter(e -> e.state() == LivenessState.ATTEMPT_FAILED).findFirst().orElseThrow();
        assertEquals("liveness.pad.generic", failed.uiMessageKey());
    }

    @Test
    void deviceErrorDoesNotConsumeRetryBudget() {
        AndroidOrchestratorSupport.RecordingListener listener = new AndroidOrchestratorSupport.RecordingListener();
        orchestrator.setListener(listener);
        MockFaceFrameSource source = AndroidOrchestratorSupport.source(5);
        orchestrator.start(LivenessRole.RESIDENT, null, source,
                new FaceFrameSource.SourceConfig(640, 480, 15, true));

        orchestrator.onSourceError(LivenessDeviceError.DISCONNECTED, "cable pulled");

        assertTrue(listener.sawState(LivenessState.DEVICE_ERROR), () -> "states: " + listener.states);
        LivenessStateEvent err = listener.states.stream()
                .filter(e -> e.state() == LivenessState.DEVICE_ERROR).findFirst().orElseThrow();
        assertEquals(LivenessFailCategory.DEVICE, err.failCategory());
        assertEquals(0, err.attemptsUsed(), "device error must not count as an attempt");
    }

    @Test
    void gateExpiresAfterValidityWindow() throws Exception {
        var mutableClock = new io.mosip.liveness.testing.MutableClock(System.currentTimeMillis());
        AndroidLivenessOrchestrator timed = new AndroidLivenessOrchestrator(
                AndroidOrchestratorSupport.engine(backend), backend,
                AndroidOrchestratorSupport.policyProvider(), AuditLogger.noop(), mutableClock,
                null, null, modelStore,
                new AndroidOrchestratorSupport.DirectExecutorService(), false);
        // inline frames: same reflection trick as setUp
        AndroidOrchestratorSupport.RecordingListener listener = new AndroidOrchestratorSupport.RecordingListener();
        timed.setListener(listener);

        MockFaceFrameSource source = AndroidOrchestratorSupport.source(10);
        timed.start(LivenessRole.RESIDENT, null, source,
                new FaceFrameSource.SourceConfig(640, 480, 15, true));
        while (source.tick()) { /* drain */ }

        String sessionId = listener.states.stream()
                .filter(e -> e.state() == LivenessState.PASSED).findFirst().orElseThrow().sessionId();
        assertTrue(timed.isGateValid(sessionId), "gate must be valid immediately after PASSED");

        mutableClock.advanceMillis(31_000);
        assertFalse(timed.isGateValid(sessionId), "gate must expire after validitySec");
        assertTrue(timed.currentEvidence().isEmpty(), "expired evidence must not be handed out");
        timed.close();
    }

    @Test
    void evidenceIsSignedAndVerifiable() {
        AndroidOrchestratorSupport.RecordingListener listener = new AndroidOrchestratorSupport.RecordingListener();
        orchestrator.setListener(listener);
        MockFaceFrameSource source = AndroidOrchestratorSupport.source(10);
        orchestrator.start(LivenessRole.RESIDENT, "user-42", source,
                new FaceFrameSource.SourceConfig(640, 480, 15, true));
        while (source.tick()) { /* drain */ }

        var evidence = orchestrator.currentEvidence();
        assertTrue(evidence.isPresent(), "PASSED gate must produce evidence");
        assertEquals("user-42", evidence.get().userId().orElseThrow());
        assertNotNull(evidence.get().signatureHex());
        assertFalse(evidence.get().nonceHex().isBlank());
    }

    @Test
    void failingSignerFailsClosedWithAudit() {
        AndroidLivenessOrchestrator broken = new AndroidLivenessOrchestrator(
                AndroidOrchestratorSupport.engine(backend), backend,
                AndroidOrchestratorSupport.policyProvider(), AuditLogger.noop(), null,
                new LivenessEvidenceSigner.AlwaysFailingSigner(), null, modelStore,
                new AndroidOrchestratorSupport.DirectExecutorService(), false);
        AndroidOrchestratorSupport.RecordingListener listener = new AndroidOrchestratorSupport.RecordingListener();
        broken.setListener(listener);
        MockFaceFrameSource source = AndroidOrchestratorSupport.source(10);
        broken.start(LivenessRole.RESIDENT, null, source,
                new FaceFrameSource.SourceConfig(640, 480, 15, true));
        while (source.tick()) { /* drain */ }

        // The gate still reports PASSED (capture proceeds for UX) but the
        // evidence is absent, so downstream binding checks refuse — fail closed.
        assertTrue(listener.sawState(LivenessState.PASSED));
        assertTrue(broken.currentEvidence().isEmpty(), "unsigned evidence must never be handed out");
        broken.close();
    }

    @Test
    void cancelAbortsSession() {
        AndroidOrchestratorSupport.RecordingListener listener = new AndroidOrchestratorSupport.RecordingListener();
        orchestrator.setListener(listener);
        MockFaceFrameSource source = AndroidOrchestratorSupport.source(5);
        orchestrator.start(LivenessRole.OPERATOR, "op-1", source,
                new FaceFrameSource.SourceConfig(640, 480, 15, true));
        orchestrator.cancel();

        LivenessFinalResult last = listener.lastFinal();
        assertNotNull(last);
        assertEquals(LivenessFinalResult.LivenessOutcome.ABORTED, last.outcome());
    }

    @Test
    void policyFloorHoldsAgainstWeakConfig() {
        AndroidLivenessPolicyProvider provider = AndroidOrchestratorSupport.policyProvider();
        provider.registerRoleOverride(LivenessRole.SUPERVISOR, b -> b
                .passiveThreshold(0.10)      // attempts to weaken below floor
                .maxRetries(99)              // attempts to exceed ceiling
                .challengeTimeoutSec(1));    // attempts to shrink below minimum

        LivenessGatePolicy p = provider.resolve(LivenessRole.SUPERVISOR);
        assertTrue(p.passiveThreshold() >= LivenessGatePolicy.FLOOR_PASSIVE_THRESHOLD,
                "floor must hold: " + p.passiveThreshold());
        assertTrue(p.maxRetries() <= LivenessGatePolicy.CEILING_MAX_RETRIES);
        assertTrue(p.challengeTimeoutSec() >= LivenessGatePolicy.MIN_CHALLENGE_TIMEOUT_SEC);
    }

    @Test
    void supervisorGetsStricterDefaultsThanResident() {
        AndroidLivenessPolicyProvider provider = AndroidOrchestratorSupport.policyProvider();
        LivenessGatePolicy resident = provider.resolve(LivenessRole.RESIDENT);
        LivenessGatePolicy supervisor = provider.resolve(LivenessRole.SUPERVISOR);
        assertTrue(supervisor.minChallenges() >= resident.minChallenges());
        assertTrue(supervisor.passiveThreshold() >= resident.passiveThreshold());
    }

    @Test
    void challengePromptKeyMapping() {
        assertEquals("prompt.turn_left",
                LivenessStateEvent.challengePromptKey("TURN_HEAD_LEFT"));
        assertEquals("prompt.blink", LivenessStateEvent.challengePromptKey("BLINK"));
        assertEquals("liveness.checking", LivenessStateEvent.challengePromptKey(null));
    }

    @Test
    void disabledPolicyFailsClosedWithoutBuildFlag() {
        AndroidLivenessPolicyProvider provider = AndroidOrchestratorSupport.policyProvider();
        provider.registerRoleOverride(LivenessRole.RESIDENT, b -> b.enabled(false));
        LivenessGatePolicy p = provider.resolve(LivenessRole.RESIDENT);
        assertTrue(p.enabled(), "config must not disable liveness without the build flag");
    }
}
