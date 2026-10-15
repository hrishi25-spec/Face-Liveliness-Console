package io.mosip.liveness.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The one place the session cookie's name, flags and clearing behaviour are
 * written down.
 *
 * <p>A cookie rather than a token in the response body, for one reason that is
 * also an acceptance criterion: a body is read by JavaScript, logged by every
 * proxy and captured by every error reporter, while {@code HttpOnly} makes the
 * value unreadable from a script — which is where a token is most likely to be
 * stolen from. Nothing in this service returns a token in a body.</p>
 *
 * <p><b>Flags.</b> {@code HttpOnly} (no script access), {@code SameSite=Lax}
 * (not sent on a cross-site POST, which is the CSRF shape that matters for a
 * cookie-authenticated API), {@code Path=/} (every API path), and {@code Secure}
 * when the deployment terminates TLS. {@code Secure} is configuration rather than
 * unconditional because the shipped deployment is loopback HTTP, where a
 * {@code Secure} cookie is silently dropped — a login that appears to succeed
 * and does not is worse than a documented flag.</p>
 *
 * <p><b>Reading.</b> {@link #read(HttpServletRequest)} prefers the cookie and
 * falls back to {@code Authorization: Bearer}, so the same session works from a
 * browser and from a command line without the token ever appearing in a body.
 * Both are the same value to everything downstream; a lookup is by digest either
 * way.</p>
 */
final class SessionCookie {

    /** Cookie name; part of the wire contract with the console. */
    static final String NAME = "MOSIP_SESSION";

    /** Header form, for a non-browser client of the same session. */
    static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * {@code SameSite=Lax} as an explicit attribute. Servlet 6.0 exposes
     * {@code Cookie#setAttribute} for exactly this — there is no typed setter for
     * SameSite — and Tomcat 10.1 implements it, so no header is hand-built here.
     */
    private static final String SAME_SITE = "Lax";

    private SessionCookie() {
    }

    /**
     * The session token this request presents, or {@code null} when it presents
     * none. The cookie wins when both are present: a browser sends the cookie
     * automatically, so a stale one must not shadow an explicitly supplied
     * header.
     */
    static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (NAME.equals(cookie.getName())) {
                    String value = cookie.getValue();
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            }
        }
        String header = request.getHeader(AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String value = header.substring(BEARER_PREFIX.length()).trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    /**
     * Hands the client its session. {@code maxAgeSeconds} mirrors the stored
     * expiry so the browser drops the cookie at the same moment the server starts
     * refusing it, instead of leaving a value nothing accepts.
     *
     * <p>No {@code Domain} attribute, so the cookie is host-only; no
     * {@code SameSite=None}, so it is never attached to a cross-site request.</p>
     */
    static void write(HttpServletResponse response, String token, long maxAgeSeconds, boolean secure) {
        Cookie cookie = new Cookie(NAME, token);
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setMaxAge((int) Math.max(0, Math.min(maxAgeSeconds, Integer.MAX_VALUE)));
        cookie.setAttribute("SameSite", SAME_SITE);
        response.addCookie(cookie);
    }

    /**
     * Removes the cookie on the client. Signing out clears the cookie even when
     * the token was already dead, so a browser never keeps a value the service
     * has forgotten.
     */
    static void clear(HttpServletResponse response, boolean secure) {
        Cookie cookie = new Cookie(NAME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", SAME_SITE);
        response.addCookie(cookie);
    }
}
