package io.mosip.liveness.device;

import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.LivenessException;

/**
 * Vendor-agnostic frame source for one biometric device. This is the adapter
 * layer between a real device SDK / SBI device service and the liveness
 * engine: adapters normalize raw camera output into
 * {@link io.mosip.liveness.core.Frame} objects; the engine and backend never
 * touch device-specific APIs (design doc §4).
 *
 * <p>Lifecycle: {@code open() -> startCapture(listener) -> stopCapture() -> close()}.
 * All methods throw {@link LivenessException} with the appropriate
 * {@link LivenessErrorCode} on invalid transitions or device failures.</p>
 */
public interface DeviceAdapter extends AutoCloseable {

    /** Stable identifier for audit/metrics (never contains PII). */
    String id();

    /** Static capability description of this device. */
    DeviceCapabilities capabilities();

    /**
     * Establish the connection to the device. Throws
     * {@code DEVICE_UNAVAILABLE}/{@code DEVICE_CONNECTION_FAILURE} when the
     * device cannot be reached. Idempotent for an already-open adapter.
     */
    void open();

    /**
     * Begin delivering frames to the listener. Frames arrive on an internal
     * capture thread; see {@link FrameListener} threading contract.
     */
    void startCapture(FrameListener listener);

    /** Stop frame delivery and release the capture thread. Safe to call twice. */
    void stopCapture();

    boolean isCapturing();

    /** Release the device entirely; the adapter cannot be reused afterwards. */
    @Override
    void close();
}
