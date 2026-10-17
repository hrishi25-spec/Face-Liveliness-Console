package io.mosip.liveness.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Mints and digests session tokens.
 *
 * <p>A token is 32 bytes from a {@link SecureRandom} — 256 bits, which is not
 * guessable and needs no password-style slow hash. {@link #digest(String)} is
 * what the database stores, so the token itself exists only in the
 * {@code Set-Cookie} header that delivered it and in the memory of the client
 * that received it. Nothing writes it to a response body or a log line, and this
 * class deliberately offers no method that would.</p>
 *
 * <p>The base64url alphabet is used so the value is header-safe and
 * cookie-safe with no escaping: a token that needed quoting would be a token
 * some client mangles.</p>
 */
public final class SessionTokens {

    /** Bytes of entropy per token. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private SessionTokens() {
    }

    /**
     * A fresh, unguessable token, ready to be handed to the client.
     *
     * <p>Only the caller that writes the response sees this value; everything
     * else works from its digest.</p>
     */
    public static String mint() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /**
     * The stored, searchable form of a token: hex SHA-256.
     *
     * <p>A fast hash on purpose — a token is 256 random bits, so there is no
     * guessable input for a slow hash to protect, and this runs on every
     * authenticated request. Missing SHA-256 cannot happen on any supported JVM;
     * the checked exception is converted rather than silently degrading to
     * storing the raw token, which would be worse than refusing to start.</p>
     */
    public static String digest(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and unavailable", e);
        }
    }

    /**
     * Whether a raw token is well-formed enough to bother hashing and looking
     * up: base64url text of exactly the minted length. A shape check only —
     * it never says whether a token is <em>valid</em>, which is decided by the
     * lookup plus the expiry and revocation checks.
     */
    public static boolean looksWellFormed(String token) {
        if (token == null) {
            return false;
        }
        int expected = (TOKEN_BYTES * 4 + 2) / 3; // base64url, unpadded
        if (token.length() != expected) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
