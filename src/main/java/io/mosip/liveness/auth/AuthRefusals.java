package io.mosip.liveness.auth;

/**
 * The fixed texts every authentication and authorization failure answers with.
 *
 * <p>One place, because two callers share each of them: the filter that refuses
 * before a handler runs and the handler that refuses when it is reached without
 * the filter chain must be indistinguishable on the wire, or a caller can map
 * the service by comparing messages. For the same reason none of these strings
 * interpolates anything from the request: no username, no header name, no
 * account state, and never a credential or a fragment of one.</p>
 *
 * <p>{@link #INVALID_CREDENTIALS} covers every login failure — unknown
 * username, disabled account, locked account and wrong password — with one
 * sentence, so the endpoint cannot be used to discover which usernames exist.
 * {@link #UNAUTHENTICATED} likewise covers an absent, malformed, unknown,
 * expired and revoked token.</p>
 */
public final class AuthRefusals {

    /** No usable session. Covers every reason a token did not authenticate. */
    public static final String UNAUTHENTICATED = "Not authenticated: sign in to continue.";

    /** Authenticated, but the role is too small for this route. */
    public static final String FORBIDDEN = "Not permitted for this role.";

    /**
     * A signed-in caller named a user type other than the one its role runs.
     *
     * <p>Refused rather than corrected: silently downgrading a supervisor's
     * request to {@code SUPERVISOR_AUTH} would hand back evidence for a flow the
     * caller did not ask for, and silently upgrading would be worse.</p>
     */
    public static final String USER_TYPE_FROM_ACCOUNT =
            "The user type is taken from the signed-in account and cannot be chosen for a session.";

    /** Every login failure, whatever the underlying reason. */
    public static final String INVALID_CREDENTIALS = "Invalid username or password.";

    /** A session that still owes a password change, reaching anything else. */
    public static final String PASSWORD_CHANGE_REQUIRED =
            "Password change required before this endpoint can be used.";

    /** Account rules, shared by create, reset and change-password. */
    public static final String WEAK_PASSWORD =
            "Password must be at least 12 characters and no more than 72 bytes.";
    public static final String PASSWORD_UNCHANGED =
            "The new password must differ from the current one.";
    public static final String BAD_USERNAME =
            "Username must be 3-64 characters: letters, digits, dot, underscore or hyphen.";
    public static final String BAD_ROLE = "Role must be SUPERVISOR or OPERATOR.";
    public static final String USER_EXISTS = "That username is already taken.";
    public static final String NO_SUCH_USER = "No such user.";
    public static final String LAST_SUPERVISOR =
            "This change would leave no enabled supervisor. "
                    + "Promote or create another supervisor first.";

    private AuthRefusals() {
    }
}
