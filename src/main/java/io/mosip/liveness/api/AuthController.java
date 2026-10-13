package io.mosip.liveness.api;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who the caller is, as far as the service can tell.
 *
 * <p>The console needs to know whether it is driving the supervisor surface or the
 * operator one, and it cannot work that out from its own state: both keys are just
 * strings it was given. This endpoint answers with the role a key proves, so a
 * client picks its surface from the service's answer instead of guessing from
 * which box someone typed into.</p>
 *
 * <p>{@code GET /api/v1/auth/role} is an operator-keyed route
 * ({@link RouteInventory#OPERATOR_KEYED}), so {@link OperatorKeyFilter} normally
 * decides who gets here and answers 401 for everyone else. The handler repeats
 * the check through the same {@link OperatorKeyGuard#role} and builds the response
 * from the same role constants — needed because the handler is also reached
 * directly (a slice test, or any future embedding that runs without the filter
 * chain), and because the endpoint's contract is its own: a role, or 401. Both
 * paths use {@link OperatorKeyGuard#REFUSAL}, so the two cannot drift and the
 * status is 401 rather than the filter's own choice.</p>
 *
 * <p>The response body is the whole answer: no key, no fingerprint of one, and no
 * distinction between a missing key and a wrong one. {@code AdminKeyGuard}'s
 * fingerprint exists for the config audit trail, where an operator has to be able
 * to tell which key is loaded; there is no such need here, and a hash of a short
 * key is a value an attacker can guess offline.</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    /**
     * The admin key; its holder is answered {@link OperatorKeyGuard#ROLE_SUPERVISOR}.
     * Blank disables that answer, which is the same fail-closed reading
     * {@link AdminKeyGuard} applies.
     */
    @Value("${mosip.security.admin-api-key:}")
    private String adminApiKey = "";

    /** The operator key; blank means no caller is ever answered {@code OPERATOR}. */
    @Value("${mosip.security.operator-api-key:}")
    private String operatorApiKey = "";

    @GetMapping("/role")
    public RoleResponse role(
            @RequestHeader(name = AdminKeyGuard.HEADER, required = false) String adminHeader,
            @RequestHeader(name = OperatorKeyGuard.HEADER, required = false) String operatorHeader) {
        String role = OperatorKeyGuard.role(operatorApiKey, adminApiKey, operatorHeader, adminHeader);
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, OperatorKeyGuard.REFUSAL);
        }
        return new RoleResponse(role);
    }

    /**
     * The caller's role: {@code SUPERVISOR} (the admin key) or {@code OPERATOR}
     * (the operator key). No other field, by design — see the class comment.
     */
    public record RoleResponse(String role) {
    }
}
