package io.mosip.liveness.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * The one place an operator key is checked.
 *
 * <p>Every route in {@link RouteInventory#OPERATOR_KEYED} is refused before it
 * reaches its handler unless the caller's headers prove a role — see
 * {@link OperatorKeyGuard#role}, which decides. The route list decides *which*
 * routes, never a call inside a handler: that shape is what {@link AdminKeyFilter}
 * and {@link SessionOwnershipFilter} exist to remove, because a new operator route
 * whose author forgets the call is simply open and nothing notices.
 * {@code ApiRouteInventoryTest} fails a route that is classified into two keyed
 * sets or into none.</p>
 *
 * <p><b>Order.</b> Late, and strictly after {@link RateLimitFilter}: an over-budget
 * key guesser is refused as a flood before this filter spends a constant-time
 * comparison on it, so brute force is throttled per client IP rather than answered
 * at full speed. Getting that wrong is not a style question — this filter stops
 * the chain when it refuses, so if it ran first the limiter would never see a
 * failed attempt and the budget would never fill. The two offsets are the reason
 * both are written as {@code LOWEST_PRECEDENCE - n} (this one ten below, the
 * limiter twenty below): the arithmetic is on {@code Integer.MAX_VALUE}, so
 * {@code + n} wraps around and sorts *ahead* of everything.</p>
 *
 * <p>{@link SecurityHeadersFilter} runs first of everything, so a refusal still
 * carries the security headers and the {@code no-store} policy.</p>
 *
 * <p><b>401, not 403.</b> An admin-keyed route refuses with {@code FORBIDDEN}
 * because the caller is known to be outside the admin surface; here the caller is
 * simply unauthenticated, and the console needs one status to switch on. The
 * status and the message are the same ones the endpoint itself would produce
 * ({@link OperatorKeyGuard#REFUSAL}), so a caller cannot tell whether the filter
 * or the handler answered — and the answer never names a header, a reason, or
 * anything derived from the key it saw.</p>
 *
 * <p>The refusal body is {@link FilterRefusal}'s — the shape
 * {@link GlobalExceptionHandler} produces — because a filter runs outside Spring
 * MVC's exception resolution.</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class OperatorKeyFilter extends OncePerRequestFilter {

    /**
     * The admin key, accepted here because a supervisor can do everything an
     * operator can. The field initializer makes the filter usable when
     * instantiated directly (a unit test), where no container resolves the
     * annotation.
     */
    @Value("${mosip.security.admin-api-key:}")
    private String adminApiKey = "";

    /**
     * Blank means "no operator key configured", which
     * {@link OperatorKeyGuard#role} reads as "nobody is an operator" —
     * fail closed, rather than an operator route open to whoever sends an empty
     * header.
     */
    @Value("${mosip.security.operator-api-key:}")
    private String operatorApiKey = "";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RouteInventory.isOperatorRoute(request.getMethod(), request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String role = OperatorKeyGuard.role(operatorApiKey, adminApiKey,
                request.getHeader(OperatorKeyGuard.HEADER), request.getHeader(AdminKeyGuard.HEADER));
        if (role == null) {
            FilterRefusal.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                    "UNAUTHORIZED", OperatorKeyGuard.REFUSAL);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
