package io.mosip.liveness.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The body of {@code POST /api/v1/auth/change-password}.
 *
 * <p>The current password is required even though the caller already holds a
 * session: it is what stops a stolen or shared session from being used to seize
 * the account outright.</p>
 *
 * <p>Neither field is echoed, logged or audited. {@code newPassword} is checked
 * for length and for not matching the current one by {@code AuthService}, which
 * is where the policy lives so the bootstrap and supervisor-reset paths enforce
 * the same rules.</p>
 */
public record ChangePasswordRequest(
        @NotBlank(message = "currentPassword is required") String currentPassword,
        @NotBlank(message = "newPassword is required") String newPassword) {
}
