package io.mosip.liveness.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flood protection for the three things a caller can spend:
 *
 * <ul>
 *   <li>{@code POST /api/v1/sessions} — keyed by <b>client IP</b>: session
 *       creation is the row-flood vector (one row + audit side effects per
 *       request).</li>
 *   <li>{@code POST /api/v1/sessions/{id}/frames} and
 *       {@code POST /api/v1/sessions/{id}/challenges/validate} — keyed by
 *       <b>session id</b>, sharing one budget: both run face detection + PAD +
 *       model scoring, and one session represents one capture stream. A single
 *       rogue session can no longer burn the CPU budget of the whole service.</li>
 *   <li>The operator-key routes ({@link RouteInventory#OPERATOR_KEYED}) — keyed by
 *       <b>client IP</b>, on the same limit and window as session creation: every
 *       request there is a key attempt, and an unauthenticated caller guessing the
 *       key should spend the flood budget that already exists rather than get
 *       unlimited constant-time comparisons. No second limiter is involved — the
 *       same fixed-window machinery with one more rule.</li>
 *   <li>{@code POST /api/v1/auth/login} — keyed by <b>client IP</b>, on that same
 *       per-IP budget again. A password guess is the most valuable attempt anyone
 *       can make against this service, so it is bounded by the budget that
 *       already exists rather than left to run at full speed; a legitimate client
 *       that signs in a normal number of times never approaches it.</li>
 * </ul>
 *
 * <p>Runs after {@link SecurityHeadersFilter}, so the 429 response carries the
 * security headers, and before the dispatcher, so rejected requests never reach
 * Jackson or the database. Fixed window counters are in-memory — the service is
 * a single local process, so there is nothing shared to coordinate.</p>
 *
 * <p><b>Order.</b> Late, but twenty below {@code LOWEST_PRECEDENCE} rather than
 * at it, so the filters that must not run ahead of it have room to be later than
 * it: {@link SupervisorGateFilter} and {@link OperatorKeyFilter} answer a keyed
 * request, and an over-budget request must be refused as a flood *before* one of
 * them spends a comparison on it — and, for the operator gate, before it refuses
 * the request at all, since a filter that stops the chain is a request the budget
 * never sees. Writing the offsets as {@code + n} instead would be the bug: the
 * arithmetic is on {@code Integer.MAX_VALUE}, so {@code LOWEST_PRECEDENCE + 5}
 * wraps around and sorts *ahead* of every filter in the service.</p>
 *
 * <p>The client IP behind {@code POST /sessions} is whatever
 * {@link ClientIpResolver} says it is — the same resolution the config audit
 * trail records, so a budget key and an audit entry can never name different
 * hosts for the same request.</p>
 *
 * <p>ponytail: a multi-node deployment would need a shared counter store (upgrade
 * path: Redis / caffeine-limiter); fixed windows are per-process.</p>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * The session-scoped write paths: /api/v1/sessions/{id}/frames,
     * .../challenges/validate, and the manually requested extra check at
     * .../active-liveness. They share one per-session budget, so a client cannot
     * escape the limiter by moving its traffic to another write route.
     */
    private static final Pattern SESSION_SCOPED = Pattern.compile(
            "^/api/v1/sessions/([^/]+)/(frames|challenges/validate|active-liveness)/?$");
    private static final String SESSION_CREATE = "/api/v1/sessions";

    /**
     * The sign-in path. Metered here rather than in the handler so a refused
     * attempt never reaches BCrypt — the limiter is what stops password guessing
     * from also being a CPU-exhaustion vector, since a verification deliberately
     * costs ~300 ms.
     */
    private static final String AUTH_LOGIN = "/api/v1/auth/login";

    /**
     * Budget names, echoed in {@code X-RateLimit-Bucket} so a client can tell
     * the independent budgets apart.
     */
    static final String BUCKET_SESSION_CREATE = "session-create";
    static final String BUCKET_FRAMES = "frames";
    static final String BUCKET_OPERATOR_KEY = "operator-key";
    static final String BUCKET_AUTH_LOGIN = "auth-login";
    static final String H_BUCKET = "X-RateLimit-Bucket";
    static final String H_LIMIT = "X-RateLimit-Limit";
    static final String H_REMAINING = "X-RateLimit-Remaining";
    static final String H_RESET = "X-RateLimit-Reset";

    // Field initializers mirror the application.yml defaults so the filter is
    // usable standalone (unit tests); @Value overrides them in a running context.
    @Value("${mosip.security.rate-limit.enabled:true}")
    private boolean enabled = true;

    @Value("${mosip.security.rate-limit.session-create-limit:30}")
    private int sessionCreateLimit = 30;

    @Value("${mosip.security.rate-limit.session-create-window-ms:60000}")
    private long sessionCreateWindowMs = 60_000L;

    @Value("${mosip.security.rate-limit.frame-limit:60}")
    private int frameLimit = 60;

    @Value("${mosip.security.rate-limit.frame-window-ms:10000}")
    private long frameWindowMs = 10_000L;

    /** Resolves the caller's address; see {@link ClientIpResolver}. */
    private final ClientIpResolver clientIpResolver;

    /** Upper bound on live window entries; see {@link #pruneIfNeeded(long)}. */
    static final int MAX_WINDOW_KEYS = 8192;

    /** key -> {windowStartMillis, count} */
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

    /** When the last O(n) sweep ran, so a busy map is not swept on every request. */
    private volatile long lastPruneMillis;

    /** Per-rule tallies surfaced by {@code /api/v1/metrics}. */
    private final RateLimitCounters counters;

    /**
     * Time source for the fixed windows. Injectable so window rollover can be
     * tested by moving time rather than by sleeping — the previous
     * {@code System.currentTimeMillis()} could only be exercised by actually
     * waiting out a 60-second window.
     */
    private final Clock clock;

    /**
     * ponytail: optional on purpose. {@code @WebMvcTest} slices instantiate every
     * {@code Filter} bean but not ordinary components, so a required dependency
     * here would fail those contexts for no reason; the fallback only matters in
     * a slice, where the real counters bean is never present anyway. The
     * application always has one.
     *
     * <p>The same reasoning applies to the {@link Clock}: an {@link ObjectProvider}
     * rather than a required bean, so a slice without {@code AppConfig} still
     * gets the real system clock instead of failing to start.
     */
    public RateLimitFilter(RateLimitCounters counters) {
        this(counters, Clock.systemUTC(), new ClientIpResolver());
    }

    @Autowired
    public RateLimitFilter(@Autowired(required = false) RateLimitCounters counters,
                           ObjectProvider<Clock> clockProvider,
                           ObjectProvider<ClientIpResolver> resolverProvider) {
        this(counters, clockProvider.getIfAvailable(Clock::systemUTC),
                resolverProvider.getIfAvailable(ClientIpResolver::new));
    }

    /** Package-private so the unit test can drive window rollover on a fake clock. */
    RateLimitFilter(RateLimitCounters counters, Clock clock) {
        this(counters, clock, new ClientIpResolver());
    }

    RateLimitFilter(RateLimitCounters counters, Clock clock, ClientIpResolver clientIpResolver) {
        this.counters = counters != null ? counters : new RateLimitCounters();
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.clientIpResolver = clientIpResolver != null ? clientIpResolver : new ClientIpResolver();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String method = request.getMethod();
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        // Match the path the dispatcher will route to, not the spelling the
        // caller used: `//api/v1/sessions`, `/api/v1/./sessions` and a trailing
        // slash all reach the same handler, and must spend the same budget.
        String path = RouteInventory.normalise(request.getRequestURI());
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String key;
        int limit;
        long windowMs;
        String bucket;
        String message;
        Rule rule;

        if (RouteInventory.isOperatorRoute(method, path)) {
            // Keyed by IP, and on the per-IP budget that session creation already
            // uses: the same limit and window, so there is nothing new to tune and
            // no second limiter to reason about. Every request here is a key
            // attempt — a success is an authenticated caller pacing itself, a
            // failure is a guess — so counting the attempts bounds guessing without
            // a special case for the outcome.
            key = "operator-key:" + clientIpResolver.resolve(request);
            limit = sessionCreateLimit;
            windowMs = sessionCreateWindowMs;
            bucket = BUCKET_OPERATOR_KEY;
            message = "Too many API key attempts from this address. Try again later.";
            rule = Rule.OPERATOR_KEY;
        } else if (AUTH_LOGIN.equals(path)) {
            // The same per-IP budget as session creation and key attempts: one
            // number to tune, and no new limiter to reason about. Every request
            // here is a credential attempt, so counting the attempts bounds
            // guessing without inspecting the outcome — the filter never sees a
            // password and never needs to.
            key = "login:" + clientIpResolver.resolve(request);
            limit = sessionCreateLimit;
            windowMs = sessionCreateWindowMs;
            bucket = BUCKET_AUTH_LOGIN;
            message = "Too many sign-in attempts from this address. Try again later.";
            rule = Rule.AUTH_LOGIN;
        } else if (!"POST".equals(method)) {
            // Everything else that is not POST is out of scope: only the operator
            // routes above are metered on a GET.
            filterChain.doFilter(request, response);
            return;
        } else if (SESSION_CREATE.equals(path)) {
            key = "create:" + clientIpResolver.resolve(request);
            limit = sessionCreateLimit;
            windowMs = sessionCreateWindowMs;
            bucket = BUCKET_SESSION_CREATE;
            message = "Too many sessions created from this address. Try again later.";
            rule = Rule.SESSION_CREATE;
        } else {
            Matcher m = SESSION_SCOPED.matcher(path);
            if (!m.matches()) {
                filterChain.doFilter(request, response);
                return;
            }
            key = "session:" + m.group(1);
            limit = frameLimit;
            windowMs = frameWindowMs;
            bucket = BUCKET_FRAMES;
            message = "Too many requests for this session. Slow down and retry.";
            rule = Rule.FRAMES;
        }

        long now = clock.millis();
        pruneIfNeeded(now);
        // Hard ceiling, after the sweep: a flood of *distinct* session ids (or
        // spoofed addresses behind a misconfigured proxy) could otherwise add a
        // key per request and grow this map without bound inside one window.
        // At the ceiling a key with no live window is refused rather than
        // admitted, so memory stays flat under the flood that caused it.
        if (windows.size() >= MAX_WINDOW_KEYS && !windows.containsKey(key)) {
            record(rule, false);
            response.setHeader(H_BUCKET, bucket);
            response.setHeader(H_LIMIT, String.valueOf(limit));
            response.setHeader(H_REMAINING, "0");
            response.setHeader(H_RESET, String.valueOf(Math.max(1, windowMs / 1000)));
            response.setHeader("Retry-After", "1");
            FilterRefusal.write(request, response, 429, "RATE_LIMITED",
                    "The rate limiter is at capacity for this window. Try again shortly.");
            return;
        }
        long[] slot = windows.compute(key, (k, current) -> {
            if (current == null || now - current[0] >= windowMs) {
                return new long[]{now, 1L};
            }
            current[1]++;
            return current;
        });
        pruneIfNeeded(now);

        long resetMs = Math.max(0, slot[0] + windowMs - now);

        // Publish the budget on *allowed* requests too, not just on rejection:
        // a client that can see it running down can pace itself, instead of
        // discovering the limiter only by getting a 429. Set before the chain
        // runs because the response is committed once Spring writes the body.
        response.setHeader(H_BUCKET, bucket);
        response.setHeader(H_LIMIT, String.valueOf(limit));
        response.setHeader(H_REMAINING, String.valueOf(Math.max(0, limit - (int) slot[1])));
        response.setHeader(H_RESET, String.valueOf(resetMs / 1000));

        if (slot[1] > limit) {
            long retryAfterSec = Math.max(1, (resetMs + 999) / 1000);
            record(rule, false);
            response.setHeader("Retry-After", String.valueOf(retryAfterSec));
            FilterRefusal.write(request, response, 429, "RATE_LIMITED", message);
            return;
        }
        record(rule, true);
        filterChain.doFilter(request, response);
    }

    /**
     * Which budget a request spends. Named rather than a boolean because there
     * are three rules now; the old flag meant "is this the session-id budget?",
     * which stops being readable the moment a rule is keyed by something other
     * than the session.
     */
    private enum Rule {
        /** {@code POST /api/v1/sessions}, keyed by client IP. */
        SESSION_CREATE,
        /** Frame and challenge writes, keyed by session id. */
        FRAMES,
        /** Operator routes, keyed by client IP. */
        OPERATOR_KEY,
        /** {@code POST /api/v1/auth/login}, keyed by client IP. */
        AUTH_LOGIN
    }

    private void record(Rule rule, boolean allowed) {
        switch (rule) {
            case SESSION_CREATE -> counters.recordSessionCreate(allowed);
            case FRAMES -> counters.recordFrames(allowed);
            case OPERATOR_KEY -> counters.recordOperatorKey(allowed);
            case AUTH_LOGIN -> counters.recordAuthLogin(allowed);
        }
    }

    /**
     * ponytail: counters for finished windows are dropped once the map exceeds
     * 4096 keys and at most once per second (an O(n) sweep), keeping the
     * steady-state path lock-free per key. The hard ceiling below the sweep is
     * what bounds memory: without it a flood of distinct session ids adds a key
     * per request, and the sweep itself becomes the attack (an O(n) scan on
     * every request that removes nothing). Upgrade path: a scheduled cleanup or
     * a caffeine expiry cache if sessions ever number in the millions per hour.
     */
    private void pruneIfNeeded(long now) {
        if (windows.size() <= 4096) {
            return;
        }
        if (now - lastPruneMillis < 1000L) {
            return;
        }
        lastPruneMillis = now;
        long maxWindow = Math.max(sessionCreateWindowMs, frameWindowMs);
        windows.entrySet().removeIf(e -> now - e.getValue()[0] >= maxWindow);
    }
}
