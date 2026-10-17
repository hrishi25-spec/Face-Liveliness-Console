package io.mosip.liveness.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The route surface, written down so it cannot grow silently.
 *
 * <p>Every endpoint this service exposes is either reachable by the frame
 * client, an admin route that must refuse a caller without
 * {@code X-Admin-API-Key}, or an operator route that must refuse a caller whose
 * headers prove no role. This test enumerates the routes actually declared on
 * the controllers and fails when one of them is not in {@code RouteInventory},
 * so adding an endpoint forces the decision — the same reason
 * {@code scripts/check-project-boundaries.mjs} exists for documentation.</p>
 *
 * <p>The classification is not the whole story for a session-scoped route: those
 * are also only reachable by their owner, and {@code SessionOwnershipFilter}
 * decides that from {@code RouteInventory.SESSION_SCOPED}. A new endpoint under
 * {@code /api/v1/sessions/{sessionId}} therefore has to appear in that list too,
 * or it is guarded by nothing — which is what
 * {@link #everySessionScopedRouteOptsIntoOwnership} fails on, since the filter
 * itself has no per-handler call that could be forgotten.</p>
 *
 * <p>The split lives in {@code RouteInventory} (main code) because
 * {@code AdminKeyFilter} and {@code RateLimitFilter} both read it; this test
 * keeps that list honest against the controllers, and separately asserts that
 * the filter's matcher agrees with it. The refusal itself is tested per route:
 * {@code ConfigControllerFailClosedTest} covers the policy write,
 * {@code CalibrationControllerFailClosedTest} covers the sweep,
 * and each runs with the key unset to prove the route fails closed through the
 * filter.</p>
 *
 * <p>{@code GET /api/v1/metrics} and {@code GET /api/v1/diagnostics} sit on the
 * client side only in the sense that they need no key: metrics is aggregate
 * counters with nothing per-session or secret-derived, and diagnostics is
 * opt-in and answers loopback callers alone (anyone else gets the same 404).
 * The config audit reads ({@code GET /api/v1/config/audit} and
 * {@code /audit/verify}) are also client-facing — hiding history would be
 * security theatre when the current policy is already public. Neither is a
 * substitute for the admin key, and neither is treated as one.</p>
 */
class ApiRouteInventoryTest {

    @Test
    void everyDeclaredRouteIsClassified() {
        Set<String> discovered = new TreeSet<>(discoverRoutes().keySet());
        Set<String> classified = new TreeSet<>(RouteInventory.CLIENT_FACING);
        classified.addAll(RouteInventory.AUTHENTICATED);
        classified.addAll(RouteInventory.SUPERVISOR_KEYED);
        classified.addAll(RouteInventory.OPERATOR_KEYED);
        classified.addAll(RouteInventory.SESSION_SCOPED);

        Set<String> unclassified = new TreeSet<>(discovered);
        unclassified.removeAll(classified);
        assertTrue(unclassified.isEmpty(),
                "New route(s) not classified in RouteInventory: " + unclassified
                        + "\nAdd each to CLIENT_FACING (reachable anonymously), to "
                        + "AUTHENTICATED (any valid session; confined to this list when the "
                        + "account owes a password change), to SUPERVISOR_KEYED (refused by "
                        + "SupervisorGateFilter, metered by RateLimitFilter, and covered in a "
                        + "*FailClosedTest), or to OPERATOR_KEYED (refused by OperatorKeyFilter "
                        + "to a caller that proves no role).");

        Set<String> stale = new TreeSet<>(classified);
        stale.removeAll(discovered);
        assertTrue(stale.isEmpty(),
                "Classified route(s) no longer declared: " + stale
                        + " — remove them from RouteInventory too, so the list stays the truth.");

        // A route in two classification sets would be claimed by two gates (or,
        // worse, by a gated tier and the open client surface): the supervisor set is
        // refused by SupervisorGateFilter, the operator set by OperatorKeyFilter, the
        // authenticated set by AuthContextFilter, and CLIENT_FACING by none of them,
        // so no route may be in two of the four.
        assertDisjoint(RouteInventory.CLIENT_FACING, RouteInventory.AUTHENTICATED);
        assertDisjoint(RouteInventory.CLIENT_FACING, RouteInventory.SUPERVISOR_KEYED);
        assertDisjoint(RouteInventory.CLIENT_FACING, RouteInventory.OPERATOR_KEYED);
        assertDisjoint(RouteInventory.AUTHENTICATED, RouteInventory.SUPERVISOR_KEYED);
        assertDisjoint(RouteInventory.AUTHENTICATED, RouteInventory.OPERATOR_KEYED);
        assertDisjoint(RouteInventory.SUPERVISOR_KEYED, RouteInventory.OPERATOR_KEYED);
    }

    private static void assertDisjoint(Set<String> one, Set<String> other) {
        Set<String> both = new TreeSet<>(one);
        both.retainAll(other);
        assertTrue(both.isEmpty(), "A route cannot be classified twice: " + both);
    }

    /**
     * A route that carries a session id is session-scoped whether or not anyone
     * said so, and {@code SessionOwnershipFilter} only guards what
     * {@code RouteInventory.SESSION_SCOPED} lists — so a new endpoint under a
     * session path that is missing from that list is reachable by anybody. This is
     * the test that makes forgetting the opt-in a failure instead of an open
     * route: the filter has no call inside a handler to leave out, and no other
     * check would notice.
     */
    @Test
    void everySessionScopedRouteOptsIntoOwnership() {
        Set<String> discovered = new TreeSet<>(discoverRoutes().keySet());

        Set<String> unguarded = new TreeSet<>();
        for (String route : discovered) {
            if (pathOf(route).contains("/{sessionId}")
                    && !RouteInventory.SESSION_SCOPED.contains(route)) {
                unguarded.add(route);
            }
        }
        assertTrue(unguarded.isEmpty(),
                "Session-scoped route(s) missing from RouteInventory.SESSION_SCOPED: " + unguarded
                        + " — add each there, or nothing checks who may reach it: the "
                        + "ownership filter guards the list, not the path shape.");

        Set<String> stale = new TreeSet<>(RouteInventory.SESSION_SCOPED);
        stale.removeAll(discovered);
        assertTrue(stale.isEmpty(),
                "Session-scoped route(s) no longer declared: " + stale
                        + " — remove them from RouteInventory too, so the list stays the truth.");

        // A session-scoped route is reached with the owner's own header, never the
        // admin key: if one lands in ADMIN_KEYED the two filters both claim it and
        // the owner cannot reach their own session.
        Set<String> keyed = new TreeSet<>(RouteInventory.SESSION_SCOPED);
        keyed.retainAll(RouteInventory.SUPERVISOR_KEYED);
        assertTrue(keyed.isEmpty(), "A session-scoped route cannot also be admin-keyed: " + keyed);
    }

    /**
     * The ownership filter reads the session id off the path through
     * {@code RouteInventory.sessionIdIn}, so its matcher has to agree with the
     * list: every session-scoped route must yield its own id, and no other route
     * may yield one — a filter that guards too little is an open route, and one
     * that guards too much refuses a request that has no session to own.
     */
    @Test
    void theOwnershipGuardMatchesExactlyTheSessionScopedRoutes() {
        for (String route : RouteInventory.SESSION_SCOPED) {
            assertEquals(SESSION_ID,
                    RouteInventory.sessionIdIn(verbOf(route), sessionPathOf(route)),
                    "SessionOwnershipFilter would not read the id off " + route
                            + " — listed as session-scoped, but the id is not matched");
        }

        Set<String> others = new TreeSet<>(RouteInventory.CLIENT_FACING);
        others.addAll(RouteInventory.AUTHENTICATED);
        others.addAll(RouteInventory.SUPERVISOR_KEYED);
        others.addAll(RouteInventory.OPERATOR_KEYED);
        others.removeAll(RouteInventory.SESSION_SCOPED);
        for (String route : others) {
            assertNull(RouteInventory.sessionIdIn(verbOf(route), samplePathOf(route)),
                    "SessionOwnershipFilter would refuse " + route
                            + " — a route with no session of its own must never match");
        }
    }

    /**
     * The shape of the gate, not just its agreement with the list: what is not a
     * session-scoped request must pass, and what looks like one must not be
     * reachable by spelling it differently.
     */
    @Test
    void onlyASessionScopedRequestIsGuarded() {
        // Creation has no session to own yet — it is the route that decides the owner.
        assertNull(RouteInventory.sessionIdIn("POST", "/api/v1/sessions"));
        // The verb is part of the route, and a browser's preflight OPTIONS cannot
        // carry the ownership claim (custom headers are not sent on it), so it has
        // to reach the CORS processor rather than being refused as a stranger.
        assertNull(RouteInventory.sessionIdIn("OPTIONS", sessionPathOf("POST /api/v1/sessions/{sessionId}/frames")));
        assertNull(RouteInventory.sessionIdIn("PUT", sessionPathOf("GET /api/v1/sessions/{sessionId}")));
        // A resolved path lands on the route it resolves to, as with the admin guard.
        assertEquals(SESSION_ID, RouteInventory.sessionIdIn("GET", "/api/v1/sessions/./" + SESSION_ID));
        // And a walk that leaves the session tree lands on a path with no id in it.
        assertNull(RouteInventory.sessionIdIn("GET", "/api/v1/config/../sessions"));
        // The id is one segment: a deeper path is not the route above it.
        assertNull(RouteInventory.sessionIdIn("POST",
                "/api/v1/sessions/" + SESSION_ID + "/frames/extra"));
        // And a path with no id at all is nobody's session.
        assertNull(RouteInventory.sessionIdIn("GET", "/api/v1/sessions"));
        assertNull(RouteInventory.sessionIdIn("GET", "/api/v1/sessions/"));
    }

    /**
     * The route inventory is what the gate reads, so the gate's own matcher has to
     * agree with it: every supervisor route must be one it refuses, and every route
     * outside that tier must be one it leaves alone. Without this, a typo in a
     * pattern ({@code PUT /api/v1/config} rather than
     * {@code PUT /api/v1/config/{workflowType}}) would leave the list correct and
     * the gate open — the exact failure this class exists to prevent.
     */
    @Test
    void theGuardMatchesExactlyTheSupervisorKeyedRoutes() {
        for (String route : RouteInventory.SUPERVISOR_KEYED) {
            assertTrue(RouteInventory.isSupervisorRoute(verbOf(route), samplePathOf(route)),
                    "SupervisorGateFilter would not refuse " + route
                            + " — listed as supervisor-keyed, but not matched");
        }
        Set<String> others = new TreeSet<>(RouteInventory.CLIENT_FACING);
        others.addAll(RouteInventory.AUTHENTICATED);
        others.addAll(RouteInventory.OPERATOR_KEYED);
        for (String route : others) {
            assertFalse(RouteInventory.isSupervisorRoute(verbOf(route), samplePathOf(route)),
                    "SupervisorGateFilter would refuse " + route
                            + " — only the supervisor tier carries that gate");
        }
    }

    /**
     * The authenticated tier's matcher has to agree with its list for the same
     * reason, and one disagreement costs more here than anywhere else: this list is
     * also the short list a session that owes a password change is confined to, so a
     * route missing from it is either unguarded or unreachable rather than merely
     * misclassified.
     */
    @Test
    void theAuthenticatedTierMatchesExactlyItsList() {
        for (String route : RouteInventory.AUTHENTICATED) {
            assertTrue(RouteInventory.isAuthenticatedRoute(verbOf(route), samplePathOf(route)),
                    "AuthContextFilter would not require a session for " + route
                            + " — listed as authenticated, but not matched");
            assertTrue(RouteInventory.isPasswordChangeAllowedRoute(verbOf(route), samplePathOf(route)),
                    route + " must be reachable by an account that owes a password change, "
                            + "or that account cannot replace its own password");
        }
        Set<String> others = new TreeSet<>(RouteInventory.CLIENT_FACING);
        others.addAll(RouteInventory.SUPERVISOR_KEYED);
        others.addAll(RouteInventory.OPERATOR_KEYED);
        for (String route : others) {
            assertFalse(RouteInventory.isAuthenticatedRoute(verbOf(route), samplePathOf(route)),
                    "AuthContextFilter would demand a session for " + route);
        }
    }

    /**
     * The confinement a flagged account lives under, pinned so it cannot widen by
     * accident: the three routes that let it fix the situation, plus login.
     */
    @Test
    void anOwedPasswordChangeMayReachOnlyTheRoutesThatFixIt() {
        assertTrue(RouteInventory.isPasswordChangeAllowedRoute("GET", "/api/v1/auth/me"));
        assertTrue(RouteInventory.isPasswordChangeAllowedRoute("POST", "/api/v1/auth/change-password"));
        assertTrue(RouteInventory.isPasswordChangeAllowedRoute("POST", "/api/v1/auth/logout"));
        // Login is allowed because a browser holding a flagged cookie must be able
        // to sign in again; it reads nothing from that cookie.
        assertTrue(RouteInventory.isPasswordChangeAllowedRoute("POST", "/api/v1/auth/login"));

        // Everything else is refused — including routes this account's own role
        // could otherwise reach, which is the point of the confinement.
        assertFalse(RouteInventory.isPasswordChangeAllowedRoute("GET", "/api/v1/sessions"));
        assertFalse(RouteInventory.isPasswordChangeAllowedRoute("PUT", "/api/v1/config/RESIDENT"));
        assertFalse(RouteInventory.isPasswordChangeAllowedRoute("GET", "/api/v1/users"));
        assertFalse(RouteInventory.isPasswordChangeAllowedRoute("POST", "/api/v1/sessions"));
        assertFalse(RouteInventory.isPasswordChangeAllowedRoute("GET", "/api/v1/metrics"));
        assertFalse(RouteInventory.isPasswordChangeAllowedRoute(null, null));
        // A resolved walk has to be evaluated on the path it lands on.
        assertFalse(RouteInventory.isPasswordChangeAllowedRoute("GET", "/api/v1/auth/../sessions"));
        assertTrue(RouteInventory.isPasswordChangeAllowedRoute("GET", "/api/v1/auth/./me"));
    }

    /**
     * The operator gate is the same shape as the admin one, and for the same
     * reason: the filter guards what the list says, so a list that disagrees with
     * the matcher is an open route (or a route nobody can reach).
     */
    @Test
    void theGuardMatchesExactlyTheOperatorKeyedRoutes() {
        for (String route : RouteInventory.OPERATOR_KEYED) {
            assertTrue(RouteInventory.isOperatorRoute(verbOf(route), samplePathOf(route)),
                    "OperatorKeyFilter would not refuse " + route
                            + " — listed as operator-keyed, but not matched");
        }
        Set<String> others = new TreeSet<>(RouteInventory.CLIENT_FACING);
        others.addAll(RouteInventory.AUTHENTICATED);
        others.addAll(RouteInventory.SUPERVISOR_KEYED);
        for (String route : others) {
            assertFalse(RouteInventory.isOperatorRoute(verbOf(route), samplePathOf(route)),
                    "OperatorKeyFilter would refuse " + route
                            + " — only operator-keyed routes carry the operator gate");
        }

        // And the two keyed gates never claim the same request: a route in both
        // lists would be decided by whichever filter ran first.
        for (String route : RouteInventory.OPERATOR_KEYED) {
            assertFalse(RouteInventory.isSupervisorRoute(verbOf(route), samplePathOf(route)),
                    route + " is claimed by the supervisor and operator gates alike");
        }

        // The operator key is refused on the supervisor tier by construction: the
        // route is not operator-keyed, so OperatorKeyFilter never runs on it and no
        // filter turns the key into a principal there.
        for (String route : RouteInventory.SUPERVISOR_KEYED) {
            assertFalse(RouteInventory.isOperatorRoute(verbOf(route), samplePathOf(route)),
                    route + " must not be reachable with the operator key");
        }
    }

    /**
     * The shape of the operator gate, not just its agreement with the list. The
     * role probe is reachable only through the gate, and a browser's preflight
     * (which cannot carry the custom header) must still reach the CORS processor.
     */
    @Test
    void onlyAnOperatorRouteIsOperatorGuarded() {
        assertTrue(RouteInventory.isOperatorRoute("GET", "/api/v1/auth/role"));
        // The operator feeds are gated the same way, and they are collections:
        // no session id of their own, so they must never be, and never be taken
        // for, a session-scoped route — a list of other people's sessions is
        // guarded by the key, not by an ownership claim the caller cannot present.
        assertTrue(RouteInventory.isOperatorRoute("GET", "/api/v1/sessions"));
        assertTrue(RouteInventory.isOperatorRoute("GET", "/api/v1/rejections"));
        assertFalse(RouteInventory.isOperatorRoute("POST", "/api/v1/sessions"),
                "creating a session is the client's own route and stays open");
        assertNull(RouteInventory.sessionIdIn("GET", "/api/v1/sessions"));
        assertNull(RouteInventory.sessionIdIn("GET", "/api/v1/rejections"));
        assertTrue(RouteInventory.isOperatorRoute("GET", "/api/v1/auth/./role"));
        assertFalse(RouteInventory.isOperatorRoute("POST", "/api/v1/auth/role"),
                "the method is part of the route");
        assertFalse(RouteInventory.isOperatorRoute("OPTIONS", "/api/v1/auth/role"),
                "a preflight cannot carry the key, so it must reach the CORS processor");
        assertFalse(RouteInventory.isOperatorRoute("GET", "/api/v1/auth/role/extra"),
                "a deeper path is not the route above it");
        assertFalse(RouteInventory.isOperatorRoute("GET", "/api/v1/auth"),
                "the path prefix alone is not the probe");
        // A walk out of the auth tree is matched on the path it resolves to, so
        // it lands somewhere ungated when that path is.
        assertFalse(RouteInventory.isOperatorRoute("GET", "/api/v1/auth/../metrics"));
        // …and when it resolves onto a gated route it is gated like any other
        // request to it: /auth/../sessions is the operator's session feed.
        assertTrue(RouteInventory.isOperatorRoute("GET", "/api/v1/auth/../sessions"));
        // …and one that resolves back into the probe is that probe, not a way round it.
        assertTrue(RouteInventory.isOperatorRoute("GET", "/api/v1/sessions/../auth/role"));
        assertFalse(RouteInventory.isOperatorRoute(null, null));
    }

    @Test
    void aVariableRouteGuardsOneSegmentNotTheWholeTree() {
        // {workflowType} is one segment, so the keyed write cannot catch the open
        // read beside it, and a slash smuggled through the variable does not widen
        // the guard into something it was never classified as.
        assertTrue(RouteInventory.isSupervisorRoute("PUT", "/api/v1/config/RESIDENT"));
        assertFalse(RouteInventory.isSupervisorRoute("GET", "/api/v1/config/RESIDENT"),
                "the policy read is how a client learns what it is judged against");
        assertFalse(RouteInventory.isSupervisorRoute("GET", "/api/v1/config/RESIDENT/effective"));
        assertFalse(RouteInventory.isSupervisorRoute("POST", "/api/v1/config/RESIDENT"),
                "the path prefix alone is not the write");
        // Audit reads are open (CLIENT_FACING), not admin-keyed
        assertFalse(RouteInventory.isSupervisorRoute("GET", "/api/v1/config/audit"));
        assertFalse(RouteInventory.isSupervisorRoute("GET", "/api/v1/config/audit/verify"));
        assertFalse(RouteInventory.isSupervisorRoute("GET", "/api/v1/config/audit/extra"));
    }

    @Test
    void aResolvedPathCannotWalkPastTheGuard() {
        // The dispatcher resolves /. and /.. before routing, so a guard comparing
        // raw URIs would refuse the spelled-out spelling and let the resolved one
        // through to a handler that no longer checks for itself.
        // Audit reads are open (CLIENT_FACING), so these resolve to open paths.
        assertFalse(RouteInventory.isSupervisorRoute("GET", "/api/v1/config/./audit"));
        // ".." pops one segment, so this resolves to /api/v1/config/audit/verify (open).
        assertFalse(RouteInventory.isSupervisorRoute("GET", "/api/v1/config/../config/audit/verify"));
        // And a walk that leaves the guarded tree lands somewhere open, not guarded.
        assertFalse(RouteInventory.isSupervisorRoute("GET", "/api/v1/config/../sessions"));
        // The actual supervisor-keyed route (config write) is still guarded.
        assertTrue(RouteInventory.isSupervisorRoute("PUT", "/api/v1/config/./RESIDENT"));
        assertTrue(RouteInventory.isSupervisorRoute("PUT", "/api/v1/config/../config/RESIDENT"));
    }

    /** A fixed, well-formed session id, so a matched segment is readable as the id. */
    private static final String SESSION_ID = "5c8f1c9e-0000-4000-8000-000000000001";

    private static String verbOf(String route) {
        return route.substring(0, route.indexOf(' '));
    }

    /** The route's path template, without its verb. */
    private static String pathOf(String route) {
        return route.substring(route.indexOf(' ') + 1);
    }

    /** The template with its variables filled in, as a real request would spell it. */
    private static String samplePathOf(String route) {
        return pathOf(route).replaceAll("\\{[^}]+}", "sample");
    }

    /** The template with {@code {sessionId}} filled in, as a real session request would spell it. */
    private static String sessionPathOf(String route) {
        return pathOf(route).replace("{sessionId}", SESSION_ID);
    }

    /** Every mapping declared on every controller in the engine, mapped to its handler. */
    private static Map<String, Method> discoverRoutes() {
        Map<String, Method> routes = new TreeMap<>();
        for (Class<?> controller : controllers()) {
            String base = basePathOf(controller.getAnnotation(RequestMapping.class));
            for (Method method : controller.getDeclaredMethods()) {
                for (String route : routesOf(method, base)) {
                    Method previous = routes.put(route, method);
                    assertTrue(previous == null,
                            "Two handlers claim " + route + ": " + previous + " and " + method);
                }
            }
        }
        return routes;
    }

    private static Set<Class<?>> controllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<Class<?>> found = new TreeSet<>((a, b) -> a.getName().compareTo(b.getName()));
        for (BeanDefinition definition : scanner.findCandidateComponents("io.mosip.liveness")) {
            try {
                found.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "Scanned class is not loadable: " + definition.getBeanClassName(), e);
            }
        }
        assertFalse(found.isEmpty(), "No @RestController found under io.mosip.liveness");
        return found;
    }

    /** Routes a single handler method declares, prefixed with the controller's base path. */
    private static Set<String> routesOf(Method method, String base) {
        Set<String> routes = new TreeSet<>();
        addMapped(routes, method, base, method.getAnnotation(GetMapping.class), "GET");
        addMapped(routes, method, base, method.getAnnotation(PostMapping.class), "POST");
        addMapped(routes, method, base, method.getAnnotation(PutMapping.class), "PUT");
        addMapped(routes, method, base, method.getAnnotation(DeleteMapping.class), "DELETE");
        addMapped(routes, method, base, method.getAnnotation(PatchMapping.class), "PATCH");

        RequestMapping mapping = method.getAnnotation(RequestMapping.class);
        if (mapping == null) {
            return routes;
        }
        String[] verbs = new String[mapping.method().length];
        for (int i = 0; i < mapping.method().length; i++) {
            verbs[i] = mapping.method()[i].name();
        }
        if (verbs.length == 0) {
            verbs = new String[] {"ANY"};
        }
        for (String verb : verbs) {
            for (String path : pathsOf(mapping)) {
                routes.add(verb + " " + join(base, path));
            }
        }
        return routes;
    }

    private static void addMapped(Set<String> routes, Method method, String base,
                                  Annotation mapping, String verb) {
        if (mapping == null) {
            return;
        }
        String[] paths = pathsOf(mapping);
        if (paths.length == 0) {
            routes.add(verb + " " + base);
            return;
        }
        for (String path : paths) {
            routes.add(verb + " " + join(base, path));
        }
    }

    /** {@code path()} and {@code value()} are aliases; Spring's own does the same. */
    private static String[] pathsOf(Annotation mapping) {
        if (mapping instanceof GetMapping m) {
            return m.path().length > 0 ? m.path() : m.value();
        }
        if (mapping instanceof PostMapping m) {
            return m.path().length > 0 ? m.path() : m.value();
        }
        if (mapping instanceof PutMapping m) {
            return m.path().length > 0 ? m.path() : m.value();
        }
        if (mapping instanceof DeleteMapping m) {
            return m.path().length > 0 ? m.path() : m.value();
        }
        if (mapping instanceof PatchMapping m) {
            return m.path().length > 0 ? m.path() : m.value();
        }
        if (mapping instanceof RequestMapping m) {
            return m.path().length > 0 ? m.path() : m.value();
        }
        return new String[0];
    }

    private static String basePathOf(RequestMapping mapping) {
        if (mapping == null) {
            return "";
        }
        String[] paths = mapping.path().length > 0 ? mapping.path() : mapping.value();
        return paths.length > 0 ? paths[0] : "";
    }

    private static String join(String base, String path) {
        String stem = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String combined = stem + (path.startsWith("/") ? path : "/" + path);
        return combined.isEmpty() ? "/" : combined;
    }
}
