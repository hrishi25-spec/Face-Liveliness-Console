package io.mosip.liveness.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Creates the first supervisor at startup, when the deployment asks for one.
 *
 * <p>With the admin API key gone there is no way to reach the privileged surface
 * on a brand-new database: every account is created by a supervisor, and there
 * is not one yet. This is the way in — {@code MOSIP_AUTH_BOOTSTRAP_USERNAME} and
 * {@code MOSIP_AUTH_BOOTSTRAP_PASSWORD} create an initial
 * {@code SUPERVISOR} account whose password must be changed at first login, so
 * the value that opened the door is not the value that keeps it open.</p>
 *
 * <p><b>Safe to leave configured.</b> It does nothing at all when any account
 * already exists — it cannot re-create a known account, cannot reset a password,
 * and cannot grant a privilege on a later boot. That is the property that makes
 * the variables acceptable in a deployment's environment rather than a
 * one-shot script: forgetting to remove them is a no-op, not a backdoor.</p>
 *
 * <p><b>Recovery</b> is the same mechanism. Losing the last supervisor is
 * otherwise unrecoverable through the API (user management needs a supervisor),
 * so an operator who has lost one deliberately takes the database back to "no
 * accounts" and reboots with the variables set. There is no second, quieter path
 * to that outcome.</p>
 *
 * <p>Nothing logged here names a credential. The two outcomes are "created" (with
 * the account name) and "nothing to do"; a refusal to create is reported as the
 * account name already existing, never as the password being rejected.</p>
 */
@Component
@Order(AuthUserBootstrap.ORDER)
public class AuthUserBootstrap implements ApplicationRunner {

    /**
     * After the schema is up and before anything serves traffic. Flyway and
     * Hibernate validation run during context refresh, ahead of every
     * {@code ApplicationRunner}; ordering inside the runner phase only decides
     * the relative position of the runners (`SecurityValidator` runs later and
     * only reads configuration).
     */
    static final int ORDER = 10;

    private static final Logger log = LoggerFactory.getLogger(AuthUserBootstrap.class);

    private final AuthService authService;
    private final Environment environment;

    /** Blank disables bootstrap entirely, which is the default. */
    @Value("${mosip.security.auth.bootstrap-username:}")
    private String bootstrapUsername = "";

    @Value("${mosip.security.auth.bootstrap-password:}")
    private String bootstrapPassword = "";

    public AuthUserBootstrap(AuthService authService, Environment environment) {
        this.authService = authService;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapUsername.isBlank() || bootstrapPassword.isBlank()) {
            // The normal case. Nothing is written, and no line is logged about
            // credentials that were not configured.
            warnWhenUnreachable();
            return;
        }
        boolean created = authService.bootstrapSupervisor(bootstrapUsername, bootstrapPassword);
        if (created) {
            log.info("Bootstrap supervisor created; its password must be changed at first login. "
                    + "Unset MOSIP_AUTH_BOOTSTRAP_USERNAME and MOSIP_AUTH_BOOTSTRAP_PASSWORD now.");
        } else {
            log.info("Bootstrap supervisor not created: at least one account already exists.");
        }
    }

    /**
     * Says so when a deployment has locked itself out.
     *
     * <p>No accounts and no bootstrap credentials means user management is
     * unreachable — every route that could create an account needs an account —
     * and nothing else at startup would mention it. The service is still
     * fail-closed, which is right; being silently unrecoverable is not, so an
     * operator gets one line naming the two variables and the reason.</p>
     *
     * <p>Production only: the {@code dev} profile starts against a fresh in-memory
     * database every time, so warning there would be noise on every boot.</p>
     */
    private void warnWhenUnreachable() {
        boolean production = !environment.acceptsProfiles(Profiles.of("dev"));
        if (production && !authService.hasAccounts()) {
            log.warn("No accounts exist and no bootstrap credentials are configured, so "
                    + "user management is unreachable. Set MOSIP_AUTH_BOOTSTRAP_USERNAME and "
                    + "MOSIP_AUTH_BOOTSTRAP_PASSWORD on the next boot to create the first supervisor.");
        }
    }
}
