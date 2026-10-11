package io.mosip.liveness.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The service's route surface, written down once and read by everything that
 * must treat a route differently: {@link AdminKeyFilter} refuses an
 * admin-keyed route when the caller has no key, {@link RateLimitFilter} meters
 * the expensive writes, and {@link SessionOwnershipFilter} refuses a
 * session-scoped route to anyone but the session's owner.
 *
 * <p>It lives in main rather than only in {@code ApiRouteInventoryTest}
 * because a filter has no compile-time view of a list that exists solely in
 * test code. Before this class, {@code RateLimitFilter} carried its own copy
 * of the four admin paths — the documented price of that gap — so a route
 * could gain the admin key in one list and not the other, and a test could
 * report the drift but not prevent it.</p>
 *
 * <p>The test still enforces both halves: every mapping declared on every
 * controller must appear in exactly one of the two classification sets
 * ({@link #CLIENT_FACING} or {@link #ADMIN_KEYED}), and every route that
 * carries a session id must additionally be in {@link #SESSION_SCOPED}. A new
 * session-scoped endpoint that is not listed there is not guarded by
 * {@link SessionOwnershipFilter} — so the test fails rather than letting an
 * owned session answer a stranger.</p>
 */
final class RouteInventory {

    /**
     * Reachable without a key. The session flow is the product; the policy read
     * exists because a client has to read the policy it is judged against;
     * config audit reads are open because hiding history would be security
     * theatre (the current policy is already public); metrics is aggregate
     * counters with nothing per-session or secret-derived; diagnostics is
     * opt-in and answers loopback callers only.
     */
    static final Set<String> CLIENT_FACING = Set.of(
            "GET /health",
            "POST /api/v1/sessions",
            "GET /api/v1/sessions/{sessionId}",
            "POST /api/v1/sessions/{sessionId}/close",
            "GET /api/v1/sessions/{sessionId}/audit",
            "POST /api/v1/sessions/{sessionId}/frames",
            "POST /api/v1/sessions/{sessionId}/challenges/validate",
            "POST /api/v1/sessions/{sessionId}/active-liveness",
            "GET /api/v1/config/{workflowType}",
            "GET /api/v1/config/{workflowType}/effective",
            "GET /api/v1/config/audit",
            "GET /api/v1/config/audit/verify",
            "GET /api/v1/metrics",
            "GET /api/v1/diagnostics");

    /**
     * Refused without the admin key, and refused entirely when none is
     * configured. Only the policy write and the calibration sweep require the
     * key — the audit reads are deliberately open (see {@link #CLIENT_FACING}).
     * The same two {@code AdminKeyGuard} calls used to live in handlers; the
     * filter removes the call site so a new admin route cannot forget it.
     */
    static final Set<String> ADMIN_KEYED = Set.of(
            "PUT /api/v1/config/{workflowType}",
            "GET /api/v1/eval/threshold-sweep");

    /**
     * Every route whose path names a session: acted on only by that session's
     * owner. A session created with a {@code subjectRef} answers only a caller
     * presenting it in {@link UserSessionGuard#HEADER};
     * {@link SessionOwnershipFilter} refuses the rest before the handler loads
     * anything. A session created without one has no owner to protect and stays
     * open — the rule itself is {@link UserSessionGuard}'s.
     *
     * <p>Membership is what opts a route in. The filter has no per-handler hook
     * to notice, so a new session-scoped endpoint that is missing here is simply
     * reachable by anybody, which is why
     * {@code ApiRouteInventoryTest.everySessionScopedRouteOptsIntoOwnership}
     * fails on the omission instead. The six entries above were the six
     * hand-written {@code UserSessionGuard.require} calls.</p>
     */
    static final Set<String> SESSION_SCOPED = Set.of(
            "GET /api/v1/sessions/{sessionId}",
            "POST /api/v1/sessions/{sessionId}/close",
            "GET /api/v1/sessions/{sessionId}/audit",
            "POST /api/v1/sessions/{sessionId}/frames",
            "POST /api/v1/sessions/{sessionId}/challenges/validate",
            "POST /api/v1/sessions/{sessionId}/active-liveness");

    /** A {@code {name}} placeholder, matched as exactly one path segment. */
    private static final Pattern VARIABLE = Pattern.compile("\\{[^}]+}");

    /**
     * Compiled once; the filter runs this on every request.
     *
     * <p>Declared after {@link #VARIABLE}, which it uses: static initialisers
     * run in textual order, so the other way round this class fails to load
     * with an NPE — which is how every filter that reads it would fail.</p>
     */
    private static final List<Route> ADMIN_ROUTES = compile(ADMIN_KEYED);

    /**
     * The session-scoped templates, compiled with their id captured.
     *
     * <p>Checked at load, not matched loosely: {@link #sessionIdIn} hands the
     * filter its first capture group, so a template with a second placeholder —
     * or one keyed on something other than {@code {sessionId}} — would make the
     * filter look up a different segment and guard the wrong thing. Both shapes
     * throw while the class loads, so the service refuses to start rather than
     * run a check that reads the wrong id.</p>
     */
    private static final List<Route> SESSION_ROUTES = compileSessionScoped();

    private RouteInventory() {
    }

    /**
     * Whether a request must carry {@link AdminKeyGuard#HEADER}.
     *
     * <p>The path is normalised first, because the guard's view has to agree
     * with the dispatcher's. Tomcat resolves {@code /.} and {@code /..}
     * before routing, so comparing the raw URI would refuse the spelled-out
     * spelling while the resolved one sailed past the filter to a handler that
     * no longer checks for itself.</p>
     *
     * @param method the request method, e.g. {@code PUT}
     * @param path   the request path, e.g. {@code /api/v1/config/RESIDENT}
     */
    static boolean isAdminRoute(String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        String resolved = normalise(path);
        for (Route route : ADMIN_ROUTES) {
            if (route.method().equals(method) && route.path().matcher(resolved).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The session id a session-scoped request acts on, or {@code null} when the
     * request is not one. This is the ownership filter's whole gate: a filter
     * cannot see which handler a request will reach, so the shape that decides
     * "is this a session's route?" has to be the same list the inventory test
     * keeps the controllers honest against.
     *
     * <p>The path is normalised first, for the same reason as
     * {@link #isAdminRoute}: a resolved {@code /..} has to match the template it
     * lands on, or the guard is on the spelling and not the route.</p>
     *
     * <p>The returned segment is the raw path text, not a parsed UUID: a
     * malformed id is the dispatcher's 400 (see {@code GlobalExceptionHandler}),
     * and turning it into a 500 in a filter would answer a client mistake with a
     * server error.</p>
     *
     * @param method the request method, e.g. {@code POST}
     * @param path   the request path, e.g. {@code /api/v1/sessions/abc/frames}
     */
    static String sessionIdIn(String method, String path) {
        if (method == null || path == null) {
            return null;
        }
        String resolved = normalise(path);
        for (Route route : SESSION_ROUTES) {
            if (!route.method().equals(method)) {
                continue;
            }
            Matcher matched = route.path().matcher(resolved);
            if (matched.matches()) {
                return matched.group(1);
            }
        }
        return null;
    }

    /** One classified route: a verb and the path pattern it guards. */
    private record Route(String method, Pattern path) {
    }

    private static List<Route> compile(Set<String> routes) {
        List<Route> compiled = new ArrayList<>(routes.size());
        for (String route : routes) {
            int verb = route.indexOf(' ');
            if (verb < 1) {
                throw new IllegalStateException(
                        "RouteInventory entry must be \"METHOD /path\": " + route);
            }
            compiled.add(new Route(route.substring(0, verb),
                    pathPattern(route.substring(verb + 1))));
        }
        return List.copyOf(compiled);
    }

    /** {@link #SESSION_SCOPED}, with each template's single id checked before it is compiled. */
    private static List<Route> compileSessionScoped() {
        for (String route : SESSION_SCOPED) {
            Matcher variable = VARIABLE.matcher(route);
            int found = 0;
            while (variable.find()) {
                found++;
                if (!"{sessionId}".equals(variable.group())) {
                    throw new IllegalStateException("A session-scoped route keys on "
                            + "{sessionId}, not " + variable.group() + ": " + route);
                }
            }
            if (found != 1) {
                throw new IllegalStateException("A session-scoped route carries exactly one "
                        + "{sessionId}, found " + found + ": " + route);
            }
        }
        return compile(SESSION_SCOPED);
    }

    /**
     * Compiles a route template to its matcher. A {@code {name}} placeholder
     * matches exactly one path segment — the same rule Spring's URI templates
     * follow — so {@code PUT /api/v1/config/{workflowType}} cannot be widened
     * by a workflow type containing a slash, and the open
     * {@code GET /api/v1/config/{workflowType}} read is never caught by it. It is
     * captured rather than merely matched so {@link #sessionIdIn} can read the
     * segment back; the admin list ignores the group.
     */
    private static Pattern pathPattern(String template) {
        // Trailing slash is optional rather than significant: Spring routes
        // both spellings, so the guard has to as well.
        String body = template.endsWith("/") ? template.substring(0, template.length() - 1) : template;
        StringBuilder regex = new StringBuilder("^");
        Matcher variable = VARIABLE.matcher(body);
        int last = 0;
        while (variable.find()) {
            regex.append(Pattern.quote(body.substring(last, variable.start()))).append("([^/]+)");
            last = variable.end();
        }
        regex.append(Pattern.quote(body.substring(last))).append("/?$");
        return Pattern.compile(regex.toString());
    }

    /**
     * Resolves {@code /.} and {@code /..} segments the way the dispatcher
     * does, so the inventory matches on the path the request is actually
     * routed to rather than the one it was written as.
     */
    private static String normalise(String path) {
        if (path.indexOf("//") < 0 && path.indexOf("/.") < 0) {
            return path; // fast path: nothing to resolve
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/+")) {
            switch (segment) {
                case "", "." -> {
                    // nothing to add
                }
                case ".." -> {
                    if (!segments.isEmpty()) {
                        segments.remove(segments.size() - 1);
                    }
                }
                default -> segments.add(segment);
            }
        }
        StringBuilder resolved = new StringBuilder();
        for (String segment : segments) {
            resolved.append('/').append(segment);
        }
        return resolved.toString();
    }
}
