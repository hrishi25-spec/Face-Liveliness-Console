package io.mosip.liveness.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;
import io.mosip.liveness.auth.AuthPrincipal;
import io.mosip.liveness.auth.AuthService;
import io.mosip.liveness.dto.ChangePasswordRequest;
import io.mosip.liveness.dto.LoginRequest;
import io.mosip.liveness.models.enums.AuthRole;
import io.mosip.liveness.testing.AuthTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/v1/auth/*} as an HTTP caller sees it.
 *
 * <p>The three things this endpoint set has to get right, each asserted where a
 * client would notice it:</p>
 *
 * <ul>
 *   <li><b>A credential goes in, a session comes out, and the session is not in
 *       the body.</b> The token travels in an {@code HttpOnly} {@code Set-Cookie}
 *       header and nowhere else — not in the login body, not in the
 *       change-password body, and not in a log line. The two "never leaves"
 *       assertions walk every case this class exercises, because a leak is exactly
 *       the kind of thing that arrives on one branch.</li>
 *   <li><b>One refusal for every failure.</b> Wrong password, unknown account and
 *       a locked account are the same 401 with the same sentence, so the endpoint
 *       is not a username oracle. Which of them fired is {@code AuthServiceTest}'s
 *       business; that the caller cannot tell is this class's.</li>
 *   <li><b>The role probe still answers a role.</b> Both credentials reach it —
 *       the operator key by header, a supervisor by session — and the answer is the
 *       role name alone.</li>
 * </ul>
 *
 * <p>{@link AuthService} is mocked (it needs a database), but the filters are real:
 * every {@code Filter} bean is part of a {@code @WebMvcTest} slice, so these
 * requests run {@code RateLimitFilter} → {@code AuthContextFilter} →
 * {@code OperatorKeyFilter}, which is the chain a console actually takes.</p>
 */
@WebMvcTest(AuthController.class)
@Import(TestConfig.class)
@TestPropertySource(properties = {
        "mosip.security.operator-api-key=" + AuthControllerTest.OPERATOR_KEY
})
class AuthControllerTest {

    /** Synthetic; long enough for the startup floor (operator ≥16). */
    static final String OPERATOR_KEY = "synthetic-operator-key-0001";

    /** The password a successful sign-in is stubbed with. Synthetic, like everything here. */
    private static final String PASSWORD = "synthetic-password-0001";

    /** The token the stubbed service hands back. Never a real session. */
    private static final String TOKEN = "synthetic-session-token-0001";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthService authService;

    /** Everything that reached a logger while the tests ran. */
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger root;

    @BeforeEach
    void setUp() {
        // The mock is a TestConfig @Bean rather than a @MockBean, so nothing resets
        // it between methods; without this the "never invoked" assertions below
        // would see the previous test's logins.
        reset(authService);
        AuthTestFixture.stub(authService);
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        logs.stop();
    }

    // ------------------------------------------------------------------ login

    @Test
    void loginWithCorrectCredentials_answersTheAccountAndSetsACookie() throws Exception {
        signInSucceeds();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(AuthTestFixture.SUPERVISOR_USERNAME, PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(AuthTestFixture.SUPERVISOR_USERNAME))
                .andExpect(jsonPath("$.role").value("SUPERVISOR"))
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                // The session is delivered as a cookie, HttpOnly so no script can
                // read it, with Path=/ for every API path. SameSite is not asserted
                // here: MockMvc's mock response renders the name, value and typed
                // flags but not Servlet 6.0 cookie attributes, so the attribute
                // would look absent whatever the code did.
                // AuthContextFilterIntegrationTest asserts it on a real container's
                // Set-Cookie header, which is the only place that can prove it.
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString(SessionCookie.NAME + "=" + TOKEN),
                                org.hamcrest.Matchers.containsString("HttpOnly"),
                                org.hamcrest.Matchers.containsString("Path=/"))));
    }

    @Test
    void loginWithAWrongPassword_isOneGeneric401() throws Exception {
        when(authService.login(anyString(), anyString())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(AuthTestFixture.SUPERVISOR_USERNAME, "wrong-password-1234"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message")
                        .value(io.mosip.liveness.auth.AuthRefusals.INVALID_CREDENTIALS))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /**
     * A missing body field is a validation error, not a credential decision, and it
     * must not reach the service: a 400 that ran a BCrypt verification would be a
     * free way to spend the service's CPU.
     */
    @Test
    void loginWithoutAPassword_isRejectedBeforeTheServiceSeesIt() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"someone\"}"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verify(authService, org.mockito.Mockito.never())
                .login(anyString(), anyString());
    }

    // ------------------------------------------------------------------ logout / me

    @Test
    void logoutClearsTheCookieAndAnswersNoContent() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").with(AuthTestFixture.asSupervisor()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString(SessionCookie.NAME + "=")));
        org.mockito.Mockito.verify(authService).logout(AuthTestFixture.SUPERVISOR_TOKEN);
    }

    @Test
    void meWithoutASession_is401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value(io.mosip.liveness.auth.AuthRefusals.UNAUTHENTICATED));
    }

    @Test
    void meWithASessionAnswersTheAccount() throws Exception {
        when(authService.currentUser(any())).thenReturn(Optional.of(AuthTestFixture.supervisorUser()));

        mockMvc.perform(get("/api/v1/auth/me").with(AuthTestFixture.asSupervisor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(AuthTestFixture.SUPERVISOR_USERNAME))
                .andExpect(jsonPath("$.role").value("SUPERVISOR"))
                // The response shape has no field a hash could arrive in.
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    /**
     * A session that outlived its account answers as unauthenticated rather than
     * reporting an identity that no longer has access.
     */
    @Test
    void meWithASessionWhoseAccountIsGone_is401() throws Exception {
        when(authService.currentUser(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/auth/me").with(AuthTestFixture.asSupervisor()))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ change password

    @Test
    void changePassword_replacesTheSessionCookie() throws Exception {
        when(authService.changePassword(any(), anyString(), anyString()))
                .thenReturn(Optional.of(new AuthService.IssuedSession(
                        TOKEN, new AuthPrincipal(null, AuthTestFixture.SUPERVISOR_USERNAME,
                                AuthRole.SUPERVISOR, false),
                        OffsetDateTime.now().plusHours(1), 3600)));

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .with(AuthTestFixture.asSupervisor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangePasswordRequest(
                                PASSWORD, "a-longer-replacement-password"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString(SessionCookie.NAME + "=" + TOKEN)));
    }

    /**
     * A wrong current password is 401, not 400: holding a session is not proof that
     * the person at the keyboard knows the password.
     */
    @Test
    void changePasswordWithAWrongCurrentPassword_is401() throws Exception {
        when(authService.changePassword(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .with(AuthTestFixture.asSupervisor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangePasswordRequest(
                                "wrong-current-password", "a-longer-replacement-password"))))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ role probe

    @Test
    void theOperatorKeyIsAnsweredOperator() throws Exception {
        mockMvc.perform(get("/api/v1/auth/role").header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR"));
    }

    @Test
    void aSupervisorSessionIsAnsweredSupervisor() throws Exception {
        mockMvc.perform(get("/api/v1/auth/role").with(AuthTestFixture.asSupervisor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPERVISOR"));
    }

    @Test
    void anOperatorSessionIsAnsweredOperator() throws Exception {
        mockMvc.perform(get("/api/v1/auth/role").with(AuthTestFixture.asOperator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR"));
    }

    @Test
    void wrongOperatorKeyIsRefusedInTheStandardErrorShape() throws Exception {
        mockMvc.perform(get("/api/v1/auth/role")
                        .header(OperatorKeyGuard.HEADER, "synthetic-wrong-key-000002"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value(OperatorKeyGuard.REFUSAL))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void noKeyAtAllIsRefusedInTheSameWayAsAWrongOne() throws Exception {
        mockMvc.perform(get("/api/v1/auth/role"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value(OperatorKeyGuard.REFUSAL))
                .andExpect(jsonPath("$.status").value(401));
    }

    // ------------------------------------------------------------------ credential hygiene

    /**
     * A credential must never come back in a body. Walked over every case rather
     * than asserted per test, because a leak that arrives on one branch is exactly
     * what a per-case assertion misses.
     */
    @Test
    void noResponseBodyEverCarriesTheTokenOrThePassword() throws Exception {
        List<String> bodies = new ArrayList<>();
        for (MvcResult result : everyCase()) {
            String body = result.getResponse().getContentAsString();
            bodies.add(result.getResponse().getStatus() + " " + body);
            assertFalse(body.contains(TOKEN), body);
            assertFalse(body.contains(PASSWORD), body);
            assertFalse(body.contains(OPERATOR_KEY), body);
        }
        assertTrue(bodies.size() >= 6, "every case must have produced a body: " + bodies);
    }

    /**
     * …and never in a log line. The token is the one value whose theft turns into an
     * immediate session, and the password is the value whose theft turns into one
     * after the next sign-in.
     */
    @Test
    void noLogLineEverCarriesTheTokenOrThePassword() throws Exception {
        everyCase();
        List<String> lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        for (String line : lines) {
            assertFalse(line.contains(TOKEN), line);
            assertFalse(line.contains(PASSWORD), line);
            assertFalse(line.contains(OPERATOR_KEY), line);
        }
    }

    /** Every case this endpoint set has, so the two "never leaks" checks cover all of them. */
    private List<MvcResult> everyCase() throws Exception {
        signInSucceeds();
        when(authService.login(anyString(), anyString())).thenReturn(Optional.empty());
        when(authService.currentUser(any())).thenReturn(Optional.of(AuthTestFixture.supervisorUser()));
        when(authService.changePassword(any(), anyString(), anyString()))
                .thenReturn(Optional.of(new AuthService.IssuedSession(
                        TOKEN, new AuthPrincipal(null, AuthTestFixture.SUPERVISOR_USERNAME,
                                AuthRole.SUPERVISOR, false),
                        OffsetDateTime.now().plusHours(1), 3600)));

        return List.of(
                mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(AuthTestFixture.SUPERVISOR_USERNAME, PASSWORD))))
                        .andReturn(),
                mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("somebody-else", PASSWORD))))
                        .andReturn(),
                mockMvc.perform(get("/api/v1/auth/me").with(AuthTestFixture.asSupervisor())).andReturn(),
                mockMvc.perform(get("/api/v1/auth/me")).andReturn(),
                mockMvc.perform(get("/api/v1/auth/role").header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                        .andReturn(),
                mockMvc.perform(get("/api/v1/auth/role")).andReturn());
    }

    /** The one stubbed success: this exact username and password mints {@link #TOKEN}. */
    private void signInSucceeds() {
        when(authService.login(AuthTestFixture.SUPERVISOR_USERNAME, PASSWORD))
                .thenReturn(Optional.of(new AuthService.IssuedSession(
                        TOKEN, new AuthPrincipal(null, AuthTestFixture.SUPERVISOR_USERNAME,
                                AuthRole.SUPERVISOR, false),
                        OffsetDateTime.now().plusHours(1), 3600)));
    }
}
