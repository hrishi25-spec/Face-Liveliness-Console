package io.mosip.liveness.core;

import java.util.Objects;

/** Runtime exception carrying a machine-readable error code and safe user message. */
public class LivenessException extends RuntimeException {

    private final LivenessErrorCode errorCode;

    public LivenessException(LivenessErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public LivenessException(LivenessErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public LivenessErrorCode errorCode() {
        return errorCode;
    }
}
