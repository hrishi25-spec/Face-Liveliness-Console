package io.mosip.liveness.android;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.mosip.liveness.audit.AuditEvent;
import io.mosip.liveness.audit.AuditEventType;
import io.mosip.liveness.audit.AuditLogger;
import io.mosip.liveness.backend.MockLivenessBackend;
import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.testing.MutableClock;
import io.mosip.liveness.testing.RecordingAuditLogger;

/**
 * Measured frame-rate fallback ({@code resource-compliance.md} §06 "3 fps
 * minimum → adaptive fallback"): the frame-source layer measures the rate
 * frames actually arrive at, and the orchestrator excludes BLINK from the
 * challenge draw when that measured rate is too low for reliable blink
 * detection — while healthy streams keep the full pool. The issuance audit
 * event carries the exclusion, so the decision is asserted deterministically.
 */
class FrameRateFallbackTest {

    private static final Set<String> FALLBACK_TYPES =
            Set.of("SMILE", "TURN_HEAD_LEFT", "TURN_HEAD_RIGHT");

    private MockLivenessBackend backend;
    private InMemoryModelStore modelStore;
    private RecordingAuditLogger engineAudit;
    private MutableClock clock;
    private AndroidLivenessOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        backend = new MockLivenessBackend();
        modelStore = new InMemoryModelStore();
        modelStore.activate("minifasnet", "2026.10-test",
                "test-model-payload".getBytes(StandardCharsets.UTF_8));
        engineAudit = new RecordingAuditLogger();
        clock = new MutableClock(System.currentTimeMillis());
        orchestrator = new AndroidLivenessOrchestrator(
                AndroidOrchestratorSupport.engine(backend, engineAudit), backend,
                AndroidOrchestratorSupport.policyProvider(),
                AuditLogger.noop(), clock, null, null, modelStore,
                new AndroidOrchestratorSupport.DirectExecutorService(), false);
    }

    @AfterEach
    void tearDown() {
        orchestrator.close();
    }

    /** Drive one gate until the engine escalates and prompts a challenge. */
    private AndroidOrchestratorSupport.RecordingListener runToPrompt(int frames,
                                                                    boolean advanceClockPerFrame) {
        AndroidOrchestratorSupport.RecordingListener listener =
                new AndroidOrchestratorSupport.RecordingListener();
        orchestrator.setListener(listener);
        MockFaceFrameSource source = AndroidOrchestratorSupport.source(frames);
        orchestrator.start(LivenessRole.RESIDENT, null, source,
                new FaceFrameSource.SourceConfig(640, 480, 15, true));
        while (source.tick()) {
            if (advanceClockPerFrame) {
                clock.advanceMillis(250);   // 4 fps — below the blink floor
            }
        }
        assertTrue(listener.sawState(LivenessState.CHALLENGE_PROMPT),
                () -> "expected escalation to a prompt; states: " + listener.states);
        return listener;
    }

    private String issuedExclusion() {
        AuditEvent issued = engineAudit.events().stream()
                .filter(e -> e.type() == AuditEventType.CHALLENGE_ISSUED)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no CHALLENGE_ISSUED event: " + engineAudit.events()));
        return issued.fields().get("excluded");
    }

    @Test
    void slowStreamExcludesBlinkAndPromptsOnlyFallbackTypes() {
        backend.subject().livenessScore = 0.30;         // forces escalation

        AndroidOrchestratorSupport.RecordingListener listener = runToPrompt(25, true);

        assertEquals("BLINK", issuedExclusion(),
                "a measured 4 fps stream must exclude blink at issuance");
        listener.states.stream()
                .filter(e -> e.state() == LivenessState.CHALLENGE_PROMPT)
                .map(LivenessStateEvent::challenge)
                .forEach(type -> {
                    assertNotEquals("BLINK", type,
                            "slow stream must never prompt blink");
                    assertTrue(FALLBACK_TYPES.contains(type),
                            "expected a fallback challenge, got: " + type);
                });
    }

    @Test
    void healthyStreamKeepsFullChallengePool() {
        backend.subject().livenessScore = 0.30;         // forces escalation

        // Frozen clock: every frame lands on the same instant — an unambiguously
        // fast burst, the healthy-stream case.
        runToPrompt(25, false);

        assertEquals("none", issuedExclusion(),
                "a fast stream must keep blink available in the pool");
    }

    @Test
    void blinkExclusionFollowsMeasuredRateBoundary() {
        assertEquals(Set.of(ChallengeType.BLINK),
                AndroidLivenessOrchestrator.challengeExclusions(4.99),
                "just below the floor must exclude blink");
        assertEquals(Set.of(ChallengeType.BLINK),
                AndroidLivenessOrchestrator.challengeExclusions(3.0),
                "the SBI minimum itself is too slow for blink");
        assertEquals(Set.of(ChallengeType.BLINK),
                AndroidLivenessOrchestrator.challengeExclusions(Double.NaN),
                "an unmeasurable rate must fail closed to no-blink");
        assertEquals(Set.of(),
                AndroidLivenessOrchestrator.challengeExclusions(
                        AndroidLivenessOrchestrator.MIN_FPS_FOR_BLINK),
                "at the floor blink stays available");
        assertEquals(Set.of(),
                AndroidLivenessOrchestrator.challengeExclusions(15.0));
        assertEquals(Set.of(),
                AndroidLivenessOrchestrator.challengeExclusions(
                        Double.POSITIVE_INFINITY),
                "same-instant bursts are fast, not unknown");
    }
}
