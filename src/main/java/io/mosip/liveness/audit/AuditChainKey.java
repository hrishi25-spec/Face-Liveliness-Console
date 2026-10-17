package io.mosip.liveness.audit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The secret that keys the config audit chain's hashes.
 *
 * <p>The chain is tamper-*detecting* on its own: anyone who can edit
 * the database can also read the stored hashes and recompute them,
 * because the unkeyed {@code SHA-256} of the canonical form is
 * public knowledge. Keying the hash with a secret the database does
 * not hold closes that gap — an attacker with write access to the
 * database but not to the application's environment cannot recompute
 * a single link, so any rebuild they attempt fails verification
 * outright. The secret arrives via the
 * {@code mosip.security.audit-hmac-secret} property, which is meant
 * to come from the environment ({@code MOSIP_AUDIT_HMAC_SECRET}) so
 * it lives on the app server, not in the database or the repo.</p>
 *
 * <h2>Documented fallback when the secret is unset</h2>
 *
 * <p>A blank secret falls back to the original unkeyed {@code SHA-256}.
 * That keeps a fresh deployment booting with no configuration and
 * keeps chains written before this key existed verifiable — but it is
 * strictly weaker, so the fallback is <em>visible</em>, not silent:
 * {@code GET /api/v1/config/audit/verify} reports the active mode in
 * its {@code hmac} field, and an operator who sees {@code false}
 * knows the chain is tamper-detecting only.</p>
 *
 * <p>The mode is chosen by configuration alone, and verification uses
 * the same key as writing. A chain written keyed and verified with a
 * different (or absent) secret reports every entry as broken — loudly,
 * at the first entry — rather than quietly accepting a downgrade. The
 * remedy is to restore the secret, not to ignore the alarm.</p>
 *
 * <h2>Rotation: retain the previous key for historical verification</h2>
 *
 * <p>The {@code mosip.security.audit-hmac-previous-secret} property lets the
 * application verify entries made with the previous key while new application
 * writes use the current key. The endpoint reports {@code rotationWindowOpen}
 * when that property is set and {@code retiredKeyHashes} when the trail still
 * contains entries that need it.</p>
 *
 * <p>The rows are immutable, so this is not a bounded retirement window: the
 * previous key remains necessary to verify old rows, and clearing it makes the
 * chain fail at the first such row. There is no in-application archive or
 * re-key operation yet. A holder of the previous key can also calculate old
 * key HMACs outside this application, so keeping it configured preserves
 * verification but does not make the key safe to discard later. Full key
 * retirement needs a separately trusted archival or re-key protocol.</p>
 *
 * <p>A secret shorter than {@value #MIN_SECRET_LENGTH} characters is
 * rejected at startup. A guessable key is no key at all: it would
 * produce keyed-looking hashes that an attacker recomputes just as
 * easily, so failing fast beats shipping a false sense of security.</p>
 */
@Component
public class AuditChainKey {

    /** Shortest accepted secret — long enough to be unguessable. */
    static final int MIN_SECRET_LENGTH = 16;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** {@code null} in fallback mode (no secret configured). */
    private final byte[] secret;

    /**
     * The retired secret, accepted for verification only; {@code null} unless
     * a rotation is in progress. Never used to write.
     */
    private final byte[] previousSecret;

    @Autowired
    public AuditChainKey(
            @Value("${mosip.security.audit-hmac-secret:}") String secret,
            @Value("${mosip.security.audit-hmac-previous-secret:}") String previousSecret) {
        this.secret = checked(secret, "mosip.security.audit-hmac-secret");
        this.previousSecret = checked(previousSecret,
                "mosip.security.audit-hmac-previous-secret");

        if (this.previousSecret != null && this.secret == null) {
            throw new IllegalArgumentException(
                    "mosip.security.audit-hmac-previous-secret is set but "
                            + "mosip.security.audit-hmac-secret is not — a retired "
                            + "secret only verifies hashes the current key also "
                            + "should produce. Set the current secret, or clear the "
                            + "retired one; accepting it alone would leave the chain "
                            + "unkeyed while the configuration claims it is keyed");
        }
        if (this.previousSecret != null && MessageDigest.isEqual(this.secret, this.previousSecret)) {
            // Almost always the rotation step where the operator pastes the new
            // value into both fields. Rejecting it is better than a window that
            // looks like a rotation and widens nothing.
            throw new IllegalArgumentException(
                    "mosip.security.audit-hmac-previous-secret must differ from "
                            + "mosip.security.audit-hmac-secret — identical values "
                            + "are not a rotation");
        }
    }

    /** Single-argument form for tests and for the fallback-only case. */
    public AuditChainKey(String secret) {
        this(secret, null);
    }

    private static byte[] checked(String value, String setting) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException(
                    setting + " must be at least "
                            + MIN_SECRET_LENGTH + " characters when set — a "
                            + "shorter secret is guessable, and a guessable "
                            + "key is no key at all");
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** Whether entries are HMAC-keyed ({@code false} = SHA-256 fallback). */
    public boolean isKeyed() {
        return secret != null;
    }

    /** Whether a retired secret is still being accepted for verification. */
    public boolean isRotating() {
        return previousSecret != null;
    }

    /**
     * Hashes a canonical entry string.
     *
     * @param canonical the stable form produced by
     *        {@link AuditChain#canonical(AuditLog)}
     * @return the 64-character hex {@code entry_hash}
     */
    public String hash(String canonical) {
        return hashWith(secret, canonical);
    }

    /**
     * Whether a stored hash is one this configuration can reproduce — under
     * the current secret, or under the previous secret when configured.
     *
     * <p>The application calls {@link #hash(String)} to write, and that method
     * always uses the current secret. This method uses the previous secret only
     * to recognize stored hashes; a holder of that secret can still calculate
     * HMACs independently of this class.</p>
     *
     * @return {@link Match#CURRENT}, {@link Match#RETIRED}, or
     *         {@link Match#NONE}
     */
    public Match match(String canonical, String storedHash) {
        if (storedHash == null) {
            return Match.NONE;
        }
        if (constantTimeEquals(hashWith(secret, canonical), storedHash)) {
            return Match.CURRENT;
        }
        if (previousSecret != null
                && constantTimeEquals(hashWith(previousSecret, canonical), storedHash)) {
            return Match.RETIRED;
        }
        return Match.NONE;
    }

    /** Which secret produced a stored hash. */
    public enum Match {
        /** Reproduced by the current secret. */
        CURRENT,
        /** Reproduced only by the previous secret. */
        RETIRED,
        /** Reproduced by neither. */
        NONE
    }

    private static String hashWith(byte[] keyBytes, String canonical) {
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        try {
            if (keyBytes == null) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(bytes));
            }
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(keyBytes, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(bytes));
        } catch (NoSuchAlgorithmException e) {
            // Both algorithms are required by the JCS, so this cannot happen.
            throw new IllegalStateException("SHA-256 / HmacSHA256 are required"
                    + " by the JCS but unavailable", e);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("the audit HMAC key was rejected", e);
        }
    }

    /**
     * Compares two hex digests without leaking their contents through timing.
     * A verification endpoint is unauthenticated, so an attacker could
     * otherwise learn a stored hash byte by byte from how long the comparison
     * takes.
     */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
}
