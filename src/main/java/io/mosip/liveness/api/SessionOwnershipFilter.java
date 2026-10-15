package io.mosip.liveness.api;

import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.models.entity.LivenessSession;
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
import java.util.UUID;

/**
 * The one place session ownership is checked.
 *
 * <p>Every route in {@link RouteInventory#SESSION_SCOPED} is refused unless the
 * caller presents the session's own {@code subjectRef} in
 * {@link UserSessionGuard#HEADER}; the decision itself is
 * {@link UserSessionGuard#refusal}. The check used to live inside each handler as
 * a call to {@code UserSessionGuard.require}, and that shape had the same failure
 * mode {@link SupervisorGateFilter} was built to remove: a new session-scoped route
 * whose author forgets the call is simply open, and nothing notices. Here there
 * is no call to forget — {@link RouteInventory} decides, and
 * {@code ApiRouteInventoryTest} fails a route that carries a session id without
 * being listed there.</p>
 *
 * <p><b>Order.</b> Last, after {@link RateLimitFilter}
 * ({@code LOWEST_PRECEDENCE}): an over-budget request is refused as a flood
 * before this filter spends a lookup on it, and the two filters cannot tie,
 * which would leave their relative order to bean registration. The
 * {@code + 10} is what makes that ordering a decision rather than a coincidence.
 * {@link SecurityHeadersFilter} runs first of everything, so a refusal still
 * carries the security headers and the {@code no-store} policy.</p>
 *
 * <p><b>What it does not do.</b> A request that is not session-scoped — including
 * the {@code OPTIONS} preflight a browser sends before a session-scoped call,
 * which cannot carry the claim — passes straight through, because the inventory
 * keys on the method and no session-scoped verb is {@code OPTIONS}. An unknown
 * session id passes too: the handler decides, which keeps the audit route's
 * empty-trail answer and every 404 the same as they were, and keeps a malformed
 * id a 400 from the dispatcher instead of a 500 here. And a session with no
 * {@code subjectRef} has no owner to protect, so it stays reachable — see
 * {@link UserSessionGuard}.</p>
 *
 * <p>The handler still loads the session it needs; this filter's lookup is an
 * extra indexed read on the same primary key rather than a shared one, because
 * the two loads answer different questions (may this caller act? does this
 * session exist, and is it still {@code ACTIVE}?) and the handler's is the one
 * that must hold when it runs.</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE + 10)
public class SessionOwnershipFilter extends OncePerRequestFilter {

    private final LivenessSessionRepository sessionRepo;

    public SessionOwnershipFilter(LivenessSessionRepository sessionRepo) {
        this.sessionRepo = sessionRepo;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return RouteInventory.sessionIdIn(request.getMethod(), request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String rawSessionId = RouteInventory.sessionIdIn(request.getMethod(), request.getRequestURI());
        // The gate above returned false, so the same call agrees on this one:
        // reaching here without an id would mean the inventory changed mid-request.
        UUID sessionId;
        try {
            sessionId = UUID.fromString(rawSessionId);
        } catch (IllegalArgumentException notAUuid) {
            // The handler's `@PathVariable UUID` turns this into a 400 through
            // GlobalExceptionHandler. Answering an ownership refusal instead would
            // report a client typo as a permissions problem.
            filterChain.doFilter(request, response);
            return;
        }

        Optional<LivenessSession> session = sessionRepo.findById(sessionId);
        if (session.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        String refusal = UserSessionGuard.refusal(session.get().getSubjectRef(),
                request.getHeader(UserSessionGuard.HEADER));
        if (refusal != null) {
            FilterRefusal.write(request, response, HttpServletResponse.SC_FORBIDDEN,
                    "FORBIDDEN", refusal);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
