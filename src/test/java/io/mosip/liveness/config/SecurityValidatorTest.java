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

    @Test
    void devProfileStillRejectsAWeakAdminKeyWhenConfigured() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("MOSIP_ADMIN_API_KEY", "short-key");

        assertThrows(IllegalStateException.class,
                () -> new SecurityValidator(environment).run(new DefaultApplicationArguments()));
    }

    @Test
    void defaultProfileRequiresDatabasePasswordAndAdminKey() {
        MockEnvironment environment = new MockEnvironment();
        SecurityValidator validator = new SecurityValidator(environment);

        assertThrows(IllegalStateException.class,
                () -> validator.run(new DefaultApplicationArguments()));

        environment.setProperty("POSTGRES_PASSWORD", "a-strong-database-password");
        assertThrows(IllegalStateException.class,
                () -> validator.run(new DefaultApplicationArguments()));

        environment.setProperty("MOSIP_ADMIN_API_KEY", "a-strong-admin-key-with-at-least-32-chars");
        assertDoesNotThrow(() -> validator.run(new DefaultApplicationArguments()));
    }

    /**
     * The operator key is optional everywhere — a blank value closes the operator
     * surface instead of opening it — so its absence is never the reason a
     * service refuses to start.
     */
    @Test
    void operatorKeyIsOptionalInEveryProfile() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertDoesNotThrow(() -> new SecurityValidator(dev).run(new DefaultApplicationArguments()));

        MockEnvironment production = new MockEnvironment();
        production.setProperty("POSTGRES_PASSWORD", "a-strong-database-password");
        production.setProperty("MOSIP_ADMIN_API_KEY", "a-strong-admin-key-with-at-least-32-chars");
        assertDoesNotThrow(() -> new SecurityValidator(production).run(new DefaultApplicationArguments()));
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

    /**
     * The admin key is accepted on operator routes, so an identical value would
     * make the two roles indistinguishable — every caller would be told
     * SUPERVISOR. Nothing would show up at runtime, so it is a startup failure.
     */
    @Test
    void theOperatorKeyMustDifferFromTheAdminKey() {
        String shared = "a-shared-key-that-is-long-enough";
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("MOSIP_ADMIN_API_KEY", shared);
        environment.setProperty("MOSIP_OPERATOR_API_KEY", shared);

        assertThrows(IllegalStateException.class,
                () -> new SecurityValidator(environment).run(new DefaultApplicationArguments()));

        environment.setProperty("MOSIP_OPERATOR_API_KEY", shared + "-different");
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
