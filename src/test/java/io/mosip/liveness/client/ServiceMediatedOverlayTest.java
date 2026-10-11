package io.mosip.liveness.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Region;

/**
 * The service-mediated path, end to end and headless: a scripted
 * {@link LivenessClient} → {@link DesktopLivenessAdapter} → the real
 * {@link LivenessChallengeOverlay}, rendered on Monocle's headless screen.
 *
 * <p>This is the claim the wiring has to earn: the overlay works
 * <em>without</em> the in-process orchestrator. The adapter is called from the
 * test thread exactly as the host's Streamer loop would call it (never on the
 * FX thread), so the marshalling through
 * {@link LivenessChallengeOverlay#asListener()} is exercised too, and the
 * assertions read the rendered nodes rather than the adapter's own state.
 */
class ServiceMediatedOverlayTest {

    private static final UUID SESSION = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String CHALLENGE_1 = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String CHALLENGE_2 = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final byte[] FRAME = {1, 2, 3};

    private ScriptedClient client;
    private DesktopLivenessAdapter adapter;
    private LivenessChallengeOverlay overlay;

    @BeforeAll
    static void startHeadlessJavaFx() {
        HeadlessFx.start();
    }

    @BeforeEach
    void wireTheOverlayToTheAdapter() {
        client = new ScriptedClient();
        adapter = new DesktopLivenessAdapter(() -> client, () -> "RESIDENT", () -> "L1-CAM-01");
        overlay = HeadlessFx.onFx(() -> {
            LivenessChallengeOverlay o = new LivenessChallengeOverlay();
            HeadlessFx.show(o);
            return o;
        });
        // The same one line the in-process orchestrator path uses.
        adapter.setListener(overlay.asListener());
    }

    @AfterEach
    void unhook() {
        HeadlessFx.hide();
        adapter.setListener(null);
    }

    @Test
    @DisplayName("a two-challenge session renders prompt, counter and success from REST responses alone")
    void aFullSessionRendersThroughTheOverlay() {
        client.policy = new LivenessClient.Policy(2, 1, 15_000, "LOCK_OUT");
        client.frames.add(frame("retry_passive", true, false, null));
        client.frames.add(frame("escalate_to_active", challenge("BLINK", CHALLENGE_1)));
        client.frames.add(frame("escalate_to_active", challenge("BLINK", CHALLENGE_1)));
        client.validations.add(challenge("retry_challenge", true, challenge("SMILE", CHALLENGE_2)));
        client.validations.add(challenge("proceed", true, null));

        run(() -> adapter.startSession());
        assertEquals("Look directly at the camera.", statusText(),
                "the session's warm-up mirrors the orchestrator's INITIALIZING → POSITIONING");
        assertTrue(card("statusCard").isVisible());

        run(() -> adapter.onFrame(FRAME));
        assertEquals("Checking face liveness…", statusText(),
                "a passive scoring frame shows the warm-up card");

        run(() -> adapter.onFrame(FRAME));
        assertTrue(card("challengeCard").isVisible(), "the escalation is a challenge prompt");
        assertEquals("Please blink", promptText(), "the prompt comes from the issued challenge type");
        assertEquals("Challenge 1 / 2", indexText(),
                "the counter comes from the frozen policy the service returned at creation");

        run(() -> adapter.onFrame(FRAME));
        assertEquals("Challenge 1 / 2", indexText(),
                "re-serving the open challenge must not advance the counter");

        run(() -> adapter.submitChallenge(UUID.fromString(CHALLENGE_1), List.of(FRAME)));
        assertEquals("Please smile", promptText(), "the follow-up challenge becomes the new prompt");
        assertEquals("Challenge 2 / 2", indexText());

        run(() -> adapter.submitChallenge(UUID.fromString(CHALLENGE_2), List.of(FRAME)));
        assertEquals("Good, face captured successfully", statusText(),
                "the service's proceed verdict renders the success card");
        assertTrue(overlay.getStyleClass().contains("passed"), "…and the green oval accent");
        assertFalse(button("retryButton").isVisible(), "a passed session offers no retry");
        assertTrue(adapter.isLivenessPassed());

        assertEquals(List.of("create", "frame", "frame", "frame", "validate", "validate", "close"),
                client.calls, "the session is closed once, after the verdict");
    }

    @Test
    @DisplayName("a rejected attempt renders the failure card with a labelled Retry")
    void aRejectedAttemptIsRetryable() {
        client.frames.add(frame("reject", true, true, "PRINT"));
        client.summary = summary("FAILED", "presentation_attack:PRINT");

        run(() -> adapter.startSession());
        run(() -> adapter.onFrame(FRAME));

        assertTrue(card("failureCard").isVisible());
        assertEquals("Face verification could not be completed. Please try again.", failureText(),
                "PAD stays generic — the attack type never reaches the UI");
        Button retry = button("retryButton");
        assertTrue(retry.isVisible(), "the user can try again (design §4)");
        assertEquals("Retry", retry.getText(), "the button carries its catalogue label");
        assertEquals("Cancel", button("cancelButton").getText());
        assertTrue(adapter.isLivenessFailed());
    }

    @Test
    @DisplayName("spent retries render recovery guidance with no retry affordance")
    void spentRetriesRenderTheRecoveryCard() {
        client.policy = new LivenessClient.Policy(2, 1, 15_000, "LOCK_OUT");
        client.frames.add(frame("reject", true, false, null));
        client.summary = summary("FAILED", "max_retries_exceeded");

        run(() -> adapter.startSession());
        run(() -> adapter.onFrame(FRAME));

        assertTrue(card("failureCard").isVisible());
        assertEquals("Could not complete verification. Recovery required.", failureText());
        assertFalse(button("retryButton").isVisible(),
                "the budget is spent: recovery guidance, not another attempt");
        assertTrue(button("cancelButton").isVisible(), "the user can still leave the flow");
    }

    @Test
    @DisplayName("a stopped session renders the cancelled state")
    void aStoppedSessionRendersCancelled() {
        run(() -> adapter.startSession());
        run(() -> adapter.stopSession());

        assertEquals("Verification cancelled.", statusText(), "the ABORTED outcome renders the cancelled card");
        assertFalse(button("retryButton").isVisible());
        assertTrue(client.calls.contains("close"), "stopping closes the service session");
    }

    @Test
    @DisplayName("a service outage renders the device error and keeps the session open")
    void aServiceOutageIsARecoverableDeviceError() {
        client.frames.add(new LivenessClientException("connection refused"));
        client.frames.add(frame("escalate_to_active", challenge("BLINK", CHALLENGE_1)));

        run(() -> adapter.startSession());
        run(() -> adapter.onFrame(FRAME));

        assertTrue(card("failureCard").isVisible());
        assertEquals("Device error. Please check the camera and try again.", failureText(),
                "the transport exception's own text must not leak into the UI");
        assertTrue(button("retryButton").isVisible(), "a device error does not consume an attempt");
        assertFalse(adapter.isLivenessFailed(), "the session stays open for the next frame");
        assertNotNull(adapter.getCurrentSessionId());

        run(() -> adapter.onFrame(FRAME));
        assertTrue(card("challengeCard").isVisible(),
                "the retry after the outage continues the same session");
        assertEquals("Please blink", promptText());
    }

    @Test
    @DisplayName("a challenge that is still being analysed renders the feedback line")
    void anOpenWindowRendersLiveFeedback() {
        client.frames.add(frame("escalate_to_active", challenge("TURN_HEAD_LEFT", CHALLENGE_1)));
        client.validations.add(challenge("continue", false, null));

        run(() -> adapter.startSession());
        run(() -> adapter.onFrame(FRAME));
        assertEquals("Please turn your head to the left", promptText());

        run(() -> adapter.submitChallenge(UUID.fromString(CHALLENGE_1), List.of(FRAME)));
        assertTrue(card("challengeCard").isVisible(), "the window is still open, not a failure");
        assertEquals("Please continue", HeadlessFx.node(overlay, "challengeFeedback", Label.class).getText());
        assertEquals(-1.0, HeadlessFx.node(overlay, "challengeProgress", ProgressBar.class).getProgress(),
                "an undecided window shows indeterminate progress, never a fabricated score");
    }

    @Test
    @DisplayName("the legacy callback surface keeps working alongside the listener")
    void theLegacyCallbackStillFires() {
        List<String> legacy = new ArrayList<>();
        adapter.setCallback(new DesktopLivenessAdapter.LivenessStateCallback() {
            @Override
            public void onFrameProcessed(LivenessClient.FrameResult result) {
                legacy.add("frame:" + result.action());
            }

            @Override
            public void onChallengeIssued(String challengeType, int timeoutMs, UUID challengeId) {
                legacy.add("challenge:" + challengeType + ":" + timeoutMs);
            }

            @Override
            public void onLivenessPassed(LivenessClient.SessionSummary summary) {
                legacy.add("passed:" + (summary != null));
            }

            @Override
            public void onLivenessFailed(String message, boolean padAttack) {
                legacy.add("failed:" + padAttack);
            }
        });
        client.frames.add(frame("escalate_to_active", challenge("BLINK", CHALLENGE_1)));
        client.frames.add(frame("proceed", true, false, null));

        run(() -> adapter.startSession());
        run(() -> adapter.onFrame(FRAME));
        run(() -> adapter.onFrame(FRAME));

        assertEquals(List.of("frame:escalate_to_active", "challenge:BLINK:15000", "frame:proceed", "passed:true"),
                legacy, "the raw-DTO callback is unchanged by the new event path");
        assertTrue(card("statusCard").isVisible(), "…and the overlay rendered the same verdict");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Run an adapter call exactly where the host calls it from — off the FX
     * thread — then let the queued render land and lay the scene out.
     */
    private void run(Runnable hostCall) {
        hostCall.run();
        HeadlessFx.onFx(() -> {
            HeadlessFx.settle(overlay);
            return null;
        });
    }

    private Region card(String id) {
        return HeadlessFx.node(overlay, id, Region.class);
    }

    private Button button(String id) {
        return HeadlessFx.node(overlay, id, Button.class);
    }

    private String statusText() {
        return HeadlessFx.node(overlay, "statusLabel", Label.class).getText();
    }

    private String promptText() {
        return HeadlessFx.node(overlay, "challengePrompt", Label.class).getText();
    }

    private String indexText() {
        return HeadlessFx.node(overlay, "challengeIndex", Label.class).getText();
    }

    private String failureText() {
        return HeadlessFx.node(overlay, "failureMessage", Label.class).getText();
    }

    private static LivenessClient.Challenge challenge(String type, String id) {
        return new LivenessClient.Challenge(id, type, 15_000, 1);
    }

    private static LivenessClient.FrameResult frame(String action, boolean faceDetected,
                                                    boolean padFlag, String padAttackType) {
        return new LivenessClient.FrameResult(SESSION, "PASSIVE", faceDetected, false, 0.9, 0.87,
                padFlag, padAttackType, action, null,
                "No face detected. Please position your face in the frame.");
    }

    private static LivenessClient.FrameResult frame(String action, LivenessClient.Challenge challenge) {
        return new LivenessClient.FrameResult(SESSION, "ACTIVE", true, false, 0.9, 0.87,
                false, null, action, challenge, "Please blink.");
    }

    private static LivenessClient.ChallengeResult challenge(String action, boolean passed,
                                                           LivenessClient.Challenge next) {
        return new LivenessClient.ChallengeResult(SESSION, passed, action, "Action detected.", next);
    }

    private static LivenessClient.SessionSummary summary(String status, String failureReason) {
        return new LivenessClient.SessionSummary(SESSION, "RESIDENT", status, false, failureReason,
                12, 1, 0, 4_200L);
    }

    /**
     * A {@link LivenessClient} that replays a scripted protocol: what the service
     * would answer, in order, plus the calls the adapter made. Throwing
     * {@link LivenessClientException} from the script models a transport outage.
     */
    private static final class ScriptedClient implements LivenessClient {

        final List<String> calls = new ArrayList<>();
        final Deque<Object> frames = new ArrayDeque<>();
        final Deque<Object> validations = new ArrayDeque<>();
        Policy policy;
        SessionSummary summary = summary("PASSED", null);

        @Override
        public SessionInfo createSession(String workflowType, String deviceId, String subjectRef) {
            calls.add("create");
            return new SessionInfo(SESSION, workflowType, "ACTIVE", policy);
        }

        @Override
        public SessionSummary closeSession(UUID sessionId) {
            calls.add("close");
            return summary;
        }

        @Override
        public FrameResult submitFrame(UUID sessionId, byte[] frameJpeg) {
            calls.add("frame");
            return (FrameResult) next(frames);
        }

        @Override
        public ChallengeResult validateChallenge(UUID sessionId, UUID challengeId,
                                                 List<byte[]> challengeJpegFrames) {
            calls.add("validate");
            return (ChallengeResult) next(validations);
        }

        @Override
        public boolean isLivenessEnabled(String workflowType) {
            return true;
        }

        private static Object next(Deque<Object> script) {
            Object next = script.poll();
            if (next == null) {
                throw new IllegalStateException("the script ran out of responses");
            }
            if (next instanceof LivenessClientException outage) {
                throw outage;
            }
            return next;
        }
    }
}
