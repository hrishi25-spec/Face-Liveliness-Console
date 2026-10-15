package io.mosip.liveness.audit;

import io.mosip.liveness.core.PadAttackType;

/**
 * Thread-safe collector for anonymized operational metrics:
 * avg processing time, challenge completion time, retry rate, failure rate,
 * passive-to-active escalation rate, PAD block count, and per-species APCER.
 */
public final class MetricsCollector {

    private long framesProcessed;
    private double totalProcessingMs;
    private long challengesCompleted;
    private double totalChallengeMs;
    private long retries;
    private long sessionsEnded;
    private long sessionsFailed;
    private long escalations;
    private long padBlocks;

    // Per-species attack counters for APCER calculation
    private long printPhotoAttacks;
    private long printPhotoAcceptedAsBonaFide;
    private long screenReplayAttacks;
    private long screenReplayAcceptedAsBonaFide;
    private long videoReplayAttacks;
    private long videoReplayAcceptedAsBonaFide;
    private long otherAttacks;
    private long otherAcceptedAsBonaFide;

    public synchronized void recordProcessingTime(double ms) {
        framesProcessed++;
        totalProcessingMs += ms;
    }

    public synchronized void recordChallengeCompletion(double ms) {
        challengesCompleted++;
        totalChallengeMs += ms;
    }

    public synchronized void recordRetry() { retries++; }

    public synchronized void recordEscalation() { escalations++; }

    public synchronized void recordPadBlock() { padBlocks++; }

    /**
     * Records a session outcome and, when {@code attackType} is non-null, one
     * independently labeled attack presentation for APCER. Runtime detector
     * output is not ground truth and should not be passed as that label.
     */
    public synchronized void recordSessionEnd(boolean failed, PadAttackType attackType, boolean attackAcceptedAsBonaFide) {
        sessionsEnded++;
        if (failed) sessionsFailed++;

        // Track per-species attack attempts for APCER calculation (only when attackType is not null)
        if (attackType != null) {
            switch (attackType) {
                case PRINTED_PHOTO -> printPhotoAttacks++;
                case SCREEN_REPLAY -> screenReplayAttacks++;
                case VIDEO_REPLAY -> videoReplayAttacks++;
                case OTHER -> otherAttacks++;
            }
        }

        // Track attacks that were accepted as bona fide for APCER calculation (only when attackType is not null)
        if (attackAcceptedAsBonaFide && attackType != null) {
            switch (attackType) {
                case PRINTED_PHOTO -> printPhotoAcceptedAsBonaFide++;
                case SCREEN_REPLAY -> screenReplayAcceptedAsBonaFide++;
                case VIDEO_REPLAY -> videoReplayAcceptedAsBonaFide++;
                case OTHER -> otherAcceptedAsBonaFide++;
            }
        }
    }

    public synchronized MetricsSnapshot snapshot() {
        long s = Math.max(1, sessionsEnded);
        // Calculate per-species APCER: proportion of attack presentations classified as bona fide
        double printPhotoApcer = printPhotoAttacks == 0 ? 0.0 : (double) printPhotoAcceptedAsBonaFide / printPhotoAttacks;
        double screenReplayApcer = screenReplayAttacks == 0 ? 0.0 : (double) screenReplayAcceptedAsBonaFide / screenReplayAttacks;
        double videoReplayApcer = videoReplayAttacks == 0 ? 0.0 : (double) videoReplayAcceptedAsBonaFide / videoReplayAttacks;
        double otherApcer = otherAttacks == 0 ? 0.0 : (double) otherAcceptedAsBonaFide / otherAttacks;

        return new MetricsSnapshot(
                sessionsEnded,
                framesProcessed,
                framesProcessed == 0 ? 0 : totalProcessingMs / framesProcessed,
                challengesCompleted == 0 ? 0 : totalChallengeMs / challengesCompleted,
                (double) retries / s,
                (double) sessionsFailed / s,
                (double) escalations / s,
                padBlocks,
                printPhotoApcer,
                screenReplayApcer,
                videoReplayApcer,
                otherApcer);
    }
}
