package io.mosip.liveness.diagnostics;

import java.time.Instant;
import java.util.List;

/**
 * What diagnostic mode exposes — orchestration spec §10: "adds raw scores,
 * per-frame timings, FPS, delegate used. <b>Still no pixels.</b>"
 *
 * <p>Served only by {@code GET /api/v1/diagnostics}, and only when the mode is
 * opted in (<code>mosip.liveness.diagnostics-enabled</code>) <em>and</em> the
 * caller is loopback — the local-only debug panel. {@code ui-ux-design.md}
 * keeps scores, PAD detail and model internals out of the user-facing UI
 * (R5); this payload is the troubleshooting escape hatch behind both gates.
 *
 * @param enabled      false for the fail-closed {@link #disabled()} snapshot
 * @param scorer       delegate that produced the scores (model id or
 *                     {@code opencv-heuristic}); null until one was scored
 * @param fps          frames/second over the sliding window; null with
 *                     fewer than two frames in it
 * @param frameCount   frames recorded since the process started (mode on)
 * @param lastScore    raw passive score of the most recent scored frame
 * @param medianScore  median of the retained raw scores
 * @param avgFrameMs   mean end-to-end frame cost over the retained window
 * @param maxFrameMs   worst end-to-end frame cost in the retained window
 * @param recentFrames chronological capped rows for the panel table, newest last
 * @param capturedAt   when the snapshot was taken
 */
public record DiagnosticsSnapshot(
        boolean enabled,
        String scorer,
        Double fps,
        long frameCount,
        Double lastScore,
        Double medianScore,
        Double avgFrameMs,
        Double maxFrameMs,
        List<FrameSample> recentFrames,
        Instant capturedAt) {

    /**
     * One retained frame — numbers, a timestamp and short labels only. The
     * type itself is the "no pixels" contract: there is nowhere in here to
     * put frame bytes, base64 or an image path.
     */
    public record FrameSample(
            Instant at,
            Double score,
            Double faceQuality,
            boolean padFlag,
            long frameMs,
            String action) {
    }

    /** Fail-closed answer when the mode is off: nothing retained, nothing to read. */
    public static DiagnosticsSnapshot disabled() {
        return new DiagnosticsSnapshot(false, null, null, 0L,
                null, null, null, null, List.of(), null);
    }
}
