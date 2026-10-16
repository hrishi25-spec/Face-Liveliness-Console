package io.mosip.liveness.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Canonical localisation message-key registry for all user-facing surfaces
 * (Desktop JavaFX overlay, Android/Flutter UI, browser console).
 *
 * <p>Serves as the single source of truth for key-coverage gates.
 * A test verifies that every key defined here exists in the default English
 * catalogue, the Flutter ARB file, and the presenter.</p>
 */
public final class LivenessMessageKeys {

    // Status / Flow
    public static final String LIVENESS_CHECKING = "liveness.checking";
    public static final String LIVENESS_SUCCESS = "liveness.success";
    public static final String LIVENESS_FAILED_TRY_AGAIN = "liveness.failed.try_again";
    public static final String LIVENESS_PAD_GENERIC = "liveness.pad.generic";
    public static final String LIVENESS_MAX_RETRIES_RECOVERY = "liveness.max_retries.recovery";
    public static final String LIVENESS_CANCELLED = "liveness.cancelled";

    // Challenges
    public static final String PROMPT_BLINK = "prompt.blink";
    public static final String PROMPT_SMILE = "prompt.smile";
    public static final String PROMPT_TURN_LEFT = "prompt.turn_left";
    public static final String PROMPT_TURN_RIGHT = "prompt.turn_right";
    public static final String PROMPT_LOOK_UP = "prompt.look_up";
    public static final String PROMPT_LOOK_DOWN = "prompt.look_down";
    public static final String PROMPT_LOOK_LEFT = "prompt.look_left";
    public static final String PROMPT_LOOK_RIGHT = "prompt.look_right";
    public static final String PROMPT_LOOK_DIRECTION = "prompt.look_direction";

    // Live feedback
    public static final String FEEDBACK_CONTINUE = "feedback.continue";
    public static final String FEEDBACK_DETECTED = "feedback.detected";
    public static final String FEEDBACK_HOLD = "feedback.hold";

    // Hardware / Connectivity
    public static final String LIVENESS_DEVICE_ERROR = "liveness.device.error";
    public static final String LIVENESS_DEVICE_UNAVAILABLE = "liveness.device.unavailable";
    public static final String LIVENESS_DEVICE_DISCONNECTED = "liveness.device.disconnected";
    public static final String LIVENESS_CAMERA_UNAVAILABLE = "liveness.camera.unavailable";

    // Positioning Hints
    public static final String LIVENESS_HINT_LOOK_CAMERA = "liveness.hint.look_camera";
    public static final String LIVENESS_HINT_SINGLE_PERSON = "liveness.hint.single_person";
    public static final String LIVENESS_HINT_DISTANCE = "liveness.hint.distance";
    public static final String LIVENESS_HINT_LIGHTING = "liveness.hint.lighting";
    public static final String LIVENESS_HINT_LOOK_STRAIGHT = "liveness.hint.look_straight";
    public static final String LIVENESS_HINT_HOLD_STILL = "liveness.hint.hold_still";

    // Actions & Templates
    public static final String LIVENESS_ACTION_RETRY = "liveness.action.retry";
    public static final String LIVENESS_ACTION_CANCEL = "liveness.action.cancel";
    public static final String LIVENESS_CHALLENGE_INDEX = "liveness.challenge.index";
    public static final String LIVENESS_LOCKOUT_RETRY_IN = "liveness.lockout.retry_in";

    public static final Set<String> ALL_KEYS = Set.of(
            LIVENESS_CHECKING,
            LIVENESS_SUCCESS,
            LIVENESS_FAILED_TRY_AGAIN,
            LIVENESS_PAD_GENERIC,
            LIVENESS_MAX_RETRIES_RECOVERY,
            LIVENESS_CANCELLED,
            PROMPT_BLINK,
            PROMPT_SMILE,
            PROMPT_TURN_LEFT,
            PROMPT_TURN_RIGHT,
            PROMPT_LOOK_UP,
            PROMPT_LOOK_DOWN,
            PROMPT_LOOK_LEFT,
            PROMPT_LOOK_RIGHT,
            PROMPT_LOOK_DIRECTION,
            FEEDBACK_CONTINUE,
            FEEDBACK_DETECTED,
            FEEDBACK_HOLD,
            LIVENESS_DEVICE_ERROR,
            LIVENESS_DEVICE_UNAVAILABLE,
            LIVENESS_DEVICE_DISCONNECTED,
            LIVENESS_CAMERA_UNAVAILABLE,
            LIVENESS_HINT_LOOK_CAMERA,
            LIVENESS_HINT_SINGLE_PERSON,
            LIVENESS_HINT_DISTANCE,
            LIVENESS_HINT_LIGHTING,
            LIVENESS_HINT_LOOK_STRAIGHT,
            LIVENESS_HINT_HOLD_STILL,
            LIVENESS_ACTION_RETRY,
            LIVENESS_ACTION_CANCEL,
            LIVENESS_CHALLENGE_INDEX,
            LIVENESS_LOCKOUT_RETRY_IN
    );

    public static final Set<String> DESKTOP_ONLY_KEYS = Set.of(
            LIVENESS_CANCELLED,
            LIVENESS_DEVICE_DISCONNECTED,
            LIVENESS_ACTION_RETRY,
            LIVENESS_ACTION_CANCEL,
            LIVENESS_CHALLENGE_INDEX,
            LIVENESS_LOCKOUT_RETRY_IN
    );

    public static final Set<String> FLUTTER_KEYS;
    static {
        Set<String> s = new java.util.HashSet<>(ALL_KEYS);
        s.removeAll(DESKTOP_ONLY_KEYS);
        FLUTTER_KEYS = Collections.unmodifiableSet(s);
    }

    private LivenessMessageKeys() { }
}
