package io.mosip.liveness.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The operator-key check itself: which role a caller's headers prove, if any.
 *
 * <p>{@link OperatorKeyFilter} calls this for every route in
 * {@link RouteInventory#OPERATOR_KEYED} — the console's role probe
 * ({@code GET /api/v1/auth/role}) today, and whatever operator-scoped routes the
 * operator surface grows later. It mirrors {@link AdminKeyGuard}: the same
 * constant-time compare, the same fail-closed reading of an unconfigured key,
 * and the same reason for existing — a handler that forgets its own check is an
 * open route, while a filter has no call to forget.</p>
 *
 * <p>Two keys are accepted, and the higher privilege wins:</p>
 * <ul>
 *   <li>{@link AdminKeyGuard#HEADER} matching
 *       {@code mosip.security.admin-api-key} → {@value #ROLE_SUPERVISOR}. A
 *       supervisor can do everything an operator can, so the admin key is
 *       accepted on operator routes too; if a caller somehow presents both,
 *       the answer is the supervisor role rather than whichever header was read
 *       first.</li>
 *   <li>{@link #HEADER} matching {@code mosip.security.operator-api-key} →
 *       {@value #ROLE_OPERATOR}. That key reaches operator routes only — it is
 *       never accepted by {@link AdminKeyGuard}, so policy writes stay
 *       supervisor-only.</li>
 * </ul>
 *
 * <p><b>Fail closed.</b> A key that is not configured never matches anything:
 * blank {@code operator-api-key} means no caller is ever answered
 * {@value #ROLE_OPERATOR}, even if the presented value is empty. With both keys
 * blank every caller is refused, so a deployment that forgot to configure them
 * has a closed operator surface rather than an open one. The admin key is
 * checked first and is unaffected by a blank operator key, deliberately: an
 * existing deployment that sets only {@code MOSIP_ADMIN_API_KEY} keeps working —
 * its holder is a supervisor and never needed the operator key — while nobody
 * gains operator access until that key is deliberately configured.</p>
 *
 * <p><b>Constant time.</b> {@link MessageDigest#isEqual} rather than
 * {@code String.equals}, because both keys are shared secrets and an early-exit
 * compare leaks a prefix to anyone who can time the response.</p>
 *
 * <p>The refusal message is a fixed string and the same whichever key was tried:
 * the endpoint's whole job is to answer with a role, so a message that
 * distinguished "no key" from "wrong key" would also tell a guesser when a
 * value was close. Nothing derived from the key — not even a fingerprint — is
 * put in a response body or a log line; the role name is the entire answer.</p>
 */
final class OperatorKeyGuard {

    /** Header carrying the operator key; one name so tests and the console cannot drift. */
    static final String HEADER = "X-Operator-API-Key";

    /** The admin key's holder: everything operator routes allow, and policy writes. */
    static final String ROLE_SUPERVISOR = "SUPERVISOR";

    /** The operator key's holder: operator routes only, never a policy write. */
    static final String ROLE_OPERATOR = "OPERATOR";

    /**
     * The one refusal text, used by the filter and by the endpoint so the two
     * cannot drift and a caller cannot tell which one answered. Generic by
     * design: it names no header, no key and no reason.
     */
    static final String REFUSAL = "Not authenticated: a valid API key is required.";

    private OperatorKeyGuard() {
    }

    /**
     * The role this caller's headers prove, or {@code null} when they prove
     * none.
     *
     * <p>Not throwing, for the same reason as {@link AdminKeyGuard#refusal}: the
     * caller may be a filter, which sits outside Spring MVC's exception
     * resolution, so a thrown {@code ResponseStatusException} would become a
     * container error page rather than the service's JSON error body.</p>
     *
     * @param configuredOperatorKey {@code MOSIP_OPERATOR_API_KEY}; blank disables
     *                              the operator role entirely
     * @param configuredAdminKey    {@code MOSIP_ADMIN_API_KEY}; blank disables the
     *                              supervisor answer
     * @param operatorHeader        the caller's {@link #HEADER} value, may be null
     * @param adminHeader           the caller's {@link AdminKeyGuard#HEADER}
     *                              value, may be null
     * @return {@link #ROLE_SUPERVISOR}, {@link #ROLE_OPERATOR}, or {@code null}
     */
    static String role(String configuredOperatorKey, String configuredAdminKey,
                       String operatorHeader, String adminHeader) {
        if (matches(configuredAdminKey, adminHeader)) {
            return ROLE_SUPERVISOR;
        }
        if (matches(configuredOperatorKey, operatorHeader)) {
            return ROLE_OPERATOR;
        }
        return null;
    }

    /**
     * Whether a presented header proves a configured key.
     *
     * <p>A blank configured key matches nothing — not even a presented blank —
     * which is what makes an unconfigured key fail closed instead of admitting
     * whoever sends an empty header.</p>
     */
    private static boolean matches(String configuredKey, String presentedKey) {
        if (configuredKey == null || configuredKey.isBlank() || presentedKey == null) {
            return false;
        }
        return MessageDigest.isEqual(configuredKey.getBytes(StandardCharsets.UTF_8),
                presentedKey.getBytes(StandardCharsets.UTF_8));
    }
}
