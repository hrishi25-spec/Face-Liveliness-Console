package io.mosip.liveness.auth;

import io.mosip.liveness.models.enums.AuthRole;

import java.util.UUID;

/**
 * Who is making this request, once the request has proved it.
 *
 * <p>Two ways in, and this type is what makes them one thing to the gates: an
 * operator API key proves {@link AuthRole#OPERATOR} with no account behind it
 * ({@link #operatorKey()}), and a session token proves its account's role. A
 * gate asks {@link #role()} only — it does not care which of the two answered,
 * which is why the operator key needed no second code path when accounts were
 * added.</p>
 *
 * <p>{@link #mustChangePassword()} is carried rather than looked up again: the
 * filter that enforces it runs before anything else reads the request, so the
 * decision and the value it was made from travel together.</p>
 *
 * <p>No field of this type is ever a credential. The token is not here (the
 * gates resolve it to this record and then work only with the record); the
 * password hash is not here; and {@link #username()} is the accountable name
 * that belongs in an audit entry — never a value from the wire.</p>
 */
public record AuthPrincipal(UUID userId, String username, AuthRole role,
                            boolean mustChangePassword) {

    /**
     * The principal an accepted operator API key proves: the smaller role, and
     * no account.
     *
     * <p>{@code userId} and {@code username} are {@code null} on purpose — the
     * key is a shared value, so there is no "who" to name, and inventing one
     * would put a false identity in an audit entry. Code that needs an account
     * (the password change, {@code GET /auth/me}) is on routes the key cannot
     * reach at all.</p>
     */
    public static AuthPrincipal operatorKey() {
        return new AuthPrincipal(null, null, AuthRole.OPERATOR, false);
    }

    /** Whether this caller was recognised by a key rather than by a session. */
    public boolean fromSharedKey() {
        return userId == null;
    }

    /** Whether this caller's role satisfies a gate that requires {@code required}. */
    public boolean holds(AuthRole required) {
        return role != null && role.atLeast(required);
    }
}
