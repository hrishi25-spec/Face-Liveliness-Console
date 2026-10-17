package io.mosip.liveness.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The startup bootstrap: when it acts, when it must not, and what it says.
 *
 * <p>The rule that carries the most weight is the negative one. These variables sit
 * in a deployment's environment and are easy to forget, so "does nothing unless the
 * database has no accounts at all" is what makes leaving them set a no-op instead of
 * a way to recreate a known account or reset a password. The second-boot case is
 * therefore asserted directly, at the runner's level and at the service's (see
 * {@code UserManagementTest.bootstrapRunsExactlyOnce}).</p>
 *
 * <p>The other behaviour worth pinning is the warning: a deployment with no accounts
 * and no bootstrap variables has locked itself out of user management, and nothing
 * else at startup would mention it. It is a warning rather than a failure — the
 * service is correctly closed, not broken — so it is asserted as a log line.</p>
 */
class AuthUserBootstrapTest {

    private AuthService authService;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger root;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        logs.stop();
    }

    @Test
    @DisplayName("nothing configured means nothing happens")
    void absentVariablesDoNothing() {
        AuthUserBootstrap bootstrap = bootstrap("", "", "dev");

        bootstrap.run(new DefaultApplicationArguments());

        verify(authService, never()).bootstrapSupervisor(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("a configured pair creates the first supervisor")
    void configuredVariablesCreateTheSupervisor() {
        when(authService.bootstrapSupervisor("first-supervisor", "boot-password-1234")).thenReturn(true);
        AuthUserBootstrap bootstrap = bootstrap("first-supervisor", "boot-password-1234", "dev");

        bootstrap.run(new DefaultApplicationArguments());

        verify(authService).bootstrapSupervisor("first-supervisor", "boot-password-1234");
        assertTrue(lines().stream().anyMatch(line -> line.contains("Bootstrap supervisor created")),
                "an operator has to be told the bootstrap password must now be replaced: " + lines());
        // The line must name the account and nothing else — no password, no token.
        assertTrue(lines().stream().noneMatch(line -> line.contains("boot-password-1234")), lines().toString());
    }

    @Test
    @DisplayName("an existing account turns the configured pair into a no-op")
    void anExistingAccountMakesItANoOp() {
        when(authService.bootstrapSupervisor("first-supervisor", "boot-password-1234")).thenReturn(false);
        AuthUserBootstrap bootstrap = bootstrap("first-supervisor", "boot-password-1234", "dev");

        bootstrap.run(new DefaultApplicationArguments());

        assertTrue(lines().stream().anyMatch(line -> line.contains("already exists")), lines().toString());
        assertTrue(lines().stream().noneMatch(line -> line.contains("boot-password-1234")), lines().toString());
    }

    /**
     * Production with no accounts and no bootstrap variables: user management is
     * unreachable, and the operator should hear about it at boot rather than at the
     * moment they need it.
     */
    @Test
    @DisplayName("production with no accounts and no bootstrap variables warns")
    void productionWithNoAccountsWarns() {
        when(authService.hasAccounts()).thenReturn(false);
        AuthUserBootstrap bootstrap = bootstrap("", "", "prod");

        bootstrap.run(new DefaultApplicationArguments());

        assertTrue(lines().stream().anyMatch(line -> line.contains("unreachable")), lines().toString());
        assertTrue(lines().stream().anyMatch(line -> line.contains("MOSIP_AUTH_BOOTSTRAP_USERNAME")), lines().toString());
    }

    /** The warning is production-only; a dev boot against a fresh in-memory database is not news. */
    @Test
    @DisplayName("the dev profile stays quiet about an empty database")
    void devProfileDoesNotWarn() {
        when(authService.hasAccounts()).thenReturn(false);
        AuthUserBootstrap bootstrap = bootstrap("", "", "dev");

        bootstrap.run(new DefaultApplicationArguments());

        assertFalse(lines().stream().anyMatch(line -> line.contains("unreachable")), lines().toString());
    }

    private AuthUserBootstrap bootstrap(String username, String password, String profile) {
        MockEnvironment environment = new MockEnvironment();
        if (!"dev".equals(profile)) {
            environment.setActiveProfiles(profile);
        } else {
            environment.setActiveProfiles("dev");
        }
        AuthUserBootstrap bootstrap = new AuthUserBootstrap(authService, environment);
        // The two @Value fields are not resolved without a container, which is why
        // they carry blank initializers; set them the way the container would.
        ReflectionTestUtils.setField(bootstrap, "bootstrapUsername", username);
        ReflectionTestUtils.setField(bootstrap, "bootstrapPassword", password);
        return bootstrap;
    }

    private List<String> lines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
