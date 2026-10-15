package io.mosip.liveness.device;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.LivenessException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process simulated biometric device for development and automated testing
 * (Bonus task #1). Renders a {@link SyntheticScene} into engine-ready frames,
 * supports failure injection, and can additionally expose the scene over a
 * built-in SBI-style MJPEG HTTP endpoint so that {@link SbiStreamDeviceAdapter}
 * can be tested against a realistic networked device service without any
 * hardware.
 *
 * <p>Dev/test only — the embedded server is plain HTTP with no auth.</p>
 */
public final class MockL0L1Device implements DeviceAdapter {

    /** Builder-style configuration; every field has a sane default. */
    public static final class Config {
        private int width = 320;
        private int height = 240;
        private int fps = 15;
        private Frame.Format format = Frame.Format.RGB_888;
        private long jitterMs = 0;
        private boolean failOnOpen = false;
        private int corruptEveryNthFrame = 0;
        private int disconnectAfterFrames = 0;
        private String deviceId = "mock-l0l1-0001";

        public Config width(int v) { width = v; return this; }
        public Config height(int v) { height = v; return this; }
        public Config fps(int v) { fps = v; return this; }
        public Config format(Frame.Format v) { format = v; return this; }
        /** Random extra delay per frame, simulating device jitter. */
        public Config jitterMs(long v) { jitterMs = v; return this; }
        /** open() throws DEVICE_UNAVAILABLE when set — tests the no-device path. */
        public Config failOnOpen() { failOnOpen = true; return this; }
        /** Every Nth emission reports INVALID_FRAME_DATA instead of a frame (0 = never). */
        public Config corruptEveryNthFrame(int n) { corruptEveryNthFrame = n; return this; }
        /** After N good frames the link drops with DEVICE_CONNECTION_FAILURE (0 = never). */
        public Config disconnectAfterFrames(int n) { disconnectAfterFrames = n; return this; }
        public Config deviceId(String v) { deviceId = v; return this; }

        MockL0L1Device buildWith(SyntheticScene scene) { return new MockL0L1Device(scene, this); }
    }

    private static final byte[] BOUNDARY = "mosipframe".getBytes(StandardCharsets.US_ASCII);

    private enum State { NEW, OPEN, CAPTURING, CLOSED }

    private final Config cfg;
    private final SyntheticScene scene;
    private final AtomicLong sequence = new AtomicLong();
    private final Object lifecycleLock = new Object();

    private volatile State state = State.NEW;
    private volatile FrameListener listener;
    private volatile Thread captureThread;
    private volatile HttpServer httpServer;
    private volatile boolean simulatedDisconnect;

    private MockL0L1Device(SyntheticScene scene, Config cfg) {
        this.scene = scene;
        this.cfg = cfg;
    }

    public static MockL0L1Device create(Config config) {
        return config.buildWith(new SyntheticScene(config.width, config.height));
    }

    /** Direct access to the rendered scene for scripting between frames. */
    public SyntheticScene scene() {
        return scene;
    }

    // ------------------------------------------------------------ DeviceAdapter

    @Override public String id() { return cfg.deviceId; }

    @Override
    public DeviceCapabilities capabilities() {
        return new DeviceCapabilities(cfg.deviceId, "MOCK_L1_FACE",
                true, true, Set.of(cfg.format), cfg.fps);
    }

    @Override
    public void open() {
        synchronized (lifecycleLock) {
            if (state == State.CLOSED) {
                throw new LivenessException(LivenessErrorCode.DEVICE_UNAVAILABLE, "device already closed");
            }
            if (cfg.failOnOpen) {
                throw new LivenessException(LivenessErrorCode.DEVICE_UNAVAILABLE,
                        "simulated: no biometric device connected");
            }
            state = State.OPEN;
        }
    }

    @Override
    public void startCapture(FrameListener listener) {
        synchronized (lifecycleLock) {
            if (state != State.OPEN && state != State.CAPTURING) {
                throw new LivenessException(LivenessErrorCode.INVALID_STATE,
                        "open() must succeed before startCapture() (state=" + state + ")");
            }
            this.listener = java.util.Objects.requireNonNull(listener);
            if (state == State.CAPTURING) {
                return;
            }
            state = State.CAPTURING;
        }
        Thread t = new Thread(this::captureLoop, cfg.deviceId + "-capture");
        t.setDaemon(true);
        captureThread = t;
        t.start();
    }

    @Override
    public void stopCapture() {
        synchronized (lifecycleLock) {
            if (state != State.CAPTURING) {
                return;
            }
            state = State.OPEN;
        }
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
        return state == State.CAPTURING && !simulatedDisconnect;
    }

    @Override
    public void close() {
        stopCapture();
        stopServer();
        state = State.CLOSED;
    }

    // ------------------------------------------------------------ capture loop

    private void captureLoop() {
        long intervalNanos = 1_000_000_000L / Math.max(1, cfg.fps);
        long nextDeadline = System.nanoTime();
        long emitted = 0;
        long attempts = 0;

        while (state == State.CAPTURING && !simulatedDisconnect && !Thread.currentThread().isInterrupted()) {
            nextDeadline += intervalNanos;
            attempts++;

            if (cfg.corruptEveryNthFrame > 0 && attempts % cfg.corruptEveryNthFrame == 0) {
                listener.onDeviceError(new LivenessException(LivenessErrorCode.INVALID_FRAME_DATA,
                        "simulated malformed frame packet"));
                sleepUntil(nextDeadline);
                continue;
            }

            long now = System.currentTimeMillis();
            Frame frame = renderFrame((int) sequence.incrementAndGet(), now);
            listener.onFrame(frame);
            emitted++;
            metricsFramesEmitted.incrementAndGet();

            if (cfg.disconnectAfterFrames > 0 && emitted >= cfg.disconnectAfterFrames) {
                simulatedDisconnect = true;
                listener.onDeviceError(new LivenessException(LivenessErrorCode.DEVICE_CONNECTION_FAILURE,
                        "simulated device link dropped after " + emitted + " frames"));
                return;
            }
            sleepUntil(nextDeadline);
            if (cfg.jitterMs > 0) {
                try {
                    Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(cfg.jitterMs + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private Frame renderFrame(int seq, long timestampMillis) {
        BufferedImage img = scene.nextFrame(timestampMillis);
        byte[] payload = switch (cfg.format) {
            case RGB_888 -> PixelFormats.rgb888(img);
            case RGB_GRAY -> PixelFormats.gray8(img);
            case NV21 -> PixelFormats.nv21(img);
            case YUV420 -> PixelFormats.nv21(img); // NV21 is a YUV420 plane variant
        };
        return Frame.of(payload, cfg.width, cfg.height, cfg.format, timestampMillis, seq);
    }

    /** A {@link CaptureSource} view of this device, for burst-capture adapters/tests. */
    public CaptureSource captureSource() {
        return (seq, ts) -> renderFrame(seq, ts);
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

    // ------------------------------------------------------------ embedded SBI-style server

    /**
     * Expose the scene as an SBI-style MJPEG STREAM endpoint
     * ({@code GET /stream} → multipart/x-mixed-replace JPEG) plus a minimal
     * {@code GET /info}. Pass 0 for an ephemeral port.
     *
     * @return the actual bound port
     */
    public int serve(int port) throws IOException {
        synchronized (lifecycleLock) {
            if (httpServer != null) {
                throw new IllegalStateException("embedded server already running");
            }
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/info", this::handleInfo);
            server.createContext("/stream", this::handleStream);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, cfg.deviceId + "-http");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            httpServer = server;
            return server.getAddress().getPort();
        }
    }

    public void stopServer() {
        HttpServer server = httpServer;
        httpServer = null;
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleInfo(HttpExchange ex) throws IOException {
        String json = "{\"deviceId\":\"" + cfg.deviceId + "\",\"deviceSubType\":\"MOCK_L1_FACE\","
                + "\"spec\":[\"STREAM\"],\"maxFps\":" + cfg.fps + "}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private void handleStream(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=mosipframe");
        ex.sendResponseHeaders(200, 0); // chunked / unbounded body
        long intervalNanos = 1_000_000_000L / Math.max(1, cfg.fps);
        long nextDeadline = System.nanoTime();
        try (OutputStream os = ex.getResponseBody()) {
            while (state != State.CLOSED) {
                BufferedImage img = scene.nextFrame(System.currentTimeMillis());
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                if (!ImageIO.write(img, "jpg", jpeg)) {
                    throw new IOException("no JPEG writer available");
                }
                byte[] data = jpeg.toByteArray();
                os.write("--mosipframe\r\n".getBytes(StandardCharsets.US_ASCII));
                os.write(("Content-Type: image/jpeg\r\nContent-Length: " + data.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                os.write(data);
                os.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                os.flush();
                nextDeadline += intervalNanos;
                sleepUntil(nextDeadline);
            }
        } catch (IOException expected) {
            // client disconnected — normal when the consumer stops
        }
    }

    /** Total frames delivered through direct capture mode (diagnostics). */
    public long framesEmitted() {
        return metricsFramesEmitted.get();
    }

    private final AtomicLong metricsFramesEmitted = new AtomicLong();
}
