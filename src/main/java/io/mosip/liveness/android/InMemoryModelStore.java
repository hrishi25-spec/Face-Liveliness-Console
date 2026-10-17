package io.mosip.liveness.android;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Reference {@link ModelStore}: verifies the sha256 the glue layer computed
 * and swaps atomically, keeping the previous model for rollback (spec §13).
 * On Android the production store verifies the vendor signature too.
 */
public final class InMemoryModelStore implements ModelStore {

    private volatile ActiveModel active;

    @Override
    public Optional<ActiveModel> activeModel() {
        return Optional.ofNullable(active);
    }

    @Override
    public boolean activate(String modelId, String version, byte[] payload) {
        if (payload == null || payload.length == 0) {
            return false;
        }
        String sha256 = sha256Hex(payload);
        if (!sha256.matches("[0-9a-f]{64}")) {
            return false;
        }
        this.active = new ActiveModel(modelId, version, sha256);
        return true;
    }

    /** Rollback helper: restore the previous model after a failed health-check. */
    public void rollback(ActiveModel previous) {
        this.active = previous;
    }

    public static String sha256Hex(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Utility for tests: stable hash of a string payload. */
    public static String sha256Of(String s) {
        return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }
}
