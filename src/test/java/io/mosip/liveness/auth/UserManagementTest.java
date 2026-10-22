package io.mosip.liveness.auth;

import io.mosip.liveness.models.entity.AuthUser;
import io.mosip.liveness.models.enums.AuthRole;
import io.mosip.liveness.testing.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Account administration, and the one change the API must refuse.
 *
 * <p>Losing every enabled supervisor is unrecoverable through the service: user
 * management needs a supervisor, so nobody would be left who could grant the role
 * back. The recovery path is an operator edging the database back to "no accounts"
 * and rebooting with the bootstrap variables, which is deliberate and noisy. Because
 * that is the only way back, the two ways to lose the last supervisor — demoting it
 * and disabling it — plus deleting it all have to be refused, and they all have to
 * be refused by the same rule, so a future route cannot get it half right.</p>
 *
 * <p>The count is over <em>enabled</em> supervisors, which is the subtle half: a
 * disabled supervisor cannot sign in, so letting one satisfy the check would let the
 * last able supervisor be removed while the table still appeared to have one.</p>
 *
 * <p>The bootstrap cases live here too, because "runs only on an empty database" is
 * the same kind of rule: a guard that has to hold on a second boot, not just the
 * first.</p>
 */
@DataJpaTest
@Import({AuthService.class, PasswordHasher.class, UserManagementTest.ClockConfig.class})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "mosip.security.auth.bcrypt-cost=4"
})
class UserManagementTest {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        MutableClock userManagementClock() {
            return new MutableClock(1_800_000_000_000L);
        }
    }

    @Autowired private AuthService authService;

    // ------------------------------------------------------------------ create and list

    @Test
    @DisplayName("a created account is listed, owes a password change and stores only a hash")
    void aCreatedAccountIsListedAndOwesAPasswordChange() {
        AuthUser created = authService.createUser("New.Operator", "operator", "an-initial-password-1");

        // Normalised to lower case on the way in, so the unique index is genuinely
        // case-insensitive without depending on a database collation.
        assertEquals("new.operator", created.getUsername());
        assertEquals(AuthRole.OPERATOR, created.getRole());
        assertTrue(created.isMustChangePassword());
        assertFalse(created.isDisabled());
        assertFalse(created.getPasswordHash().contains("an-initial-password-1"));

        assertEquals(List.of("new.operator"), authService.listUsers().stream()
                .map(AuthUser::getUsername).toList());
    }

    @Test
    @DisplayName("a duplicate username is refused, case-insensitively")
    void duplicateUsernamesAreRefused() {
        authService.createUser("someone", "OPERATOR", "an-initial-password-1");

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> authService.createUser("SOMEONE", "OPERATOR", "another-initial-pw-1"));
        assertEquals(409, refused.getStatusCode().value());
        assertEquals(AuthRefusals.USER_EXISTS, refused.getReason());
    }

    @Test
    @DisplayName("an unknown role and a malformed username are refused with one message each")
    void invalidInputIsRefused() {
        var badRole = assertThrows(ResponseStatusException.class,
                () -> authService.createUser("someone", "ROOT", "an-initial-password-1"));
        assertEquals(AuthRefusals.BAD_ROLE, badRole.getReason());

        var badName = assertThrows(ResponseStatusException.class,
                () -> authService.createUser("a", "OPERATOR", "an-initial-password-1"));
        assertEquals(AuthRefusals.BAD_USERNAME, badName.getReason());

        var badPassword = assertThrows(ResponseStatusException.class,
                () -> authService.createUser("someone", "OPERATOR", "short"));
        assertEquals(AuthRefusals.WEAK_PASSWORD, badPassword.getReason());
    }

    // ------------------------------------------------------------------ last supervisor

    @Test
    @DisplayName("the only supervisor cannot be demoted")
    void theLastSupervisorCannotBeDemoted() {
        authService.createUser("only-supervisor", "SUPERVISOR", "an-initial-password-1");

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> authService.updateUser("only-supervisor", "OPERATOR", null, null));
        assertEquals(409, refused.getStatusCode().value());
        assertEquals(AuthRefusals.LAST_SUPERVISOR, refused.getReason());

        assertEquals(AuthRole.SUPERVISOR,
                authService.listUsers().get(0).getRole(),
                "a refused change must not have been applied");
    }

    @Test
    @DisplayName("the only supervisor cannot be disabled")
    void theLastSupervisorCannotBeDisabled() {
        authService.createUser("only-supervisor", "SUPERVISOR", "an-initial-password-1");

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> authService.updateUser("only-supervisor", null, true, null));
        assertEquals(AuthRefusals.LAST_SUPERVISOR, refused.getReason());
        assertFalse(authService.listUsers().get(0).isDisabled());
    }

    @Test
    @DisplayName("the only supervisor cannot be deleted")
    void theLastSupervisorCannotBeDeleted() {
        authService.createUser("only-supervisor", "SUPERVISOR", "an-initial-password-1");

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> authService.deleteUser("only-supervisor"));
        assertEquals(409, refused.getStatusCode().value());
        assertEquals(AuthRefusals.LAST_SUPERVISOR, refused.getReason());
        assertEquals(1, authService.listUsers().size());
    }

    @Test
    @DisplayName("a second supervisor makes both changes legal")
    void aSecondSupervisorUnblocksTheChange() {
        authService.createUser("supervisor-one", "SUPERVISOR", "an-initial-password-1");
        authService.createUser("supervisor-two", "SUPERVISOR", "an-initial-password-2");

        authService.updateUser("supervisor-one", "OPERATOR", null, null);
        assertEquals(AuthRole.OPERATOR, byName("supervisor-one").getRole());

        // Wait: that demotion left supervisor-two as the only enabled supervisor, so
        // removing it is the very state the rule exists to prevent. Promote the first
        // back before the delete — the point of the test is that each change is legal
        // while another supervisor remains, not that the count is ignored.
        authService.updateUser("supervisor-one", "SUPERVISOR", null, null);
        authService.deleteUser("supervisor-two");
        assertEquals(List.of("supervisor-one"), authService.listUsers().stream()
                .map(AuthUser::getUsername).toList());
    }

    /**
     * The subtle half: a disabled supervisor cannot sign in, so it must not count as
     * the supervisor that keeps the role reachable.
     */
    @Test
    @DisplayName("a disabled supervisor does not count, so the last able one is still protected")
    void aDisabledSupervisorDoesNotSatisfyTheCheck() {
        authService.createUser("supervisor-one", "SUPERVISOR", "an-initial-password-1");
        authService.createUser("supervisor-two", "SUPERVISOR", "an-initial-password-2");

        // Disable one; the other becomes the only able supervisor.
        authService.updateUser("supervisor-one", null, true, null);

        ResponseStatusException refused = assertThrows(ResponseStatusException.class,
                () -> authService.updateUser("supervisor-two", null, true, null));
        assertEquals(AuthRefusals.LAST_SUPERVISOR, refused.getReason());

        // And it may still be demoted once another able supervisor exists again.
        authService.updateUser("supervisor-one", null, false, null);
        authService.updateUser("supervisor-two", "OPERATOR", null, null);
        assertEquals(AuthRole.OPERATOR, byName("supervisor-two").getRole());
    }

    @Test
    @DisplayName("promoting an operator to supervisor is always allowed")
    void promotionIsNeverBlocked() {
        authService.createUser("supervisor-one", "SUPERVISOR", "an-initial-password-1");
        authService.createUser("operator-one", "OPERATOR", "an-initial-password-2");

        authService.updateUser("operator-one", "SUPERVISOR", null, null);

        assertEquals(AuthRole.SUPERVISOR, byName("operator-one").getRole());
        // …and now the original may be demoted.
        authService.updateUser("supervisor-one", "OPERATOR", null, null);
    }

    // ------------------------------------------------------------------ password resets

    @Test
    @DisplayName("a supervisor-set password is an initial one and ends the account's sessions")
    void aResetPasswordIsInitialAndRevokesSessions() {
        authService.createUser("operator-one", "OPERATOR", "an-initial-password-1");
        AuthService.IssuedSession session = authService.login("operator-one", "an-initial-password-1")
                .orElseThrow();
        assertTrue(authService.authenticate(session.token()).isPresent());

        authService.updateUser("operator-one", null, null, "a-reset-password-123");

        assertTrue(authService.authenticate(session.token()).isEmpty(),
                "a reset that left the previous holder signed in would not be a reset");
        assertTrue(authService.login("operator-one", "an-initial-password-1").isEmpty());
        AuthService.IssuedSession afterReset =
                authService.login("operator-one", "a-reset-password-123").orElseThrow();
        assertTrue(afterReset.principal().mustChangePassword(),
                "a service-issued password must never become permanent by inaction");
    }

    @Test
    @DisplayName("an unknown account is a 404, never a silent no-op")
    void unknownAccountsAreNotFound() {
        var refused = assertThrows(ResponseStatusException.class,
                () -> authService.updateUser("nobody", "OPERATOR", null, null));
        assertEquals(404, refused.getStatusCode().value());
        assertEquals(AuthRefusals.NO_SUCH_USER, refused.getReason());

        var deleted = assertThrows(ResponseStatusException.class,
                () -> authService.deleteUser("nobody"));
        assertEquals(404, deleted.getStatusCode().value());
    }

    // ------------------------------------------------------------------ bootstrap

    @Test
    @DisplayName("bootstrap creates the first supervisor once and refuses every later run")
    void bootstrapRunsExactlyOnce() {
        assertTrue(authService.bootstrapSupervisor("first-supervisor", "boot-password-1234"),
                "an empty database is exactly when bootstrap must run");

        assertFalse(authService.bootstrapSupervisor("another-supervisor", "boot-password-5678"),
                "a second run must do nothing: the variables are safe to leave configured");
        assertFalse(authService.bootstrapSupervisor("first-supervisor", "different-password-99"),
                "…and it must never reset an existing password");

        assertEquals(List.of("first-supervisor"), authService.listUsers().stream()
                .map(AuthUser::getUsername).toList());

        // The password it created is the one it was given, and the account owes a
        // change — so the value that opened the door is not the value that keeps it
        // open.
        var session = authService.login("first-supervisor", "boot-password-1234").orElseThrow();
        assertTrue(session.principal().mustChangePassword());
        assertTrue(authService.login("first-supervisor", "different-password-99").isEmpty());
    }

    @Test
    @DisplayName("bootstrap creates nothing once any account exists, supervisor or not")
    void bootstrapRefusesWhenAnyAccountExists() {
        authService.createUser("operator-one", "OPERATOR", "an-initial-password-1");

        assertFalse(authService.bootstrapSupervisor("first-supervisor", "boot-password-1234"),
                "an existing account is enough to close the bootstrap path");

        assertEquals(List.of("operator-one"), authService.listUsers().stream()
                .map(AuthUser::getUsername).toList());
    }

    private AuthUser byName(String username) {
        return authService.listUsers().stream()
                .filter(user -> username.equals(user.getUsername()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such account: " + username));
    }
}
