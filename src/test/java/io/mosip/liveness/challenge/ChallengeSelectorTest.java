package io.mosip.liveness.challenge;

import io.mosip.liveness.core.Challenge;
import io.mosip.liveness.core.ChallengeType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeSelectorTest {

    private static final Set<ChallengeType> POOL = EnumSet.allOf(ChallengeType.class);

    @Test
    void everyChallengeTypeAppearsOverEnoughDraws() {
        ChallengeSelector selector = new ChallengeSelector(5000);
        Set<ChallengeType> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(selector.next(POOL, i).type());
        }
        assertEquals(POOL, seen, "selector must cover the whole pool");
    }

    @Test
    void noImmediateRepeats() {
        ChallengeSelector selector = new ChallengeSelector(5000);
        ChallengeType last = null;
        for (int i = 0; i < 500; i++) {
            ChallengeType t = selector.next(POOL, i).type();
            if (last != null) {
                assertTrue(t != last, "immediate repeat issued: " + t);
            }
            last = t;
        }
    }

    @Test
    void orderIsNotAFixedCycle() {
        // Two selectors over the same pool must diverge within a few cycles.
        ChallengeSelector a = new ChallengeSelector(1000);
        ChallengeSelector b = new ChallengeSelector(1000);
        boolean diverged = false;
        for (int i = 0; i < 10 && !diverged; i++) {
            if (a.next(POOL, i).type() != b.next(POOL, i).type()) {
                diverged = true;
            }
        }
        assertTrue(diverged, "challenge order looks like a fixed sequence");
    }

    @Test
    void lookDirectionParametersAreEngineChosen() {
        ChallengeSelector selector = new ChallengeSelector(1000);
        Set<Double> xs = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Challenge c = selector.next(EnumSet.of(ChallengeType.LOOK_DIRECTION), i);
            assertEquals(ChallengeType.LOOK_DIRECTION, c.type());
            xs.add(c.parameters().get("dirX"));
        }
        // All four direction variants must occur across draws
        Set<Double> expectedX = Set.of(1.0, -1.0, 0.0);
        assertEquals(expectedX, xs);
    }

    @Test
    void emptyPoolRejected() {
        ChallengeSelector selector = new ChallengeSelector(1000);
        assertThrows(IllegalArgumentException.class,
                () -> selector.next(Set.of(), 1));
    }

    @Test
    void noContradictoryConsecutiveChallengesWhenAlternativesExist() {
        ChallengeSelector selector = new ChallengeSelector(5000);
        ChallengeType last = null;
        for (int i = 0; i < 500; i++) {
            ChallengeType current = selector.next(POOL, i).type();
            if (last != null) {
                // Assert no contradictory back-to-back sequences
                boolean isContradictory =
                        (last == ChallengeType.TURN_HEAD_LEFT && current == ChallengeType.TURN_HEAD_RIGHT)
                        || (last == ChallengeType.TURN_HEAD_RIGHT && current == ChallengeType.TURN_HEAD_LEFT)
                        || (last == ChallengeType.LOOK_LEFT && current == ChallengeType.LOOK_RIGHT)
                        || (last == ChallengeType.LOOK_RIGHT && current == ChallengeType.LOOK_LEFT)
                        || (last == ChallengeType.LOOK_UP && current == ChallengeType.LOOK_DOWN)
                        || (last == ChallengeType.LOOK_DOWN && current == ChallengeType.LOOK_UP);
                assertTrue(!isContradictory, "contradictory consecutive challenges: " + last + " -> " + current);
            }
            last = current;
        }
    }
}
