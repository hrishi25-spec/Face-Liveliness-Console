package io.mosip.liveness.api;

import io.mosip.liveness.auth.AuthService;
import io.mosip.liveness.dto.UserCreateRequest;
import io.mosip.liveness.dto.UserResponse;
import io.mosip.liveness.dto.UserUpdateRequest;
import io.mosip.liveness.models.enums.AuthRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Account management, for supervisors only.
 *
 * <p>Every route here is in {@link RouteInventory#SUPERVISOR_KEYED}, so
 * {@link SupervisorGateFilter} refuses an operator session with 403 and an
 * unauthenticated caller with 401 before any of this runs. Each handler also
 * calls {@link AuthGate#requireRole} — not redundancy for its own sake: a
 * {@code @WebMvcTest} slice reaches a handler with no filter chain at all, and
 * the rule that matters here (only a supervisor may create a supervisor) must not
 * depend on a filter having been registered.</p>
 *
 * <p>This is the surface that replaces "edit MOSIP_ADMIN_API_KEY", which is the
 * point of the change: adding a person is now an authenticated, attributed act
 * performed by another person, rather than a shared secret copied into another
 * environment file.</p>
 *
 * <p><b>Last-supervisor protection</b> lives in {@link AuthService}, not here,
 * because the rule is about the set of accounts and not about any one route: a
 * demotion, a disable and a delete all take the same path to the same check.
 * Losing every supervisor is the one change the API could not undo — nobody would
 * be left who may grant it back — so it is refused with 409 rather than
 * permitted with a warning.</p>
 *
 * <p>Responses are {@link UserResponse}, never the entity, so a BCrypt hash
 * cannot reach the wire by accident. Nothing here logs or returns a password: an
 * initial password goes into a hash and a {@code mustChangePassword} flag, and the
 * only place it is ever visible is the response to the caller who set it — and it
 * is not in that
 * response either.</p>
 */
@RestController
@RequestMapping("/api/v1/users")
public class UsersController {

    private final AuthService authService;

    public UsersController(AuthService authService) {
        this.authService = authService;
    }

    /** Every account, by name. Never includes a hash, a token or a lockout state. */
    @GetMapping
    public List<UserResponse> list(HttpServletRequest request) {
        AuthGate.requireRole(request, AuthRole.SUPERVISOR);
        return authService.listUsers().stream().map(UserResponse::of).toList();
    }

    /**
     * Creates an account.
     *
     * <p>The new account always starts owing a password change, so the value a
     * supervisor hands over out of band cannot become the account's permanent
     * password by inaction. No credential is returned.</p>
     */
    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserCreateRequest body,
                                               HttpServletRequest request) {
        AuthGate.requireRole(request, AuthRole.SUPERVISOR);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.of(
                authService.createUser(body.username(), body.role(), body.initialPassword())));
    }

    /**
     * Applies whichever of role, disabled and password were supplied.
     *
     * <p>A supplied {@code newPassword} is treated as an initial one: the account
     * must change it, and every session it currently holds is revoked. A reset
     * that left the previous holder signed in would not be a reset.</p>
     */
    @PutMapping("/{username}")
    public UserResponse update(@PathVariable String username,
                               @RequestBody UserUpdateRequest body,
                               HttpServletRequest request) {
        AuthGate.requireRole(request, AuthRole.SUPERVISOR);
        return UserResponse.of(authService.updateUser(
                username, body.role(), body.disabled(), body.newPassword()));
    }

    /** Removes an account. Refused with 409 when it is the last enabled supervisor. */
    @DeleteMapping("/{username}")
    public ResponseEntity<Void> delete(@PathVariable String username, HttpServletRequest request) {
        AuthGate.requireRole(request, AuthRole.SUPERVISOR);
        authService.deleteUser(username);
        return ResponseEntity.noContent().build();
    }
}
