package io.mosip.liveness.testing;

import io.mosip.liveness.auth.AuthPrincipal;
import io.mosip.liveness.auth.AuthService;
import io.mosip.liveness.models.entity.AuthUser;
import io.mosip.liveness.models.enums.AuthRole;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;

/**
 * The credentials and principals slice tests authenticate with.
 *
 * <p>A {@code @WebMvcTest} slice cannot sign in — it has no database and mocks
 * {@link AuthService} — so the two tokens below stand in for real sessions.
 * {@link #stub(AuthService)} makes the mock resolve exactly those two and nothing
 * else, which keeps the interesting negative case (an unauthenticated caller, or a
 * token the service does not know) a genuine refusal rather than something the
 * stub accidentally admits.</p>
 *
 * <p>All values are synthetic. No real credential appears in a test, and the
 * tokens here are never valid against a real service — they are not even the
 * right shape, which is deliberate: a fixed value that happened to satisfy
 * {@code SessionTokens.looksWellFormed} would invite a test to believe a lookup
 * happened.</p>
 */
public final class AuthTestFixture {

    /** Resolves to a {@link AuthRole#SUPERVISOR} account in a stubbed slice. */
    public static final String SUPERVISOR_TOKEN = "test-supervisor-session";

    /** Resolves to an {@link AuthRole#OPERATOR} account in a stubbed slice. */
    public static final String OPERATOR_TOKEN = "test-operator-session";

    /** A token no stub resolves — the shape of an expired or revoked session. */
    public static final String UNKNOWN_TOKEN = "test-token-nobody-knows";

    /** Stable ids so a stubbed principal is equal to itself across calls. */
    private static final UUID SUPERVISOR_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a2");

    public static final String SUPERVISOR_USERNAME = "fixture-supervisor";
    public static final String OPERATOR_USERNAME = "fixture-operator";

    private AuthTestFixture() {
    }

    /** The supervisor a {@link #SUPERVISOR_TOKEN} request resolves to. */
    public static AuthPrincipal supervisor() {
        return new AuthPrincipal(SUPERVISOR_ID, SUPERVISOR_USERNAME, AuthRole.SUPERVISOR, false);
    }

    /** The operator an {@link #OPERATOR_TOKEN} request resolves to. */
    public static AuthPrincipal operator() {
        return new AuthPrincipal(OPERATOR_ID, OPERATOR_USERNAME, AuthRole.OPERATOR, false);
    }

    /** The supervisor as the account {@code GET /auth/me} reads back. */
    public static AuthUser supervisorUser() {
        return AuthUser.builder()
                .id(SUPERVISOR_ID)
                .username(SUPERVISOR_USERNAME)
                .passwordHash("$2a$04$not-a-real-hash-in-this-fixture")
                .role(AuthRole.SUPERVISOR)
                .mustChangePassword(false)
                .createdAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
                .updatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    /**
     * Makes a mocked {@link AuthService} resolve the two fixture tokens and
     * refuse every other value. Everything else on the mock keeps its default
     * (empty {@code Optional}, no-op) answer, so a test that forgets to stub the
     * call it exercises fails loudly instead of passing on a stub.
     */
    public static void stub(AuthService authService) {
        when(authService.authenticate(SUPERVISOR_TOKEN)).thenReturn(Optional.of(supervisor()));
        when(authService.authenticate(OPERATOR_TOKEN)).thenReturn(Optional.of(operator()));
    }

    /** A request carrying the fixture supervisor's session. */
    public static RequestPostProcessor asSupervisor() {
        return as(SUPERVISOR_TOKEN);
    }

    /** A request carrying the fixture operator's session. */
    public static RequestPostProcessor asOperator() {
        return as(OPERATOR_TOKEN);
    }

    /** A request carrying a session the stub does not know. */
    public static RequestPostProcessor asUnknownSession() {
        return as(UNKNOWN_TOKEN);
    }

    /**
     * A request carrying an arbitrary bearer token.
     *
     * <p>{@code Authorization: Bearer} rather than the cookie, because
     * {@code MockMvc} makes a cookie awkward in a fluent assertion and the filter
     * treats the two identically — {@code AuthContextFilterIntegrationTest} covers
     * the cookie path over a real container and the real service.</p>
     */
    public static RequestPostProcessor as(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }
}
