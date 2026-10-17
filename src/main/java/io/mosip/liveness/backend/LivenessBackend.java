package io.mosip.liveness.backend;

import io.mosip.liveness.core.FaceSignals;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.PadVerdict;

import java.util.Map;

/**
 * Pluggable liveness/PAD backend. The engine never hard-couples to a vendor
 * SDK; swap implementations via this interface (mock, TFLite MiniFASNet,
 * licensed SDK, ...).
 */
public interface LivenessBackend {

    /** Stable identifier used in audit records. */
    String id();

    /**
     * One-time initialization. Called once per engine construction, before any
     * session. Implementations should load models and verify hardware
     * acceleration availability here.
     * @throws io.mosip.liveness.core.LivenessException on failure
     */
    void initialize(Map<String, String> options);

    /** Detection + landmark signals for one frame (face count, quality, EAR, pose, gaze). */
    FaceSignals analyzeFrame(Frame frame);

    /** Passive liveness confidence score in [0,1] for one frame (higher = more alive). */
    double scorePassiveLiveness(Frame frame, FaceSignals signals);

    /** PAD verdict for one frame — runs as part of every assessment, passive or active. */
    PadVerdict assessPad(Frame frame, FaceSignals signals);

    /** Release model/native resources. */
    void shutdown();
}
