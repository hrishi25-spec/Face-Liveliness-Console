package io.mosip.liveness.device;

import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.LivenessException;

/**
 * One discrete frame acquisition, used by {@link BurstRCaptureDeviceAdapter}
 * to poll L0-style devices that have no continuous stream. Implementations
 * wrap whatever the device actually offers — an SBI rCapture HTTP call, a
 * vendor SDK "grab" function, or a test double.
 */
@FunctionalInterface
public interface CaptureSource {

    /**
     * Perform one capture.
     *
     * @param sequenceNumber  monotonically increasing frame counter to stamp
     * @param timestampMillis wall-clock capture time to stamp
     * @return the captured frame
     * @throws LivenessException on device/capture failure (code distinguishes retryable vs terminal)
     */
    Frame capture(int sequenceNumber, long timestampMillis) throws LivenessException;
}
