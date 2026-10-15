package io.mosip.liveness.device;

import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.LivenessException;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link DeviceAdapter} for SBI L0 face devices that only expose discrete
 * capture requests (rCapture) rather than a continuous {@code /stream} feed.
 *
 * <p>This adapter polls a {@link CaptureSource} — typically an HTTP rCapture
 * endpoint — at a configured target frame rate, normalizes each captured
 * {@link Frame}, and delivers it to the {@link FrameListener}. It implements
 * the same lifecycle as {@link SbiStreamDeviceAdapter}:
 * {@code open() → startCapture(listener) → stopCapture() → close()}.</p>
 *
 * <p>Use this when {@link DeviceCapabilities#isL1()} is {@code false}. For L1
 * devices with a continuous STREAM endpoint, prefer {@link SbiStreamDeviceAdapter}.</p>
 */
public final class BurstRCaptureDeviceAdapter implements DeviceAdapter {

    /** Tunables; defaults target 10 fps on an L0 device. */
    public static final class Options {
        private double targetFps = 10;
        private Frame.Format outputFormat = Frame.Format.RGB_888;
        private String deviceId = "burst-rcapture-0001";
        private int maxConsecutiveFailures = 3;

        public Options targetFps(double v) { targetFps = v; return this; }
        public Options outputFormat(Frame.Format v) { outputFormat = v; return this; }
        public Options deviceId(String v) { deviceId = v; return this; }
        /**
         * After this many consecutive capture failures the adapter reports a
         * terminal error and stops. Set to 0 for unlimited retries.
         */
        public Options maxConsecutiveFailures(int v) { maxConsecutiveFailures = v; return this; }
    }

    private enum State { NEW, OPEN, CAPTURING, CLOSED }

    private final CaptureSource source;
    private final Options opts;
    private final AtomicLong sequence = new AtomicLong();

    private volatile State state = State.NEW;
    private volatile FrameListener listener;
    private volatile Thread captureThread;

    BurstRCaptureDeviceAdapter(CaptureSource source, Options opts) {
        this.source = Objects.requireNonNull(source, "captureSource");
        this.opts = opts;
    }

    /**
     * Create an adapter that polls the given capture source.
     *
     * @param source  the discrete capture function (wraps rCapture HTTP, vendor SDK grab, etc.)
     * @param opts    configuration (fps, format, device id)
     */
    public static BurstRCaptureDeviceAdapter create(CaptureSource source, Options opts) {
        return new BurstRCaptureDeviceAdapter(source, opts);
    }

    /** Convenience overload with default options. */
    public static BurstRCaptureDeviceAdapter create(CaptureSource source) {
        return new BurstRCaptureDeviceAdapter(source, new Options());
    }

    @Override
    public String id() {
        return opts.deviceId;
    }

    @Override
    public DeviceCapabilities capabilities() {
        return DeviceCapabilities.l0Burst(
                opts.deviceId,
                Set.of(opts.outputFormat),
                (int) Math.round(opts.targetFps));
    }

    @Override
    public void open() {
        if (state == State.CLOSED) {
            throw new LivenessException(LivenessErrorCode.DEVICE_UNAVAILABLE,
                    "adapter already closed");
        }
        state = State.OPEN;
    }

    @Override
    public void startCapture(FrameListener listener) {
        if (state != State.OPEN && state != State.CAPTURING) {
            throw new LivenessException(LivenessErrorCode.INVALID_STATE,
                    "open() must succeed before startCapture() (state=" + state + ")");
        }
        this.listener = Objects.requireNonNull(listener, "listener");
        if (state == State.CAPTURING) {
            return;
        }
        state = State.CAPTURING;
        Thread t = new Thread(this::captureLoop, opts.deviceId + "-burst");
        t.setDaemon(true);
        captureThread = t;
        t.start();
    }

    @Override
    public void stopCapture() {
        if (state != State.CAPTURING) {
            return;
        }
        state = State.OPEN;
        Thread t = captureThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        captureThread = null;
    }

    @Override
    public boolean isCapturing() {
        return state == State.CAPTURING;
    }

    @Override
    public void close() {
        stopCapture();
        state = State.CLOSED;
    }

    // ------------------------------------------------------------ capture loop

    private void captureLoop() {
        long intervalNanos = (long) (1_000_000_000L / Math.max(0.1, opts.targetFps));
        long nextDeadline = System.nanoTime();
        int consecutiveFailures = 0;

        while (state == State.CAPTURING && !Thread.currentThread().isInterrupted()) {
            nextDeadline += intervalNanos;
            try {
                long now = System.currentTimeMillis();
                int seq = (int) sequence.incrementAndGet();
                Frame frame = source.capture(seq, now);
                consecutiveFailures = 0;
                listener.onFrame(frame);
            } catch (LivenessException e) {
                consecutiveFailures++;
                listener.onDeviceError(e);
                if (opts.maxConsecutiveFailures > 0
                        && consecutiveFailures >= opts.maxConsecutiveFailures) {
                    listener.onDeviceError(new LivenessException(
                            LivenessErrorCode.DEVICE_CONNECTION_FAILURE,
                            "device failed " + consecutiveFailures + " consecutive captures; giving up",
                            e));
                    state = State.OPEN;
                    return;
                }
            }
            sleepUntil(nextDeadline);
        }
    }

    private static void sleepUntil(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        try {
            Thread.sleep(remaining / 1_000_000, (int) (remaining % 1_000_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Total frames successfully captured (diagnostics). */
    public long framesCaptured() {
        return sequence.get();
    }
}
