package io.mosip.liveness.client;

/**
 * Unchecked exception for liveness client failures (network, serialization, etc.).
 */
public class LivenessClientException extends RuntimeException {

    public LivenessClientException(String message) {
        super(message);
    }

    public LivenessClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
