package io.mosip.liveness.device;

import io.mosip.liveness.core.Frame;

import java.util.Set;

/**
 * What a device (or simulated device) can do, discovered at open time.
 * Mirrors the MOSIP SBI capability split: L1 devices expose a continuous
 * {@code /stream} frame feed; L0 devices only answer discrete capture
 * requests — in which case the host must use the burst-capture adapter.
 */
public record DeviceCapabilities(
        String deviceId,
        String deviceSubType,      // e.g. "L1_FACE", "L0_FACE", "MOCK"
        boolean streamingSupported,
        boolean burstCaptureSupported,
        Set<Frame.Format> supportedFormats,
        int maxFps) {

    public DeviceCapabilities {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
        supportedFormats = Set.copyOf(supportedFormats);
    }

    /** Capability set for an SBI L1 face device with a STREAM endpoint. */
    public static DeviceCapabilities l1Stream(String deviceId, Set<Frame.Format> formats, int maxFps) {
        return new DeviceCapabilities(deviceId, "L1_FACE", true, true, formats, maxFps);
    }

    /** Capability set for an SBI L0 face device: discrete captures only. */
    public static DeviceCapabilities l0Burst(String deviceId, Set<Frame.Format> formats, int maxFps) {
        return new DeviceCapabilities(deviceId, "L0_FACE", false, true, formats, maxFps);
    }

    public boolean isL1() {
        return streamingSupported;
    }
}
