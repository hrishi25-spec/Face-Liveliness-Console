package io.mosip.liveness.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that answers "which client sent this request?", shared by
 * {@link RateLimitFilter} (which keys its per-IP budget on the answer) and
 * {@link ClientIpFilter} (which publishes it for the audit trail).
 *
 * <p>{@code X-Forwarded-For} / {@code X-Real-IP} are client-supplied, so they
 * are read only when the peer we actually talked to is a configured proxy
 * ({@code mosip.security.rate-limit.trusted-proxies}: comma-separated IP or
 * CIDR entries, default empty = trust nobody). With that list empty the answer
 * is just {@code getRemoteAddr()}, which behind Docker port-mapping or a reverse
 * proxy collapses every host client onto the gateway address. When the
 * immediate peer <em>is</em> a trusted proxy, {@code X-Forwarded-For}
 * (right-to-left, skipping trusted hops) and then {@code X-Real-IP} decide.
 *
 * <p>Never list a range that includes untrusted addresses (in particular not
 * {@code 0.0.0.0/0}); set it to the proxy's address or network. A {@code /0}
 * entry is refused outright rather than honoured — see {@code parseCidr}. Trusting
 * the headers unconditionally would let one caller mint an unlimited number of rate
 * limit buckets, forge an audit trail's {@code sourceIp} by pointing at somebody
 * else's address, or poison another client's bucket.
 */
@Component
public class ClientIpResolver {

    /**
     * Request attribute carrying the resolved address, set by
     * {@link ClientIpFilter}. Handlers read it instead of re-deriving the
     * answer, so the rate limiter and the audit trail cannot disagree about
     * who the caller was.
     */
    public static final String ATTRIBUTE = ClientIpResolver.class.getName() + ".clientIp";

    /**
     * Peers whose forwarded headers may be believed: bare IPs or CIDR blocks.
     * Empty (the default) means "trust nobody" — the request's own remote
     * address is the answer, exactly as before this setting existed.
     */
    @Value("${mosip.security.rate-limit.trusted-proxies:}")
    private List<String> trustedProxies = List.of();

    /** Parsed form of {@link #trustedProxies}; built on first use. */
    private volatile List<Cidr> trustedProxyCidrs;

    /** The address that identifies the client behind {@code request}. */
    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (trustedProxyList().isEmpty() || !isTrustedProxy(peer)) {
            return peer;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            // Right to left: every trusted hop is another proxy we spoke through,
            // so the first untrusted address is the client that started here.
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = normalizeHop(hops[i]);
                if (hop == null || isTrustedProxy(hop)) {
                    continue;
                }
                return hop;
            }
        }
        String realIp = normalizeHop(request.getHeader("X-Real-IP"));
        return realIp != null ? realIp : peer;
    }

    private boolean isTrustedProxy(String address) {
        byte[] bytes = parseAddress(address);
        if (bytes == null) {
            return false;
        }
        for (Cidr cidr : trustedProxyList()) {
            if (cidr.matches(bytes)) {
                return true;
            }
        }
        return false;
    }

    private List<Cidr> trustedProxyList() {
        List<Cidr> parsed = trustedProxyCidrs;
        if (parsed == null) {
            parsed = trustedProxies.stream()
                    .map(ClientIpResolver::parseCidr)
                    .filter(Objects::nonNull)
                    .toList();
            trustedProxyCidrs = parsed;
        }
        return parsed;
    }

    /** One trusted entry: a network and a prefix length, compared bit by bit. */
    record Cidr(byte[] network, int prefixBits) {
        boolean matches(byte[] address) {
            if (network.length != address.length) {
                return false;
            }
            int wholeBytes = prefixBits / 8;
            for (int i = 0; i < wholeBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int spareBits = prefixBits % 8;
            if (spareBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - spareBits)) & 0xFF;
            return (address[wholeBytes] & mask) == (network[wholeBytes] & mask);
        }
    }

    /** Parses {@code 10.0.0.0/8}, {@code ::1} or a bare address; null if unusable. */
    private static Cidr parseCidr(String entry) {
        String value = entry == null ? "" : entry.trim();
        if (value.isEmpty()) {
            return null;
        }
        String literal = value;
        int prefix = -1;
        int slash = value.indexOf('/');
        if (slash >= 0) {
            literal = value.substring(0, slash).trim();
            try {
                prefix = Integer.parseInt(value.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return null;   // an unparseable entry is ignored, never guessed at
            }
        }
        byte[] bytes = parseAddress(literal);
        if (bytes == null) {
            return null;
        }
        if (prefix < 0) {
            prefix = bytes.length * 8;   // a bare address is a /32 or /128
        }
        // A /0 entry (0.0.0.0/0, ::/0) matches every peer, so honouring it would
        // make this list equal "trust all forwarded headers" — the one thing the
        // operator is told never to configure. Refused rather than warned about,
        // because the failure it enables is silent: any caller could then mint
        // rate-limit buckets and write its own address into an audit row. Same
        // fail-closed reading as an unparseable entry.
        if (prefix < 1 || prefix > bytes.length * 8) {
            return null;
        }
        return new Cidr(bytes, prefix);
    }

    /**
     * ponytail: literals only — {@link InetAddress#getByName} would resolve a
     * hostname, and DNS in the request path is a latency and failure source.
     * An IPv4-mapped IPv6 peer ({@code ::ffff:10.0.0.7}) collapses to its 4-byte
     * form, so it still matches an IPv4 entry.
     */
    private static byte[] parseAddress(String address) {
        if (address == null) {
            return null;
        }
        String value = address.trim();
        if (value.isEmpty() || !LITERAL.matcher(value).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Accepts {@code 10.0.0.1}, {@code 10.0.0.1:54321} and {@code [::1]:443}. */
    private static String normalizeHop(String hop) {
        if (hop == null) {
            return null;
        }
        String value = hop.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("[")) {                       // [::1] or [::1]:443
            int end = value.indexOf(']');
            return end > 0 ? value.substring(1, end) : null;
        }
        Matcher withPort = IPV4_PORT.matcher(value);       // 10.0.0.1:54321
        return withPort.matches() ? withPort.group(1) : value;
    }

    /** A dotted quad, or anything IPv6-shaped — no hostnames. */
    private static final Pattern LITERAL = Pattern.compile(
            "^(?:\\d{1,3}\\.){3}\\d{1,3}$|^[0-9a-fA-F:]+$");
    // Non-capturing inner group: group(1) must be the whole address, and with a
    // repeated *capturing* group Java returns only its last repetition ("113.").
    private static final Pattern IPV4_PORT = Pattern.compile(
            "^((?:\\d{1,3}\\.){3}\\d{1,3}):\\d+$");

    /** 127.0.0.0/8 in dotted-quad form — the shape {@code getRemoteAddr()} returns. */
    private static final Pattern LOOPBACK_V4 = Pattern.compile("^127(?:\\.\\d{1,3}){3}$");

    /**
     * True only for a loopback client: {@code 127.0.0.0/8} (also seen
     * IPv4-mapped as {@code ::ffff:127.0.0.1}), {@code ::1} or its long form.
     * Everything else — including an address a trusted proxy forwarded on
     * behalf of a remote caller — is not local.
     *
     * <p>Used by {@link DiagnosticsController} to keep diagnostic mode
     * reachable from this machine only (spec §10: opt-in, <em>local</em>).
     */
    public static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String value = address.trim();
        if (value.regionMatches(true, 0, "::ffff:", 0, 7)) {
            value = value.substring(7);
        }
        if (value.equalsIgnoreCase("::1") || value.equalsIgnoreCase("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (!LOOPBACK_V4.matcher(value).matches()) {
            return false;
        }
        for (String octet : value.split("\\.")) {
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }
}