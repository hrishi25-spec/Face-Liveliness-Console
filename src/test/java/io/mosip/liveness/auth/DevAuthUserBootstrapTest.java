package io.mosip.liveness.auth;

import io.mosip.liveness.models.enums.AuthRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The dev-only demo seeder: both printed role cards, and only under {@code dev}.
 *
 * <p>The profile annotation is what keeps this out of a deployment; the unit test
 * pins the other half — that the runner seeds the same supervisor pair the boot
 * used and a separate operator pair, rather than inventing a third credential.</p>
 */
class DevAuthUserBootstrapTest {

    private AuthService authService;
    private DevAuthUserBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        bootstrap = new DevAuthUserBootstrap(authService);
        ReflectionTestUtils.setField(bootstrap, "supervisorUsername", "supervisor");
        ReflectionTestUtils.setField(bootstrap, "supervisorPassword", "supervisor123");
        ReflectionTestUtils.setField(bootstrap, "operatorUsername", "operator");
        ReflectionTestUtils.setField(bootstrap, "operatorPassword", "operator1234");
    }

    @Test
    @DisplayName("seeds the supervisor and operator demo accounts")
    void seedsBothDemoAccounts() {
        bootstrap.run(new DefaultApplicationArguments());

        verify(authService).seedDevelopmentAccount("supervisor", AuthRole.SUPERVISOR, "supervisor123");
        verify(authService).seedDevelopmentAccount("operator", AuthRole.OPERATOR, "operator1234");
    }

    @Test
    @DisplayName("a blank supervisor pair does not invent a supervisor")
    void blankSupervisorPairIsSkipped() {
        ReflectionTestUtils.setField(bootstrap, "supervisorUsername", "");
        ReflectionTestUtils.setField(bootstrap, "supervisorPassword", "");

        bootstrap.run(new DefaultApplicationArguments());

        verify(authService, never()).seedDevelopmentAccount(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(AuthRole.SUPERVISOR),
                org.mockito.ArgumentMatchers.anyString());
        verify(authService).seedDevelopmentAccount("operator", AuthRole.OPERATOR, "operator1234");
    }
}
