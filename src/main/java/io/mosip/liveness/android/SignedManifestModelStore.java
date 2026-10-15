package io.mosip.liveness.android;

import io.mosip.liveness.audit.AuditEvent;
import io.mosip.liveness.audit.AuditEventType;
import io.mosip.liveness.audit.AuditLogger;

import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Production model store for the Android glue (spec §13): activation requires
 * a {@link SignedModelManifest} whose vendor signature verifies and whose
 * embedded sha256 binds to the exact payload bytes — then an atomic swap that
 * keeps the previous version for {@link #rollback()} after a failed health
 * check. The plain {@link #activate(String, String, byte[])} path fails closed
 * (returns false): an unsigned activation is exactly the gap this store closes
 * ({@code ModelStore} promised "signature at the glue layer" but shipped no
 * implementation).
 *
 * <p><b>Key rotation.</b> The store trusts a <em>ring</em> of vendor keys,
 * addressed by {@link SignedModelManifest#keyIdOf(PublicKey) key id}:
 * a manifest naming an id verifies only under that exact key (no fallback —
 * a rewritten id cannot silently hop to another trusted key, and an id with
 * no trusted key is refused outright, which is how rotation revokes: drop the
 * key from the ring). A manifest with no id is a legacy one and may verify
 * under any key still in the ring, so old installs keep working while keys
 * rotate; retire a key only once nothing signed without an id still needs it.
 * The single-key constructors are the pre-rotation form and behave the same
 * way with a ring of one.</p>
 *
 * <p><b>Audit (spec §10 {@code MODEL_UPDATED}: "old/new version, hash ok"}).</b>
 * Every {@code activate} call and every {@link #rollback()} emits exactly one
 * {@link AuditEventType#MODEL_UPDATED} event: {@code action=SWAP} with the
 * old and new version and {@code hashOk=true} on a successful swap;
 * {@code action=ROLLBACK} with {@code oldVersion == newVersion} (the previous
 * model is kept) and a {@code reason} naming the failing check
 * ({@code INPUT}, {@code SIGNATURE}, {@code HASH}, {@code MIN_APP_VERSION},
 * {@code UNSIGNED}) on a refusal — {@code hashOk} stays independent of
 * {@code reason}, so a payload whose digest matches but whose signature does
 * not is recorded as {@code hashOk=true, reason=SIGNATURE}; and
 * {@code action=ROLLBACK, reason=HEALTH_CHECK} with the undone and restored
 * versions for {@link #rollback()} (spec §13's auto-rollback), where
 * {@code hashOk=true} records the invariant the rollback relies on — both
 * states were digest-verified when they were swapped in. Events carry no
 * session or workflow (an update is not a liveness decision), and are
 * emitted <em>before</em> any state change, so a sink that throws aborts the
 * transition fail-closed instead of leaving a swap the audit trail never
 * saw. The no-audit constructors use {@link AuditLogger#noop()}.</p>
 *
 * <p>Refusal/rollback auditing used to be the caller's job (see
 * {@code ModelStore}); it now lives here so the fields are recorded by the
 * component that checked them. Thread-safety: the active model is a single
 * volatile reference — swap is one write.</p>
 */
public final class SignedManifestModelStore implements ModelStore {

    /** keyId -> trusted vendor key; insertion order only affects legacy fallback order. */
    private final Map<String, PublicKey> trustedKeys;
    /** App version for the manifest's minAppVersion gate; null disables the gate. */
    private final String currentAppVersion;
    private final AuditLogger audit;

    private volatile ActiveModel active;
    private volatile ActiveModel previous;

    public SignedManifestModelStore(PublicKey vendorKey) {
        this(vendorKey, null);
    }

    /** @param currentAppVersion when non-null, updates whose minAppVersion exceeds it are refused. */
    public SignedManifestModelStore(PublicKey vendorKey, String currentAppVersion) {
        this(vendorKey, currentAppVersion, AuditLogger.noop());
    }

    /**
     * Audited single-key form: every activation, refusal and rollback is
     * reported as {@code MODEL_UPDATED} through {@code audit}.
     */
    public SignedManifestModelStore(PublicKey vendorKey, String currentAppVersion, AuditLogger audit) {
        this(Map.of(SignedModelManifest.keyIdOf(
                Objects.requireNonNull(vendorKey, "vendorKey")), vendorKey), currentAppVersion, audit);
    }

    /**
     * Rotation form: trust several vendor keys at once (previous + current
     * during a rotation window; revoke by constructing without the old key).
     *
     * @param currentAppVersion when non-null, updates whose minAppVersion exceeds it are refused
     * @param trustedKeys       at least one vendor key — an empty ring is refused loudly
     * @throws IllegalArgumentException when no trusted keys are given
     */
    public SignedManifestModelStore(String currentAppVersion, PublicKey... trustedKeys) {
        this(currentAppVersion, AuditLogger.noop(), trustedKeys);
    }

    /** Rotation + audit form. */
    public SignedManifestModelStore(String currentAppVersion, AuditLogger audit, PublicKey... trustedKeys) {
        this(ringOf(Objects.requireNonNull(trustedKeys, "trustedKeys")), currentAppVersion, audit);
    }

    private static Map<String, PublicKey> ringOf(PublicKey[] keys) {
        if (keys.length == 0) {
            throw new IllegalArgumentException("at least one trusted vendor key is required");
        }
        Map<String, PublicKey> ring = new LinkedHashMap<>();
        for (PublicKey key : keys) {
            Objects.requireNonNull(key, "trusted key");
            ring.put(SignedModelManifest.keyIdOf(key), key);
        }
        return ring;
    }

    private SignedManifestModelStore(Map<String, PublicKey> trustedKeys, String currentAppVersion,
                                     AuditLogger audit) {
        this.trustedKeys = Map.copyOf(trustedKeys);
        this.currentAppVersion = currentAppVersion;
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @Override
    public Optional<ActiveModel> activeModel() {
        return Optional.ofNullable(active);
    }

    /**
     * Unsigned activation is refused (and audited as such) — use
     * {@link #activate(SignedModelManifest, byte[])}. Fail closed so a caller
     * wired to the bare interface can never install an unverified model.
     */
    @Override
    public synchronized boolean activate(String modelId, String version, byte[] payload) {
        emit("ROLLBACK", "UNSIGNED", versionOf(active), versionOf(active), false, modelId, null);
        return false;
    }

    /**
     * Spec §13 path: verify signature + hash → atomic swap → keep previous.
     * Every check runs — and the outcome is audited — before any state
     * changes, so a refused update leaves the currently active model
     * untouched and a successful swap is always in the audit trail first.
     */
    public synchronized boolean activate(SignedModelManifest manifest, byte[] payload) {
        String oldVersion = versionOf(active);
        boolean inputOk = manifest != null && payload != null && payload.length > 0;
        String digest = inputOk ? InMemoryModelStore.sha256Hex(payload) : null;
        boolean hashOk = inputOk && digest.equals(manifest.sha256Hex());

        String reason = null;                                   // null = the swap happens
        if (!inputOk) {
            reason = "INPUT";
        } else if (!signatureVerifies(manifest)) {
            reason = "SIGNATURE";
        } else if (!hashOk) {
            reason = "HASH";                                    // tampered payload
        } else if (currentAppVersion != null && !minAppSatisfied(manifest.minAppVersion())) {
            reason = "MIN_APP_VERSION";
        }

        // Audit first: a sink that throws aborts before any state changes.
        emit(reason == null ? "SWAP" : "ROLLBACK", reason, oldVersion,
                reason == null ? manifest.version() : oldVersion,   // refusal: new == old (kept)
                hashOk, manifest == null ? null : manifest.modelId(),
                manifest == null ? null : manifest.keyId());

        if (reason == null) {
            previous = active;                          // keep previous version (rollback source)
            active = new ActiveModel(manifest.modelId(), manifest.version(), digest);   // atomic swap
            return true;
        }
        return false;
    }

    /**
     * Spec §13 rollback: restore the previous model after a failed health
     * check, audited as {@code MODEL_UPDATED(rollback)}. No-op (apart from
     * the audit event) when there is no previous version.
     */
    public synchronized void rollback() {
        String oldVersion = versionOf(active);
        ActiveModel restored = previous;
        // hashOk=true: both states were digest-verified when swapped in — the
        // store only ever assigns `active` after a hash match. What failed is
        // the health check, not the hash.
        emit("ROLLBACK", "HEALTH_CHECK", oldVersion, versionOf(restored), true,
                restored == null ? null : restored.modelId(), null);
        active = restored;
    }

    private void emit(String action, String reason, String oldVersion, String newVersion,
                      boolean hashOk, String modelId, String keyId) {
        audit.log(AuditEvent.of(System.currentTimeMillis(), null, null, AuditEventType.MODEL_UPDATED)
                .field("action", action)
                .field("oldVersion", oldVersion)
                .field("newVersion", newVersion)
                .field("hashOk", hashOk)                    // spec §10: "hash ok"
                .field("modelId", modelId)                  // null values are omitted
                .field("keyId", keyId)
                .field("reason", reason));
    }

    private static String versionOf(ActiveModel model) {
        return model == null ? "none" : model.version();    // "none": the evidence-record convention
    }

    /**
     * Key-id selection: an id present is authoritative (verify under exactly
     * that key or refuse — never fall back, or a rewritten id could hop
     * keys); a blank id is a legacy manifest and may verify under any key
     * still trusted.
     */
    private boolean signatureVerifies(SignedModelManifest manifest) {
        String keyId = manifest.keyId();
        if (keyId != null && !keyId.isBlank()) {
            PublicKey key = trustedKeys.get(keyId);
            return key != null && manifest.verifySignature(key);
        }
        for (PublicKey key : trustedKeys.values()) {
            if (manifest.verifySignature(key)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code current} >= {@code minimum}; malformed versions fail closed. */
    private boolean minAppSatisfied(String minimum) {
        if (minimum == null || minimum.isBlank()) {
            return true;                            // blank = no floor
        }
        String[] required = minimum.trim().split("\\.");
        String[] current = currentAppVersion.trim().split("\\.");
        for (int i = 0; i < required.length; i++) {
            int need = parseSegment(required[i]);
            if (need < 0) {
                return false;
            }
            int have = i < current.length ? parseSegment(current[i]) : 0;
            if (have < 0) {
                return false;
            }
            if (have != need) {
                return have > need;
            }
        }
        return true;
    }

    private static int parseSegment(String segment) {
        try {
            return Integer.parseInt(segment.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
