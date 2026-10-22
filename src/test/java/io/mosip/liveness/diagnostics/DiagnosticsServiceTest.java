package io.mosip.liveness.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import io.mosip.liveness.diagnostics.DiagnosticsSnapshot.FrameSample;
import io.mosip.liveness.dto.FrameProcessResult;
import io.mosip.liveness.services.PassiveScoringService;
import io.mosip.liveness.testing.MutableClock;

/**
 * Diagnostic mode's collector — orchestration spec §10: raw scores,
 * per-frame timings, FPS, delegate used, still no pixels; opt-in is
 * fail-closed.
 */
class DiagnosticsServiceTest {

    private static final long EPOCH = 1_000_000L;
    private static final long MS = 1_000_000L; // nanos in a millisecond

    // Jackson 3 carries java.time support in databind itself: the Jackson 2
    // JavaTimeModule no longer exists as a separate registration.
    private final ObjectMapper mapper = new ObjectMapper();

    // ------------------------------------------------------------ opt-in is fail-closed

    @Test
    void disabledModeRetainsNothingAtAll() {
        PassiveScoringService scorer = mock(PassiveScoringService.class);
        DiagnosticsService service = new DiagnosticsService(false, new MutableClock(EPOCH), 10, scorer);

        service.recordFrame(frame(0.91, "retry_passive"), 12 * MS);
        service.recordFrame(frame(0.42, "escalate_to_active"), 30 * MS);

        DiagnosticsSnapshot snapshot = service.snapshot();
        assertFalse(snapshot.enabled());
        assertEquals(0L, snapshot.frameCount());
        assertTrue(snapshot.recentFrames().isEmpty(), "opt-in that buffers anyway would be always-on");
        assertNull(snapshot.lastScore());
        assertNull(snapshot.medianScore());
        assertNull(snapshot.fps());
        assertNull(snapshot.scorer());
        verifyNoInteractions(scorer); // the delegate is not even asked when the mode is off
    }

    // ------------------------------------------------------------ aggregation

    @Test
    void enabledModeAggregatesRawScoresTimingsAndDelegate() {
        MutableClock clock = new MutableClock(EPOCH);
        PassiveScoringService scorer = mock(PassiveScoringService.class);
        when(scorer.scorerId()).thenReturn("minifasnet-v2");
        DiagnosticsService service = new DiagnosticsService(true, clock, 10, scorer);

        service.recordFrame(frame(0.90, "retry_passive"), 10 * MS);
        clock.advanceMillis(250);
        service.recordFrame(frame(0.60, "retry_passive"), 20 * MS);
        clock.advanceMillis(250);
        service.recordFrame(frame(0.80, "escalate_to_active"), 30 * MS);

        DiagnosticsSnapshot snapshot = service.snapshot();
        assertTrue(snapshot.enabled());
        assertEquals(3L, snapshot.frameCount());
        assertEquals(0.80, snapshot.lastScore(), 1e-9, "raw score of the most recent scored frame");
        assertEquals(0.80, snapshot.medianScore(), 1e-9, "median of [0.90, 0.60, 0.80]");
        assertEquals(20.0, snapshot.avgFrameMs(), 1e-9);
        assertEquals(30.0, snapshot.maxFrameMs(), 1e-9);
        assertEquals("minifasnet-v2", snapshot.scorer(), "delegate used, once a frame was scored");
        assertEquals(4.0, snapshot.fps(), 1e-9,
                "3 frames over a 500 ms span is (3-1)/0.5s");
        assertEquals(EPOCH + 500, snapshot.capturedAt().toEpochMilli());

        List<FrameSample> rows = snapshot.recentFrames();
        assertEquals(3, rows.size());
        assertEquals(0.80, rows.get(2).score(), 1e-9, "rows are chronological, newest last");
        assertEquals(30L, rows.get(2).frameMs());
        assertEquals("escalate_to_active", rows.get(2).action());
    }

    @Test
    void fpsSlidesOutOfWindowButTotalsAreKept() {
        MutableClock clock = new MutableClock(EPOCH);
        DiagnosticsService service =
                new DiagnosticsService(true, clock, 10, mock(PassiveScoringService.class));

        service.recordFrame(frame(0.7, "retry_passive"), 10 * MS);
        clock.advanceMillis(250);
        service.recordFrame(frame(0.7, "retry_passive"), 10 * MS);
        assertEquals(4.0, service.snapshot().fps(), 1e-9);

        clock.advanceMillis(6000); // both frames fall out of the 5 s window
        DiagnosticsSnapshot later = service.snapshot();
        assertNull(later.fps(), "fewer than two frames in the window is unknown, not zero");
        assertEquals(2L, later.frameCount(), "totals survive the window sliding");
        assertEquals(0.7, later.lastScore(), 1e-9, "retained scores are not dropped with the window");
    }

    @Test
    void theRingIsBoundedAndOnlyNewestRowsSurvive() {
        MutableClock clock = new MutableClock(EPOCH);
        DiagnosticsService service =
                new DiagnosticsService(true, clock, 3, mock(PassiveScoringService.class));

        for (int i = 1; i <= 5; i++) {
            service.recordFrame(frame(i / 10.0, "retry_passive"), 5 * MS);
            clock.advanceMillis(100);
        }

        DiagnosticsSnapshot snapshot = service.snapshot();
        assertEquals(5L, snapshot.frameCount(), "the total counts everything");
        assertEquals(3, snapshot.recentFrames().size(), "memory stays bounded at the ring size");
        assertEquals(0.5, snapshot.lastScore(), 1e-9, "only the newest rows are kept");
        assertEquals(0.4, snapshot.medianScore(), 1e-9, "median is over the retained [0.3, 0.4, 0.5]");
    }

    // ------------------------------------------------------------ delegate + flags

    @Test
    void delegateIsLearnedOnlyOnceAScoreExists() {
        PassiveScoringService scorer = mock(PassiveScoringService.class);
        when(scorer.scorerId()).thenReturn("opencv-heuristic");
        DiagnosticsService service = new DiagnosticsService(true, new MutableClock(EPOCH), 10, scorer);

        // A no-face frame never reached the scorer: no delegate to report yet.
        service.recordFrame(FrameProcessResult.builder()
                .faceDetected(false).action("retry_passive").build(), 4 * MS);
        assertNull(service.snapshot().scorer());

        service.recordFrame(frame(0.55, "retry_passive"), 8 * MS);
        service.recordFrame(frame(0.65, "retry_passive"), 9 * MS);
        assertEquals("opencv-heuristic", service.snapshot().scorer());
        verify(scorer, times(1)).scorerId();
    }

    @Test
    void padFlagsAndActionsTravelWithTheSample() {
        DiagnosticsService service =
                new DiagnosticsService(true, new MutableClock(EPOCH), 10, mock(PassiveScoringService.class));

        FrameProcessResult attack = FrameProcessResult.builder()
                .livenessScore(0.11).faceQuality(0.4).padFlag(true)
                .padAttackType("SCREEN_REPLAY").action("reject").build();
        service.recordFrame(attack, 15 * MS);

        FrameSample row = service.snapshot().recentFrames().get(0);
        assertTrue(row.padFlag());
        assertEquals("reject", row.action());
        assertEquals(0.11, row.score(), 1e-9);
    }

    // ------------------------------------------------------------ still no pixels

    @Test
    void theSnapshotSerialisesWithoutAnyPixelShapedPayload() throws Exception {
        MutableClock clock = new MutableClock(EPOCH);
        DiagnosticsService service =
                new DiagnosticsService(true, clock, 10, mock(PassiveScoringService.class));
        service.recordFrame(frame(0.93, "retry_passive"), 17 * MS);

        String json = mapper.writeValueAsString(service.snapshot());

        assertFalse(Pattern.compile("(?i)frameBase64|base64|pixel|bitmap|\"image\"").matcher(json).find(),
                "no frame representation may appear in the payload: " + json);
        // Every string value is a timestamp, an action, or the delegate id —
        // nothing long enough to be encoded frame data.
        var strings = Pattern.compile("\"([^\"]*)\"").matcher(json);
        while (strings.find()) {
            assertTrue(strings.group(1).length() <= 64,
                    "suspiciously long string in diagnostics payload: " + strings.group(1));
        }
        assertTrue(json.length() < 4096, "one frame's snapshot is a small document: " + json.length());
    }

    // ------------------------------------------------------------ helpers

    private static FrameProcessResult frame(double score, String action) {
        return FrameProcessResult.builder()
                .faceDetected(true)
                .livenessScore(score)
                .faceQuality(0.77)
                .padFlag(false)
                .action(action)
                .build();
    }
}
