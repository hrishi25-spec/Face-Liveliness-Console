package io.mosip.liveness.android;

/**
 * Generic user-facing hints (orchestration spec §6). Raw detection details go
 * to audit only; the UI resolves these keys to localised strings.
 */
public enum LivenessHint {
    NO_FACE("liveness.hint.look_camera"),
    MULTIPLE_FACES("liveness.hint.single_person"),
    TOO_FAR("liveness.hint.distance"),
    TOO_DARK("liveness.hint.lighting"),
    BLURRY("liveness.hint.lighting"),
    LOOK_STRAIGHT("liveness.hint.look_straight"),
    HOLD_STILL("liveness.hint.hold_still");

    private final String uiMessageKey;

    LivenessHint(String uiMessageKey) {
        this.uiMessageKey = uiMessageKey;
    }

    /** Localisation key (spec §9); not raw text. */
    public String uiMessageKey() {
        return uiMessageKey;
    }
}
