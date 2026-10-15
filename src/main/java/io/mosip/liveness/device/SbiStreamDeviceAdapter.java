package io.mosip.liveness.device;

import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.LivenessException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Frame source for MOSIP SBI-compatible L1 devices that expose a continuous
 * STREAM endpoint ({@code GET /stream}, multipart/x-mixed-replace MJPEG).
 *
 * <p>The adapter connects over plain HTTP (device services are local to the
 * registration client host per the SBI spec), decodes each JPEG part via
 * {@link ImageIO}, normalizes it into a {@link Frame} in the configured
 * output format, throttles to {@code maxFps}, and hands frames to the
 * listener in capture order. Connection drops are retried with exponential
 * backoff up to {@code maxReconnectAttempts} before the listener receives a
 * terminal error.</p>
 */
public final class SbiStreamDeviceAdapter implements DeviceAdapter {

    /** Tunables; defaults suit a localhost SBI device service at 15 fps. */
    public static final class Options {
        private String streamPath = "/stream";
        private int connectTimeoutMs = 3_000;
        private int readTimeoutMs = 5_000;
        private long reconnectBaseDelayMs = 200;
        private long reconnectMaxDelayMs = 4_000;
        private int maxReconnectAttempts = 5;
        private double maxFps = 15;
        private Frame.Format outputFormat = Frame.Format.RGB_888;
        private String deviceId;

        public Options streamPath(String v) { streamPath = v; return this; }
        public Options connectTimeoutMs(int v) { connectTimeoutMs = v; return this; }
        public Options readTimeoutMs(int v) { readTimeoutMs = v; return this; }
        public Options reconnectBaseDelayMs(long v) { reconnectBaseDelayMs = v; return this; }
        public Options reconnectMaxDelayMs(long v) { reconnectMaxDelayMs = v; return this; }
        public Options maxReconnectAttempts(int v) { maxReconnectAttempts = v; return this; }
        public Options maxFps(double v) { maxFps = v; return this; }
        public Options outputFormat(Frame.Format v) { outputFormat = v; return this; }
        /** Overrides the id reported to audit/metrics (defaults to host:port). */
        public Options deviceId(String v) { deviceId = v; return this; }

        SbiStreamDeviceAdapter buildWith(URI uri) { return new SbiStreamDeviceAdapter(uri, this); }
    }

    private enum State { NEW, OPEN, CAPTURING, CLOSED }

    private final URI baseUri;
    private final Options opts;
    private final AtomicLong sequence = new AtomicLong();

    private volatile State state = State.NEW;
    private volatile FrameListener listener;
    private volatile Thread captureThread;
    private volatile java.io.InputStream activeStream;
    private volatile long lastEmitNanos;

    private SbiStreamDeviceAdapter(URI baseUri, Options opts) {
        if (!"http".equalsIgnoreCase(baseUri.getScheme()) && !"https".equalsIgnoreCase(baseUri.getScheme())) {
            throw new IllegalArgumentException("SBI device service URI must be http(s): " + baseUri);
        }
        this.baseUri = baseUri;
        this.opts = opts;
    }

    public static SbiStreamDeviceAdapter create(URI deviceServiceUri, Options options) {
        return options.buildWith(deviceServiceUri);
    }

    @Override
    public String id() {
        return opts.deviceId != null ? opts.deviceId : "sbi-stream:" + baseUri.getHost() + ":" + port();
    }

    @Override
    public DeviceCapabilities capabilities() {
        return DeviceCapabilities.l1Stream(id(), Set.of(opts.outputFormat), (int) Math.round(opts.maxFps));
    }

    /**
     * Probes TCP reachability of the device service so callers fail fast at
     * open time rather than waiting for the first frame.
     */
    @Override
    public void open() {
        if (state == State.CLOSED) {
            throw new LivenessException(LivenessErrorCode.DEVICE_UNAVAILABLE, "adapter already closed");
        }
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(baseUri.getHost(), port()), opts.connectTimeoutMs);
        } catch (IOException e) {
            throw new LivenessException(LivenessErrorCode.DEVICE_CONNECTION_FAILURE,
                    "cannot reach SBI device service at " + baseUri.getHost() + ":" + port()
                            + " (" + e.getMessage() + ")", e);
        }
        state = State.OPEN;
    }

    @Override
    public void startCapture(FrameListener listener) {
        if (state != State.OPEN && state != State.CAPTURING) {
            throw new LivenessException(LivenessErrorCode.INVALID_STATE,
                    "open() must succeed before startCapture() (state=" + state + ")");
        }
        this.listener = java.util.Objects.requireNonNull(listener);
        if (state == State.CAPTURING) {
            return;
        }
        state = State.CAPTURING;
        Thread t = new Thread(this::captureLoop, id() + "-stream");
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
        closeActiveStream();          // unblocks the blocking read
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
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(opts.connectTimeoutMs))
                .build();
        MjpegDecoder decoder = new MjpegDecoder();
        long minFrameIntervalNanos = (long) (1_000_000_000L / Math.max(0.1, opts.maxFps));
        lastEmitNanos = 0;
        int consecutiveDecodeFailures = 0;
        int attempts = 0;

        while (state == State.CAPTURING) {
            try {
                HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(opts.streamPath))
                        .timeout(Duration.ofMillis(opts.readTimeoutMs))
                        .header("Accept", "multipart/x-mixed-replace")
                        .GET()
                        .build();
                HttpResponse<java.io.InputStream> response =
                        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    throw new java.io.IOException("unexpected HTTP status " + response.statusCode());
                }
                attempts = 0;
                decoder.reset();
                java.io.InputStream stream = new BufferedInputStream(response.body());
                activeStream = stream;

                byte[] chunk = new byte[16 * 1024];
                int n;
                while (state == State.CAPTURING && (n = stream.read(chunk)) >= 0) {
                    List<byte[]> jpegs = decoder.feed(chunk, 0, n);
                    for (byte[] jpeg : jpegs) {
                        long now = System.nanoTime();
                        if (now - lastEmitNanos < minFrameIntervalNanos) {
                            continue; // throttle to maxFps
                        }
                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
                        if (img == null) {
                            if (++consecutiveDecodeFailures >= 10) {
                                consecutiveDecodeFailures = 0;
                                listener.onDeviceError(new LivenessException(
                                        LivenessErrorCode.INVALID_FRAME_DATA,
                                        "repeatedly failed to decode stream JPEG part"));
                            }
                            continue;
                        }
                        consecutiveDecodeFailures = 0;
                        emit(img, now);
                    }
                }
                if (state == State.CAPTURING) {
                    throw new java.io.IOException("stream ended unexpectedly");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // stopCapture path
            } catch (Exception e) {
                closeActiveStream();
                if (state != State.CAPTURING) {
                    return; // deliberate stop, not an error
                }
                attempts++;
                if (attempts > opts.maxReconnectAttempts) {
                    listener.onDeviceError(new LivenessException(
                            LivenessErrorCode.DEVICE_CONNECTION_FAILURE,
                            "stream connection lost after " + opts.maxReconnectAttempts + " reconnect attempts: "
                                    + e.getMessage(), e));
                    state = State.OPEN;
                    return;
                }
                sleepQuietly(backoffDelay(attempts));
            } finally {
                closeActiveStream();
            }
        }
    }

    private void emit(BufferedImage img, long nowNanos) {
        byte[] payload = switch (opts.outputFormat) {
            case RGB_888 -> PixelFormats.rgb888(img);
            case RGB_GRAY -> PixelFormats.gray8(img);
            case NV21 -> PixelFormats.nv21(img);
            case YUV420 -> PixelFormats.nv21(img);
        };
        Frame frame = Frame.of(payload, img.getWidth(), img.getHeight(),
                opts.outputFormat, System.currentTimeMillis(), (int) sequence.incrementAndGet());
        listener.onFrame(frame);
        lastEmitNanos = nowNanos;
    }

    private long backoffDelay(int attempt) {
        double delay = opts.reconnectBaseDelayMs * Math.pow(2, attempt - 1);
        return Math.min((long) delay, opts.reconnectMaxDelayMs);
    }

    private void closeActiveStream() {
        java.io.InputStream s = activeStream;
        activeStream = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // best-effort unblock of the read loop
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int port() {
        if (baseUri.getPort() != -1) {
            return baseUri.getPort();
        }
        return "https".equalsIgnoreCase(baseUri.getScheme()) ? 443 : 80;
    }
}
