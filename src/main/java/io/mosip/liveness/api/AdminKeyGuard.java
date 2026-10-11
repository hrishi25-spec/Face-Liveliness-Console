package io.mosip.liveness.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The admin-key check itself: the comparison and the two ways to fail it.
 *
 * <p>{@link AdminKeyFilter} calls this for every route in
 * {@link RouteInventory#ADMIN_KEYED} — policy writes ({@code PUT
 * /api/v1/config/*}), threshold calibration ({@code GET
 * /api/v1/eval/threshold-sweep}), and the config audit reads ({@code GET
 * /api/v1/config/audit}, {@code /audit/verify}) that publish a fingerprint of
 * the key itself. It used to be called by each handler in turn, which meant a
 * handler that forgot the call was an open route; the filter removes the call
 * site to forget.</p>
 *
 * <p>Two properties matter on all of them and are the reason this is one method
 * rather than a copy per route:</p>
 * <ul>
 *   <li><b>Fail closed.</b> With no key configured the route is refused, never
 *       left open. An unauthenticated caller could otherwise set
 *       {@code passiveThreshold=0} and defeat PAD for every session, read an
 *       APCER/BPCER operating point off the calibration sweep, or collect
 *       {@code key:<12 hex>} actor fingerprints to attack offline.</li>
 *   <li><b>Constant time.</b> {@link MessageDigest#isEqual} rather than
 *       {@code String.equals}, because the key is a shared secret and an
 *       early-exit compare leaks its prefix to anyone who can time the
 *       response.</li>
 * </ul>
 *
 * <p>What stays open is as deliberate as what is guarded: the session flow
 * (create/frame/challenge/close/audit), {@code GET /api/v1/config/{workflowType}}
 * (a client must be able to read the policy it is judged against),
 * {@code GET /api/v1/metrics} (aggregate counts and rates, no per-session or
 * secret-derived data) and the diagnostics panel (opt-in, loopback only).
 * {@code ApiRouteInventoryTest} fails when a route is added without being
 * classified here.</p>
 */
final class AdminKeyGuard {

    /** Header carrying the key; one name so tests and the console cannot drift. */
    static final String HEADER = "X-Admin-API-Key";

    private AdminKeyGuard() {
    }

    /**
     * Whether this caller may reach an admin-keyed route.
     *
     * <p>Not throwing: the caller is a filter, which sits outside Spring MVC's
     * exception resolution, so a thrown {@code ResponseStatusException} would
     * become a container error page rather than the service's JSON error body.
     * The refusal reason is returned instead and the filter renders it.</p>
     *
     * @param configuredKey the service's key ({@code MOSIP_ADMIN_API_KEY});
     *                      blank disables the guarded route
     * @param providedKey   the caller's header value, may be null
     * @return the reason to refuse, or {@code null} when the caller is allowed
     */
    static String refusal(String configuredKey, String providedKey) {
        if (configuredKey == null || configuredKey.isEmpty()) {
            return "This endpoint is disabled: configure MOSIP_ADMIN_API_KEY on the service to enable it.";
        }
        byte[] expected = configuredKey.getBytes(StandardCharsets.UTF_8);
        byte[] actual = providedKey == null ? new byte[0] : providedKey.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            return "Invalid admin API key.";
        }
        return null;
    }
}
