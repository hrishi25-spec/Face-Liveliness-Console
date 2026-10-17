package io.mosip.liveness.challenge;

import io.mosip.liveness.core.Challenge;
import io.mosip.liveness.core.ChallengeType;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dynamic, unpredictable challenge selector.
 *
 * Uses a shuffled "bag" per pool cycle (SecureRandom): every type appears once
 * before a repeat, order within a cycle is cryptographically random, and no
 * challenge is ever issued twice in a row while alternatives remain. This
 * defeats fixed-sequence replay without starving any challenge type.
 */
public final class ChallengeSelector {

    private final SecureRandom random;
    private final long timeoutMs;
    private final Deque<ChallengeType> bag = new ArrayDeque<>();
    private ChallengeType lastIssued;

    public ChallengeSelector(long timeoutMs) {
        this(timeoutMs, new SecureRandom());
    }

    /** Test-visible constructor with a deterministic RNG. */
    ChallengeSelector(long timeoutMs, SecureRandom random) {
        if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be > 0");
        this.timeoutMs = timeoutMs;
        this.random = random;
    }

    /** Draw the next unpredictable challenge from the given allowed pool. */
    public synchronized Challenge next(Set<ChallengeType> pool, int attemptOrdinal) {
        if (pool == null || pool.isEmpty()) {
            throw new IllegalArgumentException("challenge pool must not be empty");
        }
        ChallengeType t = draw(pool);
        lastIssued = t;
        return new Challenge(t, timeoutMs, parametersFor(t));
    }

    private ChallengeType draw(Set<ChallengeType> pool) {
        if (bag.isEmpty()) refill(pool);
        ChallengeType t = bag.poll();

        boolean hasNonContradictory = hasNonContradictoryAlternative(pool, lastIssued);
        if (shouldReject(t, lastIssued, hasNonContradictory, pool.size() > 1)) {
            bag.addLast(t);
            int attempts = bag.size();
            boolean found = false;
            for (int i = 0; i < attempts; i++) {
                ChallengeType candidate = bag.poll();
                if (!shouldReject(candidate, lastIssued, hasNonContradictory, pool.size() > 1)) {
                    t = candidate;
                    found = true;
                    break;
                }
                bag.addLast(candidate);
            }
            if (!found) {
                refill(pool);
                int refillAttempts = bag.size();
                for (int i = 0; i < refillAttempts; i++) {
                    ChallengeType candidate = bag.poll();
                    if (!shouldReject(candidate, lastIssued, hasNonContradictory, pool.size() > 1)) {
                        t = candidate;
                        found = true;
                        break;
                    }
                    bag.addLast(candidate);
                }
                if (!found) {
                    t = bag.poll();
                }
            }
        }
        return t;
    }

    private static boolean shouldReject(ChallengeType candidate, ChallengeType last,
                                        boolean hasNonContradictory, boolean canAvoidRepeat) {
        if (last == null) return false;
        if (candidate.equals(last) && canAvoidRepeat) return true;
        if (hasNonContradictory && isContradictory(last, candidate)) return true;
        return false;
    }

    private static boolean hasNonContradictoryAlternative(Set<ChallengeType> pool, ChallengeType last) {
        if (last == null) return true;
        for (ChallengeType candidate : pool) {
            if (!isContradictory(last, candidate)) return true;
        }
        return false;
    }

    private static boolean isContradictory(ChallengeType a, ChallengeType b) {
        if (a == null || b == null) return false;
        if (a == b) return true;
        return (a == ChallengeType.TURN_HEAD_LEFT && b == ChallengeType.TURN_HEAD_RIGHT)
                || (a == ChallengeType.TURN_HEAD_RIGHT && b == ChallengeType.TURN_HEAD_LEFT)
                || (a == ChallengeType.LOOK_LEFT && b == ChallengeType.LOOK_RIGHT)
                || (a == ChallengeType.LOOK_RIGHT && b == ChallengeType.LOOK_LEFT)
                || (a == ChallengeType.LOOK_UP && b == ChallengeType.LOOK_DOWN)
                || (a == ChallengeType.LOOK_DOWN && b == ChallengeType.LOOK_UP);
    }

    private void refill(Set<ChallengeType> pool) {
        List<ChallengeType> list = new ArrayList<>(pool);
        Collections.shuffle(list, random);
        bag.clear();
        bag.addAll(list);
    }

    private Map<String, Double> parametersFor(ChallengeType type) {
        return switch (type) {
            case LOOK_DIRECTION -> {
                double[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                double[] d = dirs[random.nextInt(dirs.length)];
                yield Map.of("dirX", d[0], "dirY", d[1]);
            }
            case LOOK_UP -> Map.of("dirX", 0.0, "dirY", -1.0);
            case LOOK_DOWN -> Map.of("dirX", 0.0, "dirY", 1.0);
            case LOOK_LEFT -> Map.of("dirX", -1.0, "dirY", 0.0);
            case LOOK_RIGHT -> Map.of("dirX", 1.0, "dirY", 0.0);
            default -> Map.of();
        };
    }
}
