package io.mosip.liveness.api;

import io.mosip.liveness.auth.AuthPrincipal;
import io.mosip.liveness.models.enums.AuthRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The service's route surface, written down once and read by everything that
 * must treat a route differently: {@link AuthContextFilter} resolves the
 * caller's session and confines an account that still owes a password change,
 * {@link SupervisorGateFilter} refuses a supervisor route to anyone whose role
 * is smaller, {@link OperatorKeyFilter} refuses an operator route to a caller
 * that proves no role, {@link RateLimitFilter} meters the expensive writes and
 * the credential attempts, and {@link SessionOwnershipFilter} refuses a
 * session-scoped route to anyone but the session's owner.
 *
 * <p>It lives in main rather than only in {@code ApiRouteInventoryTest} because
 * a filter has no compile-time view of a list that exists solely in test code.
 * Before this class, {@code RateLimitFilter} carried its own copy of the four
 * admin paths — the documented price of that gap — so a route could gain the
 * supervisor gate in one list and not the other, and a test could report the
 * drift but not prevent it.</p>
 *
 * <p>The test still enforces both halves: every mapping declared on every
 * controller must appear in exactly one of the classification sets, and every
 * route that carries a session id must additionally be in
 * {@link #SESSION_SCOPED}. A new session-scoped endpoint that is not listed there
 * is not guarded by {@link SessionOwnershipFilter} — so the test fails rather
 * than letting an owned session answer a stranger — and a route listed in two
 * keyed sets would be claimed by two filters, which the test rejects as well.</p>
 *
 * <p><b>Four tiers now, not three.</b> The privileged tier was
 * {@code ADMIN_KEYED}, guarded by a shared admin API key. A shared value cannot
 * say who used it and cannot be revoked for one holder, so the tier became
 * {@link #SUPERVISOR_KEYED}: the same routes, plus user management, reachable
 * only by a session whose account holds {@link AuthRole#SUPERVISOR}. The new
 * {@link #AUTHENTICATED} tier is the third step down — any valid session, no
 * role required — which is what "who am I", "sign out" and "change my password"
 * are: they must be reachable by an operator, by a supervisor, and by an account
 * that still owes a password change.</p>
 *
 * <p><b>What the operator API key still reaches.</b> It proves
 * {@link AuthRole#OPERATOR} on the {@link #OPERATOR_KEYED} routes only. It is
 * deliberately absent from both privileged tiers, so a leaked key can read the
 * feeds and can never edit a policy or an account — the smaller value, the
 * smaller blast radius.</p>
 */
final class RouteInventory {

    /**
     * Reachable without a key or a session. The session flow is the product; the
     * policy read exists because a client has to read the policy it is judged
     * against; config audit reads are open because hiding history would be
     * security theatre (the current policy is already public); metrics is
     * aggregate counters with nothing per-session or secret-derived; diagnostics
     * is opt-in and answers loopback callers only.
     *
     * <p>{@code POST /api/v1/auth/login} belongs here rather than in a keyed
     * tier for the obvious reason — it is how a caller obtains the credential
     * the other tiers check — and it is metered per client IP by
     * {@link RateLimitFilter} on the credential-attempt budget, which is what
     * bounds password guessing.</p>
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
            "POST /api/v1/sessions/{sessionId}/evidence",
            "GET /api/v1/config/{workflowType}",
            "GET /api/v1/config/{workflowType}/effective",
            "GET /api/v1/config/audit",
            "GET /api/v1/config/audit/verify",
            "GET /api/v1/metrics",
            "GET /api/v1/diagnostics",
            "POST /api/v1/auth/login");

    /**
     * Reachable by any caller that has proved it is somebody: a valid session
     * token, no particular role. Guarded by {@link AuthContextFilter}, which
     * refuses with 401 when no session resolved.
     *
     * <p>Membership has to include every route an account with
     * {@code mustChangePassword} is permitted to reach, because that is the
     * short list {@link AuthContextFilter} confines such a session to. The three
     * here are that list: the account can see who it is, replace the password,
     * and sign out. Everything else it is refused — including the routes below
     * its own role could otherwise reach.</p>
     */
    static final Set<String> AUTHENTICATED = Set.of(
            "GET /api/v1/auth/me",
            "POST /api/v1/auth/change-password",
            "POST /api/v1/auth/logout");

    /**
     * Reachable only by a session whose account holds {@link AuthRole#SUPERVISOR}.
     * Refused by {@link SupervisorGateFilter} with 401 when no session resolved
     * and 403 when the session's role is smaller.
     *
     * <p>These are the routes that decide who passes liveness and who may
     * administer the service: the policy write, the calibration sweep that
     * publishes an operating point, and the four account-management calls. They
     * used to be guarded by a shared admin API key.</p>
     */
    static final Set<String> SUPERVISOR_KEYED = Set.of(
            "PUT /api/v1/config/{workflowType}",
            "GET /api/v1/eval/threshold-sweep",
            "GET /api/v1/users",
            "POST /api/v1/users",
            "PUT /api/v1/users/{username}",
            "DELETE /api/v1/users/{username}");

    /**
     * Reachable by a caller whose headers prove a role: the operator key
     * ({@link OperatorKeyGuard#HEADER}) or a session token held by an operator
     * or a supervisor. Refused by {@link OperatorKeyFilter} with 401, and
     * metered per client IP by {@link RateLimitFilter} so guessing a key spends
     * the same budget as flooding the service.
     *
     * <p>The three members are the console's operator surface: the role probe
     * ({@code GET /api/v1/auth/role}) that answers {@code SUPERVISOR} or
     * {@code OPERATOR}, and the two read-only feeds that surface is for — the
     * session list ({@code GET /api/v1/sessions}, with optional
     * {@code workflowType}/{@code status} filters and a capped {@code limit}) and
     * the rejection feed ({@code GET /api/v1/rejections}, failed sessions plus
     * their {@code PAD_REJECTED}/{@code CHALLENGE_FAILED} events). Reads, so they
     * carry no session id of their own and are <em>not</em> in
     * {@link #SESSION_SCOPED}: a list of other people's sessions is guarded by
     * the credential, not by an ownership claim the caller could not present.</p>
     */
    static final Set<String> OPERATOR_KEYED = Set.of(
            "GET /api/v1/auth/role",
            "GET /api/v1/sessions",
            "GET /api/v1/rejections");

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
     * fails on the omission instead. The six entries were the six hand-written
     * {@code UserSessionGuard.require} calls.</p>
     */
    static final Set<String> SESSION_SCOPED = Set.of(
            "GET /api/v1/sessions/{sessionId}",
            "POST /api/v1/sessions/{sessionId}/close",
            "GET /api/v1/sessions/{sessionId}/audit",
            "POST /api/v1/sessions/{sessionId}/frames",
            "POST /api/v1/sessions/{sessionId}/challenges/validate",
            "POST /api/v1/sessions/{sessionId}/active-liveness",
            "POST /api/v1/sessions/{sessionId}/evidence");

    /** A {@code {name}} placeholder, matched as exactly one path segment. */
    private static final Pattern VARIABLE = Pattern.compile("\\{[^}]+}");

    /**
     * Compiled once; the gates run these on every request.
     *
     * <p>Declared after {@link #VARIABLE}, which they use: static initialisers
     * run in textual order, so the other way round this class fails to load with
     * an NPE — which is how every filter that reads it would fail.</p>
     */
    private static final List<Route> SUPERVISOR_ROUTES = compile(SUPERVISOR_KEYED);

    /** The authenticated tier, compiled like the keyed ones. */
    private static final List<Route> AUTHENTICATED_ROUTES = compile(AUTHENTICATED);

    /**
     * The operator-keyed templates. The keyed sets are disjoint
     * ({@code ApiRouteInventoryTest} fails a route in two of them), so no
     * request is claimed by two gates.
     */
    private static final List<Route> OPERATOR_ROUTES = compile(OPERATOR_KEYED);

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
     * Whether a request must be made by an account holding
     * {@link AuthRole#SUPERVISOR}.
     *
     * <p>The path is normalised first, because the gate's view has to agree with
     * the dispatcher's. Tomcat resolves {@code /.} and {@code /..} before
     * routing, so comparing the raw URI would refuse the spelled-out spelling
     * while the resolved one sailed past the filter to a handler that no longer
     * checks for itself.</p>
     *
     * @param method the request method, e.g. {@code PUT}
     * @param path   the request path, e.g. {@code /api/v1/config/RESIDENT}
     */
    static boolean isSupervisorRoute(String method, String path) {
        return matches(SUPERVISOR_ROUTES, method, path);
    }

    /**
     * Whether a request must carry a valid session. The lightest tier: no role
     * required, only proof that the caller is somebody.
     *
     * @param method the request method, e.g. {@code GET}
     * @param path   the request path, e.g. {@code /api/v1/auth/me}
     */
    static boolean isAuthenticatedRoute(String method, String path) {
        return matches(AUTHENTICATED_ROUTES, method, path);
    }

    /**
     * Whether a request must prove a role with {@link OperatorKeyGuard#HEADER}
     * or a session token.
     *
     * <p>Path normalisation and the one-segment placeholder rule are shared with
     * {@link #isSupervisorRoute}: the gate has to agree with the dispatcher about
     * which path a request reaches, or a resolved {@code /..} would skip it.</p>
     *
     * @param method the request method, e.g. {@code GET}
     * @param path   the request path, e.g. {@code /api/v1/auth/role}
     */
    static boolean isOperatorRoute(String method, String path) {
        return matches(OPERATOR_ROUTES, method, path);
    }

    /**
     * The session id a session-scoped request acts on, or {@code null} when the
     * request is not one. This is the ownership filter's whole gate: a filter
     * cannot see which handler a request will reach, so the shape that decides
     * "is this a session's route?" has to be the same list the inventory test
     * keeps the controllers honest against.
     *
     * <p>The path is normalised first, for the same reason as
     * {@link #isSupervisorRoute}: a resolved {@code /..} has to match the
     * template it lands on, or the guard is on the spelling and not the route.</p>
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

    /**
     * Whether a request is on the short list a session with
     * {@code mustChangePassword} may still reach.
     *
     * <p>Evaluated on the normalised path like every other gate, so a resolved
     * {@code /..} cannot spell its way out of the confinement. This is the one
     * list {@link AuthContextFilter} consults when it refuses a flagged session,
     * which is why it lives here beside the route tiers rather than inside the
     * filter.</p>
     *
     * <p>{@code POST /api/v1/auth/login} is included even though it needs no
     * session: a browser still holding a flagged cookie has to be able to sign in
     * again — as itself once the password is replaced, or as a different account —
     * and refusing login because of a cookie the caller cannot clear would trap
     * the account rather than confine it. Login reads nothing from the cookie, so
     * allowing it grants no access.</p>
     */
    static boolean isPasswordChangeAllowedRoute(String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        String resolved = normalise(path);
        return ("GET /api/v1/auth/me".equals(method + " " + resolved)
                || "POST /api/v1/auth/change-password".equals(method + " " + resolved)
                || "POST /api/v1/auth/logout".equals(method + " " + resolved)
                || "POST /api/v1/auth/login".equals(method + " " + resolved));
    }

    /** One classified route: a verb and the path pattern it guards. */
    private record Route(String method, Pattern path) {
    }

    /** Shared matcher for a compiled tier: normalise once, then test the templates. */
    private static boolean matches(List<Route> routes, String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        String resolved = normalise(path);
        for (Route route : routes) {
            if (route.method().equals(method) && route.path().matcher(resolved).matches()) {
                return true;
            }
        }
        return false;
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
     * follow — so {@code PUT /api/v1/config/{workflowType}} cannot be widened by
     * a workflow type containing a slash, and the open
     * {@code GET /api/v1/config/{workflowType}} read is never caught by it. It is
     * captured rather than merely matched so {@link #sessionIdIn} can read the
     * segment back; the other tiers ignore the group.
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
     * Resolves {@code /.} and {@code /..} segments the way the dispatcher does,
     * so the inventory matches on the path the request is actually routed to
     * rather than the one it was written as.
     */
    static String normalise(String path) {
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
