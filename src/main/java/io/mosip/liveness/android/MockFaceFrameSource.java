package io.mosip.liveness.android;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import io.mosip.liveness.core.Frame;

/**
 * Deterministic frame source for tests, CI and dev builds (spec §11
 * {@code MockFaceFrameSource(video)}, §15 "Two frame-source adapters (mock +
 * CameraX) pass the same suite"). Frames are preloaded
 * {@link io.mosip.liveness.core.Frame}s delivered one per {@link #tick()} call,
 * so a test fully controls the timing of the state machine.
 */
public final class MockFaceFrameSource implements FaceFrameSource {

    private final Deque<Frame> frames = new ArrayDeque<>();
    private final SourceCapabilities capabilities =
            new SourceCapabilities("mock-face-source", true, false, 15, Set.of(Frame.Format.RGB_888));
    private Listener listener;
    private boolean open;

    public MockFaceFrameSource(Frame... frames) {
        for (Frame f : frames) {
            this.frames.addLast(f);
        }
    }

    public MockFaceFrameSource(List<Frame> frames) {
        frames.forEach(this.frames::addLast);
    }

    /** Queue more frames (a test can extend the session mid-flight). */
    public void enqueue(Frame frame) {
        frames.addLast(frame);
    }

    /** Deliver the next queued frame to the listener, if any. Returns true when delivered. */
    public boolean tick() {
        if (listener == null || frames.isEmpty()) {
            return false;
        }
        listener.onFrame(frames.pollFirst());
        return true;
    }

    /** Simulate a device failure. */
    public void fail(LivenessDeviceError error, String message) {
        if (listener != null) {
            listener.onSourceError(error, message);
        }
    }

    @Override
    public SourceCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public void open(SourceConfig config) {
        open = true;
    }

    @Override
    public void start(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void stop() {
        this.listener = null;
    }

    @Override
    public void close() {
        frames.clear();
        open = false;
    }

    public boolean isOpen() {
        return open;
    }
}
