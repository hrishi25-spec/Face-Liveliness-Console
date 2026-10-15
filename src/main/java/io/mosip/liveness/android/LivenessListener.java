package io.mosip.liveness.android;

/**
 * Orchestrator → UI event surface (the Pigeon glue forwards these to the
 * Dart {@code LivenessFlutterApi} on the main thread). Delivery must never
 * block the orchestrator executor.
 */
public interface LivenessListener {
    void onState(LivenessStateEvent event);
    void onFinal(LivenessFinalResult result);
}
