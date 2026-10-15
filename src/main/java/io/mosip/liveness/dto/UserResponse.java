package io.mosip.liveness.dto;

import io.mosip.liveness.auth.AuthPrincipal;
import io.mosip.liveness.models.entity.AuthUser;

/**
 * One account, as the API answers it.
 *
 * <p>A record of its own rather than the entity, for the same reason
 * {@code ConfigPolicyResponse} exists: a controller that returned
 * {@link AuthUser} directly would serialise {@code passwordHash}, and the BCrypt
 * hash of an operator's password is exactly the value that must never leave the
 * service. There is no field here that could carry one — the shape of the
 * response is the protection, not a filter that has to stay correct.</p>
 *
 * <p>The same shape answers {@code GET /auth/me} and every user-management
 * route, so "who am I" and "who exists" cannot disagree about what an account
 * looks like.</p>
 */
public record UserResponse(
        String username,
        String role,
        boolean mustChangePassword,
        boolean disabled,
        String createdAt,
        String passwordChangedAt) {

    /**
     * The response for a caller that only a principal describes — the login
     * answer, which is built from the session that was just minted and not from a
     * second read of the row. The two timestamps are null here, and stay null
     * rather than being guessed at; a client that wants them calls
     * {@code GET /auth/me}.
     */
    public static UserResponse of(AuthPrincipal principal) {
        return new UserResponse(
                principal.username(),
                principal.role() != null ? principal.role().name() : null,
                principal.mustChangePassword(),
                false,
                null,
                null);
    }

    /** The response for one account. Null timestamps stay null; nothing is invented. */
    public static UserResponse of(AuthUser user) {
        return new UserResponse(
                user.getUsername(),
                user.getRole() != null ? user.getRole().name() : null,
                user.isMustChangePassword(),
                user.isDisabled(),
                user.getCreatedAt() != null ? user.getCreatedAt().toString() : null,
                user.getPasswordChangedAt() != null
                        ? user.getPasswordChangedAt().toString() : null);
    }
}
