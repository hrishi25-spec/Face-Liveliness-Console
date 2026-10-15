package io.mosip.liveness.api;

import io.mosip.liveness.auth.AuthPrincipal;
import io.mosip.liveness.auth.AuthRefusals;
import io.mosip.liveness.auth.AuthService;
import io.mosip.liveness.dto.ChangePasswordRequest;
import io.mosip.liveness.dto.LoginRequest;
import io.mosip.liveness.dto.UserResponse;
import io.mosip.liveness.models.entity.AuthUser;
import io.mosip.liveness.api.AuthGate;
import io.mosip.liveness.api.OperatorKeyGuard;
import io.mosip.liveness.api.SessionCookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authentication: sign in, sign out, who am I, change my password — and the role
 * probe the console uses to pick its surface.
 *
 * <p>Replaces the shared admin API key as the way into the privileged surface.
 * The whole contract of this class is that a password goes in, a session comes
 * out, and the session is handed over in a {@code Set-Cookie} header rather than
 * in a body. That is not a stylistic preference: a body is read by scripts,
 * cached by proxies and captured by error reporters, so a token in one is a token
 * with a much larger audience. {@link SessionCookie} writes {@code HttpOnly} for
 * the same reason, and no method here logs a credential or a token.</p>
 *
 * <p><b>Every refusal is the service's standard error shape</b>
 * ({@code error}/{@code message}/{@code status}/{@code timestamp}), produced by
 * {@link GlobalExceptionHandler} for the {@code ResponseStatusException}s thrown
 * here — and by {@link FilterRefusal} where a filter refuses first. The two paths
 * carry the same generic texts from {@link AuthRefusals}, so a caller cannot tell
 * which one answered.</p>
 *
 * <p><b>The role probe.</b> {@code GET /api/v1/auth/role} answers which role the
 * caller proved, because the console cannot work that out from its own state —
 * both credentials are just strings it was given. It is operator-keyed
 * ({@link RouteInventory#OPERATOR_KEYED}), so the gate normally decides who gets
 * here; the handler repeats the check through {@link AuthGate} because it is also
 * reached directly (a slice test, or an embedding without the filter chain) and
 * because the endpoint's contract is a role or a 401. The answer is the role name
 * alone: no credential, and not a fingerprint of one.</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /**
     * The caller is a person that proved nothing. 401 rather than 403, for the
     * same reason the login format is 401: the answer is "these credentials are
     * not good", which is what a client retries, and it names no reason.
     */
    private static final String BAD_CREDENTIALS = AuthRefusals.INVALID_CREDENTIALS;

    private final AuthService authService;

    /**
     * Whether the session cookie carries {@code Secure}. Configuration rather
     * than unconditional: the shipped deployment is loopback HTTP, where a
     * {@code Secure} cookie is dropped by the browser — a login that appears to
     * succeed and does not is worse than a documented flag.
     */
    @Value("${mosip.security.auth.cookie-secure:false}")
    private boolean cookieSecure = false;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Exchanges a username and password for a session.
     *
     * <p>One generic 401 for every failure — unknown username, wrong password,
     * disabled account, locked account — so the endpoint cannot be used to
     * discover which accounts exist, and an unknown username still pays a full
     * BCrypt verification so it cannot be found by response time either
     * ({@link AuthService#login}).</p>
     *
     * <p>The body answers who signed in (name, role, whether a password change is
     * owed). The token is in the {@code Set-Cookie} header and nowhere else.</p>
     */
    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletResponse response) {
        AuthService.IssuedSession session = authService
                .login(request.username(), request.password())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS));
        SessionCookie.write(response, session.token(), session.maxAgeSeconds(), cookieSecure);
        return ResponseEntity.ok(UserResponse.of(session.principal()));
    }

    /**
     * Ends the session the caller presents.
     *
     * <p>Idempotent and unconditional: an unknown, expired or already-revoked
     * token is a success, because a client signing out twice — or signing out
     * after its session lapsed — has got the outcome it asked for. The cookie is
     * cleared either way, so a browser never keeps a value the service has
     * forgotten.</p>
     *
     * <p>{@code 204} and an empty body: there is nothing useful to say, and a body
     * is one more place a credential could end up.</p>
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(SessionCookie.read(request));
        SessionCookie.clear(response, cookieSecure);
        return ResponseEntity.noContent().build();
    }

    /**
     * Who the caller is. The account is read fresh rather than taken from the
     * session, so an account disabled after its token was issued answers 401 here
     * instead of reporting an identity that no longer has access.
     */
    @GetMapping("/me")
    public UserResponse me(HttpServletRequest request) {
        AuthPrincipal principal = AuthGate.requirePrincipal(request);
        AuthUser user = authService.currentUser(principal).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, AuthRefusals.UNAUTHENTICATED));
        return UserResponse.of(user);
    }

    /**
     * Replaces the caller's password.
     *
     * <p>The current password is required even though the caller already holds a
     * session — that is what stops a stolen or shared session from seizing the
     * account. A wrong one is 401, not 400: the session is not proof that the
     * person at the keyboard knows the password.</p>
     *
     * <p>On success every session the account held is revoked, including the one
     * used to make the call, and a replacement is issued. The new token goes into
     * the cookie; the response body is the account, as before, and never the
     * token.</p>
     */
    @PostMapping("/change-password")
    public ResponseEntity<UserResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                       HttpServletRequest httpRequest,
                                                       HttpServletResponse response) {
        AuthPrincipal principal = AuthGate.requirePrincipal(httpRequest);
        AuthService.IssuedSession session = authService
                .changePassword(principal, request.currentPassword(), request.newPassword())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS));
        SessionCookie.write(response, session.token(), session.maxAgeSeconds(), cookieSecure);
        return ResponseEntity.ok(UserResponse.of(session.principal()));
    }

    /**
     * Which role the caller proved — {@code SUPERVISOR} or {@code OPERATOR}.
     *
     * <p>Answers from the principal the gate resolved, so a session token and the
     * operator key give the same kind of answer through the same code path. When
     * no principal is on the request the handler refuses with the gate's own
     * message and status, so the filter and the handler cannot drift.</p>
     */
    @GetMapping("/role")
    public RoleResponse role(HttpServletRequest request) {
        AuthPrincipal principal = AuthGate.principal(request).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, OperatorKeyGuard.REFUSAL));
        return new RoleResponse(principal.role() != null ? principal.role().name() : null);
    }

    /**
     * The caller's role. No other field, by design — the role name is the entire
     * answer, and anything else would be a value derived from a credential.
     */
    public record RoleResponse(String role) {
    }
}
