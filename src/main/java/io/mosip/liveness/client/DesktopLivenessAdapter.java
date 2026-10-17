package io.mosip.liveness.client;

import io.mosip.liveness.android.LivenessListener;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Desktop (JavaFX) adapter that bridges the MOSIP Registration Client's
 * {@code Streamer} frame stream to the liveness engine.
 *
 * <h3>Integration with Streamer.java:</h3>
 * <pre>
 *   // In Streamer.java — add a frame listener field:
 *   private Consumer&lt;byte[]&gt; frameListener;
 *
 *   public void setFrameListener(Consumer&lt;byte[]&gt; listener) {
 *       this.frameListener = listener;
 *   }
 *
 *   // In the frame decode loop, after imageBytes = retrieveNextImage(urlStream):
 *   if (frameListener != null) {
 *       frameListener.accept(imageBytes);
 *   }
 * </pre>
 *
 * <h3>Usage in BiometricFxControl.java:</h3>
 * <pre>
 *   DesktopLivenessAdapter adapter = new DesktopLivenessAdapter(
 *       () -&gt; livenessClient,
 *       () -&gt; sessionContext.getCurrentWorkflow(),
 *       () -&gt; sessionContext.getDeviceId()
 *   );
 *
 *   // Subscribe to Streamer frames
 *   streamer.setFrameListener(adapter::onFrame);
 *
 *   // When face capture is triggered:
 *   adapter.startSession("RESIDENT", "L1-CAM-01");
 *   // Frames flow automatically via the listener
 *   // When liveness passes or fails, the callback fires
 * </pre>
 *
 * <h3>Rendering the overlay without the in-process orchestrator</h3>
 *
 * <p>This adapter is the service-mediated path: it calls this repository's REST
 * API through {@link LivenessClient} instead of driving
 * {@code AndroidLivenessOrchestrator} on the device. It emits the <em>same</em>
 * {@link io.mosip.liveness.android.LivenessStateEvent} /
 * {@link io.mosip.liveness.android.LivenessFinalResult} pair the orchestrator
 * emits — {@link ServiceLivenessEventMapper} is the translation — so the very
 * same {@link LivenessChallengeOverlay} renders either path (design §6):
 *
 * <pre>
 *   DesktopLivenessAdapter adapter = new DesktopLivenessAdapter(
 *       () -&gt; livenessClient, () -&gt; "RESIDENT", () -&gt; deviceId);
 *
 *   LivenessChallengeOverlay overlay = new LivenessChallengeOverlay();
 *   captureStack.getChildren().add(overlay);
 *   adapter.setListener(overlay.asListener());   // onState → applyState, onFinal → applyFinal
 *   overlay.setOnRetry(adapter::startSession);   // retry restarts the gate (fresh session)
 *   overlay.setOnCancel(adapter::stopSession);   // cancel → applyFinal(ABORTED)
 *
 *   adapter.startSession();                      // renders INITIALIZING → POSITIONING
 *   streamer.setFrameListener(adapter::onFrame);
 * </pre>
 *
 * <p>The two surfaces can be used together: {@link #setListener(LivenessListener)}
 * drives the overlay, {@link #setCallback(LivenessStateCallback)} still delivers
 * the raw {@link LivenessClient} DTOs a host-specific UI may want. Both are
 * optional and independent.
 *
 * <p>Transport failures are handled differently on the two entry points, on
 * purpose. {@link #startSession()} propagates
 * {@link LivenessClientException} — the host is in control there, has not yet
 * shown a capture screen, and the guide's error table is the right place to
 * decide. {@link #onFrame(byte[])} and
 * {@link #submitChallenge(UUID, List)} are called from a capture loop, where
 * throwing would kill the loop: they translate a transport failure into the
 * recoverable {@code DEVICE_ERROR} state (design §4 — "does not consume
 * attempts") and leave the session open so the next frame retries.
 *
 * <h3>Integration with SessionContext.validateFace():</h3>
 * <pre>
 *   // Before calling authService.authValidator():
 *   DesktopLivenessAdapter authAdapter = new DesktopLivenessAdapter(
 *       () -&gt; livenessClient, () -&gt; "OPERATOR", () -&gt; deviceId
 *   );
 *   authAdapter.startSession("OPERATOR", deviceId);
 *   // ... frames flow, liveness evaluated ...
 *   if (authAdapter.isLivenessPassed()) {
 *       authService.authValidator(biometrics);
 *   }
 * </pre>
 */
public class DesktopLivenessAdapter {

    /**
     * Callback interface for liveness state changes.
     */
    public interface LivenessStateCallback {
        /** Called when a frame is processed and the UI should update. */
        void onFrameProcessed(LivenessClient.FrameResult result);

        /** Called when a challenge is issued — show prompt to user. */
        void onChallengeIssued(String challengeType, int timeoutMs, UUID challengeId);

        /** Called when liveness passes — accept the capture. */
        void onLivenessPassed(LivenessClient.SessionSummary summary);

        /** Called when liveness fails — show error and retry. */
        void onLivenessFailed(String message, boolean padAttack);
    }

    /** Fallback prompt window when the service does not send one with the challenge. */
    private static final int DEFAULT_CHALLENGE_TIMEOUT_MS = 10_000;

    private final Supplier<LivenessClient> clientSupplier;
    private final Supplier<String> workflowSupplier;
    private final Supplier<String> deviceSupplier;

    private UUID currentSessionId;
    private boolean livenessPassed;
    private boolean livenessFailed;
    private LivenessStateCallback callback;

    // ---- UI event path (the pair the in-process orchestrator also emits) ----

    private LivenessListener listener;
    private LivenessClient.Policy policy;
    private int attemptsUsed;
    private int challengeIndex = 1;
    private String challengeType;
    private String challengeId;

    public DesktopLivenessAdapter(
            Supplier<LivenessClient> clientSupplier,
            Supplier<String> workflowSupplier,
            Supplier<String> deviceSupplier) {
        this.clientSupplier = clientSupplier;
        this.workflowSupplier = workflowSupplier;
        this.deviceSupplier = deviceSupplier;
    }

    public void setCallback(LivenessStateCallback callback) {
        this.callback = callback;
    }

    /**
     * Route this session's states to a listener — {@code overlay.asListener()} in
     * the desktop integration. Cleared by passing {@code null}.
     */
    public void setListener(LivenessListener listener) {
        this.listener = listener;
    }

    /**
     * Start a new liveness session for the current workflow and render the
     * warm-up states (INITIALIZING → POSITIONING).
     *
     * @throws LivenessClientException when the service cannot be reached — the
     *                                 host decides there whether to bypass,
     *                                 retry or fail the capture
     */
    public void startSession() {
        LivenessClient client = clientSupplier.get();
        String workflow = workflowSupplier.get();
        String device = deviceSupplier.get();

        LivenessClient.SessionInfo session = client.createSession(workflow, device);
        this.currentSessionId = session.id();
        this.livenessPassed = false;
        this.livenessFailed = false;
        // The policy frozen on the session is what lets the overlay render the
        // challenge counter and the attempt budget on this path (nullable on
        // sessions created before the service froze one).
        this.policy = session.policy();
        this.attemptsUsed = 0;
        this.challengeIndex = 1;
        this.challengeType = null;
        this.challengeId = null;

        deliver(ServiceLivenessEventMapper.started(context()));
    }

    /**
     * Start a new liveness session owned by the given subject reference.
     * The session will be protected by session ownership checks.
     *
     * @param subjectRef   the resident/operator/supervisor this session belongs to,
     *                     or {@code null} for an anonymous session
     * @throws LivenessClientException when the service cannot be reached — the
     *                                 host decides there whether to bypass,
                                 *                                 retry or fail the capture
     */
    public void startSession(String subjectRef) {
        LivenessClient client = clientSupplier.get();
        String workflow = workflowSupplier.get();
        String device = deviceSupplier.get();

        LivenessClient.SessionInfo session = client.createSession(workflow, device, subjectRef);
        this.currentSessionId = session.id();
        this.livenessPassed = false;
        this.livenessFailed = false;
        // The policy frozen on the session is what lets the overlay render the
        // challenge counter and the attempt budget on this path (nullable on
        // sessions created before the service froze one).
        this.policy = session.policy();
        this.attemptsUsed = 0;
        this.challengeIndex = 1;
        this.challengeType = null;
        this.challengeId = null;

        deliver(ServiceLivenessEventMapper.started(context()));
    }

    /**
     * Called by Streamer's frame listener on every decoded frame.
     * Routes the frame to the liveness engine and dispatches the result.
     */
    public void onFrame(byte[] jpegBytes) {
        if (currentSessionId == null || livenessPassed || livenessFailed) {
            return; // no active session or already decided
        }

        LivenessClient client = clientSupplier.get();
        LivenessClient.FrameResult result;
        try {
            result = client.submitFrame(currentSessionId, jpegBytes);
        } catch (LivenessClientException transport) {
            // Frames arrive from the streamer's decode loop: throwing here would
            // kill it. Recoverable device error, session stays open, no attempt.
            deliver(ServiceLivenessEventMapper.transportFailure(context()));
            return;
        }

        if (callback != null) {
            callback.onFrameProcessed(result);
        }

        LivenessClient.SessionSummary summary = null;
        switch (result.action()) {
            case "proceed" -> {
                livenessPassed = true;
                summary = closeSessionQuietly(client);
            }
            case "reject" -> {
                livenessFailed = true;
                summary = closeSessionQuietly(client);
            }
            default -> {
                // "retry_passive" and "escalate_to_active" leave the session open.
            }
        }

        deliver(ServiceLivenessEventMapper.fromFrame(result, context(),
                summary == null ? null : summary.failureReason()));
        advanceAfterFrame(result);

        if (callback != null) {
            switch (result.action()) {
                case "proceed" -> callback.onLivenessPassed(summary);
                case "escalate_to_active" -> {
                    LivenessClient.Challenge challenge = result.challenge();
                    if (challenge != null && challenge.challengeId() != null) {
                        callback.onChallengeIssued(
                                challenge.challengeType(),
                                challenge.timeoutMs() != null
                                        ? challenge.timeoutMs() : DEFAULT_CHALLENGE_TIMEOUT_MS,
                                UUID.fromString(challenge.challengeId()));
                    }
                }
                case "reject" -> callback.onLivenessFailed(result.message(), result.padFlag());
                default -> {
                    // frame processed, wait for the next frame
                }
            }
        }
    }

    /**
     * Called when the user completes an active challenge.
     * Submit the captured challenge frames for validation.
     */
    public void submitChallenge(UUID challengeId, List<byte[]> challengeFrames) {
        if (currentSessionId == null || livenessPassed || livenessFailed) {
            return;
        }

        LivenessClient client = clientSupplier.get();
        LivenessClient.ChallengeResult result;
        try {
            result = client.validateChallenge(currentSessionId, challengeId, challengeFrames);
        } catch (LivenessClientException transport) {
            deliver(ServiceLivenessEventMapper.transportFailure(context()));
            return;
        }

        // A failed window is an attempt, counted before the event is built so the
        // event reports the budget as spent when it is (orchestrator parity).
        if (ServiceLivenessEventMapper.consumesAttempt(result)) {
            attemptsUsed++;
        }

        LivenessClient.SessionSummary summary = null;
        switch (result.action()) {
            case "proceed" -> {
                livenessPassed = true;
                summary = closeSessionQuietly(client);
            }
            case "reject" -> {
                livenessFailed = true;
                summary = closeSessionQuietly(client);
            }
            default -> {
                // "retry_challenge" and "continue" leave the session open.
            }
        }

        deliver(ServiceLivenessEventMapper.fromValidation(result, context(),
                summary == null ? null : summary.failureReason()));
        advanceAfterValidation(result);

        if (callback != null) {
            switch (result.action()) {
                case "proceed" -> callback.onLivenessPassed(summary);
                case "reject" -> callback.onLivenessFailed(result.message(), false);
                default -> {
                    // retry_challenge: the next challenge arrives with the next frame
                }
            }
        }
    }

    /**
     * Stop the current session without completing it — the Cancel action, and the
     * desktop twin of {@code AndroidLivenessOrchestrator.cancel()}. Renders the
     * ABORTED outcome unless the session had already reached a verdict.
     */
    public void stopSession() {
        if (currentSessionId == null) {
            return;
        }
        boolean undecided = !livenessPassed && !livenessFailed;
        ServiceLivenessEventMapper.SessionContext context = context();

        closeSessionQuietly(clientSupplier.get());

        currentSessionId = null;
        livenessPassed = false;
        livenessFailed = false;
        policy = null;
        attemptsUsed = 0;
        challengeIndex = 1;
        challengeType = null;
        challengeId = null;

        if (undecided) {
            deliver(ServiceLivenessEventMapper.aborted(context));
        }
    }

    public boolean isLivenessPassed() { return livenessPassed; }
    public boolean isLivenessFailed() { return livenessFailed; }
    public UUID getCurrentSessionId() { return currentSessionId; }

    // ------------------------------------------------------------- internals

    /** The session snapshot the mapper renders: policy, counters, challenge in flight. */
    private ServiceLivenessEventMapper.SessionContext context() {
        Integer total = policy != null && policy.minChallengeCount() > 0
                ? policy.minChallengeCount() : null;
        return new ServiceLivenessEventMapper.SessionContext(
                currentSessionId == null ? null : currentSessionId.toString(),
                total == null ? null : challengeIndex,
                total,
                challengeType,
                attemptsUsed,
                policy == null ? 0 : policy.maxRetries(),
                policy == null ? null : policy.onRepeatedFailure());
    }

    private void deliver(ServiceLivenessEventMapper.Delivery delivery) {
        LivenessListener target = listener;
        if (target == null) {
            return;
        }
        for (var event : delivery.states()) {
            target.onState(event);
        }
        if (delivery.finalResult() != null) {
            target.onFinal(delivery.finalResult());
        }
    }

    /**
     * An escalation prompts a challenge. The service re-serves the <em>open</em>
     * challenge on every frame while it is active, so the counter advances only
     * when the challenge id is one this session has not prompted yet — a re-serve
     * must not turn "Challenge 1 / 2" into "Challenge 3 / 2".
     */
    private void advanceAfterFrame(LivenessClient.FrameResult result) {
        if (result == null || !"escalate_to_active".equals(result.action())) {
            return;
        }
        LivenessClient.Challenge challenge = result.challenge();
        challengeType = challenge == null ? null : challenge.challengeType();
        String id = challenge == null ? null : challenge.challengeId();
        if (id != null && !id.equals(challengeId)) {
            if (challengeId != null) {
                challengeIndex++;
            }
            challengeId = id;
        }
    }

    /**
     * A satisfied challenge that required another: the service issued the follow-up
     * with this response, so it is already the next challenge in the sequence.
     */
    private void advanceAfterValidation(LivenessClient.ChallengeResult result) {
        if (result == null || !"retry_challenge".equals(result.action()) || !result.passed()) {
            return;
        }
        LivenessClient.Challenge next = result.challenge();
        if (next != null && next.challengeId() != null) {
            challengeId = next.challengeId();
            challengeType = next.challengeType();
            challengeIndex++;
        } else {
            // No follow-up in the response: keep the last prompted id so the next
            // frame's escalation still recognises the issued challenge as new, and
            // drop the type until that prompt names it.
            challengeType = null;
        }
    }

    /**
     * Closing after a verdict is bookkeeping — the verdict itself already
     * arrived, so a transport failure here must not lose it.
     */
    private LivenessClient.SessionSummary closeSessionQuietly(LivenessClient client) {
        try {
            return client.closeSession(currentSessionId);
        } catch (LivenessClientException alreadyDecided) {
            return null;
        }
    }
}
