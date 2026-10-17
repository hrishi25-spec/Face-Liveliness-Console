package io.mosip.liveness.services;

import io.mosip.liveness.config.EffectivePolicy;
import io.mosip.liveness.core.WorkflowType;
import io.mosip.liveness.crud.FrameEventRepository;
import io.mosip.liveness.eval.ProxyPresentationCorpus;
import io.mosip.liveness.eval.ThresholdSweep;
import io.mosip.liveness.models.entity.FrameEvent;
import io.mosip.liveness.models.enums.LivenessStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs threshold calibration end-to-end and returns the BPCER/APCER/ACER table
 * plus a recommended operating point (see {@link ThresholdSweep}).
 *
 * <p>Inputs:</p>
 * <ul>
 *   <li><b>Bona-fide windows</b> — the first {@code passiveMinFrames} passive
 *       scores of every session that passed, straight from {@code frame_events}
 *       (real captures, the exact window the live path would have decided on).</li>
 *   <li><b>Attack windows</b> — presentation-attack sessions recorded in
 *       {@code frame_events} when any exist; otherwise the labelled
 *       {@linkplain ProxyPresentationCorpus proxy corpus} (simulated print /
 *       screen degradations scored by the <em>current</em> scorer).</li>
 * </ul>
 *
 * <p>The result carries {@code scorer} and {@code attackDataIsProxy} so nobody
 * can mistake proxy numbers for ISO-grade measurements — the corpus gap is
 * documented in docs/status-report.md §4.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ThresholdCalibrationService {

    /** Default budget: at most 2% of genuine users pushed into an active challenge. */
    public static final double DEFAULT_TARGET_BPCER = 0.02;

    static final int PROXY_WINDOWS_PER_KIND = 25;
    static final int FRAMES_PER_PROXY_WINDOW = 5;
    private static final int PROXY_WIDTH = 320;
    private static final int PROXY_HEIGHT = 240;

    private final FrameEventRepository frameEventRepo;
    private final ConfigService configService;
    private final LivenessEngineService livenessEngine;
    private final PassiveScoringService passiveScorer;
    private final ImageUtils imageUtils;

    /**
     * @param targetBpcer maximum acceptable bona-fide escalation rate (0.02 = 2%)
     */
    public ThresholdSweep.Result run(double targetBpcer) {
        EffectivePolicy policy = configService.getEffectivePolicy(WorkflowType.RESIDENT_REGISTRATION);
        int minFrames = policy.passiveMinFrames();
        int windowFrames = policy.passiveWindowFrames();

        List<List<Double>> bonaFide = decisionWindows(
                frameEventRepo.findPassiveScoresForPassedSessions(LivenessStage.PASSIVE), minFrames);
        List<List<Double>> attack = decisionWindows(
                frameEventRepo.findPassiveScoresForAttackSessions(LivenessStage.PASSIVE), minFrames);

        boolean proxy = false;
        if (attack.isEmpty()) {
            attack = proxyAttackWindows(minFrames);
            proxy = !attack.isEmpty();
            if (!proxy) {
                log.warn("Threshold calibration: no attack samples available (DB or proxy corpus)");
            }
        }

        log.info("Threshold sweep: {} bona-fide windows, {} attack windows ({}), scorer={}",
                bonaFide.size(), attack.size(),
                proxy ? "proxy" : (attack.isEmpty() ? "none" : "recorded"),
                passiveScorer.scorerId());

        return ThresholdSweep.run(bonaFide, attack, minFrames, windowFrames,
                targetBpcer, passiveScorer.scorerId(), proxy);
    }

    /**
     * Groups chronologically-ordered frame events into per-session decision
     * windows: the first {@code minFrames} scored frames of each session, i.e.
     * exactly the window on which the live path takes its first decision.
     */
    static List<List<Double>> decisionWindows(List<FrameEvent> events, int minFrames) {
        Map<java.util.UUID, List<Double>> bySession = new LinkedHashMap<>();
        if (events != null) {
            for (FrameEvent e : events) {
                if (e == null || e.getLivenessScore() == null || e.getSession() == null) continue;
                bySession.computeIfAbsent(e.getSession().getId(), k -> new ArrayList<>())
                        .add(e.getLivenessScore());
            }
        }
        List<List<Double>> windows = new ArrayList<>();
        for (List<Double> scores : bySession.values()) {
            windows.add(scores.size() <= minFrames
                    ? scores
                    : new ArrayList<>(scores.subList(0, minFrames)));
        }
        return windows;
    }

    private List<List<Double>> proxyAttackWindows(int minFrames) {
        try {
            List<ProxyPresentationCorpus.Window> windows = ProxyPresentationCorpus.generate(
                    this::scoreOrNull,
                    PROXY_WINDOWS_PER_KIND,
                    FRAMES_PER_PROXY_WINDOW,
                    PROXY_WIDTH,
                    PROXY_HEIGHT);
            List<List<Double>> usable = new ArrayList<>();
            for (ProxyPresentationCorpus.Window w : windows) {
                if (w.scores().size() >= minFrames) usable.add(w.scores());
            }
            return usable;
        } catch (RuntimeException e) {
            log.warn("Proxy attack corpus could not be generated: {}", e.toString());
            return List.of();
        }
    }

    /**
     * Scores one proxy-corpus frame, or returns null when the detection
     * pipeline cannot see exactly one face in it (that frame must not become a
     * fabricated APCER data point).
     */
    private Double scoreOrNull(Mat frame) {
        LivenessEngineService.FaceObservation obs = livenessEngine.observeFace(frame, imageUtils);
        if (!obs.faceDetected() || obs.multipleFaces()) {
            return null;
        }
        return passiveScorer.score(frame, obs, imageUtils);
    }
}
