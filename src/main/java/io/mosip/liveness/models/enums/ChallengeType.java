package io.mosip.liveness.models.enums;

/**
 * Active-liveness challenge types as exposed over the REST API and stored in
 * the {@code challenges} table.
 *
 * <p>Values are persisted by name (upper snake case) and are the
 * hyphenated wire values used by the config API after lower-casing, e.g.
 * {@code TURN_LEFT} &rarr; {@code "turn_left"}.</p>
 */
public enum ChallengeType {
    BLINK,
    SMILE,
    TURN_LEFT,
    TURN_RIGHT,
    LOOK_DIRECTION,
    LOOK_UP,
    LOOK_DOWN,
    LOOK_LEFT,
    LOOK_RIGHT
}
