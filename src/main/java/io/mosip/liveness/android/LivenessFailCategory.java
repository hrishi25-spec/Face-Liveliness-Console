package io.mosip.liveness.android;

/**
 * Coarse failure buckets the Pigeon bridge exposes to the Dart UI (spec §7
 * {@code failCategory}). Detail stays in audit (R5).
 */
public enum LivenessFailCategory {
    GENERIC,
    DEVICE,
    MAX_RETRIES
}
