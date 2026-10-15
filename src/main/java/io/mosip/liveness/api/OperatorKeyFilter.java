package io.mosip.liveness.api;

import io.mosip.liveness.auth.AuthPrincipal;
import io.mosip.liveness.auth.AuthRefusals;
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
import java.util.Optional;

/**
 * The one place an operator route's credential is checked.
 *
 * <p>Every route in {@link RouteInventory#OPERATOR_KEYED} is refused before it
 * reaches its handler unless the caller proves a role, by either credential:</p>
 * <ul>
 *   <li>a <b>session token</b> — already resolved by {@link AuthContextFilter}
 *       into an {@link AuthPrincipal} whose role is {@code OPERATOR} or
 *       {@code SUPERVISOR}; or</li>
 *   <li>the <b>operator API key</b> in {@link OperatorKeyGuard#HEADER}, which
 *       proves {@code OPERATOR} and nothing more.</li>
 * </ul>
 *
 * <p>The route list decides <em>which</em> routes, never a call inside a handler:
 * that shape is what {@link SupervisorGateFilter} and
 * {@link SessionOwnershipFilter} exist to remove, because a new operator route
 * whose author forgets the call is simply open and nothing notices.
 * {@code ApiRouteInventoryTest} fails a route that is classified into two tiers
 * or into none.</p>
 *
 * <p><b>The session wins when both are presented.</b> A caller holding a
 * supervisor session and an operator key is a supervisor; answering with the
 * smaller role would hide the larger one it actually proved.</p>
 *
 * <p><b>Order.</b> Late, and strictly after {@link RateLimitFilter}: an
 * over-budget credential guesser is refused as a flood before this filter spends a
 * constant-time comparison on it, so guessing is throttled per client IP rather
 * than answered at full speed. Getting that wrong is not a style question — this
 * filter stops the chain when it refuses, so if it ran first the limiter would
 * never see a failed attempt and the budget would never fill. The two offsets are
 * the reason both are written as {@code LOWEST_PRECEDENCE - n} (this one ten
 * below, the limiter twenty below): the arithmetic is on
 * {@code Integer.MAX_VALUE}, so {@code + n} wraps around and sorts *ahead* of
 * everything.</p>
 *
 * <p>It runs after {@link AuthContextFilter} ({@code - 15}) for the same reason:
 * the principal it prefers is resolved there.</p>
 *
 * <p><b>401, not 403.</b> A rejected operator route answers 401 rather than a
 * supervisor route's 403, and the message is shared with the unauthenticated
 * case everywhere else, so a caller cannot tell whether the filter or the handler
 * answered — and the answer never names a header, a credential, or anything
 * derived from the value it saw.</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class OperatorKeyFilter extends OncePerRequestFilter {

    /**
     * Blank means "no operator key configured", which closes key-based access to
     * operator routes: a session token still works, and the key proves nothing —
     * fail closed, rather than an operator route open to whoever sends an empty
     * header.
     *
     * <p>The field initializer is not decoration: it makes the filter usable when
     * instantiated directly (a unit test), where no container resolves the
     * annotation.</p>
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
        Optional<AuthPrincipal> resolved = AuthGate.principal(request);
        if (resolved.isEmpty()) {
            if (!OperatorKeyGuard.proves(operatorApiKey, request.getHeader(OperatorKeyGuard.HEADER))) {
                FilterRefusal.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                        "UNAUTHORIZED", OperatorKeyGuard.REFUSAL);
                return;
            }
            // Pin the key's role onto the request so a handler has one place to
            // read the caller from, whichever credential answered.
            request.setAttribute(AuthGate.ATTRIBUTE, AuthPrincipal.operatorKey());
        }
        filterChain.doFilter(request, response);
    }
}
