package io.mosip.liveness.api;

import io.mosip.liveness.auth.AuthPrincipal;
import io.mosip.liveness.auth.AuthRefusals;
import io.mosip.liveness.models.enums.AuthRole;
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
 * The one place the supervisor role is checked.
 *
 * <p>Every route in {@link RouteInventory#SUPERVISOR_KEYED} is refused before it
 * reaches its handler unless the caller's session belongs to an account holding
 * {@link AuthRole#SUPERVISOR}. This replaces a shared admin API key, and keeps
 * that gate's shape: the route list decides, never a call inside a handler,
 * because a handler that forgets the call is simply an open route and nothing
 * notices.
 * {@code ApiRouteInventoryTest} fails a route that is added without being
 * classified, and fails one that is classified into two tiers.</p>
 *
 * <p><b>Two statuses, because they mean different things.</b> No session at all
 * is {@code 401} with {@link AuthRefusals#UNAUTHENTICATED} — the caller has not
 * said who it is, and the console answers by showing a sign-in. A session whose
 * role is too small is {@code 403} with {@link AuthRefusals#FORBIDDEN} — the
 * caller is known, and re-authenticating will not help. Answering both with one
 * status would either tell a signed-out caller to give up or tell an operator to
 * keep typing passwords.</p>
 *
 * <p><b>The operator key is not accepted here, and that is the point.</b> It
 * proves {@link AuthRole#OPERATOR}, so it cannot become a principal on this tier:
 * {@link OperatorKeyFilter} — the only filter that reads it — does not run on
 * these routes. A leaked key therefore reaches the read-only feeds and can never
 * write a policy or an account.</p>
 *
 * <p><b>Order.</b> Last ({@code LOWEST_PRECEDENCE}): {@link RateLimitFilter} runs
 * first so an unauthenticated caller guessing credentials spends from the
 * credential budget instead of getting a free lookup per attempt, and
 * {@link AuthContextFilter} runs before this one so the principal is already
 * resolved. {@link SecurityHeadersFilter} runs first of everything, so a refusal
 * still carries the security headers and the {@code no-store} policy.</p>
 *
 * <p>The refusal body is {@link FilterRefusal}'s — the shape
 * {@link GlobalExceptionHandler} produces — because a filter runs outside Spring
 * MVC's exception resolution: throwing {@code ResponseStatusException} here would
 * surface as a container error page instead of the JSON every other 4xx in this
 * service produces.</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class SupervisorGateFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RouteInventory.isSupervisorRoute(request.getMethod(), request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Optional<AuthPrincipal> resolved = AuthGate.principal(request);
        if (resolved.isEmpty()) {
            FilterRefusal.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                    "UNAUTHORIZED", AuthRefusals.UNAUTHENTICATED);
            return;
        }
        if (!resolved.get().holds(AuthRole.SUPERVISOR)) {
            FilterRefusal.write(request, response, HttpServletResponse.SC_FORBIDDEN,
                    "FORBIDDEN", AuthRefusals.FORBIDDEN);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
