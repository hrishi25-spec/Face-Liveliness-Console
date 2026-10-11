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
 * Flood protection for the two expensive write paths:
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
 * </ul>
 *
 * <p>Runs after {@link SecurityHeadersFilter} (lowest precedence), so the 429
 * response carries the security headers, and before the dispatcher, so rejected
 * requests never reach Jackson or the database. Fixed window counters are
 * in-memory — the service is a single local process, so there is nothing
 * shared to coordinate.</p>
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
@Order(Ordered.LOWEST_PRECEDENCE)
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
     * Budget names, echoed in {@code X-RateLimit-Bucket} so a client can tell
     * the two independent budgets apart.
     */
    static final String BUCKET_SESSION_CREATE = "session-create";
    static final String BUCKET_FRAMES = "frames";
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

    /** key -> {windowStartMillis, count} */
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

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
        if (!enabled || !"POST".equals(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String key;
        int limit;
        long windowMs;
        String bucket;
        String resource;
        String message;
        boolean framesBudget;

        if (SESSION_CREATE.equals(path)) {
            key = "create:" + clientIpResolver.resolve(request);
            limit = sessionCreateLimit;
            windowMs = sessionCreateWindowMs;
            bucket = BUCKET_SESSION_CREATE;
            resource = "session creation";
            message = "Too many sessions created from this address. Try again later.";
            framesBudget = false;
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
            resource = "frame submission";
            message = "Too many requests for this session. Slow down and retry.";
            framesBudget = true;
        }

        long now = clock.millis();
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
            record(framesBudget, false);
            response.setHeader("Retry-After", String.valueOf(retryAfterSec));
            FilterRefusal.write(request, response, 429, "RATE_LIMITED", message);
            return;
        }
        record(framesBudget, true);
        filterChain.doFilter(request, response);
    }

    private void record(boolean framesBudget, boolean allowed) {
        if (framesBudget) {
            counters.recordFrames(allowed);
        } else {
            counters.recordSessionCreate(allowed);
        }
    }

    /**
     * ponytail: counters for finished windows are dropped only once the map
     * exceeds 4096 keys (an O(n) sweep), keeping the steady-state path lock-free
     * per key. Ceiling: a sustained flood of *distinct* session ids could grow
     * the map to that bound between sweeps — bounded and small. Upgrade path:
     * scheduled cleanup or a caffeine expiry cache if sessions ever number in
     * the millions per hour.
     */
    private void pruneIfNeeded(long now) {
        if (windows.size() <= 4096) {
            return;
        }
        long maxWindow = Math.max(sessionCreateWindowMs, frameWindowMs);
        windows.entrySet().removeIf(e -> now - e.getValue()[0] >= maxWindow);
    }
}
