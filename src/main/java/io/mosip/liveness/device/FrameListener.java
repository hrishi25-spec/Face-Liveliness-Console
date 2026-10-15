package io.mosip.liveness.device;

import io.mosip.liveness.core.LivenessException;

/**
 * Callbacks a {@link DeviceAdapter} invokes as frames arrive from the device.
 *
 * <p>{@link #onFrame} is called on the adapter's internal capture thread —
 * implementations must hand frames off quickly (e.g. enqueue them for the
 * engine loop) and must not block. Failures are reported via
 * {@link #onDeviceError}; the {@link io.mosip.liveness.core.LivenessErrorCode}
 * carries the retryable-vs-terminal distinction (e.g.
 * {@code DEVICE_CONNECTION_FAILURE} is transient,
 * {@code DEVICE_UNAVAILABLE} is terminal).</p>
 */
public interface FrameListener {

    /** Called once per decoded, correctly-sized frame, in capture order. */
    void onFrame(io.mosip.liveness.core.Frame frame);

    /** Called when the device or stream fails; may be followed by recovery or more errors. */
    default void onDeviceError(LivenessException error) {
        // no-op default: adapters are usable without an error handler
    }
}
