package io.mosip.liveness.characterization;

import io.mosip.liveness.backend.MockLivenessBackend;
import io.mosip.liveness.config.LivenessConfig;
import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.WorkflowType;
import io.mosip.liveness.engine.FaceLivenessEngine;
import io.mosip.liveness.testing.MutableClock;
import io.mosip.liveness.testing.RecordingAuditLogger;
import io.mosip.liveness.audit.MetricsCollector;

public class DebugChallenge {
    public static void main(String[] args) {
        MockLivenessBackend backend = new MockLivenessBackend();
        backend.subject().livenessScore = 0.30; // Below threshold to force escalation
        backend.subject().scoreNoise = 0.0;

        RecordingAuditLogger audit = new RecordingAuditLogger();
        MutableClock clock = new MutableClock(1_760_000_000_000L);
        MetricsCollector metrics = new MetricsCollector();

        // Test RESIDENT
        System.out.println("=== RESIDENT ===");
        FaceLivenessEngine engine = new FaceLivenessEngine(
            LivenessConfig.builder().passiveThreshold(0.80).build(),
            backend, audit, metrics, clock);
        String sid = engine.initSession(WorkflowType.RESIDENT_REGISTRATION);

        // Force escalation
        for (int i = 0; i < 10; i++) {
            engine.pushFrame(sid, new Frame(new byte[64], 8, 8, Frame.Format.RGB_GRAY, 0, i));
        }

        // Request first challenge to see pattern
        io.mosip.liveness.core.Challenge challenge = engine.requestChallenge(sid);
        System.out.println("First Challenge: " + challenge.type());

        // Test OPERATOR
        System.out.println("\n=== OPERATOR ===");
        engine = new FaceLivenessEngine(
            LivenessConfig.builder().passiveThreshold(0.80).build(),
            backend, audit, metrics, clock);
        sid = engine.initSession(WorkflowType.OPERATOR_AUTH);

        // Force escalation
        for (int i = 0; i < 10; i++) {
            engine.pushFrame(sid, new Frame(new byte[64], 8, 8, Frame.Format.RGB_GRAY, 0, i));
        }

        // Request first challenge to see pattern
        challenge = engine.requestChallenge(sid);
        System.out.println("First Challenge: " + challenge.type());

        // Test SUPERVISOR
        System.out.println("\n=== SUPERVISOR ===");
        engine = new FaceLivenessEngine(
            LivenessConfig.builder().passiveThreshold(0.80).build(),
            backend, audit, metrics, clock);
        sid = engine.initSession(WorkflowType.SUPERVISOR_AUTH);

        // Force escalation
        for (int i = 0; i < 10; i++) {
            engine.pushFrame(sid, new Frame(new byte[64], 8, 8, Frame.Format.RGB_GRAY, 0, i));
        }

        // Request first challenge to see pattern
        challenge = engine.requestChallenge(sid);
        System.out.println("First Challenge: " + challenge.type());
    }
}