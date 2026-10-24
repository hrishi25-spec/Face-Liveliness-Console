package io.mosip.liveness.auth;

import io.mosip.liveness.models.enums.AuthRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Makes the {@code dev} console's printed credentials work on a fresh database.
 *
 * <p>The production bootstrap creates one supervisor and deliberately requires a
 * password change at first sign-in. That is correct for a deployment, but the
 * {@code dev} console advertises three role cards with usable defaults and an
 * {@code H2} database that starts empty on every boot. Without this runner the
 * supervisor card always lands in the change-password dialog and the operator card
 * has no account at all — the two failures a demo cannot hide.</p>
 *
 * <p>Only active under the {@code dev} profile, which is also the only profile
 * allowed to use the low BCrypt cost those demo passwords are hashed at. The
 * supervisor values come from the existing bootstrap pair, so a local override of
 * {@code MOSIP_AUTH_BOOTSTRAP_USERNAME}/{@code _PASSWORD} still wins; the operator
 * pair is dev-only configuration. Nothing here logs a password.</p>
 */
@Component
@Profile("dev")
@Order(AuthUserBootstrap.ORDER + 1)
class DevAuthUserBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevAuthUserBootstrap.class);

    private final AuthService authService;

    @Value("${mosip.security.auth.bootstrap-username:}")
    private String supervisorUsername = "";

    @Value("${mosip.security.auth.bootstrap-password:}")
    private String supervisorPassword = "";

    @Value("${mosip.security.auth.dev-operator-username:operator}")
    private String operatorUsername = "operator";

    @Value("${mosip.security.auth.dev-operator-password:operator1234}")
    private String operatorPassword = "operator1234";

    DevAuthUserBootstrap(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // The supervisor pair may be blank in a dev setup that has no bootstrap
        // configured; the operator pair has its own defaults and is always seeded.
        if (!supervisorUsername.isBlank() && !supervisorPassword.isBlank()) {
            authService.seedDevelopmentAccount(supervisorUsername, AuthRole.SUPERVISOR, supervisorPassword);
        }
        authService.seedDevelopmentAccount(operatorUsername, AuthRole.OPERATOR, operatorPassword);
        log.info("Development demo accounts seeded; their printed credentials do not require a password change.");
    }
}
