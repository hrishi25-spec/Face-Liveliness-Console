package io.mosip.liveness.api;

import io.mosip.liveness.audit.AuditChainKey;
import io.mosip.liveness.audit.MetricsCollector;
import io.mosip.liveness.auth.AuthService;
import io.mosip.liveness.crud.AuditLogRepository;
import io.mosip.liveness.crud.ChallengeRepository;
import io.mosip.liveness.crud.ConfigPolicyRepository;
import io.mosip.liveness.crud.FrameEventRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.config.EffectivePolicyValidator;
import io.mosip.liveness.config.LivenessConfig;
import io.mosip.liveness.diagnostics.DiagnosticsService;
import io.mosip.liveness.services.ChallengeSelectorService;
import io.mosip.liveness.services.DecisionEngineService;
import io.mosip.liveness.services.ImageUtils;
import io.mosip.liveness.services.ConfigService;
import io.mosip.liveness.services.LivenessEngineService;
import io.mosip.liveness.services.PadEngineService;
import io.mosip.liveness.services.ThresholdCalibrationService;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Shared test configuration providing all mock beans.
 * Prevents Spring from loading real services (which depend on OpenCV native libs)
 * by providing mock alternatives for every dependency.
 */
@TestConfiguration
public class TestConfig {

    @Bean @Primary
    public LivenessSessionRepository sessionRepo() {
        return Mockito.mock(LivenessSessionRepository.class);
    }

    /**
     * Accounts and sessions, mocked like the services. A slice has no database, so
     * the real service cannot resolve a token; the filters that need it
     * ({@code AuthContextFilter}, {@code OperatorKeyFilter}) are {@code Filter}
     * beans and therefore always part of a slice, which is why this must exist
     * rather than being left out. Tests resolve the fixture tokens with
     * {@code AuthTestFixture.stub}. Default answers refuse everything, so a test
     * that forgets to stub is refused rather than silently admitted.
     */
    @Bean @Primary
    public AuthService authService() {
        return Mockito.mock(AuthService.class);
    }

    @Bean @Primary
    public FrameEventRepository frameEventRepo() {
        return Mockito.mock(FrameEventRepository.class);
    }

    @Bean @Primary
    public ChallengeRepository challengeRepo() {
        return Mockito.mock(ChallengeRepository.class);
    }

    @Bean @Primary
    public AuditLogRepository auditLogRepo() {
        return Mockito.mock(AuditLogRepository.class);
    }

    @Bean @Primary
    public ConfigPolicyRepository configRepo() {
        return Mockito.mock(ConfigPolicyRepository.class);
    }

    @Bean @Primary
    public ImageUtils imageUtils() {
        return Mockito.mock(ImageUtils.class);
    }

    @Bean @Primary
    public LivenessEngineService livenessEngine() {
        return Mockito.mock(LivenessEngineService.class);
    }

    @Bean @Primary
    public PadEngineService padEngine() {
        return Mockito.mock(PadEngineService.class);
    }

    @Bean @Primary
    public ChallengeSelectorService challengeSelector() {
        return Mockito.mock(ChallengeSelectorService.class);
    }

    @Bean @Primary
    public DecisionEngineService decisionEngine() {
        return Mockito.mock(DecisionEngineService.class);
    }

    /**
     * Diagnostic mode collector — mocked like the services, so a slice that
     * posts frames records nothing and {@code recordFrame} stays a no-op
     * (the mock's default), matching production's fail-closed flag.
     */
    @Bean @Primary
    public DiagnosticsService diagnosticsService() {
        return Mockito.mock(DiagnosticsService.class);
    }

    @Bean @Primary
    public ConfigService configService() {
        return Mockito.mock(ConfigService.class);
    }

    @Bean @Primary
    public ThresholdCalibrationService thresholdCalibrationService() {
        return Mockito.mock(ThresholdCalibrationService.class);
    }

    /**
 * MetricsController reads the limiter's tallies. Deliberately the counters, not
 * the filter: a mocked Filter bean is auto-registered in a @WebMvcTest slice and
 * swallows every request before it reaches a handler.
 */
    @Bean @Primary
    public RateLimitCounters rateLimitCounters() {
        return Mockito.mock(RateLimitCounters.class);
    }

    @Bean @Primary
    public MetricsCollector metricsCollector() {
        return Mockito.mock(MetricsCollector.class);
    }

    /**
 * Real validator, not a mock: the session-create path refuses an invalid
 * policy, and production's 15s window floor is what those slices must see.
 */
    @Bean @Primary
    public EffectivePolicyValidator effectivePolicyValidator() {
        return new EffectivePolicyValidator(LivenessConfig.MIN_CHALLENGE_WINDOW_MS);
    }

    /**
 * Real resolver, not a fallback: {@code @Value} only binds on a managed bean,
 * and a plain component is not part of a {@code @WebMvcTest} slice. Without
 * this bean the IP filter would build its own resolver, trust nobody, and
 * every forwarded-address assertion would silently read the socket peer.
 */
    @Bean @Primary
    public ClientIpResolver clientIpResolver() {
        return new ClientIpResolver();
    }

    /**
     * The chain key ConfigController hashes with, bound from the same properties
     * production uses — the slices run with them unset, which is the documented
     * SHA-256 fallback, so {@code /audit/verify} reports {@code hmac: false}.
     * A bean method is not a Spring-managed constructor, so both {@code @Value}
     * injections happen here by hand; AuditChainKeyTest covers the key behavior.
     */
    @Bean @Primary
    public AuditChainKey auditChainKey(
            @Value("${mosip.security.audit-hmac-secret:}") String secret,
            @Value("${mosip.security.audit-hmac-previous-secret:}") String previousSecret) {
        return new AuditChainKey(secret, previousSecret);
    }

    @Bean @Primary
    public io.mosip.liveness.security.AntiReplayValidator antiReplayValidator() {
        return Mockito.mock(io.mosip.liveness.security.AntiReplayValidator.class);
    }

    @Bean @Primary
    public io.mosip.liveness.services.LivenessEvidenceVerifier livenessEvidenceVerifier() {
        return Mockito.mock(io.mosip.liveness.services.LivenessEvidenceVerifier.class);
    }
}
