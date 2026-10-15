package io.mosip.liveness.android;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.mosip.liveness.core.Frame;
import io.mosip.liveness.testing.MutableClock;

/** Rate math, windowing and listener decoration of {@link FrameRateMeter}. */
class FrameRateMeterTest {

    @Test
    void fewerThanTwoFramesIsUnknown() {
        MutableClock clock = new MutableClock(0);
        FrameRateMeter meter = new FrameRateMeter(clock);
        assertTrue(Double.isNaN(meter.fps()), "no data must read as unknown");
        meter.record();
        assertTrue(Double.isNaN(meter.fps()), "a single frame cannot yield a rate");
    }

    @Test
    void steadyFourFpsIsMeasured() {
        MutableClock clock = new MutableClock(0);
        FrameRateMeter meter = new FrameRateMeter(clock);
        for (int i = 0; i < 10; i++) {
            meter.record();
            clock.advanceMillis(250);
        }
        assertEquals(4.0, meter.fps(), 0.01, "250 ms between frames is 4 fps");
    }

    @Test
    void steadyTenFpsIsMeasured() {
        MutableClock clock = new MutableClock(0);
        FrameRateMeter meter = new FrameRateMeter(clock);
        for (int i = 0; i < 20; i++) {
            meter.record();
            clock.advanceMillis(100);
        }
        assertEquals(10.0, meter.fps(), 0.01);
    }

    @Test
    void sameInstantBurstIsClearlyFast() {
        MutableClock clock = new MutableClock(0);
        FrameRateMeter meter = new FrameRateMeter(clock);
        meter.record();
        meter.record();
        meter.record();
        assertEquals(Double.POSITIVE_INFINITY, meter.fps(),
                "a burst with no elapsed time is unambiguously fast, not unknown");
    }

    @Test
    void windowReflectsRecentRateOnly() {
        MutableClock clock = new MutableClock(0);
        FrameRateMeter meter = new FrameRateMeter(clock);
        // A slow minute at 1 fps...
        for (int i = 0; i < 8; i++) {
            meter.record();
            clock.advanceMillis(1_000);
        }
        // ...then the stream recovers to 4 fps for longer than the window.
        for (int i = 0; i < 30; i++) {
            meter.record();
            clock.advanceMillis(250);
        }
        assertEquals(4.0, meter.fps(), 0.05,
                "pruned window must report the recent 4 fps, not the stale 1 fps");
    }

    @Test
    void wrapMeasuresDeliveredFramesAndPassesErrorsThrough() {
        MutableClock clock = new MutableClock(0);
        FrameRateMeter meter = new FrameRateMeter(clock);
        List<Frame> seen = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        FaceFrameSource.Listener wrapped = meter.wrap(new FaceFrameSource.Listener() {
            @Override
            public void onFrame(Frame frame) {
                seen.add(frame);
            }

            @Override
            public void onSourceError(LivenessDeviceError error, String message) {
                errors.add(error + ":" + message);
            }
        });

        for (int i = 0; i < 4; i++) {
            wrapped.onFrame(new Frame(new byte[]{1, 2, 3, 4}, 2, 2, Frame.Format.RGB_888, i, i));
            clock.advanceMillis(250);
        }
        assertEquals(4, seen.size(), "every delivered frame must reach the delegate");
        assertEquals(4.0, meter.fps(), 0.01, "delivery must have been measured");

        wrapped.onSourceError(LivenessDeviceError.DISCONNECTED, "cable pulled");
        assertEquals(List.of("DISCONNECTED:cable pulled"), errors,
                "errors must pass through without fabricating frames");
        assertEquals(4, seen.size());
    }
}
