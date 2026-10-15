package io.mosip.liveness.models.converter;

import io.mosip.liveness.config.EffectivePolicy;
import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.shared.definitions.RepeatedFailureAction;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The snapshot is written at session creation and read back by the decision
 * engine, so the round trip must be lossless — including the enum sets and the
 * {@code passiveThresholdActive} sentinel normalisation.
 */
class EffectivePolicyJsonConverterTest {

    private final EffectivePolicyJsonConverter converter = new EffectivePolicyJsonConverter();

    @Test
    void roundTripsEveryField() {
        EffectivePolicy original = new EffectivePolicy(
                true, false, 0.82, 0.50, 5, 7, 2, 1, 20_000L,
                EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE, ChallengeType.TURN_HEAD_LEFT),
                RepeatedFailureAction.ESCALATE_TO_OPERATOR,
                -1.0, 30_000L, 1, 10, 0.6, 0.4);

        EffectivePolicy restored = converter.convertToEntityAttribute(
                converter.convertToDatabaseColumn(original));

        assertEquals(original, restored);
        // Sentinel must have normalised to the passive threshold in both directions.
        assertEquals(0.82, restored.passiveThresholdActive(), 1e-9);
    }

    @Test
    void nullMapsToNullBothWays() {
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
        assertNull(converter.convertToEntityAttribute("   "));
    }
}
