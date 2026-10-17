package io.mosip.liveness.core;

import java.util.Map;

/**
 * A single active-liveness challenge issued by the engine. The user never
 * chooses the challenge; parameters (e.g. gaze direction target) are
 * engine-selected and randomized.
 */
public record Challenge(ChallengeType type, long timeoutMs, Map<String, Double> parameters) {

    public Challenge {
        if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be > 0");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
