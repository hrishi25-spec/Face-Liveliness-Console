package io.mosip.liveness.android;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;

import io.mosip.liveness.core.Frame;

/**
 * Real stream-rate meter for the frame-source layer.
 *
 * <p>Adapters advertise a configured ceiling ({@code maxFps} in
 * {@link FaceFrameSource.SourceCapabilities}), but that is a configuration
 * value, not evidence: a vendor stream configured for 15 fps can deliver 2.
 * The meter measures what actually arrives — a sliding window of delivery
 * timestamps taken at the SPI boundary — so callers can adapt to the observed
 * rate instead of the promised one (SBI spec's 3 fps stream floor;
 * {@code resource-compliance.md} §06 "3 fps minimum → adaptive fallback").</p>
 *
 * <p>Threading: {@link #record()} runs on the adapter's capture thread (via
 * {@link #wrap}), {@link #fps()} on the consumer's thread — all state is
 * synchronized and the window is bounded, so neither side can grow memory.</p>
 */
public final class FrameRateMeter {

    /** The rate is computed over the last this-many milliseconds of deliveries. */
    static final long WINDOW_MS = 5_000;

    private final Clock clock;
    private final Deque<Long> deliveries = new ArrayDeque<>();

    public FrameRateMeter(Clock clock) {
        this.clock = clock;
    }

    /** Record one frame delivery at the current clock time. */
    public synchronized void record() {
        long now = clock.millis();
        deliveries.addLast(now);
        long cutoff = now - WINDOW_MS;
        while (!deliveries.isEmpty() && deliveries.peekFirst() < cutoff) {
            deliveries.removeFirst();
        }
    }

    /**
     * Measured frames per second over the window: {@code NaN} until two frames
     * have been observed (not yet measurable), {@link Double#POSITIVE_INFINITY}
     * for a same-instant burst (unambiguously fast), otherwise frames/second
     * across the observed span.
     */
    public synchronized double fps() {
        if (deliveries.size() < 2) {
            return Double.NaN;
        }
        long span = deliveries.peekLast() - deliveries.peekFirst();
        if (span <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return (deliveries.size() - 1) * 1000.0 / span;
    }

    /**
     * Decorate a source listener so every frame the source delivers is
     * measured at the frame-source boundary before it reaches the consumer;
     * errors pass through untouched.
     */
    public FaceFrameSource.Listener wrap(FaceFrameSource.Listener delegate) {
        return new FaceFrameSource.Listener() {
            @Override
            public void onFrame(Frame frame) {
                record();
                delegate.onFrame(frame);
            }

            @Override
            public void onSourceError(LivenessDeviceError error, String message) {
                delegate.onSourceError(error, message);
            }
        };
    }
}
