package io.mosip.liveness.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Validates production credentials on startup. The local {@code dev} profile
 * uses H2 and keeps administration fail-closed when its optional key is absent.
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
        }
        validateAdminApiKey(production);
        validateOperatorApiKey();
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

    private void validateAdminApiKey(boolean required) {
        String key = environment.getProperty("MOSIP_ADMIN_API_KEY");
        if (key == null || key.isBlank()) {
            key = environment.getProperty("mosip.security.admin-api-key");
        }
        if (key == null || key.isBlank()) {
            if (required) {
                throw new IllegalStateException("MOSIP_ADMIN_API_KEY must be set");
            }
            return;
        }
        if (key.length() < 32) {
            throw new IllegalStateException("MOSIP_ADMIN_API_KEY must be at least 32 characters long");
        }
    }

    /**
     * The operator key, checked at startup rather than on the first request that
     * presents one.
     *
     * <p>One setting, three rules:</p>
     * <ul>
     *   <li><b>Blank is allowed everywhere.</b> Unlike the admin key this is not
     *       required in production: a blank value closes the operator surface
     *       instead of opening it ({@code OperatorKeyGuard} refuses a caller who
     *       proves no role), so a deployment that never configures it is a
     *       deployment without operators rather than an unguarded one.</li>
     *   <li><b>At least 16 characters when set</b> — the same floor as the audit
     *       HMAC secret ({@code AuditChainKey.MIN_SECRET_LENGTH}) and for the same
     *       reason: a guessable key is no key at all, and failing at startup beats
     *       running with one that only looks like a credential.</li>
     *   <li><b>It must differ from the admin key.</b> The admin key is accepted on
     *       operator routes, so an identical value would make the two roles
     *       indistinguishable: every caller would be answered {@code SUPERVISOR}
     *       and the operator surface would be decorative. Nothing about the
     *       mistake would show up at runtime, which is why it is rejected here.
     *       The comparison is constant time so a startup log cannot leak a prefix
     *       to someone who can time the process booting.</li>
     * </ul>
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

        String admin = environment.getProperty("MOSIP_ADMIN_API_KEY");
        if (admin == null || admin.isBlank()) {
            admin = environment.getProperty("mosip.security.admin-api-key");
        }
        if (admin == null || admin.isBlank()) {
            return;
        }
        if (MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),
                admin.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException(
                    "MOSIP_OPERATOR_API_KEY must differ from MOSIP_ADMIN_API_KEY — the admin key "
                            + "is accepted on operator routes, so an identical value would leave the "
                            + "two roles indistinguishable");
        }
    }
}
