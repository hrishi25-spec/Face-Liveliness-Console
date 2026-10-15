package io.mosip.liveness.core;

/** Supported active-liveness challenge types (extensible pool). */
public enum ChallengeType {
    BLINK,
    SMILE,
    TURN_HEAD_LEFT,
    TURN_HEAD_RIGHT,
    LOOK_DIRECTION,
    /** Look upward. Convenience alias for LOOK_DIRECTION with dirY < 0. */
    LOOK_UP,
    /** Look downward. Convenience alias for LOOK_DIRECTION with dirY > 0. */
    LOOK_DOWN,
    /** Look left. Convenience alias for LOOK_DIRECTION with dirX < 0. */
    LOOK_LEFT,
    /** Look right. Convenience alias for LOOK_DIRECTION with dirX > 0. */
    LOOK_RIGHT
}
