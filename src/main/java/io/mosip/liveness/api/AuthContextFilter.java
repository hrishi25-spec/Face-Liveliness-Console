package io.mosip.liveness.api;

import io.mosip.liveness.auth.AuthPrincipal;
import io.mosip.liveness.auth.AuthRefusals;
import io.mosip.liveness.auth.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Resolves the caller's session exactly once per request, and confines a session
 * that still owes a password change.
 *
 * <p>Two jobs, and they belong together because both are about the identity of
 * the caller rather than about a particular route:</p>
 * <ol>
 *   <li><b>Resolve.</b> The presented token (cookie or {@code Bearer}) becomes an
 *       {@link AuthPrincipal} on the request, so the gates and the handlers read
 *       one answer instead of each doing their own lookup. A token that resolves
 *       to nothing is simply "no principal" — never an error here, because most
 *       of this service is client-facing and a stale cookie in a browser must not
 *       break the liveness flow.</li>
 *   <li><b>Confine.</b> A principal whose account has
 *       {@code mustChangePassword} may reach only
 *       {@link RouteInventory#isPasswordChangeAllowedRoute}, and is refused with
 *       403 everywhere else. The bootstrap password and every supervisor-issued
 *       reset are therefore usable for exactly as long as it takes to replace
 *       them.</li>
 * </ol>
 *
 * <p>The confinement is enforced here rather than in the change-password handler
 * for the reason the whole route inventory exists: a rule that lives in a handler
 * is a rule a new handler can forget. A flagged session cannot reach a new
 * endpoint by omission — it is refused unless the route is on the short list.</p>
 *
 * <p><b>Order.</b> After {@link RateLimitFilter} ({@code LOWEST_PRECEDENCE - 20})
 * so a flood is refused before this spends a database read on it, and before both
 * gates ({@code - 10} and {@code LOWEST_PRECEDENCE}) so they find the principal
 * already on the request. {@link SecurityHeadersFilter} runs first of everything,
 * so a refusal still carries the security headers and the {@code no-store}
 * policy.</p>
 *
 * <p><b>What it does not do.</b> It does not demand a session: only the
 * {@link RouteInventory#AUTHENTICATED} tier requires one, and a request with no
 * token at all skips this filter entirely ({@link #shouldNotFilter}), so static
 * assets and the frame path cost no lookup. An authenticated route reached with
 * no session is refused by the handler itself through {@link AuthGate}, with the
 * same status and message this filter would have written.</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 15)
public class AuthContextFilter extends OncePerRequestFilter {

    private final AuthService authService;

    public AuthContextFilter(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Skips the work when there is nothing to resolve, and never touches a CORS
     * preflight: a browser does not attach credentials to {@code OPTIONS}, so
     * inspecting one would refuse the preflight a cookie-authenticated call
     * depends on.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return SessionCookie.read(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Optional<AuthPrincipal> resolved = authService.authenticate(SessionCookie.read(request));
        String method = request.getMethod();
        String path = request.getRequestURI();

        if (resolved.isEmpty()) {
            // A presented-but-unusable token. Only the authenticated tier cares:
            // everywhere else this is an anonymous caller, which is allowed.
            if (RouteInventory.isAuthenticatedRoute(method, path)) {
                FilterRefusal.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                        "UNAUTHORIZED", AuthRefusals.UNAUTHENTICATED);
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        AuthPrincipal principal = resolved.get();
        request.setAttribute(AuthGate.ATTRIBUTE, principal);

        if (principal.mustChangePassword() && !RouteInventory.isPasswordChangeAllowedRoute(method, path)) {
            FilterRefusal.write(request, response, HttpServletResponse.SC_FORBIDDEN,
                    "FORBIDDEN", AuthRefusals.PASSWORD_CHANGE_REQUIRED);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
