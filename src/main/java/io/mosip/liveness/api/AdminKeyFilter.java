package io.mosip.liveness.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;

/**
 * The one place the admin key is checked.
 *
 * <p>Every route in {@link RouteInventory#ADMIN_KEYED} is refused before it
 * reaches its handler unless the caller presents {@link AdminKeyGuard#HEADER}.
 * The check used to live inside each handler as a call to
 * {@code AdminKeyGuard.require}, and that shape had a specific failure mode: a
 * new admin route whose author forgets the call is simply open, and nothing
 * notices. Here there is no call to forget — the route inventory decides, and
 * {@code ApiRouteInventoryTest} fails when a route is added without being
 * classified into one of the two lists.</p>
 *
 * <p><b>Order.</b> Last. {@link RateLimitFilter} runs first so an
 * unauthenticated caller guessing the key spends from the operator budget
 * instead of getting a free comparison per attempt, and a request already
 * refused as a flood never reaches the constant-time compare at all.
 * {@link SecurityHeadersFilter} runs first of everything, so a refusal still
 * carries the security headers and the {@code no-store} policy.</p>
 *
 * <p>The refusal body matches {@link GlobalExceptionHandler}'s shape exactly
 * ({@code error}, {@code message}, {@code status}, {@code timestamp}) because
 * a filter runs outside Spring MVC's exception resolution: throwing
 * {@code ResponseStatusException} here would surface as a container error
 * page instead of the JSON every other 4xx in this service produces.</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AdminKeyFilter extends OncePerRequestFilter {

    /**
     * Blank means "no key configured". {@link AdminKeyGuard} refuses the route
     * outright in that case — fail closed, rather than leaving a route open
     * because the service was deployed without the key.
     *
     * <p>The field initializer is not decoration: it makes the filter usable
     * when instantiated directly (a unit test), where no container resolves
     * the annotation.</p>
     */
    @Value("${mosip.security.admin-api-key:}")
    private String adminApiKey = "";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RouteInventory.isAdminRoute(request.getMethod(), request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String refusal = AdminKeyGuard.refusal(adminApiKey, request.getHeader(AdminKeyGuard.HEADER));
        if (refusal == null) {
            filterChain.doFilter(request, response);
            return;
        }
        // Apply CORS headers even on refusal so the browser can read the error
        String origin = request.getHeader("Origin");
        if (origin != null && (origin.startsWith("http://localhost:") || origin.startsWith("http://127.0.0.1:"))) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"FORBIDDEN\",\"message\":\"" + refusal
                + "\",\"status\":403,\"timestamp\":\"" + OffsetDateTime.now() + "\"}");
    }
}
