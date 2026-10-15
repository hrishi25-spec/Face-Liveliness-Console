package io.mosip.liveness.api;

import io.mosip.liveness.testing.AuthTestFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.liveness.config.WorkflowPolicyDefaults;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/sessions}: the operator's session feed.
 *
 * <p>What the endpoint has to get right, asserted as an HTTP caller sees it: it
 * is refused without a role and answered with one (the operator key, and the
 * admin key that outranks it), the filters and the page limit reach the query
 * unchanged, an impossible page size is a 400 rather than a silent clamp, and a
 * row carries the operator's fields and <em>nothing else</em> — no
 * {@code subjectRef}, no policy snapshot.</p>
 *
 * <p>The operator filters are the real chain here (every {@code Filter} bean is
 * in a {@code @WebMvcTest} slice): {@code RateLimitFilter} meters the request
 * and {@link OperatorKeyFilter} answers the key, so a change in either shows up
 * as a failure rather than passing unnoticed. The budget is raised for this
 * class only because a test makes more requests than a console would —
 * {@code RateLimitFilterTest} owns the budget's behaviour.</p>
 */
@WebMvcTest(SessionsController.class)
@Import(TestConfig.class)
@TestPropertySource(properties = {
        "mosip.security.operator-api-key=" + SessionsListControllerTest.OPERATOR_KEY,
        "mosip.security.rate-limit.session-create-limit=1000"
})
class SessionsListControllerTest {

    /** Synthetic; long enough to satisfy the startup floor (operator ≥16). */
    static final String OPERATOR_KEY = "synthetic-operator-key-0001";

    /** A fixed id, so a refusal can be told from a real row in the body. */
    private static final UUID ID = UUID.fromString("5c8f1c9e-0000-4000-8000-000000000001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LivenessSessionRepository sessionRepo;
    @Autowired private io.mosip.liveness.auth.AuthService authService;

    @BeforeEach
    void setUp() {
        reset(sessionRepo);
        AuthTestFixture.stub(authService);
    }

    @Test
    void theFeedIsRefusedWithoutAKey() throws Exception {
        mockMvc.perform(get("/api/v1/sessions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value(OperatorKeyGuard.REFUSAL))
                .andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(sessionRepo);
    }

    @Test
    void aWrongKeyIsRefusedLikeNoKeyAtAll() throws Exception {
        mockMvc.perform(get("/api/v1/sessions")
                        .header(OperatorKeyGuard.HEADER, "synthetic-wrong-key-000002"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(OperatorKeyGuard.REFUSAL));
        verifyNoInteractions(sessionRepo);
    }

    @Test
    void theOperatorKeyReadsTheFeed() throws Exception {
        when(sessionRepo.findRecent(any(), any(), any())).thenReturn(List.of(passed()));

        mockMvc.perform(get("/api/v1/sessions").header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ID.toString()))
                .andExpect(jsonPath("$[0].workflowType").value("RESIDENT"))
                .andExpect(jsonPath("$[0].deviceId").value("L1-CAM-01"))
                .andExpect(jsonPath("$[0].status").value("PASSED"))
                .andExpect(jsonPath("$[0].currentStage").value("COMPLETED"))
                .andExpect(jsonPath("$[0].retryCount").value(1))
                .andExpect(jsonPath("$[0].finalResult").value(true));
    }

    /**
     * The gate accepts a session now as well as the key, and a supervisor session
     * reads the feed: the privileged role is allowed everything the smaller one is.
     * This is what replaced "the admin key reads the operator feed too".
     */
    @Test
    void aSupervisorSessionReadsTheOperatorFeed() throws Exception {
        when(sessionRepo.findRecent(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/sessions").with(AuthTestFixture.asSupervisor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** An operator session reads it too, without needing the key at all. */
    @Test
    void anOperatorSessionReadsTheOperatorFeed() throws Exception {
        when(sessionRepo.findRecent(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/sessions").with(AuthTestFixture.asOperator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * The row is the ten fields an operator needs and nothing that widens the
     * surface. Asserted as an exact key set rather than by absence alone: a new
     * field has to be a deliberate change to this list, not something that
     * arrives with the entity it is mapped from.
     */
    @Test
    void aRowIsTheOperatorsFieldsAndNeverTheOwnerOrThePolicy() throws Exception {
        LivenessSession owned = passed();
        owned.setSubjectRef("resident-42");
        owned.setPolicySnapshot(WorkflowPolicyDefaults.forWorkflow(
                io.mosip.liveness.core.WorkflowType.RESIDENT_REGISTRATION));
        when(sessionRepo.findRecent(any(), any(), any())).thenReturn(List.of(owned));

        String body = mockMvc.perform(get("/api/v1/sessions")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode row = objectMapper.readTree(body).get(0);
        Set<String> fields = new TreeSet<>();
        row.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id", "workflowType", "deviceId", "status", "currentStage",
                        "retryCount", "finalResult", "failureReason", "createdAt", "closedAt"),
                fields, "the feed returns these fields and no others: " + body);

        // Belt and braces: the owner reference and the frozen policy are the two
        // values this feed must never carry, whatever they are named.
        assertFalse(body.contains("resident-42"), body);
        assertFalse(body.contains("passiveThreshold"), body);
        assertTrue(body.contains("presentation_attack:print"),
                "the failure reason is what an operator triages on: " + body);
    }

    /** The three optional parameters, in every combination a console can send. */
    @Test
    void filtersAndPageSizeReachTheQueryInEveryCombination() throws Exception {
        when(sessionRepo.findRecent(any(), any(), any())).thenReturn(List.of());
        ArgumentCaptor<WorkflowType> workflow = ArgumentCaptor.forClass(WorkflowType.class);
        ArgumentCaptor<SessionStatus> status = ArgumentCaptor.forClass(SessionStatus.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);

        mockMvc.perform(get("/api/v1/sessions").header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/sessions?workflowType=OPERATOR")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/sessions?status=FAILED")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/sessions?workflowType=OPERATOR&status=FAILED&limit=7")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk());

        verify(sessionRepo, times(4)).findRecent(workflow.capture(), status.capture(), page.capture());
        assertEquals(Arrays.asList(null, WorkflowType.OPERATOR, null, WorkflowType.OPERATOR),
                workflow.getAllValues(), "an absent filter must stay absent, not default to one");
        assertEquals(Arrays.asList(null, null, SessionStatus.FAILED, SessionStatus.FAILED),
                status.getAllValues());
        assertEquals(List.of(50, 50, 50, 7),
                page.getAllValues().stream().map(Pageable::getPageSize).toList());
        assertEquals(List.of(0, 0, 0, 0),
                page.getAllValues().stream().map(Pageable::getPageNumber).toList(),
                "the feed answers the first page; the limit is the caller's, the paging is not");
    }

    /**
     * A console that renders both filter fields sends them empty rather than
     * omitting them, so a blank value has to mean "no filter" — the alternative,
     * a 400 on the shape the console itself produces, would look like a broken
     * feed.
     */
    @Test
    void blankFiltersMeanNoFilter() throws Exception {
        when(sessionRepo.findRecent(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/sessions?workflowType=&status=&limit=50")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        ArgumentCaptor<WorkflowType> workflow = ArgumentCaptor.forClass(WorkflowType.class);
        ArgumentCaptor<SessionStatus> status = ArgumentCaptor.forClass(SessionStatus.class);
        verify(sessionRepo).findRecent(workflow.capture(), status.capture(), any());
        assertNull(workflow.getValue());
        assertNull(status.getValue());
    }

    @Test
    void anUnknownFilterValueIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/sessions?workflowType=RESIDENTIAL")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'workflowType'."));

        mockMvc.perform(get("/api/v1/sessions?status=DONE")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'status'."));
        verifyNoInteractions(sessionRepo);
    }

    /** 1..200, and outside it a 400: a clamped page would read as a short result set. */
    @Test
    void thePageSizeIsBounded() throws Exception {
        when(sessionRepo.findRecent(any(), any(), any())).thenReturn(List.of());

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        mockMvc.perform(get("/api/v1/sessions?limit=1").header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/sessions?limit=200")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk());
        // Absent, and the empty spelling a cleared input field produces: both ask
        // for the default page rather than being answered with a 400.
        mockMvc.perform(get("/api/v1/sessions").header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/sessions?limit=")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk());
        verify(sessionRepo, times(4)).findRecent(any(), any(), page.capture());
        assertEquals(List.of(1, 200, 50, 50),
                page.getAllValues().stream().map(Pageable::getPageSize).toList());

        mockMvc.perform(get("/api/v1/sessions?limit=0")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 200"));

        for (String limit : List.of("-1", "201", "abc")) {
            mockMvc.perform(get("/api/v1/sessions?limit=" + limit)
                            .header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
        verify(sessionRepo, times(4)).findRecent(any(), any(), any());
    }

    /**
     * The order is the query's, so the controller must not reverse or re-sort
     * what it was handed. That {@code ORDER BY created_at DESC} is what the
     * database actually runs is asserted in {@code EntityPersistenceTest}.
     */
    @Test
    void rowsKeepTheOrderTheQueryAnsweredIn() throws Exception {
        LivenessSession newer = passed();
        LivenessSession older = passed();
        older.setId(UUID.fromString("5c8f1c9e-0000-4000-8000-000000000002"));
        older.setCreatedAt(OffsetDateTime.now().minusHours(1));
        when(sessionRepo.findRecent(any(), any(), any())).thenReturn(List.of(newer, older));

        mockMvc.perform(get("/api/v1/sessions").header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(newer.getId().toString()))
                .andExpect(jsonPath("$[1].id").value(older.getId().toString()));
    }

    /** A row for a session that is still running: the outcome fields are simply absent. */
    @Test
    void anUnfinishedSessionReturnsNoVerdictFields() throws Exception {
        LivenessSession active = LivenessSession.builder()
                .id(ID)
                .workflowType(WorkflowType.RESIDENT)
                .deviceId("L1-CAM-01")
                .status(SessionStatus.ACTIVE)
                .currentStage(LivenessStage.PASSIVE)
                .retryCount(0)
                .createdAt(OffsetDateTime.now())
                .build();
        when(sessionRepo.findRecent(any(), any(), any())).thenReturn(List.of(active));

        String body = mockMvc.perform(get("/api/v1/sessions")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].finalResult").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("\"finalResult\":null"), body);
        assertFalse(body.contains("failureReason"), body);
    }

    private static LivenessSession passed() {
        return LivenessSession.builder()
                .id(ID)
                .workflowType(WorkflowType.RESIDENT)
                .deviceId("L1-CAM-01")
                .status(SessionStatus.PASSED)
                .currentStage(LivenessStage.COMPLETED)
                .retryCount(1)
                .finalResult(true)
                .failureReason("presentation_attack:print")
                .createdAt(OffsetDateTime.now())
                .closedAt(OffsetDateTime.now())
                .build();
    }
}
