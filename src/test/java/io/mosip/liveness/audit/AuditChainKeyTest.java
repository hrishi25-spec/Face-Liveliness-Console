package io.mosip.liveness.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the audit chain's key: what it produces with a secret, what it falls
 * back to without one, and which configurations it refuses outright.
 *
 * <p>The HMAC is computed a second time here with the JCA directly, so this
 * asserts the construction rather than its output: an implementation that
 * merely produced "a different hash" would still satisfy a self-consistency
 * test, and the security here rests entirely on the secret being what the
 * database cannot compute.</p>
 */
class AuditChainKeyTest {

    private static final String SECRET = "unit-test-audit-secret-0123456789";

    @Test
    @DisplayName("with a secret the hash is HMAC-SHA-256 over the canonical string")
    void keyedHashIsHmac() throws Exception {
        AuditChainKey key = new AuditChainKey(SECRET);
        assertTrue(key.isKeyed());

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(
                mac.doFinal("entry".getBytes(StandardCharsets.UTF_8)));

        assertEquals(expected, key.hash("entry"));
    }

    @Test
    @DisplayName("without a secret the hash falls back to plain SHA-256")
    void fallbackHashIsSha256() throws Exception {
        AuditChainKey key = new AuditChainKey("");
        assertFalse(key.isKeyed());

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        assertEquals(HexFormat.of().formatHex(
                        digest.digest("entry".getBytes(StandardCharsets.UTF_8))),
                key.hash("entry"));
    }

    @Test
    @DisplayName("blank and whitespace-only secrets are the fallback, not a key")
    void blankSecretIsFallback() {
        assertFalse(new AuditChainKey("").isKeyed());
        assertFalse(new AuditChainKey("   ").isKeyed());
        assertFalse(new AuditChainKey(null).isKeyed());
    }

    @Test
    @DisplayName("keying actually changes the hash, and no two secrets agree")
    void modesAndSecretsNeverCollide() {
        String canonical = "the same canonical entry";
        AuditChainKey fallback = new AuditChainKey("");
        AuditChainKey keyed = new AuditChainKey(SECRET);
        AuditChainKey rotated = new AuditChainKey("a-different-audit-secret-9876543210");

        assertNotEquals(fallback.hash(canonical), keyed.hash(canonical),
                "if keying did not change the hash, the key would buy nothing");
        assertNotEquals(keyed.hash(canonical), rotated.hash(canonical),
                "a rotated secret must not reproduce the old hashes");
        assertEquals(keyed.hash(canonical), new AuditChainKey(SECRET).hash(canonical),
                "the same secret must reproduce the hash — verification depends on it");
    }

    @Test
    @DisplayName("a guessable secret fails at startup instead of pretending to be a key")
    void shortSecretIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new AuditChainKey("tooshort"));
        assertTrue(e.getMessage().contains("audit-hmac-secret"),
                "the failure has to name the setting to fix: " + e.getMessage());
    }

    @Test
    @DisplayName("a retired secret is accepted only alongside a current one")
    void retiredSecretNeedsACurrentOne() {
        // Accepting a retired secret with nothing to rotate onto would leave the
        // chain keyed by a secret the configuration does not name.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new AuditChainKey("", SECRET));
        assertTrue(e.getMessage().contains("previous-secret"), e.getMessage());
    }

    @Test
    @DisplayName("the retired secret must actually differ")
    void retiredSecretMustDifferFromTheCurrentOne() {
        // The rotation step where the new value is pasted into both fields: a
        // window that looks like a rotation and widens nothing.
        assertThrows(IllegalArgumentException.class,
                () -> new AuditChainKey(SECRET, SECRET));
        assertTrue(new AuditChainKey(SECRET, "a-different-audit-secret-9876543210")
                .isRotating());
        assertFalse(new AuditChainKey(SECRET).isRotating());
    }

    @Test
    @DisplayName("a retired secret is held to the same minimum length")
    void retiredSecretCannotBeShort() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuditChainKey(SECRET, "short"));
    }

    @Test
    @DisplayName("verification accepts either secret, writing uses only the current one")
    void matchingCoversTheWindowWithoutWideningWrites() {
        AuditChainKey rotating = new AuditChainKey(SECRET, "a-different-audit-secret-9876543210");
        AuditChainKey oldKey = new AuditChainKey("a-different-audit-secret-9876543210");
        String canonical = "an entry written before the rotation";

        assertEquals(AuditChainKey.Match.RETIRED, rotating.match(canonical, oldKey.hash(canonical)),
                "the window is exactly this: the old hash still verifies");
        assertEquals(AuditChainKey.Match.CURRENT, rotating.match(canonical, rotating.hash(canonical)));
        assertEquals(AuditChainKey.Match.NONE, rotating.match(canonical, "f".repeat(64)));
        assertEquals(AuditChainKey.Match.NONE, rotating.match(canonical, null));
        assertNotEquals(oldKey.hash(canonical), rotating.hash(canonical),
                "the configured application writer must use the current secret");
    }

    @Test
    @DisplayName("exactly the minimum length is accepted, one shorter is not")
    void minimumLengthSecretIsAccepted() {
        assertTrue(new AuditChainKey("a".repeat(AuditChainKey.MIN_SECRET_LENGTH)).isKeyed());
        assertThrows(IllegalArgumentException.class,
                () -> new AuditChainKey("a".repeat(AuditChainKey.MIN_SECRET_LENGTH - 1)));
    }
}
