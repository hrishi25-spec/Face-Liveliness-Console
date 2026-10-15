package io.mosip.liveness.api;

import io.mosip.liveness.auth.AuthRefusals;
import io.mosip.liveness.models.enums.AuthRole;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The operator API key check: the comparison, and the two ways to fail it.
 *
 * <p>{@link OperatorKeyFilter} calls this for every route in
 * {@link RouteInventory#OPERATOR_KEYED} that has not already proved a role with a
 * session token. Those routes are the console's role probe
 * ({@code GET /api/v1/auth/role}) and the two read-only feeds — reads, so the key
 * a deployment already has keeps working while the privileged surface moved to
 * accountable accounts.</p>
 *
 * <p><b>What this key cannot do, and why it survives.</b> It proves
 * {@link AuthRole#OPERATOR} only. It is not in {@link #HEADER} any route of
 * {@link RouteInventory#SUPERVISOR_KEYED}, no filter reads it there, and nothing
 * promotes it: a leaked key reaches the feeds and never a policy write or an
 * account. That asymmetry is the reason it was kept rather than deleted with the
 * admin key — the privileged path needed an identity, the read-only path did
 * not, and removing a working key the console depends on would have been change
 * for its own sake.</p>
 *
 * <p>Two properties matter and are the reason this is one method rather than a
 * copy per route:</p>
 * <ul>
 *   <li><b>Fail closed.</b> With no key configured the caller proves nothing at
 *       all, so the routes close to key-based access rather than opening to
 *       whoever sends an empty header — an unauthenticated caller could otherwise
 *       read every session and every rejection in the service.</li>
 *   <li><b>Constant time.</b> {@link MessageDigest#isEqual} rather than
 *       {@code String.equals}, because the key is a shared secret and an
 *       early-exit compare leaks its prefix to anyone who can time the
 *       response.</li>
 * </ul>
 *
 * <p>{@link #REFUSAL} is shared with {@link AuthContextFilter} and the
 * authenticated handlers through {@link AuthRefusals}: with tokens and keys both
 * in play, a caller must not be able to tell from the wording which credential
 * was expected — a message naming the key header would point a guesser at the one
 * credential with no account behind it.</p>
 */
final class OperatorKeyGuard {

    /** Header carrying the key; one name so tests and the console cannot drift. */
    static final String HEADER = "X-Operator-API-Key";

    /**
     * The admin key's holder: everything operator routes allow, and every
     * privileged route. Kept as a constant on this class because the console
     * switches on this exact wire name at {@code GET /api/v1/auth/role}; it is
     * {@link AuthRole#SUPERVISOR}'s name, not a second spelling of it.
     */
    static final String ROLE_SUPERVISOR = AuthRole.SUPERVISOR.name();

    /** The operator key's holder: operator routes only, never a policy write. */
    static final String ROLE_OPERATOR = AuthRole.OPERATOR.name();

    /**
     * The one refusal text, used by the filter and by the endpoint so the two
     * cannot drift and a caller cannot tell which one answered. Generic by
     * design: it names no header, no credential and no reason.
     */
    static final String REFUSAL = AuthRefusals.UNAUTHENTICATED;

    private OperatorKeyGuard() {
    }

    /**
     * Whether a presented header proves the configured operator key.
     *
     * <p>A blank configured key matches nothing — not even a presented blank —
     * which is what makes an unconfigured key fail closed instead of admitting
     * whoever sends an empty header.</p>
     *
     * @param configuredKey {@code MOSIP_OPERATOR_API_KEY}; blank disables the
     *                      operator role entirely
     * @param presentedKey  the caller's {@link #HEADER} value, may be null
     */
    static boolean proves(String configuredKey, String presentedKey) {
        if (configuredKey == null || configuredKey.isBlank() || presentedKey == null) {
            return false;
        }
        return MessageDigest.isEqual(configuredKey.getBytes(StandardCharsets.UTF_8),
                presentedKey.getBytes(StandardCharsets.UTF_8));
    }
}
