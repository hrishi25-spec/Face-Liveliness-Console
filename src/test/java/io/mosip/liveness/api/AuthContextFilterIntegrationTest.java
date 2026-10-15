package io.mosip.liveness.api;

import io.mosip.liveness.auth.AuthRefusals;
import io.mosip.liveness.auth.AuthService;
import io.mosip.liveness.testing.RawHttpSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sessions over real HTTP: the cookie, and the confinement an owed password change
 * imposes.
 *
 * <p>Two things live here that a slice cannot show, because both are about the real
 * chain rather than one component.</p>
 *
 * <p><b>The cookie is the transport.</b> A signed-in caller's token arrives in an
 * {@code HttpOnly} {@code Set-Cookie} header, and the browser's own call carries it
 * back in {@code Cookie}. Nothing here reads the token out of a response body,
 * because nothing puts it there.</p>
 *
 * <p><b>An owed password change is a wall, not a hint.</b> A supervisor creates an
 * account with an initial password, so that account's first session has
 * {@code mustChangePassword} set. While it does, the session reaches
 * {@code GET /auth/me}, {@code POST /auth/change-password} and
 * {@code POST /auth/logout} and is refused with 403 everywhere else — including the
 * client-facing routes, which is the part that has to be checked over real paths
 * rather than reasoned about. The confinement is enforced by
 * {@code AuthContextFilter} against the route inventory, so this test ends by
 * showing the same session reaching the operator feed for real once the password is
 * replaced.</p>
 *
 * <p>Runs the real application (dev profile, H2, real filters) on a random port. The
 * rate-limit budget is raised for the class only, because every request here arrives
 * from one address and the feed is metered per address.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "mosip.security.rate-limit.session-create-limit=100000",
        "spring.jpa.show-sql=false"
})
class AuthContextFilterIntegrationTest extends RawHttpSupport {

    private static final String USERNAME = "cookie-flow-supervisor";
    private static final String INITIAL_PASSWORD = "cookie-flow-initial-1";
    private static final String FINAL_PASSWORD = "cookie-flow-final-123";

    @Autowired private AuthService authService;

    @Test
    void anOwedPasswordChangeConfinesTheSessionUntilItIsReplaced() {
        authService.createUser(USERNAME, "SUPERVISOR", INITIAL_PASSWORD);

        // ---- sign in: the token arrives as a cookie and nowhere else
        RawResponse login = send(HttpMethod.POST, "/api/v1/auth/login",
                "{\"username\":\"" + USERNAME + "\",\"password\":\"" + INITIAL_PASSWORD + "\"}",
                jsonHeaders());
        assertEquals(200, login.status(), login.body());
        assertTrue(login.body().contains("mustChangePassword\":true"), login.body());

        String setCookie = login.header("Set-Cookie");
        assertNotNull(setCookie, "the session must be handed over as a cookie");
        assertTrue(setCookie.contains("HttpOnly"), setCookie);
        assertTrue(setCookie.contains("SameSite=Lax"), setCookie);

        String session = cookieValue(setCookie);
        assertTrue(login.body().contains(USERNAME));
        assertTrue(!login.body().contains(session),
                "the token must never be echoed in the body: " + login.body());

        // ---- the routes that fix the situation are reachable
        assertEquals(200, getWith("/api/v1/auth/me", session).status(),
                "who am I must be answerable, so a client knows what it owes");

        // ---- everything else is refused, including routes this role would hold
        RawResponse feedWhileFlagged = getWith("/api/v1/sessions", session);
        assertEquals(403, feedWhileFlagged.status(), feedWhileFlagged.body());
        // The standard error shape, with the status family as the machine-readable
        // code and the specific sentence in message — the same pairing every other
        // refusal here uses. Asserted in full because the console reads this body to
        // know it must send the user to the password screen rather than to a
        // sign-in, and a shape change would silently turn that into an error page.
        assertTrue(feedWhileFlagged.body().contains("\"error\":\"FORBIDDEN\""),
                feedWhileFlagged.body());
        assertTrue(feedWhileFlagged.body().contains(AuthRefusals.PASSWORD_CHANGE_REQUIRED),
                feedWhileFlagged.body());
        assertTrue(feedWhileFlagged.body().contains("\"status\":403"), feedWhileFlagged.body());
        assertTrue(feedWhileFlagged.body().contains("\"timestamp\":"), feedWhileFlagged.body());

        RawResponse policyWhileFlagged = send(HttpMethod.PUT, "/api/v1/config/RESIDENT",
                "{\"passiveThreshold\":0.9}", headersWithCookie(session));
        assertEquals(403, policyWhileFlagged.status(),
                "a supervisor that owes a password change must not write policy yet: "
                        + policyWhileFlagged.body());

        // A client-facing route too: the confinement is about the session, not about
        // the tier, so it covers the liveness flow as well.
        RawResponse newSessionWhileFlagged = send(HttpMethod.POST, "/api/v1/sessions",
                "{\"workflowType\":\"RESIDENT\",\"deviceId\":\"cookie-flow\"}",
                headersWithCookie(session));
        assertEquals(403, newSessionWhileFlagged.status(), newSessionWhileFlagged.body());

        // ---- replace the password: a fresh session comes back, unconfined
        RawResponse changed = send(HttpMethod.POST, "/api/v1/auth/change-password",
                "{\"currentPassword\":\"" + INITIAL_PASSWORD + "\",\"newPassword\":\""
                        + FINAL_PASSWORD + "\"}",
                headersWithCookie(session));
        assertEquals(200, changed.status(), changed.body());
        String rotatedCookie = changed.header("Set-Cookie");
        assertNotNull(rotatedCookie, "the replacement session must be handed over too");
        String rotated = cookieValue(rotatedCookie);
        assertTrue(!rotated.equals(session), "the token must be rotated, not reused");

        // The old cookie is dead: it was revoked by the change.
        assertEquals(401, getWith("/api/v1/auth/me", session).status(),
                "the session that changed the password must not survive it");

        // …and the replacement reaches what was refused a moment ago.
        assertEquals(200, getWith("/api/v1/sessions", rotated).status(),
                "the same account reaches the operator feed once the change is made");
        assertEquals(200, getWith("/api/v1/auth/me", rotated).status());

        // ---- signing out clears the cookie client-side and kills it server-side
        RawResponse logout = send(HttpMethod.POST, "/api/v1/auth/logout", null,
                headersWithCookie(rotated));
        assertEquals(204, logout.status(), logout.body());
        assertNotNull(logout.header("Set-Cookie"), "signing out must clear the cookie");
        assertEquals(401, getWith("/api/v1/auth/me", rotated).status(),
                "a signed-out session must not authenticate");
    }

    /**
     * The liveness flow is untouched: an anonymous caller reaches the session
     * create/read routes exactly as before. This is the regression guard for the
     * whole change — an auth filter that demanded a session everywhere would break
     * the product rather than protect it.
     */
    @Test
    void anAnonymousCallerStillReachesTheLivenessFlow() {
        RawResponse created = send(HttpMethod.POST, "/api/v1/sessions",
                "{\"workflowType\":\"RESIDENT\",\"deviceId\":\"anonymous-flow\"}", jsonHeaders());
        assertEquals(201, created.status(), created.body());

        // A stale or junk cookie must not break it either: an unusable session is
        // simply an anonymous caller on a client-facing route.
        RawResponse withJunkCookie = send(HttpMethod.POST, "/api/v1/sessions",
                "{\"workflowType\":\"RESIDENT\",\"deviceId\":\"anonymous-junk\"}",
                headersWithCookie("not-a-real-token"));
        assertEquals(201, withJunkCookie.status(), withJunkCookie.body());
    }

    /** A session sent as a bearer token works the same as the cookie. */
    @Test
    void theSameSessionWorksOverTheAuthorizationHeader() {
        authService.createUser("bearer-flow-supervisor", "SUPERVISOR", "bearer-flow-initial-1");

        RawResponse login = send(HttpMethod.POST, "/api/v1/auth/login",
                "{\"username\":\"bearer-flow-supervisor\",\"password\":\"bearer-flow-initial-1\"}",
                jsonHeaders());
        String session = cookieValue(login.header("Set-Cookie"));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + session);
        assertEquals(200, send(HttpMethod.GET, "/api/v1/auth/me", null, headers).status());
        // …and is confined identically, because the confinement looks at the
        // principal rather than at how it arrived.
        assertEquals(403, send(HttpMethod.GET, "/api/v1/sessions", null, headers).status());
    }

    // ------------------------------------------------------------------ helpers

    private RawResponse getWith(String path, String session) {
        return send(HttpMethod.GET, path, null, headersWithCookie(session));
    }

    private static HttpHeaders headersWithCookie(String session) {
        HttpHeaders headers = jsonHeaders();
        headers.set(HttpHeaders.COOKIE, SessionCookie.NAME + "=" + session);
        return headers;
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** The token out of a {@code Set-Cookie} header, up to its first attribute. */
    private static String cookieValue(String setCookie) {
        String name = SessionCookie.NAME + "=";
        int start = setCookie.indexOf(name);
        assertTrue(start >= 0, "no session cookie in: " + setCookie);
        int valueStart = start + name.length();
        int end = setCookie.indexOf(';', valueStart);
        return end < 0 ? setCookie.substring(valueStart) : setCookie.substring(valueStart, end);
    }
}
