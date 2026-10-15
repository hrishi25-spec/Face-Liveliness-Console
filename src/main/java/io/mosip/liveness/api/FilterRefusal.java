package io.mosip.liveness.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.time.OffsetDateTime;

/**
 * The body a filter writes when it refuses a request, in the shape every other
 * error in this service uses ({@code error}, {@code message}, {@code status},
 * {@code timestamp} — see {@link GlobalExceptionHandler}).
 *
 * <p>Filters need their own copy of that shape because they run outside Spring
 * MVC's exception resolution: throwing {@code ResponseStatusException} from one
 * surfaces as a container error page, not JSON. That is why the shape is written
 * out by hand instead of produced by the handler — and why it lives in one place
 * here, now that several filters ({@link SupervisorGateFilter},
 * {@link OperatorKeyFilter}, {@link RateLimitFilter}, {@link SessionOwnershipFilter},
 * {@link AuthContextFilter}) refuse in it. The client guide documents this
 * body, and a client parses {@code message} out of it, so a second spelling of
 * it is a contract change waiting to happen.</p>
 *
 * <p>{@code message} is interpolated into the JSON unescaped, which is safe only
 * because every message here is a fixed string in this repository — no value from
 * the request reaches it. A refusal that wants to name client input has to gain
 * an escaping step first.</p>
 */
final class FilterRefusal {

    private FilterRefusal() {
    }

    /**
     * Writes the refusal and stops the chain: the caller returns immediately
     * after this call, so nothing downstream sees the request.
     *
     * <p>CORS headers are applied even on the refusal, so a browser host on
     * another local port can read the error instead of seeing an opaque network
     * failure. Loopback origins only, matching {@code AppConfig}'s allow-list.</p>
     *
     * @param error   the machine-readable code, e.g. {@code FORBIDDEN}
     * @param message the safe, user-facing reason — never a value from the request
     */
    static void write(HttpServletRequest request, HttpServletResponse response,
                      int status, String error, String message) throws IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && (origin.startsWith("http://localhost:")
                || origin.startsWith("http://127.0.0.1:"))) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + error
                + "\",\"message\":\"" + message
                + "\",\"status\":" + status
                + ",\"timestamp\":\"" + OffsetDateTime.now() + "\"}");
    }
}
