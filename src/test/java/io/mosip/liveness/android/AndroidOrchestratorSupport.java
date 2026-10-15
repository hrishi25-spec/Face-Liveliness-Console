package io.mosip.liveness.android;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import io.mosip.liveness.audit.AuditLogger;
import io.mosip.liveness.backend.MockLivenessBackend;
import io.mosip.liveness.config.LivenessConfig;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.engine.FaceLivenessEngine;

/**
 * Shared fixtures for the Android orchestrator tests: a direct executor so the
 * state machine runs deterministically on the test thread, a recording
 * listener, and a mock frame source fed synthetic 2x2 frames.
 */
final class AndroidOrchestratorSupport {

    private AndroidOrchestratorSupport() { }

    /** Runs tasks inline — deterministic state-machine tests. */
    static final Executor DIRECT = Runnable::run;

    /** Same-thread {@link ExecutorService} so orchestrator frames run inline. */
    static final class DirectExecutorService extends java.util.concurrent.AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { }
        @Override public java.util.List<Runnable> shutdownNow() { return java.util.List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return true;
        }
    }

    static FaceLivenessEngine engine(MockLivenessBackend backend) {
        return engine(backend, AuditLogger.noop());
    }

    /** Same engine with an injected audit sink (assertions on issuance). */
    static FaceLivenessEngine engine(MockLivenessBackend backend, AuditLogger audit) {
        return new FaceLivenessEngine(
                LivenessConfig.builder()
                        .passiveMinFrames(3)
                        .passiveWindowFrames(5)
                        .minChallengeCount(1)
                        .maxRetries(2)
                        // Spec §3 default pool: gaze challenges need landmark
                        // fidelity the mock does not model deterministically.
                        .supportedChallengeTypes(java.util.EnumSet.of(
                                io.mosip.liveness.core.ChallengeType.BLINK,
                                io.mosip.liveness.core.ChallengeType.SMILE,
                                io.mosip.liveness.core.ChallengeType.TURN_HEAD_LEFT,
                                io.mosip.liveness.core.ChallengeType.TURN_HEAD_RIGHT))
                        .build(),
                backend,
                audit,
                new io.mosip.liveness.audit.MetricsCollector(),
                java.time.Clock.systemUTC());
    }

    static AndroidLivenessPolicyProvider policyProvider() {
        return new AndroidLivenessPolicyProvider(AuditLogger.noop(), null);
    }

    static MockFaceFrameSource source(int frameCount) {
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < frameCount; i++) {
            frames.add(new Frame(new byte[]{1, 2, 3, 4}, 2, 2, Frame.Format.RGB_888, 1000L * i, i));
        }
        return new MockFaceFrameSource(frames);
    }

    /** Records every state and final event for assertions. */
    static final class RecordingListener implements LivenessListener {
        final List<LivenessStateEvent> states = new ArrayList<>();
        final List<LivenessFinalResult> finals = new ArrayList<>();

        @Override
        public void onState(LivenessStateEvent event) {
            states.add(event);
        }

        @Override
        public void onFinal(LivenessFinalResult result) {
            finals.add(result);
        }

        boolean sawState(LivenessState state) {
            return states.stream().anyMatch(e -> e.state() == state);
        }

        LivenessFinalResult lastFinal() {
            return finals.isEmpty() ? null : finals.get(finals.size() - 1);
        }
    }
}
