package io.mosip.liveness.auth;

import io.mosip.liveness.crud.AuthTokenRepository;
import io.mosip.liveness.crud.AuthUserRepository;
import io.mosip.liveness.models.entity.AuthToken;
import io.mosip.liveness.models.entity.AuthUser;
import io.mosip.liveness.models.enums.AuthRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Accounts and sessions: the one place a password is checked and the one place a
 * session begins or ends.
 *
 * <p>Replaces the shared admin API key. A key could not name the person who used
 * it, could not be revoked for one holder without rotating it for every holder,
 * and — having no expiry — stayed good forever once a {@code .env} leaked. What
 * stands here instead is an accountable account, a password checked with BCrypt,
 * and a short-lived token the server can revoke.</p>
 *
 * <p><b>What the operator API key still does.</b> It is deliberately <em>not</em>
 * removed: it proves {@link AuthRole#OPERATOR} and reaches the read-only operator
 * feeds and the role probe, never a policy write and never user management. So
 * the privileged surface always needs an account, while a deployment's existing
 * read-only console keeps working.</p>
 *
 * <p><b>Fail closed, and say nothing.</b> Every login failure — unknown username,
 * disabled account, locked account, wrong password — is an empty
 * {@link Optional} and one shared sentence, so the endpoint is not a
 * username-enumeration oracle. An unknown username still pays a full BCrypt
 * verification against {@link PasswordHasher#verifyAgainstDummy}, so it also
 * cannot be found by response time.</p>
 *
 * <p><b>Tokens.</b> 256 bits of CSPRNG output, returned to the caller exactly
 * once, stored only as a SHA-256 digest. Logout revokes; a password change
 * revokes every other session and mints a replacement; expiry is a column, not
 * a job. Nothing here logs a token or a password, and no method returns one
 * except as the value the caller is about to set as a cookie.</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * How stale {@code last_used_at} may get before it is written again. A
     * session that wrote on every request would double the write load of the
     * frame path for a field nothing reads on a hot path.
     */
    private static final Duration LAST_USED_REFRESH = Duration.ofSeconds(60);

    /**
     * Usernames are normalised before this is applied: lower case, and limited
     * to characters that survive a URL path segment without escaping (the name
     * appears in {@code /api/v1/users/{username}}).
     */
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,63}");

    private final AuthUserRepository users;
    private final AuthTokenRepository tokens;
    private final PasswordHasher hasher;
    private final Clock clock;
    private final long tokenTtlSeconds;
    private final int maxFailedAttempts;
    private final long lockoutSeconds;

    /**
     * Named rather than inferred: the second constructor below is the test seam,
     * and with two of them Spring would otherwise refuse to guess which one wires
     * the application. The same shape {@code RateLimitFilter} and
     * {@code ClientIpFilter} already use.
     */
    @Autowired
    public AuthService(AuthUserRepository users,
                       AuthTokenRepository tokens,
                       PasswordHasher hasher,
                       /*
                        * Optional so a @WebMvcTest slice that mocks this service
                        * never has to build it, and so an embedding without
                        * AppConfig's clock still gets real time rather than
                        * failing to start.
                        */
                       ObjectProvider<Clock> clockProvider,
                       @Value("${mosip.security.auth.token-ttl-seconds:3600}") long tokenTtlSeconds,
                       @Value("${mosip.security.auth.max-failed-attempts:5}") int maxFailedAttempts,
                       @Value("${mosip.security.auth.lockout-seconds:900}") long lockoutSeconds) {
        this(users, tokens, hasher, clockProvider.getIfAvailable(Clock::systemUTC),
                tokenTtlSeconds, maxFailedAttempts, lockoutSeconds);
    }

    /**
     * The same wiring with the clock supplied outright.
     *
     * <p>Package-private, and the only reason it exists is that the behaviours worth
     * testing here are all about elapsed time — a lockout that has to lapse, a token
     * that has to expire — and waiting them out for real would mean a test that
     * sleeps for fifteen minutes. Every production path calls the constructor above,
     * which resolves the application's clock; both share the assignments below, so
     * there is no second set of defaults to drift.</p>
     */
    AuthService(AuthUserRepository users,
                AuthTokenRepository tokens,
                PasswordHasher hasher,
                Clock clock,
                long tokenTtlSeconds,
                int maxFailedAttempts,
                long lockoutSeconds) {
        this.users = users;
        this.tokens = tokens;
        this.hasher = hasher;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.tokenTtlSeconds = tokenTtlSeconds;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockoutSeconds = lockoutSeconds;
    }

    // ------------------------------------------------------------------ sessions

    /**
     * A token the caller has just been issued, and who it belongs to.
     *
     * <p>{@code token} is the only secret this service ever returns, and it goes
     * straight into a {@code Set-Cookie} header — never a response body, never a
     * log line. {@code expiresAt} is echoed to the client as the cookie's
     * lifetime so a client can pre-empt the expiry instead of discovering it.
     */
    public record IssuedSession(String token, AuthPrincipal principal,
                                OffsetDateTime expiresAt, long maxAgeSeconds) {
    }

    /**
     * Checks a password and, on success, starts a session.
     *
     * <p>Empty means "refused", for every reason, with no distinction available
     * to the caller. A failure is counted and, at the configured threshold,
     * locks the account until {@code lockout-seconds} have passed — a timestamp,
     * so the lock ends by itself and a restart cannot clear it.</p>
     */
    @Transactional
    public Optional<IssuedSession> login(String username, String password) {
        OffsetDateTime now = now();
        String normalized = normalize(username);
        Optional<AuthUser> found = normalized == null
                ? Optional.empty()
                : users.findByUsername(normalized);

        if (found.isEmpty()) {
            // Burn the same work a real check costs, so "no such user" is not
            // the fast path an attacker sorts by.
            hasher.verifyAgainstDummy(password);
            return Optional.empty();
        }

        AuthUser user = found.get();
        if (!user.isActive() || user.isLockedAt(now)) {
            hasher.verifyAgainstDummy(password);
            return Optional.empty();
        }

        if (!hasher.verify(password, user.getPasswordHash())) {
            registerFailure(user, now);
            return Optional.empty();
        }

        // A success clears both halves of the lock, so one good login after the
        // window re-arms the account rather than leaving it one attempt from a
        // re-lock.
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        users.save(user);
        return Optional.of(issue(user, now));
    }

    /**
     * Resolves a raw token to its principal, or empty when it authenticates
     * nothing.
     *
     * <p>One answer for absent, malformed, unknown, expired, revoked and
     * disabled-account: the gates above turn "empty" into a 401 whose text does
     * not vary either. A disabled account is refused here rather than only at
     * login, so disabling someone ends the sessions they already hold instead of
     * leaving them live until the tokens expire.</p>
     */
    @Transactional
    public Optional<AuthPrincipal> authenticate(String rawToken) {
        if (!SessionTokens.looksWellFormed(rawToken)) {
            return Optional.empty();
        }
        OffsetDateTime now = now();
        Optional<AuthToken> found = tokens.findByTokenHash(SessionTokens.digest(rawToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AuthToken token = found.get();
        AuthUser user = token.getUser();
        if (user == null || !user.isActive() || !token.isUsableAt(now)) {
            return Optional.empty();
        }
        touch(token, now);
        return Optional.of(principalOf(user));
    }

    /**
     * Ends the session this token names. An unknown or already-revoked token is
     * a no-op rather than an error: a client logging out twice, or logging out
     * after its session expired, has got what it wanted.
     */
    @Transactional
    public void logout(String rawToken) {
        if (!SessionTokens.looksWellFormed(rawToken)) {
            return;
        }
        tokens.findByTokenHash(SessionTokens.digest(rawToken)).ifPresent(token -> {
            token.revoke(now());
            tokens.save(token);
        });
    }

    /**
     * Replaces a password, and the session it was changed from.
     *
     * <p>Empty means the current password was wrong — a 401 to the caller, not a
     * validation error, because the session is not proof that the person at the
     * keyboard knows the password. On success every session the account holds is
     * revoked and a fresh one is issued: a password change is what someone does
     * about a suspected compromise, so leaving the old sessions alive would
     * defeat the point. The caller's own token is replaced too, and its
     * replacement is the return value.</p>
     */
    @Transactional
    public Optional<IssuedSession> changePassword(AuthPrincipal principal,
                                                  String currentPassword,
                                                  String newPassword) {
        AuthUser user = requireAccount(principal);
        if (!hasher.verify(currentPassword, user.getPasswordHash())) {
            return Optional.empty();
        }
        if (hasher.verify(newPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, AuthRefusals.PASSWORD_UNCHANGED);
        }
        requireAcceptablePassword(newPassword);

        OffsetDateTime now = now();
        user.setPasswordHash(hasher.hash(newPassword));
        user.setMustChangePassword(false);
        user.setPasswordChangedAt(now);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        users.save(user);

        revokeAllSessions(user.getId(), now);
        log.info("Password changed for account {}", user.getUsername());
        return Optional.of(issue(user, now));
    }

    // ------------------------------------------------------------------ bootstrap

    /**
     * Creates the first supervisor, and only the first.
     *
     * <p>Runs at startup when {@code MOSIP_AUTH_BOOTSTRAP_USERNAME} and
     * {@code MOSIP_AUTH_BOOTSTRAP_PASSWORD} are set. Returns {@code false}
     * without doing anything when any account already exists — which is what
     * makes the variables safe to leave in a deployment's environment: they
     * cannot re-create a known account, cannot reset anyone's password, and a
     * second boot is a no-op rather than a privilege grant. Recovery from a lost
     * supervisor is the same mechanism, run deliberately against an emptied or
     * hand-corrected table.</p>
     *
     * <p>The account starts with {@code mustChangePassword = true}, so the
     * bootstrap password cannot quietly become permanent: the filter confines
     * that session to {@code /auth/me}, {@code /auth/change-password} and
     * {@code /auth/logout} until it is replaced.</p>
     *
     * <p>Two nodes booting at once both see an empty table, so the insert is
     * also allowed to lose the race on the unique username index and report a
     * no-op — the alternative is a crash loop on a healthy cluster.</p>
     */
    @Transactional
    public boolean bootstrapSupervisor(String username, String password) {
        if (users.count() > 0) {
            return false;
        }
        AuthUser supervisor;
        try {
            supervisor = createAccount(username, AuthRole.SUPERVISOR, password);
        } catch (DataIntegrityViolationException raced) {
            // Another instance inserted first; that is the outcome we wanted.
            return false;
        }
        // Logs the account name and nothing else — no password, and no token.
        log.info("Bootstrapped the first supervisor account: {}", supervisor.getUsername());
        return true;
    }

    /**
     * Development-only: makes a known account usable without the first-password
     * hand-off.
     *
     * <p>The production bootstrap deliberately sets {@code mustChangePassword = true},
     * and that is the right default for a value that arrived through an environment
     * variable. The {@code dev} profile is different: its database is recreated on
     * every boot, its credentials are printed on the role cards, and a demo that
     * stops at a password-change dialog has not demonstrated anything. The
     * {@code DevAuthUserBootstrap} runner is the only caller.</p>
     *
     * <p>It rewrites the demo account's hash on every dev boot. That is deliberate:
     * there is no durable data to preserve, and a password changed in a previous run
     * must not silently make the printed default stop working.</p>
     */
    AuthUser seedDevelopmentAccount(String username, AuthRole role, String password) {
        String normalized = validateUsername(username);
        requireAcceptablePassword(password);
        AuthUser user = users.findByUsername(normalized).orElseGet(() ->
                AuthUser.builder().username(normalized).build());
        user.setRole(role);
        user.setPasswordHash(hasher.hash(password));
        user.setMustChangePassword(false);
        user.setDisabled(false);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setPasswordChangedAt(now());
        return users.save(user);
    }

    // ------------------------------------------------------------------ user management

    /** Every account, by name, as a supervisor sees the list. */
    @Transactional(readOnly = true)
    public List<AuthUser> listUsers() {
        return users.findAllByOrderByUsernameAsc();
    }

    /**
     * Whether any account exists at all. Read by the startup check that warns a
     * deployment with no accounts and no bootstrap credentials that its
     * privileged surface is unreachable.
     */
    @Transactional(readOnly = true)
    public boolean hasAccounts() {
        return users.count() > 0;
    }

    /**
     * The account behind a resolved principal, for {@code GET /auth/me}.
     *
     * <p>Empty when the principal has no account (the operator key) or the
     * account has since been removed or disabled — a session that outlived its
     * account must answer as unauthenticated rather than report a stale
     * identity.</p>
     */
    @Transactional(readOnly = true)
    public Optional<AuthUser> currentUser(AuthPrincipal principal) {
        if (principal == null || principal.fromSharedKey()) {
            return Optional.empty();
        }
        return users.findById(principal.userId()).filter(AuthUser::isActive);
    }

    /** Creates an account. It always starts owing a password change. */
    @Transactional
    public AuthUser createUser(String username, String role, String initialPassword) {
        return createAccount(username, parseRole(role), initialPassword);
    }

    /**
     * Applies whichever of role, disabled and password the caller supplied,
     * leaving the rest alone.
     *
     * <p>Last-supervisor protection runs before anything is written and covers
     * both halves of losing a supervisor: a demotion to {@code OPERATOR} and a
     * disable. Losing the ability to administer the service is the one user
     * change that cannot be undone through the API — nobody would be left who
     * may undo it — so it is refused rather than warned about.</p>
     */
    @Transactional
    public AuthUser updateUser(String username, String role, Boolean disabled, String newPassword) {
        AuthUser user = requireAccountByUsername(username);
        AuthRole targetRole = role == null ? user.getRole() : parseRole(role);
        boolean targetDisabled = disabled == null ? user.isDisabled() : disabled;

        boolean wasEnabledSupervisor = user.getRole() == AuthRole.SUPERVISOR && !user.isDisabled();
        boolean stillEnabledSupervisor = targetRole == AuthRole.SUPERVISOR && !targetDisabled;
        if (wasEnabledSupervisor && !stillEnabledSupervisor) {
            requireAnotherEnabledSupervisor(user.getId());
        }

        user.setRole(targetRole);
        user.setDisabled(targetDisabled);

        if (newPassword != null && !newPassword.isBlank()) {
            requireAcceptablePassword(newPassword);
            user.setPasswordHash(hasher.hash(newPassword));
            // A supervisor-set password is an initial one, never the account's
            // final password, and the account's existing sessions die with the
            // old one — otherwise a reset would leave the previous holder signed
            // in.
            user.setMustChangePassword(true);
            user.setPasswordChangedAt(now());
            user.setFailedAttempts(0);
            user.setLockedUntil(null);
            AuthUser saved = users.save(user);
            revokeAllSessions(saved.getId(), now());
            log.info("Password reset for account {}", saved.getUsername());
            return saved;
        }

        AuthUser saved = users.save(user);
        if (targetDisabled) {
            // A disable is an immediate revocation, not just a login refusal.
            revokeAllSessions(saved.getId(), now());
            log.info("Account disabled: {}", saved.getUsername());
        }
        return saved;
    }

    /**
     * Removes an account, with the same last-supervisor protection as a
     * demotion. Its sessions go with it through the table's cascade.
     *
     * <p>No audit entry is written here, and that is a known gap rather than an
     * oversight: {@code audit_logs} chains only {@code CONFIG_CHANGED} today, so
     * the account changes a supervisor makes are recorded in the service log and
     * not in the tamper-evident trail. Extending the chain to user events is
     * deliberately left out of this change because the chaining logic is
     * security-critical and single-owner; see the note in docs/brain.md.</p>
     */
    @Transactional
    public void deleteUser(String username) {
        AuthUser user = requireAccountByUsername(username);
        if (user.getRole() == AuthRole.SUPERVISOR && !user.isDisabled()) {
            requireAnotherEnabledSupervisor(user.getId());
        }
        users.delete(user);
        log.info("Account deleted: {}", user.getUsername());
    }

    // ------------------------------------------------------------------ internals

    private AuthUser createAccount(String username, AuthRole role, String password) {
        String normalized = validateUsername(username);
        if (users.existsByUsername(normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, AuthRefusals.USER_EXISTS);
        }
        requireAcceptablePassword(password);
        return users.save(AuthUser.builder()
                .username(normalized)
                .role(role)
                .passwordHash(hasher.hash(password))
                // Not configurable: every password the service hands out is an
                // initial one.
                .mustChangePassword(true)
                .disabled(false)
                .build());
    }

    /** Mints a token, stores its digest, and answers both the token and who it is for. */
    private IssuedSession issue(AuthUser user, OffsetDateTime now) {
        String token = SessionTokens.mint();
        OffsetDateTime expiresAt = now.plusSeconds(tokenTtlSeconds);
        tokens.save(AuthToken.builder()
                .user(user)
                .tokenHash(SessionTokens.digest(token))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build());
        // The cookie's lifetime comes from here rather than from a second read
        // of the configured TTL, so the value the browser drops the cookie at is
        // the value the row actually expires at.
        return new IssuedSession(token, principalOf(user), expiresAt, tokenTtlSeconds);
    }

    private void revokeAllSessions(UUID userId, OffsetDateTime now) {
        List<AuthToken> open = tokens.findByUserIdAndRevokedAtIsNull(userId);
        open.forEach(token -> token.revoke(now));
        tokens.saveAll(open);
    }

    private void touch(AuthToken token, OffsetDateTime now) {
        OffsetDateTime last = token.getLastUsedAt();
        if (last == null || last.plus(LAST_USED_REFRESH).isBefore(now)) {
            token.setLastUsedAt(now);
            tokens.save(token);
        }
    }

    private void registerFailure(AuthUser user, OffsetDateTime now) {
        int attempts = user.getFailedAttempts() + 1;
        user.setFailedAttempts(attempts);
        if (attempts >= maxFailedAttempts) {
            user.setLockedUntil(now.plusSeconds(lockoutSeconds));
        }
        users.save(user);
    }

    private AuthUser requireAccount(AuthPrincipal principal) {
        if (principal == null || principal.fromSharedKey()) {
            // A shared key has no account to change a password on, so it can
            // never reach the change-password path — refuse as unauthenticated
            // rather than as a missing account.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, AuthRefusals.UNAUTHENTICATED);
        }
        return users.findById(principal.userId())
                .filter(AuthUser::isActive)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, AuthRefusals.UNAUTHENTICATED));
    }

    private AuthUser requireAccountByUsername(String username) {
        String normalized = normalize(username);
        if (normalized == null) {
            throw noSuchUser();
        }
        return users.findByUsername(normalized).orElseThrow(AuthService::noSuchUser);
    }

    /** An unknown account is a 404 on the management routes, never a silent no-op. */
    private static ResponseStatusException noSuchUser() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, AuthRefusals.NO_SUCH_USER);
    }

    /**
     * Whether removing {@code excludedId} from the enabled supervisors would
     * leave none. Checked inside the same transaction as the write, so two
     * concurrent demotions cannot both see "one other supervisor" and both
     * succeed.
     */
    private void requireAnotherEnabledSupervisor(UUID excludedId) {
        boolean another = users.findByRoleAndDisabledFalse(AuthRole.SUPERVISOR).stream()
                .anyMatch(candidate -> !candidate.getId().equals(excludedId));
        if (!another) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, AuthRefusals.LAST_SUPERVISOR);
        }
    }

    private AuthPrincipal principalOf(AuthUser user) {
        return new AuthPrincipal(user.getId(), user.getUsername(), user.getRole(),
                user.isMustChangePassword());
    }

    private static void requireAcceptablePassword(String password) {
        if (!PasswordHasher.isAcceptable(password)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, AuthRefusals.WEAK_PASSWORD);
        }
    }

    private static AuthRole parseRole(String role) {
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, AuthRefusals.BAD_ROLE);
        }
        try {
            return AuthRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, AuthRefusals.BAD_ROLE);
        }
    }

    private static String validateUsername(String username) {
        String normalized = normalize(username);
        if (normalized == null || !USERNAME.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, AuthRefusals.BAD_USERNAME);
        }
        return normalized;
    }

    /**
     * The stored and looked-up form of a login name, or {@code null} when there
     * is nothing to look up. Lowercasing here (rather than relying on a database
     * collation) is what lets {@code uq_auth_users_username} be a plain unique
     * index on both H2 and PostgreSQL.
     */
    private static String normalize(String username) {
        if (username == null) {
            return null;
        }
        String trimmed = username.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
