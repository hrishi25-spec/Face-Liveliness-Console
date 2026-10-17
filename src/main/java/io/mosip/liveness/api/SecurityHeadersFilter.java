package io.mosip.liveness.api;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Applies security headers to every response and rejects oversized request
 * bodies before they reach a controller.
 *
 * <p>Runs first ({@link Ordered#HIGHEST_PRECEDENCE}) so the 413 rejection and
 * the headers themselves apply to every path, including error responses.</p>
 *
 * <p>CSP is strict ({@code script-src 'self'}, {@code style-src 'self'}) for the
 * browser console because its script/style live in static files rather than
 * inline. Swagger UI / springdoc pages ship inline bootstrap scripts inside
 * their webjar, so they are exempt from CSP only — they still receive every
 * other header.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter implements Filter {

    /** Configurable so low-memory deployments can lower it. */
    @Value("${mosip.security.max-request-body-bytes:25165824}") // 24 MB
    private long maxRequestBodyBytes;

    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; connect-src 'self'; object-src 'none'; "
            + "base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest req && response instanceof HttpServletResponse res) {
            applyHeaders(req, res);

            String method = req.getMethod();
            if (("POST".equals(method) || "PUT".equals(method))
                    && req.getContentLengthLong() > maxRequestBodyBytes) {
                res.setStatus(413);
                res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                res.getWriter().write("{\"error\":\"PAYLOAD_TOO_LARGE\","
                        + "\"message\":\"Request body exceeds the configured limit.\","
                        + "\"status\":413}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void applyHeaders(HttpServletRequest req, HttpServletResponse res) {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        res.setHeader("Permissions-Policy", "camera=(self), microphone=(), geolocation=()");
        String uri = req.getRequestURI();
        boolean docs = uri.startsWith("/swagger-ui") || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/webjars");
        if (!docs) {
            res.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        }
    }
}
