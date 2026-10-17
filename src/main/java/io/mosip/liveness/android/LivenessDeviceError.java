package io.mosip.liveness.android;

/**
 * Device/camera/engine failures (orchestration spec §6). These are
 * <em>not</em> user attempts (spec §4 invariant): failing hardware must never
 * burn the retry budget, and the gate fails closed instead of skipping.
 */
public enum LivenessDeviceError {
    DEVICE_UNAVAILABLE,
    DISCONNECTED,
    CAMERA_UNAVAILABLE,
    PERMISSION_DENIED,
    INVALID_FRAME,
    ENGINE_ERROR
}
