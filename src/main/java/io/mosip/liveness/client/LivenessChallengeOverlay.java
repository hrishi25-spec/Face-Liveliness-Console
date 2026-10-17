package io.mosip.liveness.client;

import io.mosip.liveness.android.LivenessFinalResult;
import io.mosip.liveness.android.LivenessListener;
import io.mosip.liveness.android.LivenessStateEvent;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.IOException;
import java.net.URL;
import java.util.Map;

/**
 * Desktop (JavaFX) liveness challenge overlay — the surface named by
 * {@code docs/ui-ux-design.md} and {@code docs/client-integration-guide.md}
 * §3.6.
 *
 * <p>A full-size {@link StackPane} the registration client drops on top of
 * its capture screen. It renders the design's screen: live preview slot
 * (JavaFX ImageView path), oval positioning guide, the status / challenge /
 * failure card for the current orchestrator state, progress, and the
 * context-sensitive Retry / Cancel actions. All decision logic lives in
 * {@link LivenessOverlayPresenter} (headless-tested against the design's
 * state→UI table); this class only paints it.
 *
 * <p>Text resolves through i18n keys from the shared message catalogue —
 * scores, PAD detail and model internals never reach the UI (design §1
 * "no technical exposure"). Events are the same {@link LivenessStateEvent} /
 * {@link LivenessFinalResult} pair Android renders (design §6: one state
 * machine drives all surfaces), delivered straight from the orchestrator's
 * {@link LivenessListener} and marshalled onto the FX Application Thread.
 *
 * <p>The event pair is also what the service-mediated path emits: when the gate
 * runs against this repository's REST API instead of in process,
 * {@link DesktopLivenessAdapter} translates its responses into the very same
 * events ({@link ServiceLivenessEventMapper}), so this overlay renders either
 * path without knowing which one it is driving.
 *
 * <h3>Integration</h3>
 * <pre>
 * // On the JavaFX Application Thread, over the capture screen:
 * LivenessChallengeOverlay overlay = new LivenessChallengeOverlay();
 * captureStack.getChildren().add(overlay);        // unmanaged while hidden
 * overlay.setOnRetry(orchestrator::startFreshAttempt);
 * overlay.setOnCancel(orchestrator::cancel);
 * orchestrator.setListener(overlay.asListener()); // onState / onFinal
 * overlay.setPreviewImage(webcamFrame);           // host-supplied FX Image
 * </pre>
 *
 * <p>Construct on the FX Application Thread (the host JavaFX runtime — the
 * MOSIP Registration Client — provides the toolkit; see the provided-scope
 * JavaFX dependencies in {@code pom.xml}).
 */
public class LivenessChallengeOverlay extends StackPane {

    /** Default English strings (design §9) — see {@link LivenessOverlayPresenter#DEFAULT_MESSAGES}. */
    public static final Map<String, String> DEFAULT_MESSAGES = LivenessOverlayPresenter.DEFAULT_MESSAGES;

    // FXML-bound nodes (contract tested against LivenessChallengeOverlay.fxml).
    @FXML private StackPane previewSlot;
    @FXML private StackPane statusCard;
    @FXML private ProgressBar statusProgress;
    @FXML private Label statusLabel;
    @FXML private VBox challengeCard;
    @FXML private Label challengeIndex;
    @FXML private Label challengePrompt;
    @FXML private ProgressBar challengeProgress;
    @FXML private Label challengeFeedback;
    @FXML private VBox failureCard;
    @FXML private Label failureMessage;
    @FXML private Label lockoutLabel;
    @FXML private Button retryButton;
    @FXML private Button cancelButton;

    private Map<String, String> messageLookup = DEFAULT_MESSAGES;
    private Runnable onRetry;
    private Runnable onCancel;
    private final ImageView previewView = new ImageView();

    private PauseTransition lockoutTimer;
    private int lockoutRemaining;

    public LivenessChallengeOverlay() {
        this(DEFAULT_MESSAGES);
    }

    /**
     * @param lookup i18n catalogue; {@code null}/empty falls back to
     *               {@link #DEFAULT_MESSAGES}. Swappable later via
     *               {@link #setMessageLookup(Map)}.
     */
    public LivenessChallengeOverlay(Map<String, String> lookup) {
        if (lookup != null && !lookup.isEmpty()) {
            this.messageLookup = lookup;
        }
        URL fxml = LivenessChallengeOverlay.class.getResource("/fxml/LivenessChallengeOverlay.fxml");
        if (fxml == null) {
            throw new IllegalStateException("Missing classpath resource /fxml/LivenessChallengeOverlay.fxml");
        }
        FXMLLoader loader = new FXMLLoader(fxml);
        // The overlay IS the FXML root and controller — deliberately no
        // fx:controller attribute (that would instantiate a second copy), and
        // the document declares <fx:root>: FXMLLoader only accepts a pre-set
        // root for an fx:root document and otherwise builds its own.
        loader.setRoot(this);
        loader.setController(this);
        try {
            loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load LivenessChallengeOverlay.fxml", e);
        }
        URL css = LivenessChallengeOverlay.class.getResource("/fxml/liveness-overlay.css");
        if (css != null) {
            getStylesheets().add(css.toExternalForm());
        }

        previewView.setPreserveRatio(true);
        previewView.setSmooth(true);
        previewView.fitWidthProperty().bind(previewSlot.widthProperty());
        previewView.fitHeightProperty().bind(previewSlot.heightProperty());
        previewSlot.getChildren().add(previewView);

        retryButton.setOnAction(e -> {
            if (onRetry != null) {
                onRetry.run();
            }
        });
        cancelButton.setOnAction(e -> {
            if (onCancel != null) {
                onCancel.run();
            }
        });
    }

    // ------------------------------------------------------------ event input

    /** Render an orchestrator state event (design §1 state→UI table). */
    public void applyState(LivenessStateEvent event) {
        render(LivenessOverlayPresenter.forState(event));
    }

    /** Render a terminal outcome (design §4: PASSED / recovery+lockout / cancelled). */
    public void applyFinal(LivenessFinalResult result) {
        render(LivenessOverlayPresenter.forFinal(result));
    }

    /**
     * A ready-made listener for {@code AndroidLivenessOrchestrator.setListener(...)}:
     * {@code onState} → {@link #applyState}, {@code onFinal} → {@link #applyFinal}.
     */
    public LivenessListener asListener() {
        return new LivenessListener() {
            @Override
            public void onState(LivenessStateEvent event) {
                applyState(event);
            }

            @Override
            public void onFinal(LivenessFinalResult result) {
                applyFinal(result);
            }
        };
    }

    /** Hide the overlay (e.g. after the capture dialog closes). */
    public void reset() {
        render(LivenessOverlayPresenter.OverlayView.hidden());
    }

    // ------------------------------------------------------------ configuration

    /** Swap the i18n catalogue; {@code null} restores the defaults. */
    public void setMessageLookup(Map<String, String> lookup) {
        this.messageLookup = (lookup == null || lookup.isEmpty()) ? DEFAULT_MESSAGES : lookup;
    }

    public Map<String, String> getMessageLookup() {
        return messageLookup;
    }

    /** Fired when the user presses Retry (visible only where design §4 allows). */
    public void setOnRetry(Runnable onRetry) {
        this.onRetry = onRetry;
    }

    /** Fired when the user presses Cancel (host aborts the session → applyFinal(ABORTED)). */
    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    /** The preview slot: the host may add its own camera node here… */
    public StackPane getPreviewSlot() {
        return previewSlot;
    }

    /** …or just hand each decoded webcam frame over as a JavaFX {@link Image}. */
    public void setPreviewImage(Image image) {
        previewView.setImage(image);
    }

    // ------------------------------------------------------------ rendering

    private void render(LivenessOverlayPresenter.OverlayView view) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> render(view));
            return;
        }

        getStyleClass().remove("passed");
        if (view.passedAccent()) {
            getStyleClass().add("passed");   // green oval (design §1 PASSED row)
        }
        setVisible(view.visible());
        setManaged(view.visible());
        setMouseTransparent(!view.visible());

        boolean showStatus = view.visible() && view.card() == LivenessOverlayPresenter.Card.STATUS;
        boolean showChallenge = view.visible() && view.card() == LivenessOverlayPresenter.Card.CHALLENGE;
        boolean showFailure = view.visible() && view.card() == LivenessOverlayPresenter.Card.FAILURE;

        statusCard.setVisible(showStatus);
        statusCard.setManaged(showStatus);
        if (showStatus) {
            statusLabel.setText(resolve(view.messageKey()));
        }
        statusProgress.setVisible(showStatus && view.statusProgressVisible());
        statusProgress.setManaged(statusProgress.isVisible());
        if (statusProgress.isVisible()) {
            statusProgress.setProgress(-1);  // indeterminate warm-up window
        }

        challengeCard.setVisible(showChallenge);
        challengeCard.setManaged(showChallenge);
        if (showChallenge) {
            challengePrompt.setText(resolve(view.promptKey()));
            boolean numbered = view.challengeIndex() != null && view.challengeTotal() != null;
            challengeIndex.setVisible(numbered);
            challengeIndex.setManaged(numbered);
            if (numbered) {
                challengeIndex.setText(LivenessOverlayPresenter.challengeIndexText(
                        messageLookup, view.challengeIndex(), view.challengeTotal()));
            }
            boolean liveFeedback = view.feedbackKey() != null;
            challengeFeedback.setVisible(liveFeedback);
            challengeFeedback.setManaged(liveFeedback);
            if (liveFeedback) {
                challengeFeedback.setText(resolve(view.feedbackKey()));
            }
            // Indeterminate while the combined score is still unknown (design §3).
            challengeProgress.setProgress(view.progressIndeterminate() ? -1 : view.progress());
        }

        failureCard.setVisible(showFailure);
        failureCard.setManaged(showFailure);
        if (showFailure) {
            failureMessage.setText(resolve(view.messageKey()));
        }
        if (view.lockoutSeconds() != null) {
            startLockoutCountdown(view.lockoutSeconds());
        } else {
            stopLockoutCountdown();
        }

        // Labels, not just visibility: the FXML declares bare buttons, so
        // without these two lines Retry and Cancel render as blank boxes.
        retryButton.setText(resolve("liveness.action.retry"));
        retryButton.setVisible(view.visible() && view.retryVisible());
        retryButton.setManaged(retryButton.isVisible());
        cancelButton.setText(resolve("liveness.action.cancel"));
        cancelButton.setVisible(view.visible() && view.cancelVisible());
        cancelButton.setManaged(cancelButton.isVisible());
    }

    /** LOCK_OUT countdown ("Try again in Ns", design §4) — ticks once a second. */
    private void startLockoutCountdown(int seconds) {
        stopLockoutCountdown();
        if (seconds <= 0) {
            return;
        }
        lockoutRemaining = seconds;
        lockoutLabel.setVisible(true);
        lockoutLabel.setManaged(true);
        lockoutLabel.setText(LivenessOverlayPresenter.lockoutText(messageLookup, lockoutRemaining));
        lockoutTimer = new PauseTransition(Duration.seconds(1));
        lockoutTimer.setOnFinished(e -> {
            lockoutRemaining--;
            if (lockoutRemaining <= 0) {
                stopLockoutCountdown();
                return;
            }
            lockoutLabel.setText(LivenessOverlayPresenter.lockoutText(messageLookup, lockoutRemaining));
            lockoutTimer.play();
        });
        lockoutTimer.play();
    }

    private void stopLockoutCountdown() {
        if (lockoutTimer != null) {
            lockoutTimer.stop();
            lockoutTimer = null;
        }
        lockoutLabel.setVisible(false);
        lockoutLabel.setManaged(false);
        lockoutLabel.setText("");
    }

    private String resolve(String key) {
        return LivenessOverlayPresenter.resolve(messageLookup, key);
    }
}
