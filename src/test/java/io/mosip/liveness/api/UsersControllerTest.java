package io.mosip.liveness.api;

import tools.jackson.databind.ObjectMapper;
import io.mosip.liveness.auth.AuthService;
import io.mosip.liveness.dto.UserCreateRequest;
import io.mosip.liveness.dto.UserUpdateRequest;
import io.mosip.liveness.models.entity.AuthUser;
import io.mosip.liveness.models.enums.AuthRole;
import io.mosip.liveness.testing.AuthTestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The role wall on account management.
 *
 * <p>Every route here is supervisor-only, and every refusal is asserted as well as
 * the acceptance — on all four verbs, because a gate that covers three of them
 * covers none: an operator that could reach {@code POST /api/v1/users} could create
 * itself a supervisor and the other three refusals would be decoration.</p>
 *
 * <p>The statuses carry meaning and are pinned: <b>401</b> when no session resolved
 * (the caller has not said who it is), <b>403</b> when an operator session did (the
 * caller is known, and signing in again will not help). A single status for both
 * would either tell a signed-out caller to give up or tell an operator to keep
 * trying passwords.</p>
 *
 * <p>Refusals also assert the service was never called, so a check that ran after
 * the write would fail — and no response is ever asserted to contain a hash, because
 * {@link io.mosip.liveness.dto.UserResponse} has no field one could arrive in.</p>
 */
@WebMvcTest(UsersController.class)
@Import(TestConfig.class)
class UsersControllerTest {

    private static final String SUPERVISOR_AUTH = "Bearer " + AuthTestFixture.SUPERVISOR_TOKEN;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthService authService;

    @BeforeEach
    void signIn() {
        // A TestConfig @Bean mock, not a @MockBean, so nothing resets it between
        // methods; the "never invoked" assertions below would otherwise see the
        // supervisor-path calls the earlier tests made.
        reset(authService);
        AuthTestFixture.stub(authService);
    }

    // ------------------------------------------------------------------ the role wall

    @Test
    @DisplayName("every user-management route refuses an operator session with 403")
    void anOperatorSessionCannotManageUsers() throws Exception {
        mockMvc.perform(get("/api/v1/users").with(AuthTestFixture.asOperator()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(post("/api/v1/users").with(AuthTestFixture.asOperator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UserCreateRequest("sneaky", "SUPERVISOR", "an-initial-password-1"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/users/somebody").with(AuthTestFixture.asOperator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SUPERVISOR\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/users/somebody").with(AuthTestFixture.asOperator()))
                .andExpect(status().isForbidden());

        verify(authService, never()).createUser(anyString(), anyString(), anyString());
        verify(authService, never()).updateUser(anyString(), any(), any(), any());
        verify(authService, never()).deleteUser(anyString());
        verify(authService, never()).listUsers();
    }

    @Test
    @DisplayName("every user-management route refuses a caller with no session with 401")
    void aCallerWithNoSessionIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/users/somebody")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/users/somebody"))
                .andExpect(status().isUnauthorized());

        verify(authService, never()).listUsers();
    }

    /**
     * The operator API key reaches the feeds and not this: it proves
     * {@link AuthRole#OPERATOR}, and it is not read on a supervisor route at all.
     */
    @Test
    @DisplayName("the operator API key cannot manage users either")
    void theOperatorApiKeyCannotManageUsers() throws Exception {
        mockMvc.perform(get("/api/v1/users").header(OperatorKeyGuard.HEADER, "synthetic-operator-key-0001"))
                .andExpect(status().isUnauthorized());
        verify(authService, never()).listUsers();
    }

    // ------------------------------------------------------------------ the supervisor path

    @Test
    @DisplayName("a supervisor session lists accounts and the response carries no hash")
    void aSupervisorSessionListsAccounts() throws Exception {
        when(authService.listUsers()).thenReturn(List.of(AuthUser.builder()
                .id(java.util.UUID.randomUUID())
                .username("operator-one")
                .passwordHash("$2a$12$hash-that-must-not-be-serialised")
                .role(AuthRole.OPERATOR)
                .mustChangePassword(true)
                .createdAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
                .updatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
                // Non-null so every field of the response is present: the app omits
                // nulls, and the key-count assertion below is only an exact key set
                // when nothing has been dropped.
                .passwordChangedAt(OffsetDateTime.parse("2026-01-02T00:00:00Z"))
                .build()));

        mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("operator-one"))
                .andExpect(jsonPath("$[0].role").value("OPERATOR"))
                .andExpect(jsonPath("$[0].mustChangePassword").value(true))
                // The response shape has no field a hash could arrive in, asserted
                // as an exact key set so a future edit to the DTO is deliberate.
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$[0].length()").value(6));
    }

    @Test
    @DisplayName("a supervisor session can create an account, and the answer is the account and no credential")
    void aSupervisorSessionCreatesAnAccount() throws Exception {
        when(authService.createUser(anyString(), anyString(), anyString()))
                .thenReturn(AuthUser.builder()
                        .username("operator-one").role(AuthRole.OPERATOR)
                        .passwordHash("$2a$12$hash-that-must-not-be-serialised")
                        .mustChangePassword(true).build());

        String body = mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UserCreateRequest(
                                "operator-one", "OPERATOR", "an-initial-password-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("operator-one"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains("an-initial-password-1"),
                "the credential the caller supplied must not be echoed back: " + body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("$2a$"), body);
    }

    @Test
    @DisplayName("a supervisor session can update and delete")
    void aSupervisorSessionUpdatesAndDeletes() throws Exception {
        when(authService.updateUser(anyString(), any(), any(), any()))
                .thenReturn(AuthUser.builder()
                        .username("operator-one").role(AuthRole.SUPERVISOR)
                        .passwordHash("$2a$12$hash-that-must-not-be-serialised").build());

        mockMvc.perform(put("/api/v1/users/operator-one")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UserUpdateRequest("SUPERVISOR", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPERVISOR"));

        mockMvc.perform(delete("/api/v1/users/operator-one")
                        .header(HttpHeaders.AUTHORIZATION, SUPERVISOR_AUTH))
                .andExpect(status().isNoContent());

        verify(authService).deleteUser("operator-one");
    }
}
