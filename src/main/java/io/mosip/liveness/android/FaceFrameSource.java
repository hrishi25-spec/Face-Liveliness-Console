package io.mosip.liveness.android;

import java.util.Set;

import io.mosip.liveness.core.Frame;

/**
 * Frame-source SPI (orchestration spec §6 {@code FaceFrameSource}, §11
 * vendor independence). Implementations normalize a CameraX stream, an SBI
 * vendor stream, or a mock replay into shared {@link Frame}s; the
 * orchestrator and engine never see vendor types.
 *
 * <p>Threading: implementations call {@code listener.onFrame} from their own
 * capture thread and must never block; the orchestrator applies
 * keep-only-latest backpressure. Errors are reported via
 * {@link Listener#onSourceError} and mapped to {@link LivenessDeviceError}.</p>
 */
public interface FaceFrameSource {

    /** Static capability description used for source selection (spec §11). */
    SourceCapabilities capabilities();

    /** Open the source. Throws {@link LivenessSourceException} on failure (fail closed). */
    void open(SourceConfig config) throws LivenessSourceException;

    /** Begin delivering frames; a source may be opened and started once per session. */
    void start(Listener listener);

    /** Stop frame delivery. Safe to call twice. */
    void stop();

    /** Release the source entirely; not reusable afterwards. */
    void close();

    /** Capability flags for adapter selection (spec §11 discover()). */
    record SourceCapabilities(
            String sourceId,
            boolean streaming,
            boolean signedStream,
            int maxFps,
            Set<Frame.Format> supportedFormats) {
    }

    /** Per-session source configuration (analysis resolution per spec §12). */
    record SourceConfig(int targetWidth, int targetHeight, int targetFps, boolean frontCameraMirrored) {
    }

    /** Errors surfaced by an adapter, mapped 1:1 to {@link LivenessDeviceError}. */
    class LivenessSourceException extends Exception {
        private final LivenessDeviceError error;

        public LivenessSourceException(LivenessDeviceError error, String message) {
            super(message);
            this.error = error;
        }

        public LivenessSourceException(LivenessDeviceError error, String message, Throwable cause) {
            super(message, cause);
            this.error = error;
        }

        public LivenessDeviceError error() {
            return error;
        }
    }

    /** Callbacks invoked on the adapter's capture thread. */
    interface Listener {
        /** One decoded frame. Implementations must return quickly (enqueue, don't process). */
        void onFrame(Frame frame);

        /** Source failure (disconnect, permission, invalid frames). */
        default void onSourceError(LivenessDeviceError error, String message) { }
    }
}
