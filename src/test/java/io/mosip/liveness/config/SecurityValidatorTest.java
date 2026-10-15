package io.mosip.liveness.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityValidatorTest {

    // All key values in this class are synthetic.

    @Test
    void devProfileDoesNotRequireProductionSecrets() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        assertDoesNotThrow(() -> new SecurityValidator(environment).run(new DefaultApplicationArguments()));
    }

    /**
     * The production requirement that an admin API key be set is gone with the key.
     * What is left is the database credential — and, crucially, no new requirement
     * in its place: there is no environment-provided supervisor credential to
     * insist on, so a deployment with no accounts and no bootstrap variables is
     * closed rather than rejected. {@code AuthUserBootstrap} warns about that state;
     * it is not a boot failure.
     */
    @Test
    void defaultProfileRequiresTheDatabasePasswordAndNothingElse() {
        MockEnvironment environment = new MockEnvironment();
        SecurityValidator validator = new SecurityValidator(environment);

        assertThrows(IllegalStateException.class,
                () -> validator.run(new DefaultApplicationArguments()));

        environment.setProperty("POSTGRES_PASSWORD", "a-strong-database-password");
        assertDoesNotThrow(() -> validator.run(new DefaultApplicationArguments()));
    }

    /**
     * The operator key is optional everywhere — a blank value closes the key-based
     * path to the operator feeds instead of opening it — so its absence is never the
     * reason a service refuses to start.
     */
    @Test
    void operatorKeyIsOptionalInEveryProfile() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertDoesNotThrow(() -> new SecurityValidator(dev).run(new DefaultApplicationArguments()));

        MockEnvironment production = new MockEnvironment();
        production.setProperty("POSTGRES_PASSWORD", "a-strong-database-password");
        assertDoesNotThrow(() -> new SecurityValidator(production).run(new DefaultApplicationArguments()));
    }

    /**
     * A BCrypt cost below the floor outside dev makes every stored password cheaper
     * to attack offline without changing anything visible at runtime, so it is a
     * startup failure rather than a warning. The dev profile is exempt so the test
     * suite can run at cost 4.
     */
    @Test
    void aWeakBcryptCostIsRefusedOutsideDev() {
        MockEnvironment production = new MockEnvironment();
        production.setProperty("POSTGRES_PASSWORD", "a-strong-database-password");
        production.setProperty("mosip.security.auth.bcrypt-cost", "4");

        assertThrows(IllegalStateException.class,
                () -> new SecurityValidator(production).run(new DefaultApplicationArguments()));

        production.setProperty("mosip.security.auth.bcrypt-cost", "12");
        assertDoesNotThrow(() -> new SecurityValidator(production).run(new DefaultApplicationArguments()));
    }

    /**
     * A half-configured bootstrap is a failed boot: an operator who set only one half
     * would believe recovery was armed when it was not, which is the worst state to
     * be in — they would also believe there was still a way in.
     */
    @Test
    void aBootstrapPairMustBeCompleteOrAbsent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("MOSIP_AUTH_BOOTSTRAP_USERNAME", "first-supervisor");

        assertThrows(IllegalStateException.class,
                () -> new SecurityValidator(environment).run(new DefaultApplicationArguments()));

        environment.setProperty("MOSIP_AUTH_BOOTSTRAP_PASSWORD", "a-long-enough-password");
        assertDoesNotThrow(() -> new SecurityValidator(environment).run(new DefaultApplicationArguments()));
    }

    @Test
    void aShortBootstrapPasswordIsRejectedRatherThanGuessable() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("MOSIP_AUTH_BOOTSTRAP_USERNAME", "first-supervisor");
        environment.setProperty("MOSIP_AUTH_BOOTSTRAP_PASSWORD", "elevenchars"); // 11

        assertThrows(IllegalStateException.class,
                () -> new SecurityValidator(environment).run(new DefaultApplicationArguments()));

        environment.setProperty("MOSIP_AUTH_BOOTSTRAP_PASSWORD", "twelve-chars"); // 12
        assertDoesNotThrow(() -> new SecurityValidator(environment).run(new DefaultApplicationArguments()));
    }

    /** The property form is validated the same way as the environment variable. */
    @Test
    void theBootstrapPropertyFormIsValidatedToo() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("mosip.security.auth.bootstrap-username", "first-supervisor");

        assertThrows(IllegalStateException.class,
                () -> new SecurityValidator(environment).run(new DefaultApplicationArguments()));
    }

    @Test
    void aShortOperatorKeyIsRejectedRatherThanGuessable() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("MOSIP_OPERATOR_API_KEY", "fifteen-chars!!"); // 15

        assertThrows(IllegalStateException.class,
                () -> new SecurityValidator(environment).run(new DefaultApplicationArguments()));

        environment.setProperty("MOSIP_OPERATOR_API_KEY", "sixteen-chars!!!"); // 16
        assertDoesNotThrow(() -> new SecurityValidator(environment).run(new DefaultApplicationArguments()));
    }

    @Test
    void thePropertyFormIsValidatedTheSameWayAsTheEnvironmentVariable() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("mosip.security.operator-api-key", "too-short");

        assertThrows(IllegalStateException.class,
                () -> new SecurityValidator(environment).run(new DefaultApplicationArguments()));
    }
}
