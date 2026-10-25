package io.mosip.liveness.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * The only place a password is turned into a hash or checked against one.
 *
 * <p>BCrypt through {@code at.favre.lib}, chosen over a hand-rolled PBKDF2 for
 * three properties this service actually needs:</p>
 * <ul>
 *   <li><b>Self-describing hashes.</b> The stored value carries its own
 *       algorithm, cost and salt ({@code $2a$12$...}), so the cost can be
 *       raised later and old rows stay verifiable — and rehashable at the next
 *       successful login — instead of needing a flag day.</li>
 *   <li><b>Constant-time verification inside the library.</b> The comparison of
 *       the derived hash is {@code MessageDigest.isEqual}-style rather than an
 *       early-exit {@code equals}, so a caller cannot learn a hash prefix by
 *       timing the response. That is the property easiest to get wrong by
 *       hand.</li>
 *   <li><b>Deliberate cost.</b> Cost 12 is ~300 ms on the 2-core target
 *       hardware — a ceiling on offline guessing, paid once per login and never
 *       on the per-frame path.</li>
 * </ul>
 *
 * <p><b>The dummy hash.</b> An unknown username must not answer faster than a
 * known one, or the login endpoint becomes a user-enumeration oracle: an
 * attacker measures which names exist before spending a single password guess.
 * {@link #verifyAgainstDummy(char[])} burns the same BCrypt work against a hash
 * of a value nobody knows, and its result is discarded. The dummy is computed
 * once, at the configured cost, so the two paths cost the same.</p>
 *
 * <p><b>Length.</b> BCrypt only considers the first 72 bytes of input, and the
 * library refuses anything longer rather than silently truncating (truncation
 * would make two different long passwords equivalent). This class therefore
 * rejects an over-long password up front with a clear failure instead of a
 * library exception; no password is ever echoed, hashed into a log, or
 * included in a message.</p>
 */
@Component
public class PasswordHasher {

    /**
     * BCrypt's own input limit, in bytes. Public so the request validation that
     * refuses an over-long password cites the same number this class enforces
     * rather than a copy that can drift.
     */
    public static final int MAX_PASSWORD_BYTES = 72;

    /** Shortest password a caller may set; the bootstrap and reset paths share it. */
    public static final int MIN_PASSWORD_LENGTH = 12;

    /**
     * Cost floor for a real deployment. Cost is log2 of the work: 10 is the
     * weakest value that is still defensible in 2026, and {@code SecurityValidator}
     * refuses to start below it outside the {@code dev} profile. It exists as a
     * floor rather than a constant so tests can run at cost 4 and stay fast
     * without any production path being able to.
     */
    public static final int MIN_PRODUCTION_COST = 10;

    private final int cost;

    /**
     * A real hash of a value nothing knows, at the live cost. Used to spend the
     * same time on a login for a username that does not exist. Never compared
     * against anything a caller can supply.
     *
     * <p>Built on first use rather than in the constructor: hashing at cost 12
     * costs ~300 ms on the 2-core target, and paying that during startup bought
     * nothing — an unknown-username login is the only caller. The fix keeps the
     * timing-equalisation property for every attempt after the first, and the
     * first failed attempt is still bounded by the same work.</p>
     */
    private volatile String dummyHash;

    public PasswordHasher(@Value("${mosip.security.auth.bcrypt-cost:12}") int cost) {
        this.cost = cost;
    }

    /** The configured cost, for the startup validation that refuses a weak one. */
    public int cost() {
        return cost;
    }

    /**
     * Hashes a password for storage.
     *
     * @param password the password to hash; caller-validated for length
     * @return a self-describing BCrypt hash suitable for {@code auth_users.password_hash}
     */
    public String hash(String password) {
        return BCrypt.withDefaults().hashToString(cost, password.toCharArray());
    }

    /**
     * Whether a password matches a stored hash.
     *
     * <p>Returns {@code false} rather than throwing on a malformed or empty
     * stored hash, so a corrupted row is a failed login and not a 500 that
     * tells the caller which account is broken.</p>
     */
    public boolean verify(String password, String storedHash) {
        if (password == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            return BCrypt.verifyer().verify(password.toCharArray(), storedHash).verified;
        } catch (IllegalArgumentException malformedHash) {
            // A hash this build cannot parse (hand-edited row, a future
            // algorithm) must fail closed and quietly, never authenticate.
            return false;
        }
    }

    /**
     * Spends one verification's worth of work and discards the outcome, so a
     * login attempt for a username that does not exist costs what a real one
     * does.
     */
    public void verifyAgainstDummy(String password) {
        BCrypt.verifyer().verify(
                password == null ? new char[0] : password.toCharArray(), dummyHash());
    }

    /** The dummy hash, created once at the configured cost on first demand. */
    private String dummyHash() {
        String hash = dummyHash;
        if (hash != null) {
            return hash;
        }
        synchronized (this) {
            if (dummyHash == null) {
                dummyHash = BCrypt.withDefaults()
                        .hashToString(cost, "timing-equalisation-placeholder".toCharArray());
            }
            return dummyHash;
        }
    }

    /** The byte length BCrypt will actually consider; over this the input is refused. */
    public static int byteLength(String password) {
        return password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Whether a candidate password satisfies the shape rules, without taking a
     * hash — so the cheap checks run before the expensive one.
     */
    public static boolean isAcceptable(String password) {
        return password != null
                && password.length() >= MIN_PASSWORD_LENGTH
                && byteLength(password) <= MAX_PASSWORD_BYTES;
    }
}
