package io.mosip.liveness.services;

import io.mosip.liveness.config.EffectivePolicy;
import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.WorkflowType;
import io.mosip.liveness.crud.ConfigPolicyRepository;
import io.mosip.liveness.models.entity.ConfigPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks the DB &harr; engine conversions in {@link ConfigService}. These used to
 * contain contradictory branches (duplicate case labels that collapsed every
 * gaze challenge to {@code LOOK_DIRECTION}, plus an unresolved
 * {@code passiveThresholdActive} sentinel).
 */
class ConfigServiceTest {

    private final ConfigService service = new ConfigService(mock(ConfigPolicyRepository.class));

    @Test
    void gazeChallengesStayDistinctInsteadOfCollapsingToLookDirection() {
        ConfigPolicy db = ConfigPolicy.builder()
                .challengeTypes(new ArrayList<>(List.of(
                        "blink", "smile", "turn_left", "turn_right",
                        "look_up", "look_down", "look_left", "look_right")))
                .build();

        assertEquals(
                EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE,
                        ChallengeType.TURN_HEAD_LEFT, ChallengeType.TURN_HEAD_RIGHT,
                        ChallengeType.LOOK_UP, ChallengeType.LOOK_DOWN,
                        ChallengeType.LOOK_LEFT, ChallengeType.LOOK_RIGHT),
                service.mapToEffectivePolicy(db).allowedChallenges());
    }

    @Test
    void lookupDownLeftRightPickTheirOwnCoreTypes() {
        ConfigPolicy db = ConfigPolicy.builder()
                .challengeTypes(new ArrayList<>(List.of("look_up", "look_down", "look_left", "look_right")))
                .build();

        var challenges = service.mapToEffectivePolicy(db).allowedChallenges();
        assertEquals(EnumSet.of(ChallengeType.LOOK_UP, ChallengeType.LOOK_DOWN,
                ChallengeType.LOOK_LEFT, ChallengeType.LOOK_RIGHT), challenges);
        assertNotEquals(EnumSet.of(ChallengeType.LOOK_DIRECTION), challenges);
    }

    @Test
    void unknownChallengeNameFallsBackToBlink() {
        ConfigPolicy db = ConfigPolicy.builder()
                .challengeTypes(new ArrayList<>(List.of("not_a_challenge")))
                .build();

        assertEquals(EnumSet.of(ChallengeType.BLINK),
                service.mapToEffectivePolicy(db).allowedChallenges());
    }

    @Test
    void emptyChallengeListFallsBackToBlink() {
        ConfigPolicy db = ConfigPolicy.builder().challengeTypes(new ArrayList<>()).build();

        assertEquals(EnumSet.of(ChallengeType.BLINK),
                service.mapToEffectivePolicy(db).allowedChallenges());
    }

    @Test
    void negativeActiveThresholdSentinelResolvesToPassiveThreshold() {
        ConfigPolicy db = ConfigPolicy.builder().passiveThreshold(0.82).build();

        EffectivePolicy policy = service.mapToEffectivePolicy(db);

        assertEquals(0.82, policy.passiveThreshold(), 1e-9);
        // The -1.0 sentinel written by ConfigService must not leak to consumers:
        // FaceLivenessEngine compares it directly against a live score.
        assertEquals(0.82, policy.passiveThresholdActive(), 1e-9);
    }

    @Test
    void defaultPolicyAlsoResolvesTheSentinel() {
        ConfigPolicyRepository repo = mock(ConfigPolicyRepository.class);
        when(repo.findByWorkflowType(io.mosip.liveness.models.enums.WorkflowType.RESIDENT))
                .thenReturn(Optional.empty());
        EffectivePolicy defaults = new ConfigService(repo)
                .getEffectivePolicy(WorkflowType.RESIDENT_REGISTRATION);

        assertTrue(defaults.passiveThresholdActive() > 0,
                "sentinel must be resolved even on the DB-miss fallback path");
        assertEquals(defaults.passiveThreshold(), defaults.passiveThresholdActive(), 1e-9);
    }

    @Test
    void dbMissFallbackUsesTheEngineDefaultThreshold() {
        // The fallback used to be 0.75 while application.yml, LivenessConfig and
        // the docs said 0.80 — a missing config_policies row silently changed
        // the operating point.
        ConfigPolicyRepository repo = mock(ConfigPolicyRepository.class);
        when(repo.findByWorkflowType(io.mosip.liveness.models.enums.WorkflowType.RESIDENT))
                .thenReturn(Optional.empty());

        EffectivePolicy defaults = new ConfigService(repo)
                .getEffectivePolicy(WorkflowType.RESIDENT_REGISTRATION);

        assertEquals(io.mosip.liveness.config.LivenessConfig.DEFAULT_PASSIVE_THRESHOLD,
                defaults.passiveThreshold(), 1e-9);
        assertEquals(0.80, defaults.passiveThreshold(), 1e-9);
    }

    @Test
    void eachWorkflowFallsBackToItsOwnDistinctDefault() {
        // The three workflows must not collapse onto one operating point when a
        // config row is missing — that is the whole point of taking the user type
        // at session start.
        ConfigPolicyRepository repo = mock(ConfigPolicyRepository.class);
        when(repo.findByWorkflowType(any())).thenReturn(Optional.empty());
        ConfigService svc = new ConfigService(repo);

        EffectivePolicy resident = svc.getEffectivePolicy(WorkflowType.RESIDENT_REGISTRATION);
        EffectivePolicy operator = svc.getEffectivePolicy(WorkflowType.OPERATOR_AUTH);
        EffectivePolicy supervisor = svc.getEffectivePolicy(WorkflowType.SUPERVISOR_AUTH);

        assertEquals(0.80, resident.passiveThreshold(), 1e-9);
        assertEquals(0.82, operator.passiveThreshold(), 1e-9);
        assertEquals(0.85, supervisor.passiveThreshold(), 1e-9);

        assertEquals(io.mosip.liveness.shared.definitions.RepeatedFailureAction.ESCALATE_TO_OPERATOR,
                resident.onRepeatedFailure());
        assertEquals(io.mosip.liveness.shared.definitions.RepeatedFailureAction.FALLBACK,
                operator.onRepeatedFailure());
        assertEquals(io.mosip.liveness.shared.definitions.RepeatedFailureAction.LOCK_OUT,
                supervisor.onRepeatedFailure());

        assertEquals(2, supervisor.minChallengeCount());
        assertNotEquals(resident.minChallengeCount(), supervisor.minChallengeCount());
    }

    @Test
    void failureActionConversionsRoundTrip() {
        for (io.mosip.liveness.shared.definitions.RepeatedFailureAction core
                : io.mosip.liveness.shared.definitions.RepeatedFailureAction.values()) {
            ConfigPolicy row = ConfigPolicy.builder()
                    .onRepeatedFailure(service.toDbFailure(core))
                    .build();
            assertEquals(core, service.mapToEffectivePolicy(row).onRepeatedFailure(),
                    "round trip failed for " + core);
        }
    }

    @Test
    void dbPolicyIsReadForTheMappedWorkflow() {
        ConfigPolicyRepository repo = mock(ConfigPolicyRepository.class);
        ConfigPolicy row = ConfigPolicy.builder().passiveThreshold(0.91).build();
        when(repo.findByWorkflowType(io.mosip.liveness.models.enums.WorkflowType.SUPERVISOR))
                .thenReturn(Optional.of(row));

        EffectivePolicy policy = new ConfigService(repo)
                .getEffectivePolicy(WorkflowType.SUPERVISOR_AUTH);

        assertEquals(0.91, policy.passiveThreshold(), 1e-9);
    }

    @Test
    void workflowConversionsRoundTrip() {
        for (io.mosip.liveness.models.enums.WorkflowType db
                : io.mosip.liveness.models.enums.WorkflowType.values()) {
            WorkflowType core = service.toCoreWorkflow(db);
            assertEquals(db, service.toDbWorkflow(core));
        }
    }

    @Test
    void challengeConversionsRoundTrip() {
        for (ChallengeType core : ChallengeType.values()) {
            String dbName = service.toDbChallenge(core).name();
            ConfigPolicy db = ConfigPolicy.builder()
                    .challengeTypes(new ArrayList<>(List.of(dbName)))
                    .build();

            assertEquals(EnumSet.of(core), service.mapToEffectivePolicy(db).allowedChallenges(),
                    "round trip failed for " + core);
        }
    }
}
