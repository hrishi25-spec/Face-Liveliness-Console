package io.mosip.liveness.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.testfx.util.WaitForAsyncUtils;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * Headless JavaFX harness shared by the overlay tests.
 *
 * <p>Three things every one of them needs and only one of them may do:
 * <ul>
 *   <li><b>Start the toolkit — once per JVM.</b> JavaFX starts a toolkit a single
 *       time and cannot restart one that exited, so {@link #start()} is idempotent
 *       and no test class calls {@link Platform#exit()} — the Surefire fork's own
 *       exit ends the toolkit. A second test class in the same fork would otherwise
 *       fail with "Toolkit already initialized", which says nothing about the code
 *       under test.</li>
 *   <li><b>Prove it is headless.</b> The assertions here fail loudly when the
 *       surefire configuration (Monocle's Headless screen + the software
 *       rasteriser, see pom.xml) is lost — otherwise the tests would quietly
 *       require a display and a GPU and could not run in CI.</li>
 *   <li><b>One stage, reused.</b> Every case renders into it and hides it again,
 *       which keeps layout real and the tests order-independent.</li>
 * </ul>
 */
final class HeadlessFx {

    private static final int FX_TIMEOUT_SECONDS = 30;

    private static Stage stage;

    private HeadlessFx() {
    }

    /** Idempotent: every test class may call this from {@code @BeforeAll}. */
    static synchronized void start() {
        assertEquals("Monocle", System.getProperty("glass.platform"),
                "the overlay tests must run on Monocle's headless glass platform — see the "
                        + "surefire systemPropertyVariables in pom.xml");
        assertEquals("Headless", System.getProperty("monocle.platform"),
                "Monocle's headless screen must be the one selected");
        assertEquals("sw", System.getProperty("prism.order"),
                "headless runners have no GPU, so the software rasteriser is required");
        if (stage != null) {
            return;
        }

        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyStarted) {
            // Another class in this fork got there first; the latch is already moot.
            ready.countDown();
        }
        try {
            assertTrue(ready.await(60, TimeUnit.SECONDS), "the JavaFX toolkit did not start");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while starting the JavaFX toolkit", e);
        }
        // Hiding the stage between cases must not let the runtime shut down.
        Platform.setImplicitExit(false);

        stage = onFx(() -> {
            Stage s = new Stage();
            s.setScene(new Scene(new StackPane(), 900, 640));
            return s;
        });
    }

    /** Render {@code content} as the whole stage and lay it out. */
    static void show(Parent content) {
        onFx(() -> {
            stage.setScene(new Scene(content, 900, 640));
            stage.show();
            settle(content);
            return null;
        });
    }

    static void hide() {
        onFx(() -> {
            stage.hide();
            return null;
        });
    }

    /** Run work on the JavaFX Application Thread and surface failures here. */
    static <T> T onFx(Callable<T> work) {
        Future<T> task = WaitForAsyncUtils.asyncFx(work);
        try {
            return WaitForAsyncUtils.waitFor(FX_TIMEOUT_SECONDS, TimeUnit.SECONDS, task);
        } catch (TimeoutException e) {
            throw new AssertionError(
                    "timed out after " + FX_TIMEOUT_SECONDS + "s waiting for the JavaFX Application Thread", e);
        }
    }

    /**
     * Apply CSS and a layout pass so the assertions read what the renderer
     * produced rather than what the FXML declared.
     */
    static void settle(Node node) {
        Scene scene = node.getScene();
        assertNotNull(scene, "the node must be shown in a scene for CSS and layout to apply");
        Parent root = scene.getRoot();
        root.applyCss();
        root.layout();
    }

    static <T extends Node> T node(Parent root, String id, Class<T> type) {
        Node node = root.lookup("#" + id);
        assertNotNull(node, "node #" + id + " must be in the rendered overlay");
        assertTrue(type.isInstance(node), "#" + id + " must be a " + type.getSimpleName()
                + " but is a " + node.getClass().getSimpleName());
        return type.cast(node);
    }
}
