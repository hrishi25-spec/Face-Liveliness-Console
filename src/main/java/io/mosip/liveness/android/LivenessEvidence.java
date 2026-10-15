package io.mosip.liveness.android;

import java.util.List;
import java.util.Optional;

import io.mosip.liveness.core.ChallengeType;

/**
 * Signed liveness evidence produced by a successful gate (analyse.md §5.4
 * binding, orchestration spec §8 {@code pass()}). Contains no pixels: frames
 * stay in memory and are released
 * when the session ends (N5, R5 privacy rules).
 *
 * @param passiveScore     windowed median that satisfied the threshold
 * @param challenges       the engine-selected sequence (user never chooses — R3)
 * @param bestFrameSha256  hash of the best frame, for post-capture binding checks
 * @param bypassed         true when policy disabled the gate (audited)
 */
public record LivenessEvidence(
        String sessionId,
        String nonceHex,
        LivenessRole role,
        Optional<String> userId,
        String policyVersion,
        String engineId,
        String modelVersion,
        boolean engineCertified,
        double passiveScore,
        List<ChallengeType> challenges,
        List<Boolean> challengeResults,
        long startedEpochMs,
        long completedEpochMs,
        String bestFrameSha256,
        String deviceId,
        boolean bypassed,
        String signatureHex) {

    /** Canonical payload that the signer signs and the verifier re-derives. */
    public String canonicalPayload() {
        return String.join("|",
                String.valueOf(sessionId),
                nonceHex,
                role.name(),
                userId.orElse(""),
                policyVersion,
                engineId,
                modelVersion,
                String.valueOf(engineCertified),
                Double.toString(passiveScore),
                challenges.stream().map(Enum::name).reduce((a, b) -> a + "," + b).orElse(""),
                challengeResults.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""),
                String.valueOf(startedEpochMs),
                String.valueOf(completedEpochMs),
                bestFrameSha256 == null ? "" : bestFrameSha256,
                deviceId,
                String.valueOf(bypassed));
    }
}
