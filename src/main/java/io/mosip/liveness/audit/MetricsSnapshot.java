package io.mosip.liveness.audit;

/** Anonymized operational metrics snapshot (no PII, no raw model data). */
public record MetricsSnapshot(
        long sessionsEnded,
        long framesProcessed,
        double avgProcessingTimeMs,
        double avgChallengeCompletionMs,
        double retryRate,
        double failureRate,
        double escalationRate,
        long padBlocks,

        // Per-species APCER (Attack Presentation Classification Error Rate)
        // APCER = proportion of attack presentations classified as bona fide
        double printPhotoApcer,
        double screenReplayApcer,
        double videoReplayApcer,
        double otherApcer) {
}
