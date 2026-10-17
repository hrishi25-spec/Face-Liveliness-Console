package io.mosip.liveness.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The page-size rule the operator feeds share ({@code GET /api/v1/sessions} and
 * {@code GET /api/v1/rejections}).
 *
 * <p>Written down once because both endpoints answer a request parameter with
 * the same name and must answer it the same way: a caller that learned
 * {@code ?limit=200} works on one feed should not discover a different cap on
 * the other. Outside 1..{@value #MAX} the request is refused with a 400 rather
 * than silently clamped — a clamped page looks like a shortened result set, and
 * a client that asked for 5000 rows has to be told its page size was refused,
 * not left to assume there were only 200 rejections. (The config audit feed
 * clamps instead; that read is consulted by a console rendering a fixed panel,
 * while these feeds take their limit from the caller.)</p>
 */
final class FeedLimit {

    /** The page size a feed uses when the request does not ask for one. */
    static final int DEFAULT = 50;

    /** The largest page a feed will return. */
    static final int MAX = 200;

    private FeedLimit() {
    }

    /**
     * The checked page size for a request.
     *
     * <p>An absent parameter and an empty one ({@code ?limit=}) both mean "the
     * default": a console that always renders the field, or clears it, is asking
     * for the feed and not for a debate about its page size. Only a value that
     * was actually given is checked, and a value outside the range is refused
     * rather than clamped.</p>
     *
     * @param limit the requested page size, or {@code null} when none was given
     * @return the page size to use, guaranteed to be within 1..{@value #MAX}
     * @throws ResponseStatusException 400 when it is outside that range
     */
    static int checked(Integer limit) {
        if (limit == null) {
            return DEFAULT;
        }
        if (limit < 1 || limit > MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and " + MAX);
        }
        return limit;
    }
}
