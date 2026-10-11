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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The route surface, written down so it cannot grow silently.
 *
 * <p>Every endpoint this service exposes is either reachable by the frame
 * client or it is an operator route that must refuse a caller without
 * {@code X-Admin-API-Key}. This test enumerates the routes actually declared on
 * the controllers and fails when one of them is not in
 * {@code RouteInventory}'s two lists, so adding an endpoint forces the
 * decision — the same reason {@code scripts/check-project-boundaries.mjs}
 * exists for documentation.</p>
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
        classified.addAll(RouteInventory.ADMIN_KEYED);

        Set<String> unclassified = new TreeSet<>(discovered);
        unclassified.removeAll(classified);
        assertTrue(unclassified.isEmpty(),
                "New route(s) not classified in RouteInventory: " + unclassified
                        + "\nAdd each to CLIENT_FACING (reachable by the frame client) or to "
                        + "ADMIN_KEYED (refused by AdminKeyFilter, metered by RateLimitFilter, "
                        + "and covered in a *FailClosedTest).");

        Set<String> stale = new TreeSet<>(classified);
        stale.removeAll(discovered);
        assertTrue(stale.isEmpty(),
                "Classified route(s) no longer declared: " + stale
                        + " — remove them from RouteInventory too, so the list stays the truth.");

        Set<String> both = new TreeSet<>(RouteInventory.CLIENT_FACING);
        both.retainAll(RouteInventory.ADMIN_KEYED);
        assertTrue(both.isEmpty(), "A route cannot be both open and keyed: " + both);
    }

    /**
     * The route inventory is what the filter reads, so the filter's own matcher
     * has to agree with it: every admin-keyed route must be one it refuses, and
     * every client-facing route must be one it leaves alone. Without this, a
     * typo in a pattern ({@code PUT /api/v1/config} rather than
     * {@code PUT /api/v1/config/{workflowType}}) would leave the list correct and
     * the guard open — the exact failure this refactor exists to remove.
     */
    @Test
    void theGuardMatchesExactlyTheAdminKeyedRoutes() {
        for (String route : RouteInventory.ADMIN_KEYED) {
            assertTrue(RouteInventory.isAdminRoute(verbOf(route), samplePathOf(route)),
                    "AdminKeyFilter would not refuse " + route
                            + " — listed as admin-keyed, but not matched");
        }
        for (String route : RouteInventory.CLIENT_FACING) {
            assertFalse(RouteInventory.isAdminRoute(verbOf(route), samplePathOf(route)),
                    "AdminKeyFilter would refuse " + route
                            + " — a client-facing route must never match the guard");
        }
    }

    @Test
    void aVariableRouteGuardsOneSegmentNotTheWholeTree() {
        // {workflowType} is one segment, so the keyed write cannot catch the open
        // read beside it, and a slash smuggled through the variable does not widen
        // the guard into something it was never classified as.
        assertTrue(RouteInventory.isAdminRoute("PUT", "/api/v1/config/RESIDENT"));
        assertFalse(RouteInventory.isAdminRoute("GET", "/api/v1/config/RESIDENT"),
                "the policy read is how a client learns what it is judged against");
        assertFalse(RouteInventory.isAdminRoute("GET", "/api/v1/config/RESIDENT/effective"));
        assertFalse(RouteInventory.isAdminRoute("POST", "/api/v1/config/RESIDENT"),
                "the path prefix alone is not the write");
        // Audit reads are open (CLIENT_FACING), not admin-keyed
        assertFalse(RouteInventory.isAdminRoute("GET", "/api/v1/config/audit"));
        assertFalse(RouteInventory.isAdminRoute("GET", "/api/v1/config/audit/verify"));
        assertFalse(RouteInventory.isAdminRoute("GET", "/api/v1/config/audit/extra"));
    }

    @Test
    void aResolvedPathCannotWalkPastTheGuard() {
        // The dispatcher resolves /. and /.. before routing, so a guard comparing
        // raw URIs would refuse the spelled-out spelling and let the resolved one
        // through to a handler that no longer checks for itself.
        // Audit reads are open (CLIENT_FACING), so these resolve to open paths.
        assertFalse(RouteInventory.isAdminRoute("GET", "/api/v1/config/./audit"));
        // ".." pops one segment, so this resolves to /api/v1/config/audit/verify (open).
        assertFalse(RouteInventory.isAdminRoute("GET", "/api/v1/config/../config/audit/verify"));
        // And a walk that leaves the guarded tree lands somewhere open, not guarded.
        assertFalse(RouteInventory.isAdminRoute("GET", "/api/v1/config/../sessions"));
        // The actual admin-keyed route (config write) is still guarded.
        assertTrue(RouteInventory.isAdminRoute("PUT", "/api/v1/config/./RESIDENT"));
        assertTrue(RouteInventory.isAdminRoute("PUT", "/api/v1/config/../config/RESIDENT"));
    }

    private static String verbOf(String route) {
        return route.substring(0, route.indexOf(' '));
    }

    /** The template with its variables filled in, as a real request would spell it. */
    private static String samplePathOf(String route) {
        return route.substring(route.indexOf(' ') + 1).replaceAll("\\{[^}]+}", "sample");
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
