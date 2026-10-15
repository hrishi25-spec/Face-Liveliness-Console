package io.mosip.liveness.models.enums;

/**
 * What an authenticated caller may do.
 *
 * <p>Two roles, ranked: {@link #SUPERVISOR} outranks {@link #OPERATOR} and can
 * do everything the smaller role can, so a gate asks
 * {@link #atLeast(AuthRole)} rather than comparing names. The ranks are the
 * order of the enum constants, which keeps the privilege ladder in one place —
 * a third role inserted at the wrong end would be a silent privilege change if
 * the comparison were spelled out per call site instead.</p>
 *
 * <p>The names are the wire names: {@code GET /api/v1/auth/role} answers with
 * one of them, the console switches on them, and they are what
 * {@code auth_users.role} stores, so a rename is an API change and not a
 * refactor.</p>
 */
public enum AuthRole {

    /**
     * The smaller role: the read-only operator feeds and the role probe. A
     * supervisor can reach everything an operator can.
     */
    OPERATOR,

    /**
     * The larger role: the policy write, the calibration sweep, and user
     * management. Never held by the operator API key — that key proves
     * {@link #OPERATOR} only, so the privileged surface always requires an
     * accountable account.
     */
    SUPERVISOR;

    /**
     * Whether this role may reach something that requires {@code required}.
     *
     * <p>Rank order, not equality: every supervisor-only gate is therefore also
     * satisfied by a supervisor, and an operator is refused with no per-route
     * special case.</p>
     */
    public boolean atLeast(AuthRole required) {
        return ordinal() >= required.ordinal();
    }
}
