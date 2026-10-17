package io.mosip.liveness.android;

import java.util.Optional;

/**
 * Signed-model lifecycle (spec §13): models are activated only after hash
 * verification, the previous version is retained, and active sessions never
 * switch models mid-flight. The Android glue loads the TFLite MiniFASNet
 * asset; tests use scripted versions.
 */
public interface ModelStore {

    /** Currently active model (verified); empty when none has ever been installed. */
    Optional<ActiveModel> activeModel();

    /**
     * Verify (sha256 + signature at the glue layer) and atomically activate.
     * Returns false when verification fails — the caller must keep the
     * previous version and audit {@code MODEL_UPDATED(rollback)}. Audited
     * implementations ({@code SignedManifestModelStore}) emit that event
     * themselves, with old/new version and hash-ok, so their callers must
     * not log it a second time.
     */
    boolean activate(String modelId, String version, byte[] payload);

    record ActiveModel(String modelId, String version, String sha256Hex) { }
}
