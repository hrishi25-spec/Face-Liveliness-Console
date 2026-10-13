package io.mosip.liveness.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/auth/role}, with both keys configured.
 *
 * <p>The five cases the endpoint has to get right — the two valid keys, a wrong
 * key, no key at all, and no key configured — are asserted as an HTTP caller sees
 * them, because this endpoint's whole contract is the status and the body. A wrong
 * key and a missing key must be indistinguishable: both 401, both the service's
 * standard error shape, the same generic message. Nothing derived from the key
 * may come back in a body or reach a log line — the role name is the entire
 * answer — so the last two tests walk every case and assert that neither key text
 * appears anywhere.</p>
 *
 * <p>{@link OperatorKeyFilter} is in the slice (every {@code Filter} bean is), so
 * these requests are answered through the real chain: RateLimitFilter, then the
 * operator gate, then the handler. That is the path a console takes.</p>
 */
@WebMvcTest(AuthController.class)
@Import(TestConfig.class)
@TestPropertySource(properties = {
        "mosip.security.operator-api-key=" + AuthControllerTest.OPERATOR_KEY,
        "mosip.security.admin-api-key=" + AuthControllerTest.ADMIN_KEY
})
class AuthControllerTest {

    /** Synthetic; long enough to satisfy the startup rules (operator ≥16, admin ≥32). */
    static final String OPERATOR_KEY = "synthetic-operator-key-0001";
    static final String ADMIN_KEY = "synthetic-admin-key-0000000000000000001";

    @Autowired private MockMvc mockMvc;

    /** Everything that reached a logger while the tests ran. */
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger root;

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(logs);
        logs.stop();
    }

    @Test
    void operatorKeyAnswersTheOperatorRole() throws Exception {
        mockMvc.perform(get("/api/v1/auth/role").header(OperatorKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR"));
    }

    @Test
    void adminKeyAnswersTheSupervisorRole() throws Exception {
        mockMvc.perform(get("/api/v1/auth/role").header(AdminKeyGuard.HEADER, ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPERVISOR"));
    }

    @Test
    void adminKeyOutranksTheOperatorKeyWhenBothArePresented() throws Exception {
        mockMvc.perform(get("/api/v1/auth/role")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY)
                        .header(AdminKeyGuard.HEADER, ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPERVISOR"));
    }

    @Test
    void wrongKeyIsRefusedInTheStandardErrorShape() throws Exception {
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

    @Test
    void aKeyInTheAdminHeaderThatIsNotTheAdminKeyIsRefused() throws Exception {
        // The admin header is not a free pass: it is compared like the other one,
        // and the operator key in it is still just a wrong value.
        mockMvc.perform(get("/api/v1/auth/role").header(AdminKeyGuard.HEADER, OPERATOR_KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(OperatorKeyGuard.REFUSAL));
    }

    @Test
    void noResponseBodyEverCarriesAKey() throws Exception {
        List<String> bodies = new ArrayList<>();
        for (MvcResult result : everyCase()) {
            String body = result.getResponse().getContentAsString();
            bodies.add(result.getResponse().getStatus() + " " + body);
            assertFalse(body.contains(OPERATOR_KEY), body);
            assertFalse(body.contains(ADMIN_KEY), body);
        }
        assertTrue(bodies.size() >= 5, "every case must have produced a body: " + bodies);
    }

    @Test
    void noLogLineEverCarriesAKey() throws Exception {
        everyCase();
        List<String> lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        for (String line : lines) {
            assertFalse(line.contains(OPERATOR_KEY), line);
            assertFalse(line.contains(ADMIN_KEY), line);
        }
    }

    /** Every case this endpoint has, so the "never echoes the key" checks cover all of them. */
    private List<MvcResult> everyCase() throws Exception {
        return List.of(
                mockMvc.perform(get("/api/v1/auth/role")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY)).andReturn(),
                mockMvc.perform(get("/api/v1/auth/role")
                        .header(AdminKeyGuard.HEADER, ADMIN_KEY)).andReturn(),
                mockMvc.perform(get("/api/v1/auth/role")
                        .header(OperatorKeyGuard.HEADER, OPERATOR_KEY)
                        .header(AdminKeyGuard.HEADER, ADMIN_KEY)).andReturn(),
                mockMvc.perform(get("/api/v1/auth/role")
                        .header(OperatorKeyGuard.HEADER, "synthetic-wrong-key-000002")).andReturn(),
                mockMvc.perform(get("/api/v1/auth/role")).andReturn());
    }
}
