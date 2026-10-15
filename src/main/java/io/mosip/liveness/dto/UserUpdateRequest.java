package io.mosip.liveness.dto;

/**
 * The body of {@code PUT /api/v1/users/{username}}.
 *
 * <p>Every field is optional and an absent one means "leave it alone", which is
 * what makes one route serve a role change, a disable, a re-enable and a
 * password reset without three endpoints that could drift apart. The rename is
 * deliberately not offered: {@code username} is the key in the URL, appears in
 * every audit line naming this account, and renaming it would rewrite history
 * that is meant to be immutable.</p>
 *
 * <p>When {@code newPassword} is present the account's
 * {@code mustChangePassword} flag is set and every session it holds is revoked —
 * a reset that left old sessions alive would not be a reset. All rules,
 * including last-supervisor protection, are {@code AuthService}'s.</p>
 */
public record UserUpdateRequest(
        String role,
        Boolean disabled,
        String newPassword) {
}
