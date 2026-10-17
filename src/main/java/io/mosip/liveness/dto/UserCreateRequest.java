package io.mosip.liveness.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The body of {@code POST /api/v1/users} — a supervisor creating an account.
 *
 * <p>{@code initialPassword} is the one the supervisor hands over out of band.
 * The created account always starts with {@code mustChangePassword = true}, so
 * that value cannot become the account's permanent password by inaction: the
 * new holder must replace it before the session can reach anything.</p>
 *
 * <p>{@code role} is a plain string rather than the enum so an unknown value is
 * a 400 the service decides on, with one message, instead of a Spring binding
 * failure that answers differently.</p>
 */
public record UserCreateRequest(
        @NotBlank(message = "username is required") String username,
        @NotBlank(message = "role is required") String role,
        @NotBlank(message = "initialPassword is required") String initialPassword) {
}
