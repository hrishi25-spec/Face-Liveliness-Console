package io.mosip.liveness.api;

import io.mosip.liveness.audit.MetricsCollector;
import io.mosip.liveness.audit.MetricsSnapshot;
import io.mosip.liveness.metrics.PipelineTimers;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.FrameEventRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MetricsController.class)
@Import(TestConfig.class)
class MetricsControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private LivenessSessionRepository sessionRepo;
    @Autowired private FrameEventRepository frameEventRepo;
    @Autowired private ChallengeRepository challengeRepo;
    @Autowired private RateLimitCounters rateLimitCounters;
    @Autowired private MetricsCollector metricsCollector;

    @Test
    void getMetrics_emptyDatabase_returnsZeros() throws Exception {
        when(sessionRepo.count()).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.PASSED)).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.FAILED)).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.ACTIVE)).thenReturn(0L);
        when(frameEventRepo.countDistinctSessionsByStage(LivenessStage.ACTIVE)).thenReturn(0L);
        when(frameEventRepo.count()).thenReturn(0L);
        when(challengeRepo.count()).thenReturn(0L);
        when(sessionRepo.sumRetryCount()).thenReturn(0L);
        when(metricsCollector.snapshot()).thenReturn(new MetricsSnapshot(
                0L, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0.0, 0.0, 0.0, 0.0));
        when(rateLimitCounters.snapshot()).thenReturn(new RateLimitCounters.Counts(0, 0, 0, 0, 0, 0, 0, 0));
        try (MockedStatic<PipelineTimers> mockedPipelineTimers = Mockito.mockStatic(PipelineTimers.class)) {
            mockedPipelineTimers.when(PipelineTimers::snapshot).thenReturn(java.util.Map.of());

            mockMvc.perform(get("/api/v1/metrics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalSessions").value(0))
                    .andExpect(jsonPath("$.passRate").value(0.0));
        }
    }

    @Test
    void getMetrics_withData_returnsCorrectRates() throws Exception {
        when(sessionRepo.count()).thenReturn(100L);
        when(sessionRepo.countByStatus(SessionStatus.PASSED)).thenReturn(80L);
        when(sessionRepo.countByStatus(SessionStatus.FAILED)).thenReturn(15L);
        when(sessionRepo.countByStatus(SessionStatus.ACTIVE)).thenReturn(5L);
        when(frameEventRepo.countDistinctSessionsByStage(LivenessStage.ACTIVE)).thenReturn(20L);
        when(frameEventRepo.count()).thenReturn(500L);
        when(challengeRepo.count()).thenReturn(30L);
        when(sessionRepo.sumRetryCount()).thenReturn(25L);
        when(metricsCollector.snapshot()).thenReturn(new MetricsSnapshot(
                0L, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0.0, 0.0, 0.0, 0.0));
        when(rateLimitCounters.snapshot()).thenReturn(new RateLimitCounters.Counts(0, 0, 0, 0, 0, 0, 0, 0));
        try (MockedStatic<PipelineTimers> mockedPipelineTimers = Mockito.mockStatic(PipelineTimers.class)) {
            mockedPipelineTimers.when(PipelineTimers::snapshot).thenReturn(java.util.Map.of());

            mockMvc.perform(get("/api/v1/metrics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalSessions").value(100))
                    .andExpect(jsonPath("$.passRate").value(0.8))
                    .andExpect(jsonPath("$.livenessFailureRate").value(0.15))
                    .andExpect(jsonPath("$.avgFramesPerSession").value(5.0));
        }
    }

    @Test
    void getMetrics_reportsRateLimiterCountersPerRule() throws Exception {
        when(sessionRepo.count()).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.PASSED)).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.FAILED)).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.ACTIVE)).thenReturn(0L);
        when(frameEventRepo.countDistinctSessionsByStage(LivenessStage.ACTIVE)).thenReturn(0L);
        when(frameEventRepo.count()).thenReturn(0L);
        when(challengeRepo.count()).thenReturn(0L);
        when(sessionRepo.sumRetryCount()).thenReturn(0L);
        when(metricsCollector.snapshot()).thenReturn(new MetricsSnapshot(
                0L, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0.0, 0.0, 0.0, 0.0));
        // 3 allowed, 2 refused on session creation; 1 allowed, 4 refused on frames.
        when(rateLimitCounters.snapshot()).thenReturn(new RateLimitCounters.Counts(3, 2, 1, 4, 0, 0, 0, 0));
        try (MockedStatic<PipelineTimers> mockedPipelineTimers = Mockito.mockStatic(PipelineTimers.class)) {
            mockedPipelineTimers.when(PipelineTimers::snapshot).thenReturn(java.util.Map.of());

            mockMvc.perform(get("/api/v1/metrics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rateLimitAllowedRequests").value(4))
                    .andExpect(jsonPath("$.rateLimitedRequests").value(6))
                    .andExpect(jsonPath("$.rateLimitedSessionCreate").value(2))
                    .andExpect(jsonPath("$.rateLimitedFrames").value(4))
                    .andExpect(jsonPath("$.rateLimitedOperatorKey").value(0))
                    .andExpect(jsonPath("$.rateLimitRejectionRate").value(0.6));
        }
    }

    @Test
    void getMetrics_neverReportsNaNWhenTheLimiterHasSeenNothing() throws Exception {
        when(sessionRepo.count()).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.PASSED)).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.FAILED)).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.ACTIVE)).thenReturn(0L);
        when(frameEventRepo.countDistinctSessionsByStage(LivenessStage.ACTIVE)).thenReturn(0L);
        when(frameEventRepo.count()).thenReturn(0L);
        when(challengeRepo.count()).thenReturn(0L);
        when(sessionRepo.sumRetryCount()).thenReturn(0L);
        when(metricsCollector.snapshot()).thenReturn(new MetricsSnapshot(
                0L, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0.0, 0.0, 0.0, 0.0));
        when(rateLimitCounters.snapshot()).thenReturn(new RateLimitCounters.Counts(0, 0, 0, 0, 0, 0, 0, 0));
        try (MockedStatic<PipelineTimers> mockedPipelineTimers = Mockito.mockStatic(PipelineTimers.class)) {
            mockedPipelineTimers.when(PipelineTimers::snapshot).thenReturn(java.util.Map.of());

            mockMvc.perform(get("/api/v1/metrics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rateLimitedRequests").value(0))
                    .andExpect(jsonPath("$.rateLimitRejectionRate").value(0.0));
        }
    }

    @Test
    void getMetrics_reportsApcerValues() throws Exception {
        // Setup metrics collector to return specific APCER values
        MetricsSnapshot metricsSnapshot = new MetricsSnapshot(
                10L,      // sessionsEnded
                100L,     // framesProcessed
                50.0,     // avgProcessingTimeMs
                200.0,    // avgChallengeCompletionMs
                0.1,      // retryRate
                0.2,      // failureRate
                0.05,     // escalationRate
                5L,       // padBlocks
                0.3,      // printPhotoApcer
                0.4,      // screenReplayApcer
                0.5,      // videoReplayApcer
                0.6       // otherApcer
        );

        when(metricsCollector.snapshot()).thenReturn(metricsSnapshot);
        when(sessionRepo.count()).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.PASSED)).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.FAILED)).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.ACTIVE)).thenReturn(0L);
        when(frameEventRepo.countDistinctSessionsByStage(LivenessStage.ACTIVE)).thenReturn(0L);
        when(frameEventRepo.count()).thenReturn(0L);
        when(challengeRepo.count()).thenReturn(0L);
        when(sessionRepo.sumRetryCount()).thenReturn(0L);
        when(rateLimitCounters.snapshot()).thenReturn(new RateLimitCounters.Counts(0, 0, 0, 0, 0, 0, 0, 0));
        try (MockedStatic<PipelineTimers> mockedPipelineTimers = Mockito.mockStatic(PipelineTimers.class)) {
            mockedPipelineTimers.when(PipelineTimers::snapshot).thenReturn(java.util.Map.of());

            mockMvc.perform(get("/api/v1/metrics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.printPhotoApcer").value(0.3))
                    .andExpect(jsonPath("$.screenReplayApcer").value(0.4))
                    .andExpect(jsonPath("$.videoReplayApcer").value(0.5))
                    .andExpect(jsonPath("$.otherApcer").value(0.6));
        }
    }

    /**
     * The operator-key rule is a third per-IP budget, so it needs its own tally:
     * reporting a refused key guess as a refused session creation would send an
     * operator looking at the wrong traffic.
     */
    @Test
    void getMetrics_reportsTheOperatorKeyRuleSeparately() throws Exception {
        when(sessionRepo.count()).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.PASSED)).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.FAILED)).thenReturn(0L);
        when(sessionRepo.countByStatus(SessionStatus.ACTIVE)).thenReturn(0L);
        when(frameEventRepo.countDistinctSessionsByStage(LivenessStage.ACTIVE)).thenReturn(0L);
        when(frameEventRepo.count()).thenReturn(0L);
        when(challengeRepo.count()).thenReturn(0L);
        when(sessionRepo.sumRetryCount()).thenReturn(0L);
        when(metricsCollector.snapshot()).thenReturn(new MetricsSnapshot(
                0L, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0.0, 0.0, 0.0, 0.0));
        // Nothing on the other two rules; five refused key attempts.
        when(rateLimitCounters.snapshot()).thenReturn(new RateLimitCounters.Counts(0, 0, 0, 0, 0, 5, 0, 0));
        try (MockedStatic<PipelineTimers> mockedPipelineTimers = Mockito.mockStatic(PipelineTimers.class)) {
            mockedPipelineTimers.when(PipelineTimers::snapshot).thenReturn(java.util.Map.of());

            mockMvc.perform(get("/api/v1/metrics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rateLimitedOperatorKey").value(5))
                    .andExpect(jsonPath("$.rateLimitedRequests").value(5))
                    .andExpect(jsonPath("$.rateLimitedSessionCreate").value(0))
                    .andExpect(jsonPath("$.rateLimitedFrames").value(0))
                    .andExpect(jsonPath("$.rateLimitRejectionRate").value(1.0));
        }
    }
}
