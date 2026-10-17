package io.mosip.liveness.device;

import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.LivenessException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the device adapter layer: MockL0L1Device, BurstRCaptureDeviceAdapter,
 * SbiStreamDeviceAdapter, MjpegDecoder, PixelFormats, and SyntheticScene.
 */
class DeviceAdapterTest {

    private final List<DeviceAdapter> adapters = new ArrayList<>();

    @AfterEach
    void tearDown() {
        adapters.forEach(a -> {
            try { a.close(); } catch (Exception ignored) {}
        });
    }

    private <T extends DeviceAdapter> T track(T adapter) {
        adapters.add(adapter);
        return adapter;
    }

    // ============================================================ MockL0L1Device

    @Test
    @Timeout(10)
    void mockDeviceLifecycleAndFrameDelivery() throws Exception {
        MockL0L1Device device = track(MockL0L1Device.create(
                new MockL0L1Device.Config().width(160).height(120).fps(30)));

        device.open();
        assertFalse(device.isCapturing()); // not yet capturing until startCapture

        CountDownLatch latch = new CountDownLatch(5);
        CopyOnWriteArrayList<Frame> frames = new CopyOnWriteArrayList<>();

        device.startCapture(new FrameListener() {
            @Override
            public void onFrame(Frame frame) {
                frames.add(frame);
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        device.stopCapture();
        assertFalse(device.isCapturing());
        // Capture runs until stopCapture(), so a frame delivered between the
        // latch opening and the stop can race in — assert the guaranteed
        // lower bound, not an exact count (exact counts flaked under load).
        assertTrue(frames.size() >= 5,
                "latch guarantees at least 5 frames, got " + frames.size());

        // Frame metadata validation
        Frame first = frames.get(0);
        assertEquals(160, first.width());
        assertEquals(120, first.height());
        assertEquals(Frame.Format.RGB_888, first.format());
        assertTrue(first.sequenceNumber() > 0);
        assertTrue(first.timestampMillis() > 0);
    }

    @Test
    @Timeout(10)
    void mockDeviceCapabilities() {
        MockL0L1Device device = track(MockL0L1Device.create(
                new MockL0L1Device.Config().deviceId("test-dev").fps(20)));

        DeviceCapabilities caps = device.capabilities();
        assertEquals("test-dev", caps.deviceId());
        assertTrue(caps.isL1());
        assertTrue(caps.streamingSupported());
        assertTrue(caps.burstCaptureSupported());
        assertEquals(20, caps.maxFps());
        assertTrue(caps.supportedFormats().contains(Frame.Format.RGB_888));
    }

    @Test
    @Timeout(10)
    void mockDeviceFailOnOpen() {
        MockL0L1Device device = track(MockL0L1Device.create(
                new MockL0L1Device.Config().failOnOpen()));

        LivenessException ex = assertThrows(LivenessException.class, device::open);
        assertEquals(LivenessErrorCode.DEVICE_UNAVAILABLE, ex.errorCode());
    }

    @Test
    @Timeout(10)
    void mockDeviceCorruptFrames() throws Exception {
        MockL0L1Device device = track(MockL0L1Device.create(
                new MockL0L1Device.Config().corruptEveryNthFrame(2)));

        device.open();

        CountDownLatch frameLatch = new CountDownLatch(3);
        CountDownLatch errorLatch = new CountDownLatch(2);
        List<LivenessException> errors = new CopyOnWriteArrayList<>();

        device.startCapture(new FrameListener() {
            @Override
            public void onFrame(Frame frame) {
                frameLatch.countDown();
            }

            @Override
            public void onDeviceError(LivenessException error) {
                errors.add(error);
                errorLatch.countDown();
            }
        });

        assertTrue(frameLatch.await(5, TimeUnit.SECONDS));
        assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
        device.stopCapture();
        // corruptEveryNthFrame keeps firing while capture runs, so more errors
        // can land between the latches opening and stopCapture() — the latch
        // guarantees at least two, and the first is always the first corrupt
        // frame (an exact count here flaked under load).
        assertTrue(errors.size() >= 2,
                "latch guarantees at least 2 errors, got " + errors.size());
        assertEquals(LivenessErrorCode.INVALID_FRAME_DATA, errors.get(0).errorCode());
    }

    @Test
    @Timeout(10)
    void mockDeviceDisconnectAfterFrames() throws Exception {
        MockL0L1Device device = track(MockL0L1Device.create(
                new MockL0L1Device.Config().disconnectAfterFrames(3)));

        device.open();

        CountDownLatch errorLatch = new CountDownLatch(1);
        List<LivenessException> errors = new CopyOnWriteArrayList<>();

        device.startCapture(new FrameListener() {
            @Override
            public void onFrame(Frame frame) { }

            @Override
            public void onDeviceError(LivenessException error) {
                errors.add(error);
                errorLatch.countDown();
            }
        });

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
        device.stopCapture();
        assertFalse(device.isCapturing());
        assertEquals(LivenessErrorCode.DEVICE_CONNECTION_FAILURE, errors.get(0).errorCode());
    }

    @Test
    @Timeout(10)
    void mockDeviceNV21Format() throws Exception {
        MockL0L1Device device = track(MockL0L1Device.create(
                new MockL0L1Device.Config().width(160).height(120).format(Frame.Format.NV21)));

        device.open();

        CountDownLatch latch = new CountDownLatch(2);
        CopyOnWriteArrayList<Frame> frames = new CopyOnWriteArrayList<>();

        device.startCapture(new FrameListener() {
            @Override
            public void onFrame(Frame frame) {
                frames.add(frame);
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        device.stopCapture();

        Frame f = frames.get(0);
        assertEquals(Frame.Format.NV21, f.format());
        assertEquals(160 * 120 * 3 / 2, f.data().length);
    }

    @Test
    @Timeout(10)
    void mockDeviceStartCaptureRequiresOpen() {
        MockL0L1Device device = track(MockL0L1Device.create(new MockL0L1Device.Config()));

        LivenessException ex = assertThrows(LivenessException.class,
                () -> device.startCapture(frame -> {}));
        assertEquals(LivenessErrorCode.INVALID_STATE, ex.errorCode());
    }

    @Test
    @Timeout(10)
    void mockDeviceCaptureSource() throws Exception {
        MockL0L1Device device = track(MockL0L1Device.create(
                new MockL0L1Device.Config().width(80).height(60)));

        Frame frame = device.captureSource().capture(1, System.currentTimeMillis());
        assertEquals(80, frame.width());
        assertEquals(60, frame.height());
    }

    // ============================================================ BurstRCaptureDeviceAdapter

    @Test
    @Timeout(15)
    void burstAdapterCapturesFrames() throws Exception {
        MockL0L1Device mockDevice = new MockL0L1Device.Config()
                .width(160).height(120).buildWith(
                        new SyntheticScene(160, 120));
        BurstRCaptureDeviceAdapter adapter = track(BurstRCaptureDeviceAdapter.create(
                mockDevice.captureSource(),
                new BurstRCaptureDeviceAdapter.Options()
                        .targetFps(20)
                        .deviceId("burst-test")));

        adapter.open();
        assertFalse(adapter.isCapturing());

        CountDownLatch latch = new CountDownLatch(5);
        CopyOnWriteArrayList<Frame> frames = new CopyOnWriteArrayList<>();

        adapter.startCapture(new FrameListener() {
            @Override
            public void onFrame(Frame frame) {
                frames.add(frame);
                latch.countDown();
            }
        });

        assertTrue(adapter.isCapturing());
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        adapter.stopCapture();
        assertFalse(adapter.isCapturing());
        assertTrue(frames.size() >= 5);

        // Validate frame dimensions
        for (Frame f : frames) {
            assertEquals(160, f.width());
            assertEquals(120, f.height());
        }
    }

    @Test
    @Timeout(15)
    void burstAdapterCapabilities() {
        BurstRCaptureDeviceAdapter adapter = track(BurstRCaptureDeviceAdapter.create(
                (seq, ts) -> null,
                new BurstRCaptureDeviceAdapter.Options()
                        .targetFps(12)
                        .deviceId("cap-test")
                        .outputFormat(Frame.Format.RGB_GRAY)));

        DeviceCapabilities caps = adapter.capabilities();
        assertEquals("cap-test", caps.deviceId());
        assertFalse(caps.isL1());
        assertTrue(caps.burstCaptureSupported());
        assertEquals(12, caps.maxFps());
        assertEquals(Set.of(Frame.Format.RGB_GRAY), caps.supportedFormats());
    }

    @Test
    @Timeout(10)
    void burstAdapterStartRequiresOpen() {
        BurstRCaptureDeviceAdapter adapter = track(BurstRCaptureDeviceAdapter.create(
                (seq, ts) -> null));

        LivenessException ex = assertThrows(LivenessException.class,
                () -> adapter.startCapture(frame -> {}));
        assertEquals(LivenessErrorCode.INVALID_STATE, ex.errorCode());
    }

    @Test
    @Timeout(10)
    void burstAdapterCloseAfterClose() {
        BurstRCaptureDeviceAdapter adapter = track(BurstRCaptureDeviceAdapter.create(
                (seq, ts) -> null));
        adapter.open();
        adapter.close();
        // Second close is safe
        adapter.close();
    }

    @Test
    @Timeout(15)
    void burstAdapterReportsCaptureFailures() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        CaptureSource failingSource = (seq, ts) -> {
            callCount.incrementAndGet();
            throw new LivenessException(LivenessErrorCode.INVALID_FRAME_DATA,
                    "simulated capture failure #" + callCount.get());
        };

        BurstRCaptureDeviceAdapter adapter = track(BurstRCaptureDeviceAdapter.create(
                failingSource,
                new BurstRCaptureDeviceAdapter.Options()
                        .targetFps(50) // fast to trigger failures quickly
                        .maxConsecutiveFailures(3)));

        adapter.open();

        CountDownLatch errorLatch = new CountDownLatch(4); // 3 failures + 1 terminal
        List<LivenessException> errors = new CopyOnWriteArrayList<>();

        adapter.startCapture(new FrameListener() {
            @Override
            public void onFrame(Frame frame) { }

            @Override
            public void onDeviceError(LivenessException error) {
                errors.add(error);
                errorLatch.countDown();
            }
        });

        assertTrue(errorLatch.await(10, TimeUnit.SECONDS));
        adapter.stopCapture();

        // 3 individual failures + 1 terminal DEVICE_CONNECTION_FAILURE
        assertEquals(4, errors.size());
        assertEquals(LivenessErrorCode.INVALID_FRAME_DATA, errors.get(0).errorCode());
        assertEquals(LivenessErrorCode.DEVICE_CONNECTION_FAILURE,
                errors.get(errors.size() - 1).errorCode());
    }

    @Test
    @Timeout(10)
    void burstAdapterClosedThrowsOnOpen() {
        BurstRCaptureDeviceAdapter adapter = track(BurstRCaptureDeviceAdapter.create(
                (seq, ts) -> null));
        adapter.close();

        LivenessException ex = assertThrows(LivenessException.class, adapter::open);
        assertEquals(LivenessErrorCode.DEVICE_UNAVAILABLE, ex.errorCode());
    }

    // ============================================================ SbiStreamDeviceAdapter

    @Test
    @Timeout(15)
    void sbiStreamAdapterReadsFromMockDevice() throws Exception {
        MockL0L1Device mockDevice = new MockL0L1Device.Config()
                .width(320).height(240).fps(30).format(Frame.Format.RGB_888)
                .buildWith(new SyntheticScene(320, 240));
        adapters.add(mockDevice);

        int port = mockDevice.serve(0);
        SbiStreamDeviceAdapter adapter = track(SbiStreamDeviceAdapter.create(
                URI.create("http://127.0.0.1:" + port),
                new SbiStreamDeviceAdapter.Options()
                        .maxFps(30)
                        .outputFormat(Frame.Format.RGB_888)
                        .deviceId("sbi-mock-test")));

        adapter.open();
        assertEquals("sbi-mock-test", adapter.id());
        assertTrue(adapter.capabilities().isL1());

        CountDownLatch latch = new CountDownLatch(3);
        CopyOnWriteArrayList<Frame> frames = new CopyOnWriteArrayList<>();

        adapter.startCapture(new FrameListener() {
            @Override
            public void onFrame(Frame frame) {
                frames.add(frame);
                latch.countDown();
            }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        adapter.stopCapture();
        assertTrue(frames.size() >= 3);

        Frame f = frames.get(0);
        assertEquals(320, f.width());
        assertEquals(240, f.height());
        assertEquals(Frame.Format.RGB_888, f.format());
    }

    @Test
    @Timeout(10)
    void sbiStreamAdapterOpenFailsForUnreachableHost() {
        SbiStreamDeviceAdapter adapter = track(SbiStreamDeviceAdapter.create(
                URI.create("http://127.0.0.1:1"),  // unlikely to be in use
                new SbiStreamDeviceAdapter.Options()
                        .connectTimeoutMs(500)
                        .deviceId("sbi-fail-test")));

        LivenessException ex = assertThrows(LivenessException.class, adapter::open);
        assertEquals(LivenessErrorCode.DEVICE_CONNECTION_FAILURE, ex.errorCode());
    }

    @Test
    @Timeout(10)
    void sbiStreamAdapterInvalidScheme() {
        assertThrows(IllegalArgumentException.class, () ->
                SbiStreamDeviceAdapter.create(
                        URI.create("ftp://device.local"),
                        new SbiStreamDeviceAdapter.Options()));
    }

    // ============================================================ MjpegDecoder

    @Test
    void mjpegDecoderExtractsSingleImage() {
        MjpegDecoder decoder = new MjpegDecoder();
        byte[] jpeg = createMinimalJpeg();
        List<byte[]> result = decoder.feed(jpeg);
        assertEquals(1, result.size());
        assertArrayEquals(jpeg, result.get(0));
    }

    @Test
    void mjpegDecoderHandlesChunkedData() {
        MjpegDecoder decoder = new MjpegDecoder();
        byte[] jpeg = createMinimalJpeg();

        // Split across two feeds
        int mid = jpeg.length / 2;
        List<byte[]> r1 = decoder.feed(jpeg, 0, mid);
        assertTrue(r1.isEmpty()); // incomplete
        List<byte[]> r2 = decoder.feed(jpeg, mid, jpeg.length - mid);
        assertEquals(1, r2.size());
        assertArrayEquals(jpeg, r2.get(0));
    }

    @Test
    void mjpegDecoderMultipleImages() {
        MjpegDecoder decoder = new MjpegDecoder();
        byte[] img1 = createMinimalJpeg();
        byte[] img2 = createMinimalJpeg();
        byte[] combined = new byte[img1.length + img2.length];
        System.arraycopy(img1, 0, combined, 0, img1.length);
        System.arraycopy(img2, 0, combined, img1.length, img2.length);

        List<byte[]> result = decoder.feed(combined);
        assertEquals(2, result.size());
    }

    @Test
    void mjpegDecoderResetClearsBuffer() {
        MjpegDecoder decoder = new MjpegDecoder();
        byte[] jpeg = createMinimalJpeg();
        decoder.feed(jpeg, 0, jpeg.length / 2);
        assertTrue(decoder.bufferedBytes() > 0);
        decoder.reset();
        assertEquals(0, decoder.bufferedBytes());
    }

    @Test
    void mjpegDecoderRejectsBadBounds() {
        MjpegDecoder decoder = new MjpegDecoder();
        assertThrows(IndexOutOfBoundsException.class,
                () -> decoder.feed(new byte[10], 0, 20));
    }

    /** Create a minimal valid JPEG (SOI + EOI = 4 bytes). */
    private static byte[] createMinimalJpeg() {
        return new byte[] {
                (byte) 0xFF, (byte) 0xD8,  // SOI
                (byte) 0xFF, (byte) 0xD9   // EOI
        };
    }

    // ============================================================ PixelFormats

    @Test
    void pixelFormatsRgb888() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0xFF0000); // red
        img.setRGB(1, 0, 0x00FF00); // green
        img.setRGB(0, 1, 0x0000FF); // blue
        img.setRGB(1, 1, 0xFFFFFF); // white

        byte[] data = PixelFormats.rgb888(img);
        assertEquals(2 * 2 * 3, data.length);
        // First pixel (0,0) = red: R=0xFF, G=0x00, B=0x00
        assertEquals((byte) 0xFF, data[0]);
        assertEquals(0, data[1]);
        assertEquals(0, data[2]);
    }

    @Test
    void pixelFormatsGray8() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                3, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0x808080); // gray
        img.setRGB(1, 0, 0xFFFFFF);  // white
        img.setRGB(2, 0, 0x000000);  // black

        byte[] data = PixelFormats.gray8(img);
        assertEquals(3, data.length);
    }

    @Test
    void pixelFormatsNV21() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0x808080);

        byte[] data = PixelFormats.nv21(img);
        assertEquals(4 * 4 + 2 * 2 * 2, data.length);
    }

    @Test
    void pixelFormatsBytesFor() {
        assertEquals(100, PixelFormats.bytesFor(Frame.Format.RGB_GRAY, 10, 10));
        assertEquals(300, PixelFormats.bytesFor(Frame.Format.RGB_888, 10, 10));
        assertEquals(150, PixelFormats.bytesFor(Frame.Format.NV21, 10, 10));
        assertEquals(150, PixelFormats.bytesFor(Frame.Format.YUV420, 10, 10));
    }

    // ============================================================ SyntheticScene

    @Test
    void syntheticSceneProducesImages() {
        SyntheticScene scene = new SyntheticScene(200, 150);
        java.awt.image.BufferedImage f1 = scene.nextFrame(1000);
        java.awt.image.BufferedImage f2 = scene.nextFrame(2000);
        assertEquals(200, f1.getWidth());
        assertEquals(150, f1.getHeight());
        assertNotEquals(f1, f2); // different timestamps → different frames
    }

    @Test
    void syntheticSceneScripting() throws Exception {
        SyntheticScene scene = new SyntheticScene(100, 100);
        scene.setFacePresent(false);
        java.awt.image.BufferedImage noFace = scene.nextFrame(0);

        scene.setFacePresent(true);
        java.awt.image.BufferedImage withFace = scene.nextFrame(0);

        // Both produce valid images of correct size
        assertEquals(100, noFace.getWidth());
        assertEquals(100, withFace.getWidth());
    }

    @Test
    void syntheticSceneInvalidSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new SyntheticScene(0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new SyntheticScene(100, -1));
    }

    // ============================================================ DeviceCapabilities

    @Test
    void deviceCapabilitiesFactories() {
        DeviceCapabilities l1 = DeviceCapabilities.l1Stream("dev1", Set.of(Frame.Format.RGB_888), 15);
        assertTrue(l1.isL1());
        assertEquals("L1_FACE", l1.deviceSubType());
        assertEquals(15, l1.maxFps());

        DeviceCapabilities l0 = DeviceCapabilities.l0Burst("dev2", Set.of(Frame.Format.NV21), 30);
        assertFalse(l0.isL1());
        assertEquals("L0_FACE", l0.deviceSubType());
        assertEquals(30, l0.maxFps());
    }

    @Test
    void deviceCapabilitiesBlankIdRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceCapabilities("", "MOCK", true, true, Set.of(), 15));
    }

    @Test
    @Timeout(10)
    void mockL0ProfileCapabilitiesAndHttpInfo() throws Exception {
        MockL0L1Device mockL0 = track(MockL0L1Device.create(
                new MockL0L1Device.Config().l0().deviceId("mock-l0-device").width(160).height(120).fps(15)));

        assertTrue(mockL0.capabilities().isL0());
        assertFalse(mockL0.capabilities().isL1());
        assertFalse(mockL0.capabilities().streamingSupported());
        assertEquals("MOCK_L0_FACE", mockL0.capabilities().deviceSubType());

        int port = mockL0.serve(0);
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/info"))
                .GET().build();
        java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"deviceSubType\":\"MOCK_L0_FACE\""));
        assertTrue(resp.body().contains("\"spec\":[\"BURST\"]"));

        // Burst adapter can capture from this L0 device captureSource
        BurstRCaptureDeviceAdapter burstAdapter = track(BurstRCaptureDeviceAdapter.create(
                mockL0.captureSource(),
                new BurstRCaptureDeviceAdapter.Options().deviceId("burst-from-l0").targetFps(15)));
        burstAdapter.open();
        CountDownLatch latch = new CountDownLatch(2);
        burstAdapter.startCapture(f -> latch.countDown());
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        burstAdapter.stopCapture();
    }

    @Test
    @Timeout(10)
    void httpFailureInjectionOverTheWire() throws Exception {
        MockL0L1Device mockDevice = track(MockL0L1Device.create(
                new MockL0L1Device.Config().deviceId("mock-wire-fail").width(160).height(120)));
        int port = mockDevice.serve(0);

        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();

        // Dynamically inject failure parameters over /config endpoint
        java.net.http.HttpRequest cfgReq = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/config?corruptEveryNthFrame=2&disconnectAfterFrames=5&networkLatencyMs=10"))
                .GET().build();
        java.net.http.HttpResponse<String> cfgResp = client.send(cfgReq, java.net.http.HttpResponse.BodyHandlers.ofString());

        assertEquals(200, cfgResp.statusCode());
        assertTrue(cfgResp.body().contains("\"corruptEveryNthFrame\":2"));
        assertTrue(cfgResp.body().contains("\"disconnectAfterFrames\":5"));
        assertTrue(cfgResp.body().contains("\"networkLatencyMs\":10"));
    }
}
