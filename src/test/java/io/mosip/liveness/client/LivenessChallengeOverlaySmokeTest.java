package io.mosip.liveness.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.mosip.liveness.android.LivenessFailCategory;
import io.mosip.liveness.android.LivenessFinalResult;
import io.mosip.liveness.android.LivenessHint;
import io.mosip.liveness.android.LivenessState;
import io.mosip.liveness.android.LivenessStateEvent;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Ellipse;

/**
 * Headless render smoke test for the Desktop liveness overlay — the real
 * {@link LivenessChallengeOverlay} built by the real {@code FXMLLoader}, on
 * Monocle's headless glass platform, laid out by the real JavaFX pipeline and
 * with the real {@code liveness-overlay.css} applied.
 *
 * <p>Why a third overlay test: {@code LivenessChallengeOverlayFxmlTest} proves
 * the FXML <em>declares</em> the right structure but never instantiates it, and
 * {@code LivenessOverlayPresenterTest} proves the state→view decision but never
 * touches a node. A card that is never toggled visible — or a button whose text
 * is never resolved — passes both. This test reads the rendered nodes after
 * each orchestrator event, so it exercises the wiring the two cannot.
 *
 * <p>Headless through {@link HeadlessFx}, which owns the toolkit for the whole
 * test JVM and fails loudly if the surefire configuration that makes it headless
 * is lost — otherwise this would quietly need a display and a GPU.
 */
class LivenessChallengeOverlaySmokeTest {

    private static final String STATUS_CARD = "statusCard";
    private static final String CHALLENGE_CARD = "challengeCard";
    private static final String FAILURE_CARD = "failureCard";

    /** Every card the overlay can draw; exactly one is visible per state. */
    private static final String[] CARDS = {STATUS_CARD, CHALLENGE_CARD, FAILURE_CARD};

    // ---------------------------------------------------------------- toolkit

    @BeforeAll
    static void startHeadlessJavaFx() {
        HeadlessFx.start();
    }

    private LivenessChallengeOverlay overlay;

    @BeforeEach
    void showOverlay() {
        overlay = HeadlessFx.onFx(() -> {
            LivenessChallengeOverlay o = new LivenessChallengeOverlay();
            HeadlessFx.show(o);
            return o;
        });
    }

    @AfterEach
    void hideOverlay() {
        HeadlessFx.hide();
    }

    // ------------------------------------------------------- the real thing

    @Test
    @DisplayName("the overlay is the real FXML tree with the real stylesheet applied")
    void theRealOverlayLoadsItsFxmlTreeAndStylesheet() {
        assertTrue(overlay.getStyleClass().contains("liveness-overlay"),
                "root style class comes from the FXML");
        assertEquals(1, overlay.getStylesheets().size(),
                "exactly the overlay stylesheet is attached by the constructor");
        assertTrue(overlay.getStylesheets().get(0).endsWith("/fxml/liveness-overlay.css"),
                "styles are applied from liveness-overlay.css, not from an inline or default sheet");
        assertNotNull(overlay.getPreviewSlot(), "the host preview slot is a real node from the FXML");

        // Every node the renderer drives is reachable by the fx:id the FXML declares.
        for (String id : new String[]{"statusCard", "statusLabel", "statusProgress", "challengeCard",
                "challengeIndex", "challengePrompt", "challengeProgress", "challengeFeedback",
                "failureCard", "failureMessage", "lockoutLabel", "retryButton", "cancelButton"}) {
            assertNotNull(overlay.lookup("#" + id), "node #" + id + " must exist in the rendered tree");
        }

        // CSS, not just the class attribute: the visible label resolves to the
        // sheet's white, while the toolkit default is near-black.
        apply(event(LivenessState.INITIALIZING));
        assertRenderedWhite(HeadlessFx.node(overlay, "statusLabel", Label.class));
    }

    @Test
    @DisplayName("every LivenessState renders exactly its design §1 card and text")
    void everyStateRendersExactlyItsDesignCard() {
        for (LivenessState state : LivenessState.values()) {
            apply(event(state));
            String expected = expectedCardId(state);

            assertEquals(state != LivenessState.IDLE, overlay.isVisible(), state + ": visibility");
            assertEquals(state != LivenessState.IDLE, overlay.isManaged(), state + ": managed");
            assertEquals(state == LivenessState.IDLE, overlay.isMouseTransparent(),
                    state + ": a hidden overlay must not swallow the host's clicks");

            for (String cardId : CARDS) {
                Region card = HeadlessFx.node(overlay, cardId, Region.class);
                boolean visible = cardId.equals(expected);
                assertEquals(visible, card.isVisible(), state + ": " + cardId + " visible");
                assertEquals(visible, card.isManaged(), state + ": " + cardId + " managed");
            }

            if (expected == null) {
                assertFalse(hasVisibleCard(), state + ": a hidden overlay draws no card");
                continue;
            }
            assertTrue(hasVisibleCard(), state + ": exactly one card is drawn");

            Label title = HeadlessFx.node(overlay, cardTextId(expected), Label.class);
            assertEquals(expectedCardText(state), title.getText(), state + ": card text");
            assertRenderedWhite(title);
            Region card = HeadlessFx.node(overlay, expected, Region.class);
            assertTrue(card.getWidth() > 0 && card.getHeight() > 0,
                    state + ": the " + expected + " must actually be laid out, got "
                            + card.getWidth() + "x" + card.getHeight());

            // design §4: Retry only where an attempt can be retried, Cancel whenever
            // the user can still abandon a visible session.
            Button retry = HeadlessFx.node(overlay, "retryButton", Button.class);
            Button cancel = HeadlessFx.node(overlay, "cancelButton", Button.class);
            assertEquals(retryOffered(state), retry.isVisible(), state + ": Retry affordance");
            assertTrue(cancel.isVisible(), state + ": Cancel is offered on every visible state");
            assertActionLabel(overlay, retry, "liveness.action.retry", state);
            assertActionLabel(overlay, cancel, "liveness.action.cancel", state);

            // The oval guide is green only for the PASSED row.
            if (state == LivenessState.PASSED) {
                assertPassedGreen(ovalGuide(overlay).getStroke(), state);
            } else {
                assertNeutralGuide(ovalGuide(overlay).getStroke(), state);
            }
        }
    }

    @Test
    @DisplayName("IDLE, a null event and reset() hide every card")
    void idleAndResetHideEveryCard() {
        apply(event(LivenessState.PASSIVE_EVALUATING));
        assertTrue(HeadlessFx.node(overlay, STATUS_CARD, Region.class).isVisible(), "precondition: something is drawn");

        apply((LivenessStateEvent) null);
        assertHidden();

        apply(event(LivenessState.IDLE));
        assertHidden();

        apply(event(LivenessState.ATTEMPT_FAILED));
        assertTrue(HeadlessFx.node(overlay, FAILURE_CARD, Region.class).isVisible(), "precondition: failure drawn");
        HeadlessFx.onFx(() -> {
            overlay.reset();
            HeadlessFx.settle(overlay);
            return null;
        });
        assertHidden();
    }

    // ------------------------------------------------------- card specifics

    @Test
    @DisplayName("the STATUS card renders its message and only its own progress bar")
    void statusCardRendersMessageAndProgress() {
        apply(event(LivenessState.PASSIVE_EVALUATING));
        assertEquals("Checking face liveness…", HeadlessFx.node(overlay, "statusLabel", Label.class).getText());
        ProgressBar statusProgress = HeadlessFx.node(overlay, "statusProgress", ProgressBar.class);
        assertRendered(statusProgress, "design §1: the heat-up window shows indeterminate progress");
        assertEquals(-1.0, statusProgress.getProgress(),
                "indeterminate (design §1) — a finite bar here would be invented information");
        assertNotRendered(HeadlessFx.node(overlay, "challengeProgress", ProgressBar.class),
                "the challenge card's bar stays hidden with the challenge card");

        // The warm-up bar is the STATUS card's alone; INITIALIZING has none.
        apply(event(LivenessState.INITIALIZING));
        assertNotRendered(HeadlessFx.node(overlay, "statusProgress", ProgressBar.class),
                "INITIALIZING has no warm-up window to report");
    }

    @Test
    @DisplayName("the CHALLENGE card renders prompt, index and live progress")
    void challengeCardRendersPromptIndexAndFeedback() {
        apply(event(LivenessState.CHALLENGE_PROMPT, "BLINK", null, null, 1, 2, null));
        Label prompt = HeadlessFx.node(overlay, "challengePrompt", Label.class);
        assertEquals("Please blink", prompt.getText(), "the system-selected challenge, never a raw type name");
        Label index = HeadlessFx.node(overlay, "challengeIndex", Label.class);
        assertTrue(index.isVisible(), "design §3: the turn counter (1/N) is shown");
        assertEquals("Challenge 1 / 2", index.getText());
        assertNotRendered(HeadlessFx.node(overlay, "challengeFeedback", Label.class),
                "no feedback line until frames are analysed");
        assertEquals(0.0, HeadlessFx.node(overlay, "challengeProgress", ProgressBar.class).getProgress(),
                "progress starts at zero for the issued challenge");

        // Verifying: the feedback line appears and the bar follows the combined score.
        apply(event(LivenessState.CHALLENGE_VERIFYING, "BLINK", null, null, 1, 2, 0.42));
        assertEquals("Please blink", HeadlessFx.node(overlay, "challengePrompt", Label.class).getText(),
                "the prompt stays up while the action is analysed");
        Label feedback = HeadlessFx.node(overlay, "challengeFeedback", Label.class);
        assertRendered(feedback, "the live feedback line appears while frames are analysed");
        assertEquals("Please continue", feedback.getText());
        assertEquals(0.42, HeadlessFx.node(overlay, "challengeProgress", ProgressBar.class).getProgress(), 0.001);

        // Unknown score → indeterminate, never a fabricated 0%.
        apply(event(LivenessState.CHALLENGE_VERIFYING, "SMILE", null, null, 2, 2, null));
        assertEquals("Please smile", HeadlessFx.node(overlay, "challengePrompt", Label.class).getText());
        assertEquals("Challenge 2 / 2", HeadlessFx.node(overlay, "challengeIndex", Label.class).getText());
        assertEquals(-1.0, HeadlessFx.node(overlay, "challengeProgress", ProgressBar.class).getProgress());

        // A hint-driven state keeps the hint on the STATUS card, not the challenge one.
        apply(event(LivenessState.POSITIONING, null, null, LivenessHint.TOO_FAR, null, null, null));
        assertEquals("Move a little closer.", HeadlessFx.node(overlay, "statusLabel", Label.class).getText());
    }

    @Test
    @DisplayName("the FAILURE card is painted in the failure tone and offers Retry where allowed")
    void failureCardRendersFailureToneAndActions() {
        apply(event(LivenessState.ATTEMPT_FAILED));
        assertEquals("We could not verify face liveness. Please try again.",
                HeadlessFx.node(overlay, "failureMessage", Label.class).getText(),
                "generic message only — no score, PAD verdict or model detail (design §1)");
        assertTrue(HeadlessFx.node(overlay, "retryButton", Button.class).isVisible());
        assertFailureTone(HeadlessFx.node(overlay, FAILURE_CARD, Region.class));

        apply(event(LivenessState.DEVICE_ERROR));
        assertEquals("Device error. Please check the camera and try again.",
                HeadlessFx.node(overlay, "failureMessage", Label.class).getText());
        assertTrue(HeadlessFx.node(overlay, "retryButton", Button.class).isVisible(),
                "a device failure is recoverable and does not consume an attempt (design §4)");

        apply(event(LivenessState.TERMINAL_FAILURE));
        assertEquals("Could not complete verification. Recovery required.",
                HeadlessFx.node(overlay, "failureMessage", Label.class).getText());
        assertFalse(HeadlessFx.node(overlay, "retryButton", Button.class).isVisible(),
                "retry budget exhausted → recovery guidance, no retry (design §4)");

        // The neutral status card must not be painted in the failure tone.
        apply(event(LivenessState.ABORTED));
        Color status = cardBackground(HeadlessFx.node(overlay, STATUS_CARD, Region.class));
        assertEquals(status.getRed(), status.getGreen(), 0.01, "status card keeps the neutral card tone");
        assertEquals(status.getGreen(), status.getBlue(), 0.01, "status card keeps the neutral card tone");
    }

    @Test
    @DisplayName("terminal LOCK_OUT renders the retry-in-seconds countdown")
    void lockoutCountdownIsRendered() {
        applyFinal(finalResult(LivenessFinalResult.LivenessOutcome.TERMINAL_FAILURE,
                LivenessFinalResult.NextAction.LOCKOUT, Optional.of(30)));
        Label lockout = HeadlessFx.node(overlay, "lockoutLabel", Label.class);
        assertTrue(lockout.isVisible(), "design §4 LOCK_OUT row draws the cool-down in the failure card");
        assertEquals("Try again in 30s", lockout.getText());
        assertFalse(HeadlessFx.node(overlay, "retryButton", Button.class).isVisible(),
                "the countdown replaces Retry; the gate is closed until it elapses");

        applyFinal(finalResult(LivenessFinalResult.LivenessOutcome.TERMINAL_FAILURE,
                LivenessFinalResult.NextAction.BLOCK, Optional.empty()));
        assertFalse(HeadlessFx.node(overlay, "lockoutLabel", Label.class).isVisible(),
                "no countdown when the terminal action is not LOCKOUT");
    }

    @Test
    @DisplayName("a PASSED outcome turns the oval green and keeps only Cancel")
    void passedOutcomeTurnsTheOvalGreen() {
        Paint before = ovalGuide(overlay).getStroke();
        assertNeutralGuide(before, "before any event");

        applyFinal(finalResult(LivenessFinalResult.LivenessOutcome.PASSED,
                LivenessFinalResult.NextAction.PROCEED, Optional.empty()));
        assertEquals("Good, face captured successfully",
                HeadlessFx.node(overlay, "statusLabel", Label.class).getText());
        assertTrue(overlay.getStyleClass().contains("passed"),
                "design §1: the PASSED row turns the oval green through the overlay's state class");
        assertPassedGreen(ovalGuide(overlay).getStroke(), "final PASSED");

        applyFinal(finalResult(LivenessFinalResult.LivenessOutcome.ABORTED,
                LivenessFinalResult.NextAction.BLOCK, Optional.empty()));
        assertEquals("Verification cancelled.", HeadlessFx.node(overlay, "statusLabel", Label.class).getText());
        assertFalse(overlay.getStyleClass().contains("passed"), "the green accent is cleared again");
        assertNeutralGuide(ovalGuide(overlay).getStroke(), "after ABORTED");
    }

    @Test
    @DisplayName("the rendered Retry / Cancel buttons fire the host callbacks")
    void renderedActionButtonsFireTheirCallbacks() {
        AtomicInteger retries = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();
        HeadlessFx.onFx(() -> {
            overlay.setOnRetry(retries::incrementAndGet);
            overlay.setOnCancel(cancels::incrementAndGet);
            return null;
        });

        apply(event(LivenessState.ATTEMPT_FAILED));
        HeadlessFx.onFx(() -> {
            HeadlessFx.node(overlay, "retryButton", Button.class).fire();
            HeadlessFx.node(overlay, "cancelButton", Button.class).fire();
            return null;
        });
        assertEquals(1, retries.get(), "pressing the rendered Retry runs the host's retry action");
        assertEquals(1, cancels.get(), "pressing the rendered Cancel runs the host's cancel action");
    }

    @Test
    @DisplayName("a swapped i18n catalogue reaches the rendered nodes")
    void swappedCatalogueRendersThroughTheSameNodes() {
        Map<String, String> catalogue = new HashMap<>(LivenessChallengeOverlay.DEFAULT_MESSAGES);
        catalogue.put("prompt.blink", "Blinque, please");
        catalogue.put("liveness.action.retry", "Try once more");
        HeadlessFx.onFx(() -> {
            overlay.setMessageLookup(catalogue);
            return null;
        });

        apply(event(LivenessState.CHALLENGE_PROMPT, "BLINK", null, null, 1, 1, null));
        assertEquals("Blinque, please", HeadlessFx.node(overlay, "challengePrompt", Label.class).getText());
        assertEquals("Challenge 1 / 1", HeadlessFx.node(overlay, "challengeIndex", Label.class).getText());

        apply(event(LivenessState.ATTEMPT_FAILED));
        assertEquals("Try once more", HeadlessFx.node(overlay, "retryButton", Button.class).getText());
        assertEquals("Retry", LivenessChallengeOverlay.DEFAULT_MESSAGES.get("liveness.action.retry"),
                "a deployment's catalogue must not leak back into the shared defaults");
    }

    // ------------------------------------------------------------- tables

    /**
     * The design's state→UI table, spelled out here so this test is an
     * independent oracle: the presenter's own expectations cannot make a wrong
     * mapping pass twice.
     */
    private static String expectedCardId(LivenessState state) {
        return switch (state) {
            case IDLE -> null;
            case INITIALIZING, POSITIONING, PASSIVE_EVALUATING, CHALLENGE_PASSED, PASSED, ABORTED -> STATUS_CARD;
            case CHALLENGE_PROMPT, CHALLENGE_VERIFYING -> CHALLENGE_CARD;
            case ATTEMPT_FAILED, RETRY_WAIT, TERMINAL_FAILURE, DEVICE_ERROR -> FAILURE_CARD;
        };
    }

    /** Card copy for a state event carrying no message key, hint or challenge. */
    private static String expectedCardText(LivenessState state) {
        return switch (state) {
            case IDLE -> null;
            case INITIALIZING, POSITIONING -> "Look directly at the camera.";
            case PASSIVE_EVALUATING -> "Checking face liveness…";
            case CHALLENGE_PROMPT, CHALLENGE_VERIFYING -> "Checking face liveness…";
            case CHALLENGE_PASSED -> "Action detected";
            case ATTEMPT_FAILED, RETRY_WAIT -> "We could not verify face liveness. Please try again.";
            case TERMINAL_FAILURE -> "Could not complete verification. Recovery required.";
            case DEVICE_ERROR -> "Device error. Please check the camera and try again.";
            case PASSED -> "Good, face captured successfully";
            case ABORTED -> "Verification cancelled.";
        };
    }

    /** design §4: Retry is offered exactly where a failure can be retried. */
    private static boolean retryOffered(LivenessState state) {
        return state == LivenessState.ATTEMPT_FAILED
                || state == LivenessState.RETRY_WAIT
                || state == LivenessState.DEVICE_ERROR;
    }

    private static String cardTextId(String cardId) {
        return switch (cardId) {
            case STATUS_CARD -> "statusLabel";
            case CHALLENGE_CARD -> "challengePrompt";
            case FAILURE_CARD -> "failureMessage";
            default -> throw new AssertionError("unknown card " + cardId);
        };
    }

    // ------------------------------------------------------------ assertions

    private boolean hasVisibleCard() {
        for (String cardId : CARDS) {
            if (HeadlessFx.node(overlay, cardId, Region.class).isVisible()) {
                return true;
            }
        }
        return false;
    }

    private void assertHidden() {
        assertFalse(overlay.isVisible(), "a hidden overlay draws nothing");
        assertFalse(overlay.isManaged(), "…and must not reserve layout space");
        assertTrue(overlay.isMouseTransparent(), "…and must not swallow the host's clicks");
        for (String cardId : CARDS) {
            Region card = HeadlessFx.node(overlay, cardId, Region.class);
            assertFalse(card.isVisible(), cardId + " hidden");
            assertFalse(card.isManaged(), cardId + " unmanaged");
        }
        assertFalse(HeadlessFx.node(overlay, "retryButton", Button.class).isVisible());
        assertFalse(HeadlessFx.node(overlay, "cancelButton", Button.class).isVisible());
    }

    /**
     * Whether the node is actually drawn: {@link Node#isVisible()} reports the
     * node's own flag, so a bar inside a hidden card still reads {@code true}.
     */
    private static boolean effectivelyVisible(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static void assertRendered(Node node, String message) {
        assertTrue(effectivelyVisible(node), message);
    }

    private static void assertNotRendered(Node node, String message) {
        assertFalse(effectivelyVisible(node), message);
    }

    /** The visible action buttons must carry their catalogue label, not a blank face. */
    private static void assertActionLabel(LivenessChallengeOverlay overlay, Button button,
                                          String key, LivenessState state) {
        if (!button.isVisible()) {
            return;
        }
        String expected = LivenessOverlayPresenter.resolve(overlay.getMessageLookup(), key);
        assertNotNull(button.getText(), state + ": a visible action button must be labelled, not blank");
        assertEquals(expected, button.getText(),
                state + ": the button renders the " + key + " catalogue string");
    }

    private static void assertRenderedWhite(Label label) {
        Paint fill = label.getTextFill();
        assertTrue(fill instanceof Color, "text fill must be a resolved colour, got " + fill);
        Color color = (Color) fill;
        assertEquals(1.0, color.getRed(), 0.05, "the overlay stylesheet paints card text white");
        assertEquals(1.0, color.getGreen(), 0.05, "the overlay stylesheet paints card text white");
        assertEquals(1.0, color.getBlue(), 0.05, "the overlay stylesheet paints card text white");
    }

    private static void assertFailureTone(Region card) {
        Color fill = cardBackground(card);
        assertTrue(fill.getRed() > fill.getGreen(),
                "the failure card renders in the red failure tone, not the neutral card tone");
        assertEquals(fill.getGreen(), fill.getBlue(), 0.01, "the failure tone is a red, not a hue");
    }

    private static Color cardBackground(Region card) {
        Background background = card.getBackground();
        assertNotNull(background, "the stylesheet's card background must be applied to " + card.getId());
        assertFalse(background.getFills().isEmpty(), ".liveness-card must paint a background for " + card.getId());
        return (Color) background.getFills().get(0).getFill();
    }

    /** {@code rgba(255, 255, 255, 0.75)} — the neutral positioning guide. */
    private static void assertNeutralGuide(Paint paint, Object where) {
        Color stroke = resolvedColour(paint, where + ": the oval guide stroke");
        assertEquals(1.0, stroke.getRed(), 0.01, where + ": the oval guide is white before PASSED");
        assertEquals(1.0, stroke.getGreen(), 0.01, where + ": the oval guide is white before PASSED");
        assertEquals(1.0, stroke.getBlue(), 0.01, where + ": the oval guide is white before PASSED");
        assertEquals(0.75, stroke.getOpacity(), 0.01, where + ": the guide is translucent");
    }

    /** {@code #4caf50} — the PASSED accent from {@code .liveness-overlay.passed}. */
    private static void assertPassedGreen(Paint paint, Object where) {
        Color stroke = resolvedColour(paint, where + ": the oval guide stroke");
        assertEquals(0x4c / 255.0, stroke.getRed(), 0.01, where + ": PASSED accent red channel");
        assertEquals(0xaf / 255.0, stroke.getGreen(), 0.01, where + ": PASSED accent green channel");
        assertEquals(0x50 / 255.0, stroke.getBlue(), 0.01, where + ": PASSED accent blue channel");
    }

    /** CSS resolves to a concrete colour; anything else means the sheet did not apply. */
    private static Color resolvedColour(Paint paint, String what) {
        assertTrue(paint instanceof Color, what + " must be a resolved colour, got " + paint);
        return (Color) paint;
    }

    // --------------------------------------------------------------- helpers

    private static Ellipse ovalGuide(LivenessChallengeOverlay overlay) {
        Node guide = overlay.lookup(".liveness-oval-guide");
        assertNotNull(guide, "the oval positioning guide is reachable by its style class");
        return (Ellipse) guide;
    }

    private void apply(LivenessStateEvent event) {
        HeadlessFx.onFx(() -> {
            overlay.applyState(event);
            HeadlessFx.settle(overlay);
            return null;
        });
    }

    private void applyFinal(LivenessFinalResult result) {
        HeadlessFx.onFx(() -> {
            overlay.applyFinal(result);
            HeadlessFx.settle(overlay);
            return null;
        });
    }

    private static LivenessStateEvent event(LivenessState state) {
        return event(state, null, null, null, null, null, null);
    }

    private static LivenessStateEvent event(LivenessState state, String challenge, String key,
                                            LivenessHint hint, Integer index, Integer total, Double progress) {
        return new LivenessStateEvent("session-1", state, challenge, hint, key,
                1, 3, index, total, progress, LivenessFailCategory.GENERIC);
    }

    private static LivenessFinalResult finalResult(LivenessFinalResult.LivenessOutcome outcome,
                                                   LivenessFinalResult.NextAction nextAction,
                                                   Optional<Integer> lockoutSeconds) {
        return new LivenessFinalResult("session-1", outcome, nextAction, lockoutSeconds, 60);
    }

}
