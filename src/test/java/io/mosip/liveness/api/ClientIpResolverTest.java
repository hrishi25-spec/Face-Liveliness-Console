package io.mosip.liveness.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for the shared client-IP resolution, now that two callers depend on
 * it: {@link RateLimitFilter} keys its per-IP budget on the answer and
 * {@link ClientIpFilter} publishes it into the config audit trail. If they
 * disagree, a rate limit and an audit entry can name different hosts for one
 * request.
 *
 * <p>Most of the trust semantics are exercised end-to-end in
 * {@link RateLimitFilterTest}; these cover the pieces that belong to the shared
 * component itself — the published attribute and the no-DNS guarantee.</p>
 */
class ClientIpResolverTest {

    private static ClientIpResolver resolverTrusting(String trustedProxies) {
        ClientIpResolver resolver = new ClientIpResolver();
        ReflectionTestUtils.setField(resolver, "trustedProxies", List.of(trustedProxies.split(",")));
        return resolver;
    }

    private static MockHttpServletRequest request(String peer, String forwardedFor, String realIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/config/RESIDENT");
        request.setRemoteAddr(peer);
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        if (realIp != null) request.addHeader("X-Real-IP", realIp);
        return request;
    }

    // ------------------------------------------------------------------ resolution

    @Test
    void withNoTrustedProxies_thePeerAddressIsTheAnswer() {
        ClientIpResolver resolver = resolverTrusting("");
        assertEquals("172.18.0.2", resolver.resolve(request("172.18.0.2", "203.0.113.1", null)));
    }

    @Test
    void anUntrustedPeerCannotNameTheClient() {
        ClientIpResolver resolver = resolverTrusting("10.0.0.0/8");
        assertEquals("198.51.100.9", resolver.resolve(request("198.51.100.9", "203.0.113.1", "203.0.113.2")));
    }

    @Test
    void theChainIsWalkedFromTheProxyBackTowardsTheClient() {
        // edge proxy (trusted) -> inner proxy (trusted) -> client
        ClientIpResolver resolver = resolverTrusting("172.18.0.0/16, 10.8.0.0/24");
        assertEquals("203.0.113.7",
                resolver.resolve(request("10.8.0.4", "203.0.113.7, 172.18.0.9", null)));
        assertEquals("198.51.100.7",
                resolver.resolve(request("10.8.0.4", "198.51.100.7, 172.18.0.9", null)));
    }

    @Test
    void xRealIpDecidesWhenForwardedForIsAbsentOrAllTrusted() {
        ClientIpResolver resolver = resolverTrusting("172.18.0.0/16");
        assertEquals("203.0.113.5", resolver.resolve(request("172.18.0.2", null, "203.0.113.5")));
        // An XFF listing only trusted hops names no client, so X-Real-IP wins.
        assertEquals("203.0.113.8", resolver.resolve(request("172.18.0.2", "172.18.0.7", "203.0.113.8")));
    }

    @Test
    void oneClientWrittenTwoWaysResolvesToTheSameAddress() {
        ClientIpResolver resolver = resolverTrusting("172.18.0.0/16");
        assertEquals("203.0.113.9", resolver.resolve(request("172.18.0.2", "203.0.113.9:51234", null)));
        assertEquals("203.0.113.9", resolver.resolve(request("172.18.0.2", "203.0.113.9", null)));
    }

    @Test
    void hostnamesArePassedThroughWithoutEverBeingResolved() {
        // Nothing here may hit DNS: that would put a lookup in the request path
        // (latency, and a failure a caller could induce). The resolver only ever
        // parses literals, so a hostname is carried through as text.
        ClientIpResolver resolver = resolverTrusting("172.18.0.0/16");
        assertEquals("client.example.invalid",
                resolver.resolve(request("172.18.0.2", "client.example.invalid", null)));
        // …and it can never match a trust entry, so it cannot open a chain of
        // trusted hops and smuggle in a later hop.
        // The rightmost entry is the client; a hostname in a trusted-hop
        // chain does not promote a later hop to become the client.
        assertEquals("client.example.invalid",
                resolver.resolve(request("172.18.0.2", "203.0.113.4, client.example.invalid", null)));
    }

    // ------------------------------------------------------------------ the published attribute

    @Test
    void theFilterPublishesTheResolvedAddressForHandlersToRead() throws Exception {
        ClientIpFilter filter = new ClientIpFilter(resolverTrusting("172.18.0.0/16"));
        MockHttpServletRequest request = request("172.18.0.2", "203.0.113.42", null);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals("203.0.113.42", request.getAttribute(ClientIpResolver.ATTRIBUTE));
        assertNotNull(chain.getRequest(), "the filter must not short-circuit the chain");
    }

    @Test
    void anAbsentResolverFallsBackToTrustingNobody() throws Exception {
        // The @WebMvcTest fallback: an ordinary component is not in a slice, and
        // the filter must still publish something rather than fail to start.
        ClientIpFilter filter = new ClientIpFilter((ClientIpResolver) null);
        MockHttpServletRequest request = request("172.18.0.2", "203.0.113.42", null);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals("172.18.0.2", request.getAttribute(ClientIpResolver.ATTRIBUTE));
    }

    @Test
    void theAttributeNameIsNamespaced() {
        // A bare "clientIp" would collide with anything a container or library
        // parks on the request.
        assertEquals(ClientIpResolver.class.getName() + ".clientIp", ClientIpResolver.ATTRIBUTE);
    }

    // ------------------------------------------------------------------ the trust list

    @Test
    void aWildcardTrustEntryIsRefusedInsteadOfTrustingEveryForwardedHeader() {
        // "Trust all forwarded headers" is the one configuration the setting tells
        // an operator never to write. Honoured, it makes every peer a trusted hop,
        // so the right-to-left walk over X-Forwarded-For finds no untrusted address
        // and the resolver falls through to the caller's own X-Real-IP — a value
        // any client can send. That is how one caller forges the audit trail's
        // sourceIp and spends somebody else's rate-limit budget, so the entry is
        // dropped like an unparseable one: fail closed, never warn and proceed.
        ClientIpResolver v4 = resolverTrusting("0.0.0.0/0");
        assertEquals("198.51.100.9",
                v4.resolve(request("198.51.100.9", "203.0.113.1", "203.0.113.99")),
                "a forged X-Real-IP must never be believed");

        ClientIpResolver v6 = resolverTrusting("::/0");
        assertEquals("2001:db8::5",
                v6.resolve(request("2001:db8::5", null, "203.0.113.99")),
                "the IPv6 wildcard is refused for the same reason");
    }

    @Test
    void theValidEntriesBesideARefusedWildcardStillWork() {
        ClientIpResolver resolver = resolverTrusting("0.0.0.0/0,172.18.0.0/16");
        assertEquals("203.0.113.7", resolver.resolve(request("172.18.0.2", "203.0.113.7", null)),
                "the named proxy network is still honoured");
        assertEquals("198.51.100.9", resolver.resolve(request("198.51.100.9", null, "203.0.113.99")),
                "an untrusted peer still cannot name itself");
    }

    @Test
    void aPrefixWiderThanTheAddressIsStillIgnored() {
        ClientIpResolver resolver = resolverTrusting("172.18.0.0/33, 172.18.0.0/-1");
        assertEquals("198.51.100.9", resolver.resolve(request("198.51.100.9", "203.0.113.1", null)));
    }

    // ------------------------------------------------------------------ locality

    @Test
    void loopbackIsRecognisedInEveryFormThePeerAddressCanTake() {
        assertTrue(ClientIpResolver.isLoopback("127.0.0.1"));
        assertTrue(ClientIpResolver.isLoopback("127.1.2.3"), "all of 127.0.0.0/8 is local");
        assertTrue(ClientIpResolver.isLoopback("::1"));
        assertTrue(ClientIpResolver.isLoopback("0:0:0:0:0:0:0:1"));
        assertTrue(ClientIpResolver.isLoopback("::ffff:127.0.0.1"),
                "an IPv4-mapped loopback peer is still loopback");
        assertTrue(ClientIpResolver.isLoopback(" 127.0.0.1 "));
    }

    @Test
    void anythingNotLoopbackIsNotLocal() {
        assertFalse(ClientIpResolver.isLoopback("10.0.0.5"));
        assertFalse(ClientIpResolver.isLoopback("192.168.1.7"));
        assertFalse(ClientIpResolver.isLoopback("198.51.100.7"));
        assertFalse(ClientIpResolver.isLoopback("fe80::1"));
        assertFalse(ClientIpResolver.isLoopback("127.0.0.256"),
                "an out-of-range octet is not quietly accepted");
        assertFalse(ClientIpResolver.isLoopback("evil.example.com"));
        assertFalse(ClientIpResolver.isLoopback(null));
        assertFalse(ClientIpResolver.isLoopback(""));
    }

    @Test
    void aTrustedProxysForwardedClientIsJudgedAsTheRemoteCallerItIs() {
        // Diagnostic mode is local-only; if the answer trusted a local proxy's
        // forwarded header blindly, any remote client could open the panel.
        // Here the proxy peer itself is the loopback address.
        ClientIpResolver resolver = resolverTrusting("127.0.0.1");
        MockHttpServletRequest viaProxy = request("127.0.0.1", "198.51.100.7", null);
        assertFalse(ClientIpResolver.isLoopback(resolver.resolve(viaProxy)),
                "a local proxy must not launder a remote caller into loopback");

        MockHttpServletRequest localClient = request("127.0.0.1", "127.0.0.1", null);
        assertTrue(ClientIpResolver.isLoopback(resolver.resolve(localClient)),
                "a genuinely local client behind the proxy stays local");
    }
}