package io.mosip.registration.liveness;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.PublicKey;
import java.util.Optional;
import java.util.Properties;

import io.mosip.liveness.android.ModelStore;
import io.mosip.liveness.android.SignedManifestModelStore;
import io.mosip.liveness.android.SignedModelManifest;
import io.mosip.liveness.audit.AuditLogger;

/**
 * Production {@link ModelStore} glue for the Registration Client (spec §13):
 * loads a vendor-signed manifest plus model bundle from disk and delegates
 * every security decision to the engine's {@link SignedManifestModelStore} —
 * vendor signature → sha256 bound to the payload bytes → atomic swap keeping
 * the previous version → rollback after a failed health check.
 *
 * <p>This class is I/O only: verification is pure-JVM crypto covered by
 * {@code SignedManifestModelStoreTest} in the engine repo, so the security
 * behaviour is engine-tested even though this file is wired by hand into the
 * client build (it is not compiled by the engine's Maven build).</p>
 */
public final class AndroidModelStore implements ModelStore {

    private final SignedManifestModelStore delegate;

    public AndroidModelStore(PublicKey vendorKey, String currentAppVersion) {
        this.delegate = new SignedManifestModelStore(vendorKey, currentAppVersion);
    }

    /**
     * Rotation form: trust several vendor keys at once (the previous and the
     * current signing key during a rotation window). Manifests that name a
     * {@code keyId} verify only under that exact key; manifests without one
     * (legacy) may verify under any key still trusted. Revoke a key by
     * constructing without it.
     */
    public AndroidModelStore(String currentAppVersion, PublicKey... trustedKeys) {
        this.delegate = new SignedManifestModelStore(currentAppVersion, trustedKeys);
    }

    /**
     * Audited form: every swap, refusal and rollback is emitted as a
     * {@code MODEL_UPDATED} event (old/new version, hash-ok, failing check)
     * through {@code audit} — no caller-side logging needed.
     */
    public AndroidModelStore(PublicKey vendorKey, String currentAppVersion, AuditLogger audit) {
        this.delegate = new SignedManifestModelStore(vendorKey, currentAppVersion, audit);
    }

    /** Rotation + audit form — see {@link SignedManifestModelStore}. */
    public AndroidModelStore(String currentAppVersion, AuditLogger audit, PublicKey... trustedKeys) {
        this.delegate = new SignedManifestModelStore(currentAppVersion, audit, trustedKeys);
    }

    /**
     * Install a downloaded or sideloaded update (spec §13 online and offline
     * paths share this verification). The manifest file is properties format
     * with keys {@code modelId}, {@code version}, {@code sha256},
     * {@code minAppVersion}, {@code keyId}, {@code signature}. Returns false — keeping the
     * currently active model — on any I/O or verification failure (fail
     * closed); the delegate audits each outcome as {@code MODEL_UPDATED}
     * itself (old/new version, hash-ok), so the caller must not duplicate it.
     */
    public boolean install(File manifestFile, File modelPayloadFile) {
        try {
            Properties properties = new Properties();
            try (FileInputStream in = new FileInputStream(manifestFile)) {
                properties.load(in);
            }
            SignedModelManifest manifest = new SignedModelManifest(
                    properties.getProperty("modelId"),
                    properties.getProperty("version"),
                    properties.getProperty("sha256"),
                    properties.getProperty("minAppVersion"),
                    properties.getProperty("signature"),
                    properties.getProperty("keyId"));
            byte[] payload = Files.readAllBytes(modelPayloadFile.toPath());
            return delegate.activate(manifest, payload);
        } catch (IOException | RuntimeException e) {
            return false;   // fail closed — never activate what could not be read and verified
        }
    }

    @Override
    public Optional<ActiveModel> activeModel() {
        return delegate.activeModel();
    }

    /** Unsigned activation is refused — see {@link SignedManifestModelStore}. */
    @Override
    public boolean activate(String modelId, String version, byte[] payload) {
        return delegate.activate(modelId, version, payload);
    }
}
