package io.mosip.liveness.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flood-protection contract for {@link RateLimitFilter}: limits trip with a
 * 429 + Retry-After, budgets are keyed per client IP (session creation) and
 * per session id (frame work), and everything unscoped passes through.
 */
class RateLimitFilterTest {

    private RateLimitFilter filter(int createLimit, int frameLimit) {
        RateLimitFilter filter = new RateLimitFilter(new RateLimitCounters());
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "sessionCreateLimit", createLimit);
        ReflectionTestUtils.setField(filter, "frameLimit", frameLimit);
        return filter;
    }

    /** A filter that trusts the given proxies, so forwarded client IPs count. */
    private RateLimitFilter filterBehindProxies(int createLimit, String trustedProxies) {
        RateLimitFilter filter = filter(createLimit, 60);
        // The trust list lives on the shared resolver the filter now delegates
        // to — set before the first request, while its parsed cache is still empty.
        ReflectionTestUtils.setField(
                ReflectionTestUtils.getField(filter, "clientIpResolver"), "trustedProxies",
                List.of(trustedProxies.split(",")));
        return filter;
    }

    /** The counters the filter writes to (constructor-injected). */
    private static RateLimitCounters counters(RateLimitFilter filter) {
        return (RateLimitCounters) ReflectionTestUtils.getField(filter, "counters");
    }

    private record Result(MockHttpServletResponse response, boolean passed) {}

    private Result run(RateLimitFilter filter, String method, String uri, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(response, chain.getRequest() != null);
    }

    /** Same, but with the headers a reverse proxy or a client would add. */
    private Result runBehindProxy(RateLimitFilter filter, String ip, String forwardedFor, String realIp)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sessions");
        request.setRemoteAddr(ip);
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        if (realIp != null) request.addHeader("X-Real-IP", realIp);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(response, chain.getRequest() != null);
    }

    // ------------------------------------------------------------------ session creation (per IP)

    @Test
    void sessionCreate_allowsUpToLimit_then429() throws Exception {
        RateLimitFilter filter = filter(2, 60);

        Result first = run(filter, "POST", "/api/v1/sessions", "10.0.0.1");
        Result second = run(filter, "POST", "/api/v1/sessions", "10.0.0.1");
        assertTrue(first.passed(), "first request must pass");
        assertTrue(second.passed(), "request at the limit must pass");

        Result third = run(filter, "POST", "/api/v1/sessions", "10.0.0.1");
        assertTrue(!third.passed(), "request over the limit must not reach the dispatcher");
        assertEquals(429, third.response().getStatus());
        assertTrue(third.response().getContentAsString().contains("RATE_LIMITED"));
        assertTrue(Integer.parseInt(third.response().getHeader("Retry-After")) >= 1,
                "429 must carry Retry-After");
    }

    @Test
    void sessionCreate_budgetIsPerClientIp() throws Exception {
        RateLimitFilter filter = filter(1, 60);

        assertTrue(run(filter, "POST", "/api/v1/sessions", "10.0.0.1").passed());
        assertEquals(429, run(filter, "POST", "/api/v1/sessions", "10.0.0.1").response().getStatus());

        // A different client still has its own budget.
        assertTrue(run(filter, "POST", "/api/v1/sessions", "10.0.0.2").passed(),
                "another IP must not be starved by the first");
    }

    // ------------------------------------------------------------------ frames (per session)

    @Test
    void frames_areKeyedBySession_notGlobally() throws Exception {
        RateLimitFilter filter = filter(30, 2);
        String sessionA = "/api/v1/sessions/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/frames";
        String sessionB = "/api/v1/sessions/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/frames";

        assertTrue(run(filter, "POST", sessionA, "10.0.0.1").passed());
        assertTrue(run(filter, "POST", sessionA, "10.0.0.1").passed());
        assertEquals(429, run(filter, "POST", sessionA, "10.0.0.1").response().getStatus());

        assertTrue(run(filter, "POST", sessionB, "10.0.0.1").passed(),
                "a flooded session must not starve other sessions");
    }

    @Test
    void challengeValidate_sharesTheSessionFrameBudget() throws Exception {
        RateLimitFilter filter = filter(30, 2);
        String frames = "/api/v1/sessions/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/frames";
        String validate = "/api/v1/sessions/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/challenges/validate";

        assertTrue(run(filter, "POST", frames, "10.0.0.1").passed());
        assertTrue(run(filter, "POST", validate, "10.0.0.1").passed());
        assertEquals(429, run(filter, "POST", validate, "10.0.0.1").response().getStatus(),
                "validate posts frames too and must share the session budget");
    }

    @Test
    void manualActiveCheck_sharesTheSessionFrameBudget() throws Exception {
        RateLimitFilter filter = filter(30, 2);
        String frames = "/api/v1/sessions/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/frames";
        String extra = "/api/v1/sessions/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/active-liveness";

        assertTrue(run(filter, "POST", frames, "10.0.0.1").passed());
        assertTrue(run(filter, "POST", extra, "10.0.0.1").passed());
        assertEquals(429, run(filter, "POST", extra, "10.0.0.1").response().getStatus(),
                "the manual extra check writes per session and must share its budget");
    }

    // ------------------------------------------------------------------ pass-through

    @Test
    void getRequests_areNeverRateLimited() throws Exception {
        RateLimitFilter filter = filter(1, 1);
        for (int i = 0; i < 5; i++) {
            assertTrue(run(filter, "GET", "/api/v1/metrics", "10.0.0.1").passed());
        }
    }

    @Test
    void disabledFlag_passesEverythingThrough() throws Exception {
        RateLimitFilter filter = filter(1, 1);
        ReflectionTestUtils.setField(filter, "enabled", false);
        for (int i = 0; i < 5; i++) {
            assertTrue(run(filter, "POST", "/api/v1/sessions", "10.0.0.1").passed());
        }
    }

    @Test
    void unscopedPostPaths_bypassTheLimiter() throws Exception {
        RateLimitFilter filter = filter(1, 1);
        // close / config / unrelated POSTs are outside the flood scope
        assertTrue(run(filter, "POST", "/api/v1/sessions/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/close",
                "10.0.0.1").passed());
        assertTrue(run(filter, "POST", "/api/v1/config/RESIDENT", "10.0.0.1").passed());
    }

    @Test
    void allowedRequests_reachTheChainWithIntactResponse() throws Exception {
        RateLimitFilter filter = filter(30, 60);
        Result result = run(filter, "POST", "/api/v1/sessions", "10.0.0.1");
        assertTrue(result.passed());
        assertEquals(200, result.response().getStatus());
        // Budget headers yes (a client must be able to pace itself), Retry-After no.
        assertNull(result.response().getHeader("Retry-After"),
                "an allowed request must not tell the client to retry");
        assertNotNull(result.response());
    }

    // ------------------------------------------------------- injectable clock

    @Test
    void windowRollsOver_whenTheInjectedClockMovesPastIt() throws Exception {
        // This is the reason the clock is injectable: with System.currentTimeMillis()
        // the only way to prove a 60s window resets was to wait 60s.
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        RateLimitFilter filter = new RateLimitFilter(new RateLimitCounters(), clock);
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "sessionCreateLimit", 2);
        ReflectionTestUtils.setField(filter, "sessionCreateWindowMs", 60_000L);

        assertTrue(run(filter, "POST", "/api/v1/sessions", "10.0.0.1").passed());
        assertTrue(run(filter, "POST", "/api/v1/sessions", "10.0.0.1").passed());
        assertEquals(429,
                run(filter, "POST", "/api/v1/sessions", "10.0.0.1").response().getStatus(),
                "the third request in the window must be refused");

        // Move time past the window instead of sleeping through it.
        clock.advance(Duration.ofSeconds(59));
        assertEquals(429, run(filter, "POST", "/api/v1/sessions", "10.0.0.1").response().getStatus(),
                "one second short of the window is still inside it");

        clock.advance(Duration.ofSeconds(2));
        Result rolled = run(filter, "POST", "/api/v1/sessions", "10.0.0.1");
        assertTrue(rolled.passed(), "a new window must reset the budget");
        assertEquals("1", rolled.response().getHeader("X-RateLimit-Remaining"));
    }

    @Test
    void frameBudgetsRollOverOnTheSameClock() throws Exception {
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        RateLimitFilter filter = new RateLimitFilter(new RateLimitCounters(), clock);
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "frameLimit", 1);
        ReflectionTestUtils.setField(filter, "frameWindowMs", 10_000L);
        String frames = "/api/v1/sessions/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/frames";

        assertTrue(run(filter, "POST", frames, "10.0.0.1").passed());
        assertEquals(429, run(filter, "POST", frames, "10.0.0.1").response().getStatus());

        clock.advance(Duration.ofSeconds(11));
        assertTrue(run(filter, "POST", frames, "10.0.0.1").passed(),
                "the frame budget must reset on its own window, not the session one");
    }

    @Test
    void withoutAnExplicitClockTheFilterUsesRealTime() throws Exception {
        // The one-arg constructor is what @WebMvcTest slices and older callers
        // use; it must not fall back to a frozen clock.
        RateLimitFilter filter = filter(1, 1);
        assertTrue(run(filter, "POST", "/api/v1/sessions", "10.0.0.1").passed());
        assertEquals(429, run(filter, "POST", "/api/v1/sessions", "10.0.0.1").response().getStatus());
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(long epochMilli) {
            this.now = Instant.ofEpochMilli(epochMilli);
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // ------------------------------------------------------- published budget

    @Test
    void allowedRequests_publishTheBudgetSoClientsCanPaceThemselves() throws Exception {
        RateLimitFilter filter = filter(3, 60);

        Result first = run(filter, "POST", "/api/v1/sessions", "10.0.0.1");
        assertEquals("session-create", first.response().getHeader("X-RateLimit-Bucket"));
        assertEquals("3", first.response().getHeader("X-RateLimit-Limit"));
        assertEquals("2", first.response().getHeader("X-RateLimit-Remaining"));
        assertNotNull(first.response().getHeader("X-RateLimit-Reset"));

        // The count must actually go down as the window fills.
        Result second = run(filter, "POST", "/api/v1/sessions", "10.0.0.1");
        assertEquals("1", second.response().getHeader("X-RateLimit-Remaining"));
        Result third = run(filter, "POST", "/api/v1/sessions", "10.0.0.1");
        assertEquals("0", third.response().getHeader("X-RateLimit-Remaining"));

        long reset = Long.parseLong(third.response().getHeader("X-RateLimit-Reset"));
        assertTrue(reset >= 0 && reset <= 60, "reset must be seconds into a 60s window: " + reset);
    }

    @Test
    void theTwoBudgetsAreNamedSeparately() throws Exception {
        RateLimitFilter filter = filter(30, 60);
        String session = "/api/v1/sessions/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/frames";

        assertEquals("session-create",
                run(filter, "POST", "/api/v1/sessions", "10.0.0.1").response().getHeader("X-RateLimit-Bucket"));
        // Frames and challenge validation share one budget, so both report it.
        assertEquals("frames",
                run(filter, "POST", session, "10.0.0.1").response().getHeader("X-RateLimit-Bucket"));
        assertEquals("frames", run(filter, "POST",
                        "/api/v1/sessions/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/challenges/validate",
                        "10.0.0.1").response().getHeader("X-RateLimit-Bucket"));
    }

    @Test
    void rejectedRequest_reportsAnEmptyBudgetAndRetryAfter() throws Exception {
        RateLimitFilter filter = filter(1, 60);
        run(filter, "POST", "/api/v1/sessions", "10.0.0.1");

        Result limited = run(filter, "POST", "/api/v1/sessions", "10.0.0.1");
        assertEquals(429, limited.response().getStatus());
        assertEquals("0", limited.response().getHeader("X-RateLimit-Remaining"),
                "a 429 must tell the client the budget is empty, not leave it guessing");
        assertEquals("1", limited.response().getHeader("X-RateLimit-Limit"));
        assertTrue(Integer.parseInt(limited.response().getHeader("Retry-After")) >= 1);
    }

    @Test
    void unlimitedPaths_carryNoBudgetHeaders() throws Exception {
        RateLimitFilter filter = filter(1, 1);
        // A client must not mistake an unlimited route for an exhausted budget.
        assertNull(run(filter, "GET", "/api/v1/metrics", "10.0.0.1")
                .response().getHeader("X-RateLimit-Bucket"));
        assertNull(run(filter, "POST", "/api/v1/config/RESIDENT", "10.0.0.1")
                .response().getHeader("X-RateLimit-Bucket"));
    }

    // ------------------------------------------------------- reported counters

    @Test
    void counts_separateAllowedAndRejectedPerRule() throws Exception {
        RateLimitFilter filter = filter(1, 1);
        String frames = "/api/v1/sessions/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/frames";

        run(filter, "POST", "/api/v1/sessions", "10.0.0.1");           // allowed
        run(filter, "POST", "/api/v1/sessions", "10.0.0.1");           // rejected
        run(filter, "POST", frames, "10.0.0.1");                        // allowed
        run(filter, "POST", frames, "10.0.0.1");                        // rejected

        RateLimitCounters.Counts counts = counters(filter).snapshot();
        assertEquals(1, counts.sessionCreateAllowed());
        assertEquals(1, counts.sessionCreateRejected());
        assertEquals(1, counts.framesAllowed());
        assertEquals(1, counts.framesRejected());
        assertEquals(2, counts.allowed());
        assertEquals(2, counts.rejected());
        assertEquals(0.5, counts.rejectionRate(), 1e-9);
    }

    @Test
    void counts_startAtZeroAndNeverDivideByZero() {
        RateLimitCounters.Counts counts = new RateLimitCounters().snapshot();
        assertEquals(0, counts.allowed());
        assertEquals(0, counts.rejected());
        assertEquals(0.0, counts.rejectionRate(), 1e-9,
                "no traffic must report 0.0, not NaN");
    }

    @Test
    void counts_ignoreUnlimitedPathsAndDisabledLimiter() throws Exception {
        RateLimitFilter filter = filter(1, 1);
        // GETs, config writes and session close never touch the counters.
        run(filter, "GET", "/api/v1/metrics", "10.0.0.1");
        run(filter, "POST", "/api/v1/config/RESIDENT", "10.0.0.1");
        assertEquals(0, counters(filter).snapshot().allowed() + counters(filter).snapshot().rejected());

        ReflectionTestUtils.setField(filter, "enabled", false);
        RateLimitFilter fresh = filter(1, 1);
        ReflectionTestUtils.setField(fresh, "enabled", false);
        for (int i = 0; i < 5; i++) {
            run(fresh, "POST", "/api/v1/sessions", "10.0.0.1");
        }
        assertEquals(0, counters(fresh).snapshot().allowed() + counters(fresh).snapshot().rejected(),
                "a disabled limiter must not report traffic it never limited");
    }

    @Test
    void counts_includeEveryRejectedAttempt_notJustTheFirst() throws Exception {
        RateLimitFilter filter = filter(1, 60);
        run(filter, "POST", "/api/v1/sessions", "10.0.0.1");
        for (int i = 0; i < 4; i++) {
            run(filter, "POST", "/api/v1/sessions", "10.0.0.1");
        }
        assertEquals(4, counters(filter).snapshot().sessionCreateRejected(),
                "every 429 is a refused caller worth counting");
    }

    // ------------------------------------------------------- trusted proxies

    @Test
    void byDefault_forwardedHeadersAreIgnored() throws Exception {
        // Empty trusted-proxies is the shipped default: a client must not be able
        // to mint fresh budgets by claiming a new address.
        RateLimitFilter filter = filter(2, 60);
        assertTrue(runBehindProxy(filter, "172.18.0.2", "203.0.113.1", null).passed());
        assertTrue(runBehindProxy(filter, "172.18.0.2", "203.0.113.2", null).passed());
        assertEquals(429, runBehindProxy(filter, "172.18.0.2", "203.0.113.3", null).response().getStatus(),
                "spoofed X-Forwarded-For must not buy extra budget");
    }

    @Test
    void anUntrustedPeerCannotSpoofItsWayToAFreshBudget() throws Exception {
        RateLimitFilter filter = filter(1, 60);
        // The peer is not in the trust list, so its headers are noise.
        assertTrue(runBehindProxy(filter, "10.0.0.9", "198.51.100.1", null).passed());
        assertEquals(429, runBehindProxy(filter, "10.0.0.9", "198.51.100.2", null).response().getStatus());
    }

    @Test
    void aTrustedProxyGivesEachForwardedClientItsOwnBudget() throws Exception {
        RateLimitFilter filter = filterBehindProxies(1, "172.18.0.0/16");
        // Docker gateway peer, two different clients behind it.
        assertTrue(runBehindProxy(filter, "172.18.0.2", "203.0.113.1", null).passed());
        assertTrue(runBehindProxy(filter, "172.18.0.2", "203.0.113.2", null).passed(),
                "distinct clients must not share one budget behind a trusted proxy");
        // …but each of them still has a limit of its own.
        assertEquals(429, runBehindProxy(filter, "172.18.0.2", "203.0.113.1", null).response().getStatus());
    }

    @Test
    void forwardedChain_isWalkedFromTheProxyBackToTheClient() throws Exception {
        RateLimitFilter filter = filterBehindProxies(1, "172.18.0.0/16,10.8.0.0/24");
        // edge proxy -> inner proxy -> client: both proxies are trusted, so the
        // client at the end of the chain owns the budget.
        assertTrue(runBehindProxy(filter, "10.8.0.4", "203.0.113.7, 172.18.0.9", null).passed());
        assertTrue(runBehindProxy(filter, "10.8.0.4", "198.51.100.7, 172.18.0.9", null).passed(),
                "a different client further back is a different budget");
        assertEquals(429, runBehindProxy(filter, "10.8.0.4", "203.0.113.7, 172.18.0.9", null)
                .response().getStatus());
    }

    @Test
    void xRealIp_isUsedWhenForwardedForIsAbsentOrAllTrusted() throws Exception {
        RateLimitFilter filter = filterBehindProxies(1, "172.18.0.0/16");
        assertTrue(runBehindProxy(filter, "172.18.0.2", null, "203.0.113.5").passed());
        assertTrue(runBehindProxy(filter, "172.18.0.2", null, "203.0.113.6").passed(),
                "distinct clients keep distinct budgets via X-Real-IP");
        assertEquals(429, runBehindProxy(filter, "172.18.0.2", null, "203.0.113.5")
                .response().getStatus());

        // XFF listing only trusted hops carries no client info; X-Real-IP wins.
        assertTrue(runBehindProxy(filter, "172.18.0.2", "172.18.0.7", "203.0.113.8").passed());
        assertEquals(429, runBehindProxy(filter, "172.18.0.2", "172.18.0.7", "203.0.113.8")
                .response().getStatus());
    }

    @Test
    void forwardedHops_mayCarryAPortOrBracketedIpv6() throws Exception {
        RateLimitFilter filter = filterBehindProxies(1, "172.18.0.0/16");
        // Same client, two spellings — must be one budget, not two.
        assertTrue(runBehindProxy(filter, "172.18.0.2", "203.0.113.9:51234", null).passed());
        assertEquals(429, runBehindProxy(filter, "172.18.0.2", "203.0.113.9", null).response().getStatus(),
                "a port suffix must not mint a second budget for the same client");
    }

    @Test
    void trustedProxyMatching_handlesCidrSingleAddressesAndJunk() throws Exception {
        RateLimitFilter filter = filterBehindProxies(1, "127.0.0.1, ::1, 10.0.0.0/8, not-an-ip, 10.0.0.0/99");
        // 127.0.0.1 (bare), ::1 (bare IPv6) and a host inside 10/8 are all trusted.
        assertTrue(runBehindProxy(filter, "127.0.0.1", "203.0.113.20", null).passed());
        assertTrue(runBehindProxy(filter, "10.9.8.7", "203.0.113.21", null).passed());
        assertEquals(429, runBehindProxy(filter, "127.0.0.1", "203.0.113.20", null).response().getStatus());
        // The junk entries are ignored rather than throwing at startup, and
        // 172.16.x is outside both configured ranges.
        assertTrue(runBehindProxy(filter, "172.16.5.5", "203.0.113.22", null).passed());
        assertEquals(429, runBehindProxy(filter, "172.16.5.5", "203.0.113.23", null).response().getStatus());
    }

    @Test
    void frameBudget_isUnaffectedByTrustedProxies() throws Exception {
        // Frames are keyed by session id, so a forwarded IP must not change the
        // key or let a session escape its budget.
        RateLimitFilter filter = filterBehindProxies(30, "0.0.0.0/0");
        String session = "/api/v1/sessions/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/frames";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", session);
        request.setRemoteAddr("172.18.0.2");
        request.addHeader("X-Forwarded-For", "203.0.113.99");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertTrue(chain.getRequest() != null);
        assertEquals(RateLimitFilter.BUCKET_FRAMES, response.getHeader("X-RateLimit-Bucket"));
    }
}
