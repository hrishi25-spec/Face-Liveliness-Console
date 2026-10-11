package io.mosip.liveness.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The ownership check for every session-scoped route: a caller may act on a
 * session only by presenting, in {@link #HEADER}, the same subject reference the
 * session was created with.
 *
 * <p><b>What this is.</b> Resource-level authorization — the isolation half of
 * "users can only reach what is theirs". It is the strongest thing this service
 * can do without an identity provider: there is no user table, no session cookie
 * and no token issuer here ({@code LivenessSession.subjectRef} is a caller-supplied
 * reference, not a wire credential), so the claim is checked against the stored
 * owner. It is <em>not</em> authentication of the caller's identity: a caller who
 * can guess or learn a subject reference holds it. Pair it with the port binding
 * ({@code SERVER_ADDRESS}, loopback by default) as the real access boundary, and
 * treat a subject reference like the identifier it is — MOSIP subjects are
 * predictable, so this isolates honest clients from each other, it does not
 * withstand an attacker who can enumerate the ids.</p>
 *
 * <p><b>Owned and unowned sessions.</b> A session created without a subject
 * reference has no owner to protect, and stays reachable to anyone who can reach
 * the port — exactly as it was before this guard existed. Refusing it instead
 * would protect nothing (the same caller can create their own session) and would
 * break every client that legitimately creates anonymous sessions, including the
 * shipped {@code LivenessHttpClient} when it is wired without a subject
 * reference. So the rule is: <em>a session that declares an owner is guarded,
 * and one that does not is not.</em> A session can never lose its owner after
 * creation — nothing in the API writes {@code subjectRef} — so this cannot be
 * turned into a way to strip ownership off somebody else's session.</p>
 *
 * <p><b>Constant time.</b> {@link MessageDigest#isEqual} rather than
 * {@code String.equals}, matching {@link AdminKeyGuard}: {@code equals}
 * short-circuits on the first differing character, so it leaks how much of a
 * subject reference a guess got right. (Both compares still return immediately
 * when the lengths differ, so a guess reveals length and nothing more.)</p>
 */
public final class UserSessionGuard {

    /** Header carrying the ownership claim; one name so tests and clients cannot drift. */
    public static final String HEADER = "X-Subject-Ref";

    private UserSessionGuard() {
    }

    /**
     * @param sessionSubjectRef  the session's owner ({@code subjectRef}); null or
     *                           blank means the session was created without one
     * @param providedSubjectRef the caller's header value, may be {@code null}
     * @throws ResponseStatusException 403 when the session has an owner and the
     *                                 caller is not it, or did not say who it is
     */
    static void require(String sessionSubjectRef, String providedSubjectRef) {
        // Checked before the caller's value on purpose: an unowned session must
        // never reach the compare below. `sessionSubjectRef.equals(...)` would
        // throw a NullPointerException on a subjectRef-less session, which surfaced
        // as a 500 on a route that is supposed to answer 403 — a crash a caller
        // could trigger, and the one path where this guard was not fail-closed.
        if (isBlank(sessionSubjectRef)) {
            return;
        }
        if (isBlank(providedSubjectRef)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This session belongs to a subject reference; send it in the "
                            + HEADER + " header to act on it.");
        }
        byte[] expected = sessionSubjectRef.getBytes(StandardCharsets.UTF_8);
        byte[] actual = providedSubjectRef.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Invalid subject reference for this session.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
