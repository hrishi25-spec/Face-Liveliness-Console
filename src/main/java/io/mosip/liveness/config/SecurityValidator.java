package io.mosip.liveness.config;

import io.mosip.liveness.auth.PasswordHasher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;


/**
 * Validates production credentials and the authentication settings on startup.
 *
 * <p>The {@code dev} profile keeps administration reachable with no configuration
 * at all; every other profile refuses to start with a weak or missing production
 * secret. Failing here rather than on the first request is deliberate: a service
 * that boots with a guessable credential is a service that is already
 * compromised, and an operator only finds out from a log line they never
 * read.</p>
 *
 * <p><b>What is no longer checked, and why.</b> The production requirement that
 * {@code MOSIP_ADMIN_API_KEY} be set is gone with the key itself. There is no
 * environment-provided supervisor credential any more, so there is nothing here
 * to require: the privileged surface is reached with an account, created either
 * by another account or by {@code AuthUserBootstrap} on a fresh database. A
 * deployment that sets neither is closed rather than weak, which is a state this
 * class has nothing to add to.</p>
 */
@Component
public class SecurityValidator implements ApplicationRunner {

    private final Environment environment;

    @Autowired
    public SecurityValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean production = !environment.acceptsProfiles(Profiles.of("dev"));
        if (production) {
            validatePostgresPassword();
            validateBcryptCost();
        }
        validateOperatorApiKey();
        validateBootstrapCredentials();
    }

    private void validatePostgresPassword() {
        String password = environment.getProperty("POSTGRES_PASSWORD");
        if (password == null || password.isBlank()) {
            password = environment.getProperty("spring.datasource.password");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("POSTGRES_PASSWORD must be set");
        }
        if ("change_me".equals(password)) {
            throw new IllegalStateException("POSTGRES_PASSWORD must not be the default value 'change_me'");
        }
        if (password.length() < 8) {
            throw new IllegalStateException("POSTGRES_PASSWORD must be at least 8 characters long");
        }
    }

    /**
     * The BCrypt cost, refused below {@link PasswordHasher#MIN_PRODUCTION_COST}
     * outside the {@code dev} profile.
     *
     * <p>Cost is log2 of the work a verification does, so lowering it is the one
     * change that makes every stored password cheaper to attack offline without
     * changing anything visible at runtime. It is settable at all only so the test
     * suite can run at cost 4; this check is what keeps that from becoming a
     * deployment setting nobody notices.</p>
     */
    private void validateBcryptCost() {
        Integer cost = environment.getProperty("mosip.security.auth.bcrypt-cost", Integer.class);
        if (cost != null && cost < PasswordHasher.MIN_PRODUCTION_COST) {
            throw new IllegalStateException("mosip.security.auth.bcrypt-cost must be at least "
                    + PasswordHasher.MIN_PRODUCTION_COST + " outside the dev profile — a lower cost "
                    + "makes every stored password cheaper to crack offline");
        }
    }

    /**
     * The operator key, checked at startup rather than on the first request that
     * presents one.
     *
     * <p>One setting, three rules:</p>
     * <ul>
     *   <li><b>Blank is allowed everywhere.</b> Unlike a supervisor credential
     *       this is not required in production: a blank value closes the
     *       key-based path to the operator feeds instead of opening it
     *       ({@code OperatorKeyGuard.proves} refuses a blank configured key), so a
     *       deployment that never configures it keeps working for accounts and
     *       simply has no key-based reader.</li>
     *   <li><b>At least 16 characters when set</b> — the same floor as the audit
     *       HMAC secret ({@code AuditChainKey.MIN_SECRET_LENGTH}) and for the same
     *       reason: a guessable key is no key at all, and failing at startup beats
     *       running with one that only looks like a credential.</li>
     * </ul>
     *
     * <p>The rule that it must differ from the admin key is gone with the admin
     * key. There is no longer a second shared secret for it to be confused
     * with.</p>
     */
    private void validateOperatorApiKey() {
        String key = environment.getProperty("MOSIP_OPERATOR_API_KEY");
        if (key == null || key.isBlank()) {
            key = environment.getProperty("mosip.security.operator-api-key");
        }
        if (key == null || key.isBlank()) {
            return;
        }
        if (key.length() < 16) {
            throw new IllegalStateException("MOSIP_OPERATOR_API_KEY must be at least 16 characters long");
        }
    }

    /**
     * The bootstrap pair, checked at startup so a half-configured recovery is a
     * failed boot rather than a surprise at the moment somebody has already lost
     * access.
     *
     * <p>Both or neither: a username with no password cannot create the account
     * it names, and a password with no username has nothing to attach to. Either
     * way the operator would believe recovery was armed when it was not — which is
     * the worst state to be in, because they would also believe there was still a
     * way in.</p>
     */
    private void validateBootstrapCredentials() {
        String username = bootstrapProperty("MOSIP_AUTH_BOOTSTRAP_USERNAME",
                "mosip.security.auth.bootstrap-username");
        String password = bootstrapProperty("MOSIP_AUTH_BOOTSTRAP_PASSWORD",
                "mosip.security.auth.bootstrap-password");

        if ((username == null) != (password == null)) {
            throw new IllegalStateException("MOSIP_AUTH_BOOTSTRAP_USERNAME and "
                    + "MOSIP_AUTH_BOOTSTRAP_PASSWORD must be set together or not at all — one without "
                    + "the other cannot create the bootstrap account it promises");
        }
        if (password != null && !PasswordHasher.isAcceptable(password)) {
            // The message never echoes the value, only the rule.
            throw new IllegalStateException("MOSIP_AUTH_BOOTSTRAP_PASSWORD must be at least "
                    + PasswordHasher.MIN_PASSWORD_LENGTH + " characters and no more than "
                    + PasswordHasher.MAX_PASSWORD_BYTES + " bytes");
        }
    }

    /** The env-var form first, then the property form; blank counts as absent. */
    private String bootstrapProperty(String environmentVariable, String property) {
        String value = environment.getProperty(environmentVariable);
        if (value == null || value.isBlank()) {
            value = environment.getProperty(property);
        }
        return value == null || value.isBlank() ? null : value;
    }
}
