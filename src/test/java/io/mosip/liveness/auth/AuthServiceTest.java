package io.mosip.liveness.auth;

import io.mosip.liveness.crud.AuthTokenRepository;
import io.mosip.liveness.crud.AuthUserRepository;
import io.mosip.liveness.models.entity.AuthToken;
import io.mosip.liveness.models.entity.AuthUser;
import io.mosip.liveness.models.enums.AuthRole;
import io.mosip.liveness.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * Accounts and sessions, against real repositories on a real database.
 *
 * <p>The behaviours here are the ones a review would ask about, and each is about
 * elapsed time or about what is <em>not</em> stored:</p>
 *
 * <ul>
 *   <li><b>Lockout, then recovery.</b> N consecutive failures lock the account; the
 *       correct password is then refused; once the window lapses the same correct
 *       password works and the counter is clear. Driven by a movable clock, because
 *       the alternative is a test that sleeps through the production fifteen
 *       minutes.</li>
 *   <li><b>Expiry and revocation.</b> Both refuse, and both refuse identically —
 *       {@code Optional.empty()}, no reason, no distinction. A caller that could
 *       tell "expired" from "revoked" would learn whether a session it holds was
 *       ended deliberately.</li>
 *   <li><b>What the database holds.</b> The password column is a BCrypt hash and
 *       never the password; the token column is a digest and never the token. Those
 *       two assertions are the reason a dumped database is not a set of credentials.
 *       </li>
 *   <li><b>The unknowable username costs the same.</b> Asserted by observing that
 *       the timing-equalisation call actually happened, not by measuring wall clock —
 *       a timing assertion on a loaded CI runner is a flaky test, and the property
 *       worth pinning is that the dummy verification is on the path at all.</li>
 * </ul>
 *
 * <p>BCrypt runs at cost 4 here (set below) rather than 12, which is the only reason
 * this class is fast; {@code SecurityValidatorTest} pins that a low cost is refused
 * outside the dev profile, so this cannot leak into a deployment.</p>
 */
@DataJpaTest
@Import({AuthService.class, PasswordHasher.class, AuthServiceTest.ClockConfig.class})
@TestPropertySource(properties = {
        // Flyway is off because the committed migrations are PostgreSQL-specific from
        // V5 onwards (JSONB, PL/pgSQL triggers); the schema here comes from the
        // entities, which is the mapping this class is about.
        // PostgresSchemaTest owns the migrations-on-PostgreSQL half, and
        // V12MigrationH2Test owns the new migration on H2.
        "spring.flyway.enabled=false",
        // application.yml pins validate for production; a slice has no migrated schema
        // to validate against, so the entities build it.
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "mosip.security.auth.bcrypt-cost=4",
        "mosip.security.auth.token-ttl-seconds=60",
        "mosip.security.auth.max-failed-attempts=3",
        "mosip.security.auth.lockout-seconds=300"
})
class AuthServiceTest {

    @TestConfiguration
    static class ClockConfig {
        /** The clock every case moves by hand; see the class comment. */
        @Bean
        MutableClock authTestClock() {
            return new MutableClock(1_800_000_000_000L); // fixed, so nothing depends on "now"
        }
    }

    private static final String USERNAME = "test-supervisor";
    private static final String PASSWORD = "initial-password-123";

    @Autowired private AuthService authService;
    @Autowired private AuthUserRepository users;
    @Autowired private AuthTokenRepository tokens;
    @Autowired private MutableClock clock;

    /**
     * Spied so the timing-equalisation on an unknown username can be observed
     * directly. A spy rather than a measurement: {@code verify} says the dummy
     * verification ran, which is the property; a stopwatch would say the machine was
     * quiet.
     */
    @MockitoSpyBean private PasswordHasher passwordHasher;

    private AuthUser account;

    @BeforeEach
    void createAnAccount() {
        account = authService.createUser(USERNAME, "SUPERVISOR", PASSWORD);
    }

    // ------------------------------------------------------------------ login

    @Test
    @DisplayName("the right password starts a session and the database never holds the token")
    void loginWithTheRightPassword_startsASession() {
        AuthService.IssuedSession session = login(PASSWORD);

        assertNotNull(session.token());
        assertEquals(USERNAME, session.principal().username());
        assertEquals(AuthRole.SUPERVISOR, session.principal().role());

        // The row exists and is keyed by the digest of the token. The token itself
        // appears nowhere in the table — that is the whole point of storing a digest.
        Optional<AuthToken> stored = tokens.findByTokenHash(SessionTokens.digest(session.token()));
        assertTrue(stored.isPresent(), "the session must be recorded");
        assertFalse(tokens.findAll().stream()
                        .anyMatch(row -> session.token().equals(row.getTokenHash())),
                "the raw token must never be stored");
    }

    @Test
    @DisplayName("a created account owes a password change and its password is only ever a hash")
    void aNewAccountOwesAPasswordChange() {
        assertTrue(account.isMustChangePassword(),
                "a service-issued password must never become permanent by inaction");
        assertNotEquals(PASSWORD, account.getPasswordHash());
        assertTrue(account.getPasswordHash().startsWith("$2"),
                "the stored value must be a BCrypt hash, not a password: " + account.getPasswordHash());
        assertFalse(account.getPasswordHash().contains(PASSWORD));
    }

    @Test
    @DisplayName("the wrong password is refused and counted")
    void loginWithTheWrongPassword_isRefusedAndCounted() {
        assertTrue(authService.login(USERNAME, "not-the-password-1").isEmpty());
        assertEquals(1, users.findByUsername(USERNAME).orElseThrow().getFailedAttempts());
    }

    @Test
    @DisplayName("an unknown username is refused, and still pays a verification")
    void loginForAnUnknownUsername_isRefusedAndTimingEqualised() {
        assertTrue(authService.login("nobody-here", PASSWORD).isEmpty(),
                "an unknown account must authenticate nobody");

        // The property worth pinning: the refusal ran the same work a real check
        // would, so the response time does not reveal which usernames exist.
        verify(passwordHasher).verifyAgainstDummy(PASSWORD);
    }

    @Test
    @DisplayName("a disabled account is refused without being told apart from a wrong password")
    void loginForADisabledAccount_isRefused() {
        account.setDisabled(true);
        users.saveAndFlush(account);

        assertTrue(authService.login(USERNAME, PASSWORD).isEmpty());
        verify(passwordHasher).verifyAgainstDummy(PASSWORD);
    }

    // ------------------------------------------------------------------ lockout

    @Test
    @DisplayName("failures lock the account, the lock refuses the right password, and it lapses")
    void lockoutRefusesEvenTheRightPasswordUntilItLapses() {
        int threshold = 3; // mosip.security.auth.max-failed-attempts in this class

        for (int attempt = 0; attempt < threshold; attempt++) {
            assertTrue(authService.login(USERNAME, "wrong-password-" + attempt).isEmpty());
        }

        AuthUser locked = users.findByUsername(USERNAME).orElseThrow();
        assertNotNull(locked.getLockedUntil(), "the threshold must set a lock");
        assertTrue(locked.isLockedAt(OffsetDateTime.now(clock)));

        // The correct password is refused while locked — that is what makes it a
        // lockout rather than a delay.
        assertTrue(authService.login(USERNAME, PASSWORD).isEmpty(),
                "a locked account must refuse even its own password");

        // Advance past the window without changing anything else: the lock ends by
        // itself, from a stored timestamp rather than a reaper job.
        clock.advanceMillis(301_000L);

        AuthService.IssuedSession recovered = login(PASSWORD);
        assertNotNull(recovered.token(), "the account must recover on its own");

        AuthUser after = users.findByUsername(USERNAME).orElseThrow();
        assertNull(after.getLockedUntil(), "a successful sign-in must clear the lock");
        assertEquals(0, after.getFailedAttempts(), "and the failure counter with it");
    }

    @Test
    @DisplayName("a successful sign-in before the threshold clears the counter, so failures cannot accumulate forever")
    void aSuccessResetsTheFailureCount() {
        assertTrue(authService.login(USERNAME, "wrong-password-0").isEmpty());
        assertTrue(authService.login(USERNAME, "wrong-password-1").isEmpty());
        assertEquals(2, users.findByUsername(USERNAME).orElseThrow().getFailedAttempts());

        login(PASSWORD);

        assertEquals(0, users.findByUsername(USERNAME).orElseThrow().getFailedAttempts());
    }

    // ------------------------------------------------------------------ expiry and revocation

    @Test
    @DisplayName("an expired token authenticates nobody")
    void anExpiredTokenIsRefused() {
        AuthService.IssuedSession session = login(PASSWORD);
        assertTrue(authService.authenticate(session.token()).isPresent(),
                "the session must work while it is fresh");

        // mosip.security.auth.token-ttl-seconds is 60 in this class.
        clock.advanceMillis(61_000L);

        assertTrue(authService.authenticate(session.token()).isEmpty(),
                "a token past its expiry must resolve to nothing");
    }

    @Test
    @DisplayName("a revoked token authenticates nobody, indistinguishably from an expired one")
    void aRevokedTokenIsRefused() {
        AuthService.IssuedSession session = login(PASSWORD);
        authService.logout(session.token());

        Optional<AuthPrincipal> after = authService.authenticate(session.token());
        assertTrue(after.isEmpty(), "a signed-out session must not authenticate");

        // The row is kept, not deleted: "this session was ended" stays answerable,
        // and the refusal is the same empty Optional an expiry produces.
        AuthToken row = tokens.findByTokenHash(SessionTokens.digest(session.token())).orElseThrow();
        assertNotNull(row.getRevokedAt());
    }

    @Test
    @DisplayName("signing out twice is not an error")
    void logoutIsIdempotent() {
        AuthService.IssuedSession session = login(PASSWORD);

        authService.logout(session.token());
        authService.logout(session.token());
        authService.logout("not-a-real-token");
        authService.logout(null);

        assertTrue(authService.authenticate(session.token()).isEmpty());
    }

    @Test
    @DisplayName("everything that is not a session is refused: blank, malformed and unknown tokens")
    void junkTokensAreRefused() {
        assertTrue(authService.authenticate(null).isEmpty());
        assertTrue(authService.authenticate("").isEmpty());
        assertTrue(authService.authenticate("   ").isEmpty());
        assertTrue(authService.authenticate("short").isEmpty());
        assertTrue(authService.authenticate(SessionTokens.mint()).isEmpty(),
                "a well-formed token nobody ever issued is still nobody");
    }

    @Test
    @DisplayName("disabling an account ends the sessions it already holds")
    void disablingAnAccountKillsItsLiveSessions() {
        AuthService.IssuedSession session = login(PASSWORD);
        assertTrue(authService.authenticate(session.token()).isPresent());

        account = users.findByUsername(USERNAME).orElseThrow();
        account.setDisabled(true);
        users.saveAndFlush(account);

        assertTrue(authService.authenticate(session.token()).isEmpty(),
                "a disable must revoke access immediately, not at the next expiry");
    }

    // ------------------------------------------------------------------ change password

    @Test
    @DisplayName("changing the password revokes every session and mints a replacement")
    void changePasswordRotatesEverySession() {
        AuthService.IssuedSession first = login(PASSWORD);
        AuthService.IssuedSession second = login(PASSWORD);
        assertTrue(authService.authenticate(first.token()).isPresent());
        assertTrue(authService.authenticate(second.token()).isPresent());

        String replaced = "a-brand-new-password-1";
        AuthService.IssuedSession rotated = authService
                .changePassword(first.principal(), PASSWORD, replaced)
                .orElseThrow(() -> new AssertionError("the current password is right, so this must succeed"));

        // A password change is what someone does about a suspected compromise, so
        // every session the account held — including the one that made the call —
        // must be gone, and the new one must work.
        assertTrue(authService.authenticate(first.token()).isEmpty(),
                "the session that changed the password must not survive it");
        assertTrue(authService.authenticate(second.token()).isEmpty(),
                "…nor any other session the account held");
        assertTrue(authService.authenticate(rotated.token()).isPresent(),
                "the replacement must work");

        assertFalse(users.findByUsername(USERNAME).orElseThrow().isMustChangePassword(),
                "replacing the password settles what was owed");
        assertTrue(authService.login(USERNAME, replaced).isPresent(),
                "the new password must be the one that works now");
        assertTrue(authService.login(USERNAME, PASSWORD).isEmpty(),
                "the old password must stop working");
    }

    @Test
    @DisplayName("a wrong current password changes nothing")
    void changePasswordWithAWrongCurrentPassword_isRefused() {
        AuthService.IssuedSession session = login(PASSWORD);

        assertTrue(authService
                        .changePassword(session.principal(), "not-the-current-one", "a-new-password-1234")
                        .isEmpty(),
                "holding a session is not proof of knowing the password");

        assertTrue(authService.authenticate(session.token()).isPresent(),
                "a refused change must not revoke anything");
        assertTrue(authService.login(USERNAME, PASSWORD).isPresent(),
                "…nor change the password");
    }

    @Test
    @DisplayName("the new password must differ from the old one")
    void changePasswordToTheSamePassword_isRefused() {
        AuthService.IssuedSession session = login(PASSWORD);

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> authService.changePassword(session.principal(), PASSWORD, PASSWORD));

        assertEquals(400, refused.getStatusCode().value());
        assertEquals(AuthRefusals.PASSWORD_UNCHANGED, refused.getReason());
    }

    @Test
    @DisplayName("a password that is too short, or longer than BCrypt can take, is refused")
    void weakPasswordsAreRefused() {
        AuthService.IssuedSession session = login(PASSWORD);

        var tooShort = assertThrows(ResponseStatusException.class,
                () -> authService.changePassword(session.principal(), PASSWORD, "elevenchars"));
        assertEquals(AuthRefusals.WEAK_PASSWORD, tooShort.getReason());

        // BCrypt only reads the first 72 bytes and refuses more rather than silently
        // truncating — truncation would make two different passwords equivalent.
        var tooLong = assertThrows(ResponseStatusException.class,
                () -> authService.changePassword(session.principal(), PASSWORD, "x".repeat(73)));
        assertEquals(AuthRefusals.WEAK_PASSWORD, tooLong.getReason());
    }

    @Test
    @DisplayName("the operator key has no account, so it can never change a password")
    void aSharedKeyCannotChangeAPassword() {
        var refused = assertThrows(ResponseStatusException.class,
                () -> authService.changePassword(AuthPrincipal.operatorKey(), PASSWORD, "a-new-password-1234"));
        assertEquals(401, refused.getStatusCode().value());
    }

    @Test
    @DisplayName("a development demo account is usable without the first-password change")
    void developmentSeeding_createsAUsableAccount() {
        AuthUser operator = authService.seedDevelopmentAccount(
                "demo-operator", AuthRole.OPERATOR, "operator1234");

        assertEquals(AuthRole.OPERATOR, operator.getRole());
        assertFalse(operator.isMustChangePassword(),
                "the dev role card is a one-click demo, not a first-password hand-off");
        assertNotEquals("operator1234", operator.getPasswordHash(),
                "the seeded value is still stored only as a hash");
        assertTrue(authService.login("demo-operator", "operator1234").isPresent(),
                "the printed credential must actually open a session");
    }

    // ------------------------------------------------------------------ helpers

    private AuthService.IssuedSession login(String password) {
        return authService.login(USERNAME, password)
                .orElseThrow(() -> new AssertionError("expected a session for " + USERNAME));
    }
}
