package io.mosip.liveness.core;

/** Explicit, enumerable failure conditions of the liveness/PAD pipeline. */
public enum LivenessErrorCode {
    // Device / capture
    DEVICE_UNAVAILABLE("E101", "Biometric device is unavailable."),
    DEVICE_CONNECTION_FAILURE("E102", "Could not connect to the biometric device."),
    CAMERA_UNAVAILABLE("E103", "Camera is unavailable."),

    // Face / frame quality
    FACE_NOT_DETECTED("E201", "No face detected. Please look at the camera."),
    MULTIPLE_FACES_DETECTED("E202", "Multiple faces detected. Only one person may be in the frame."),
    POOR_FACE_QUALITY("E203", "Face is not clear. Please improve lighting and hold still."),

    // Liveness
    LIVENESS_SCORE_BELOW_THRESHOLD("E301", "Liveness check incomplete. Please continue looking at the camera."),

    // PAD — deliberately generic user message; never reveal detection method.
    PAD_FAILURE("E401", "Face verification could not be completed. Please try again."),

    // Active challenges
    ACTIVE_CHALLENGE_FAILURE("E501", "Verification action was not completed. Please try again."),
    CHALLENGE_TIMEOUT("E502", "Verification timed out. Please try again."),
    MAX_RETRIES_EXCEEDED("E503", "Verification could not be completed. Please contact support."),

    // v3 additions
    SESSION_TIMEOUT("E504", "Face verification timed out. Please try again."),
    MODEL_INTEGRITY("E505", "Model integrity check failed."),
    ACTIVE_REEVAL_FAILED("E506", "Liveness score dropped during active challenge. Please try again."),

    // Input / state
    INVALID_FRAME_DATA("E601", "Invalid capture data received."),
    INVALID_SESSION("E602", "Unknown or closed session."),
    INVALID_STATE("E603", "Operation not allowed in the current state."),
    CONFIGURATION_INVALID("E604", "Liveness configuration is invalid."),

    // Internal
    ENGINE_INTERNAL_ERROR("E901", "An internal error occurred. Please try again.");

    private final String code;
    private final String userMessage;

    LivenessErrorCode(String code, String userMessage) {
        this.code = code;
        this.userMessage = userMessage;
    }

    public String code() {
        return code;
    }

    /** Generic, safe-to-display message. Never exposes detection internals. */
    public String userMessage() {
        return userMessage;
    }

    public boolean isRetryableByUser() {
        return this != MAX_RETRIES_EXCEEDED && this != PAD_FAILURE;
    }
}
