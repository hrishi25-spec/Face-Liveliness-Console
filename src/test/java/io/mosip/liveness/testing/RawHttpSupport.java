package io.mosip.liveness.testing;

import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

/**
 * Raw-HTTP plumbing for browser-style tests, on a base class so any
 * future test can send real {@code Origin} and preflight headers
 * without duplicating the JDK client plumbing.
 *
 * <p>Extend this from a {@code @SpringBootTest(webEnvironment =
 * RANDOM_PORT)} class and call {@link #send}. {@code TestRestTemplate}
 * uses {@code HttpURLConnection}, which silently strips {@code Origin}
 * and {@code Access-Control-Request-*} as restricted headers — with
 * those gone a preflight is indistinguishable from a plain same-origin
 * OPTIONS, so a CORS test would pass without ever exercising CORS.
 * The JDK client sends them verbatim.</p>
 *
 * <p>Only the transport lives here. Endpoint paths, fixtures and
 * assertions belong to the test class, which keeps this reusable
 * across slices of the API.</p>
 */
public abstract class RawHttpSupport {

    @LocalServerPort
    protected int port;

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * A browser-shaped request sent through the JDK HTTP client.
     */
    protected RawResponse send(HttpMethod method, String path, String body, HttpHeaders headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path)));
        headers.forEach((name, values) -> values.forEach(v -> builder.header(name, v)));
        builder.method(method.name(), body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        try {
            java.net.http.HttpResponse<String> res = java.net.http.HttpClient.newHttpClient()
                    .send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            return new RawResponse(res.statusCode(), res.headers().map(), res.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("browser-style " + method + " " + path + " failed", e);
        }
    }

    /** Status, headers and body of a raw browser-style request. */
    protected record RawResponse(int status, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (name.equalsIgnoreCase(e.getKey()) && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    /** Headers Chrome sends on a same-origin request from the console. */
    protected static HttpHeaders browserHeaders(String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ORIGIN, origin);
        headers.set(HttpHeaders.REFERER, origin + "/");
        headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
        headers.set("Sec-Fetch-Site", "same-origin");
        headers.set("Sec-Fetch-Mode", "cors");
        headers.set("Sec-Fetch-Dest", "empty");
        headers.set(HttpHeaders.USER_AGENT,
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/126 Safari/537.36");
        return headers;
    }
}
