package io.mosip.liveness.api;

import io.mosip.liveness.auth.AuthPrincipal;
import io.mosip.liveness.auth.AuthRefusals;
import io.mosip.liveness.models.enums.AuthRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Where the authenticated caller is parked for the rest of the request, and the
 * two helpers that read it back.
 *
 * <p>{@code AuthContextFilter} resolves a session once and puts the
 * {@link AuthPrincipal} on the request; the gates, the auth endpoints and
 * {@code ConfigController} read it back from here. Resolving once matters
 * because the lookup is a database read: without the attribute every gate and
 * every handler would repeat it, and two components could disagree about who the
 * caller is within one request.</p>
 *
 * <p>The refusal texts live in {@link AuthRefusals} rather than here, because the
 * service throws some of them too and the filter/handler pair must be
 * indistinguishable to a caller.</p>
 */
final class AuthGate {

    /**
     * Request attribute holding the resolved {@link AuthPrincipal}. Namespaced
     * by class name so it cannot collide with an attribute another filter sets.
     */
    static final String ATTRIBUTE = AuthGate.class.getName() + ".principal";

    private AuthGate() {
    }

    /** The resolved principal, when a filter has already run for this request. */
    static Optional<AuthPrincipal> principal(HttpServletRequest request) {
        Object attribute = request.getAttribute(ATTRIBUTE);
        return attribute instanceof AuthPrincipal principal
                ? Optional.of(principal)
                : Optional.empty();
    }

    /**
     * The resolved principal, or a 401.
     *
     * <p>Handlers on authenticated routes call this rather than trusting that a
     * filter ran: a {@code @WebMvcTest} slice reaches a handler with no filter
     * chain at all, and an embedding that bypasses the filters must fail closed
     * rather than act as nobody. The status and message match the filter's, so
     * both paths answer identically.</p>
     */
    static AuthPrincipal requirePrincipal(HttpServletRequest request) {
        return principal(request).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, AuthRefusals.UNAUTHENTICATED));
    }

    /**
     * The resolved principal when its role satisfies {@code required}, or a 403
     * otherwise — what a slice test and any non-filter caller of a privileged
     * handler uses, so the handler is safe on its own and not only behind the
     * gate.
     */
    static AuthPrincipal requireRole(HttpServletRequest request, AuthRole required) {
        AuthPrincipal principal = requirePrincipal(request);
        if (!principal.holds(required)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, AuthRefusals.FORBIDDEN);
        }
        return principal;
    }
}
