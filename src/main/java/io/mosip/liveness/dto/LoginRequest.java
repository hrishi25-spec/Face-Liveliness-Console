package io.mosip.liveness.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The credentials a caller presents to {@code POST /api/v1/auth/login}.
 *
 * <p>{@code password} is validated for presence only. Length and shape rules
 * belong to the account being created, not to a login: refusing an over-long
 * password here would tell an unauthenticated caller something about the
 * policy, and a wrong-length guess must be indistinguishable from a wrong
 * password. {@code PasswordHasher} refuses an input BCrypt cannot handle.</p>
 *
 * <p>Nothing in this record is logged, returned, or put in an audit entry. It is
 * a wire shape and nothing else.</p>
 */
public record LoginRequest(
        @NotBlank(message = "username is required") String username,
        @NotBlank(message = "password is required") String password) {
}
