package io.mosip.liveness.services;

import io.mosip.liveness.models.enums.ChallengeType;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;

/**
 * Dynamic, unpredictable challenge selector.
 * Maps to the Python framework's challenge_selector.py.
 */
@Service
public class ChallengeSelectorService {

    private final SecureRandom random = new SecureRandom();

    /**
     * Select the next challenge from allowed types, avoiding back-to-back repeats.
     */
    public ChallengeType selectChallenge(List<String> allowedTypes, String previousType) {
        List<ChallengeType> candidates = allowedTypes.stream()
                .map(t -> ChallengeType.valueOf(t.toUpperCase(Locale.ROOT)))
                .toList();

        if (candidates.isEmpty()) {
            candidates = Arrays.asList(ChallengeType.values());
        }

        if (previousType != null && candidates.size() > 1) {
            String prevUpper = previousType.toUpperCase(Locale.ROOT);
            candidates = candidates.stream()
                    .filter(c -> !c.name().equals(prevUpper))
                    .toList();
            if (candidates.isEmpty()) {
                candidates = Arrays.asList(ChallengeType.values());
            }
        }

        return candidates.get(random.nextInt(candidates.size()));
    }
}
