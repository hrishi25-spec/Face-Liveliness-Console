package io.mosip.liveness.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The service's route surface, written down once and read by everything that
 * must treat a route differently: {@link AdminKeyFilter} refuses an
 * admin-keyed route when the caller has no key, and {@link RateLimitFilter}
 * meters the same routes against the operator budget.
 *
 * <p>It lives in main rather than only in {@code ApiRouteInventoryTest}
 * because a filter has no compile-time view of a list that exists solely in
 * test code. Before this class, {@code RateLimitFilter} carried its own copy
 * of the four admin paths — the documented price of that gap — so a route
 * could gain the admin key in one list and not the other, and a test could
 * report the drift but not prevent it.</p>
 *
 * <p>The test still enforces both halves: every mapping declared on every
 * controller must appear in exactly one of the two sets, so adding an endpoint
 * forces the decision of who may reach it.</p>
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

    /** A {@code {name}} placeholder, matched as exactly one path segment. */
    private static final Pattern VARIABLE = Pattern.compile("\\{[^}]+}");

    /**
     * Compiled once; the filter runs this on every request.
     *
     * <p>Declared after {@link #VARIABLE}, which it uses: static initialisers
     * run in textual order, so the other way round this class fails to load
     * with an NPE — which is how every filter that reads it would fail.</p>
     */
    private static final List<AdminRoute> ADMIN_ROUTES = compile(ADMIN_KEYED);

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
        for (AdminRoute route : ADMIN_ROUTES) {
            if (route.method().equals(method) && route.path().matcher(resolved).matches()) {
                return true;
            }
        }
        return false;
    }

    /** One classified route: a verb and the path pattern it guards. */
    private record AdminRoute(String method, Pattern path) {
    }

    private static List<AdminRoute> compile(Set<String> routes) {
        List<AdminRoute> compiled = new ArrayList<>(routes.size());
        for (String route : routes) {
            int verb = route.indexOf(' ');
            if (verb < 1) {
                throw new IllegalStateException(
                        "RouteInventory entry must be \"METHOD /path\": " + route);
            }
            compiled.add(new AdminRoute(route.substring(0, verb),
                    pathPattern(route.substring(verb + 1))));
        }
        return List.copyOf(compiled);
    }

    /**
     * Compiles a route template to its matcher. A {@code {name}} placeholder
     * matches exactly one path segment — the same rule Spring's URI templates
     * follow — so {@code PUT /api/v1/config/{workflowType}} cannot be widened
     * by a workflow type containing a slash, and the open
     * {@code GET /api/v1/config/{workflowType}} read is never caught by it.
     */
    private static Pattern pathPattern(String template) {
        // Trailing slash is optional rather than significant: Spring routes
        // both spellings, so the guard has to as well.
        String body = template.endsWith("/") ? template.substring(0, template.length() - 1) : template;
        StringBuilder regex = new StringBuilder("^");
        Matcher variable = VARIABLE.matcher(body);
        int last = 0;
        while (variable.find()) {
            regex.append(Pattern.quote(body.substring(last, variable.start()))).append("[^/]+");
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
