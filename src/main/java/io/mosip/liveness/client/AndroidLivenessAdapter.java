package io.mosip.liveness.client;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Android (Flutter/Pigeon) adapter that bridges the MOSIP Registration Client's
 * {@code Biometrics095Service} to the liveness engine.
 *
 * <h3>Integration with HostApiModule.java (Pigeon bridge):</h3>
 * <pre>
 *   // In HostApiModule.java — register liveness handlers:
 *   private AndroidLivenessAdapter livenessAdapter;
 *
 *   public void init(LivenessClient client) {
 *       this.livenessAdapter = new AndroidLivenessAdapter(client);
 *   }
 *
 *   // Pigeon method handlers:
 *   public String startLivenessSession(String workflowType, String deviceId) {
 *       return livenessAdapter.startSession(workflowType, deviceId).toString();
 *   }
 *
 *   public LivenessFrameResult submitLivenessFrame(String sessionId, byte[] frameJpeg) {
 *       return livenessAdapter.submitFrame(UUID.fromString(sessionId), frameJpeg);
 *   }
 *
 *   public LivenessChallengeResult validateLivenessChallenge(
 *           String sessionId, String challengeId, List&lt;byte[]&gt; frames) {
 *       return livenessAdapter.validateChallenge(
 *           UUID.fromString(sessionId), UUID.fromString(challengeId), frames);
 *   }
 * </pre>
 *
 * <h3>Integration with Biometrics095Service.java:</h3>
 * <pre>
 *   // Add STREAM consumption (new capability, existing SBI protocol):
 *   public void startLivenessStream(String modality, Consumer&lt;byte[]&gt; frameConsumer) {
 *       // Open STREAM endpoint (same operation Desktop already uses)
 *       // Feed decoded JPEG frames to frameConsumer
 *       // frameConsumer feeds to AndroidLivenessAdapter.onFrame()
 *   }
 *
 *   // Fallback: burst-rCapture for devices without STREAM support:
 *   public List&lt;byte[]&gt; burstCaptureForLiveness(int frameCount, long intervalMs) {
 *       List&lt;byte[]&gt; frames = new ArrayList&lt;&gt;();
 *       for (int i = 0; i &lt; frameCount; i++) {
 *           frames.add(captureSingleFrame());
 *           Thread.sleep(intervalMs);
 *       }
 *       return frames;
 *   }
 * </pre>
 *
 * <h3>Integration with Dart UI (liveness_capture_control.dart):</h3>
 * <pre>
 *   // In liveness_capture_control.dart:
 *   class LivenessCaptureControl extends ChangeNotifier {
 *       LivenessState _state = LivenessState.idle;
 *       String? _challengeType;
 *       String? _challengePrompt;
 *
 *       Future&lt;void&gt; startLivenessCheck(String workflow) async {
 *           _state = LivenessState.scoring;
 *           _sessionId = await LivenessPigeon.startSession(workflow, deviceId);
 *           notifyListeners();
 *       }
 *
 *       Future&lt;void&gt; onFrame(Uint8List frameJpeg) async {
 *           final result = await LivenessPigeon.submitFrame(_sessionId, frameJpeg);
 *           if (result.action == 'escalate_to_active') {
 *               _state = LivenessState.challengeActive;
 *               _challengeType = result.challengeType;
 *               _challengePrompt = _challengePromptFor(result.challengeType);
 *               notifyListeners();
 *           } else if (result.action == 'proceed') {
 *               _state = LivenessState.passed;
 *               notifyListeners();
 *           }
 *       }
 *   }
 * </pre>
 */
public class AndroidLivenessAdapter {

    private final LivenessClient client;
    private UUID currentSessionId;
    private boolean livenessPassed;
    private boolean livenessFailed;
    private Consumer<LivenessStateEvent> eventConsumer;

    public enum LivenessStateEvent {
        SCORING,
        CHALLENGE_ISSUED,
        PASSED,
        FAILED,
        PAD_ATTACK_DETECTED
    }

    public AndroidLivenessAdapter(LivenessClient client) {
        this.client = client;
    }

    /** Set event consumer for Dart UI notifications (called from Pigeon bridge). */
    public void setEventConsumer(Consumer<LivenessStateEvent> consumer) {
        this.eventConsumer = consumer;
    }

    /** Start a liveness session. Returns session ID for subsequent calls. */
    public UUID startSession(String workflowType, String deviceId) {
        return startSession(workflowType, deviceId, null);
    }

    /**
     * Start a liveness session owned by the given subject reference.
     * The session will be protected by session ownership checks.
     *
     * @param workflowType one of "RESIDENT", "OPERATOR", "SUPERVISOR"
     * @param deviceId     identifier of the capture device
     * @param subjectRef   the resident/operator/supervisor this session belongs to,
     *                     or {@code null} for an anonymous session
     * @return session ID for subsequent calls
     */
    public UUID startSession(String workflowType, String deviceId, String subjectRef) {
        LivenessClient.SessionInfo session = client.createSession(workflowType, deviceId, subjectRef);
        this.currentSessionId = session.id();
        this.livenessPassed = false;
        this.livenessFailed = false;
        return session.id();
    }

    /** Submit a frame from the STREAM endpoint or burst-rCapture. */
    public LivenessClient.FrameResult submitFrame(UUID sessionId, byte[] frameJpeg) {
        LivenessClient.FrameResult result = client.submitFrame(sessionId, frameJpeg);

        switch (result.action()) {
            case "proceed" -> {
                livenessPassed = true;
                notifyEvent(LivenessStateEvent.PASSED);
            }
            case "escalate_to_active" -> {
                notifyEvent(LivenessStateEvent.CHALLENGE_ISSUED);
            }
            case "reject" -> {
                livenessFailed = true;
                if (result.padFlag()) {
                    notifyEvent(LivenessStateEvent.PAD_ATTACK_DETECTED);
                } else {
                    notifyEvent(LivenessStateEvent.FAILED);
                }
            }
            default -> notifyEvent(LivenessStateEvent.SCORING);
        }

        return result;
    }

    /** Validate challenge frames from the Flutter challenge overlay. */
    public LivenessClient.ChallengeResult validateChallenge(
            UUID sessionId, UUID challengeId, List<byte[]> challengeFrames) {
        LivenessClient.ChallengeResult result = client.validateChallenge(
                sessionId, challengeId, challengeFrames);

        switch (result.action()) {
            case "proceed" -> {
                livenessPassed = true;
                notifyEvent(LivenessStateEvent.PASSED);
            }
            case "reject" -> {
                livenessFailed = true;
                notifyEvent(LivenessStateEvent.FAILED);
            }
        }

        return result;
    }

    /** Close the current session. */
    public LivenessClient.SessionSummary closeSession() {
        if (currentSessionId != null) {
            LivenessClient.SessionSummary summary = client.closeSession(currentSessionId);
            currentSessionId = null;
            livenessPassed = false;
            livenessFailed = false;
            return summary;
        }
        return null;
    }

    private void notifyEvent(LivenessStateEvent event) {
        if (eventConsumer != null) {
            eventConsumer.accept(event);
        }
    }

    public boolean isLivenessPassed() { return livenessPassed; }
    public boolean isLivenessFailed() { return livenessFailed; }
    public UUID getCurrentSessionId() { return currentSessionId; }
}
