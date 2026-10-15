package io.mosip.liveness.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javafx.fxml.FXML;

/**
 * Structural contract of {@code LivenessChallengeOverlay.fxml} against the
 * {@link LivenessChallengeOverlay} controller — parsed with plain DOM, so it
 * runs headless without starting the JavaFX toolkit.
 */
class LivenessChallengeOverlayFxmlTest {

    private static final String FXML = "/fxml/LivenessChallengeOverlay.fxml";
    private static final String CSS = "/fxml/liveness-overlay.css";

    @Test
    void fxmlNodesMatchTheControllerFieldsExactly() throws Exception {
        Document doc = parse(FXML);
        Element root = doc.getDocumentElement();

        assertEquals("fx:root", root.getTagName(),
                "the root must be <fx:root>: with a concrete element FXMLLoader builds its own root "
                        + "and rejects the controller instance handed in via setRoot");
        assertEquals("javafx.scene.layout.StackPane", root.getAttribute("type"),
                "<fx:root> must name the overlay class the host supplies");
        assertTrue(styleClasses(root).contains("liveness-overlay"));
        assertEquals("false", root.getAttribute("visible"), "hidden until the first event");
        assertEquals("false", root.getAttribute("managed"), "unmanaged while hidden");
        assertFalse(root.hasAttribute("fx:controller"),
                "controller is attached via FXMLLoader.setController(this) — an fx:controller "
                        + "would build a second overlay instance");
        assertFalse(root.hasAttribute("fx:id"), "the root IS the overlay instance");

        Set<String> fxIds = new HashSet<>();
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (e.hasAttribute("fx:id")) {
                assertTrue(fxIds.add(e.getAttribute("fx:id")),
                        "duplicate fx:id " + e.getAttribute("fx:id"));
            }
        }

        Set<String> fields = Arrays.stream(LivenessChallengeOverlay.class.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(FXML.class))
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertEquals(fields, fxIds,
                "every @FXML field must bind a node and every fx:id must bind a field");
    }

    @Test
    void fxmlCarriesTheWholeDesignSurface() throws Exception {
        Document doc = parse(FXML);

        // Preview slot + oval guide (design §1 screen).
        assertTrue(hasFxId(doc, "previewSlot"));
        assertEquals(1, doc.getElementsByTagName("Ellipse").getLength(), "oval positioning guide");

        // Status / challenge / failure cards.
        assertTrue(hasFxId(doc, "statusCard"));
        assertTrue(hasFxId(doc, "statusLabel"));
        assertTrue(hasFxId(doc, "challengeCard"));
        assertTrue(hasFxId(doc, "challengePrompt"));
        assertTrue(hasFxId(doc, "challengeIndex"));
        assertTrue(hasFxId(doc, "challengeFeedback"));
        assertTrue(hasFxId(doc, "failureCard"));
        assertTrue(hasFxId(doc, "failureMessage"));
        assertTrue(hasFxId(doc, "lockoutLabel"), "LOCK_OUT countdown (design §4)");

        // Progress: status (indeterminate warm-up) + challenge (combined score).
        assertEquals(2, doc.getElementsByTagName("ProgressBar").getLength());
        assertTrue(hasFxId(doc, "statusProgress"));
        assertTrue(hasFxId(doc, "challengeProgress"));

        // Context-sensitive actions (design §1 screen sketch: Retry / Cancel).
        assertEquals(2, doc.getElementsByTagName("Button").getLength());
        assertTrue(hasFxId(doc, "retryButton"));
        assertTrue(hasFxId(doc, "cancelButton"));

        // Failure card is styled as the failure tone.
        Element failure = findFxId(doc, "failureCard");
        assertTrue(styleClasses(failure).contains("failure-card"));
        Element challenge = findFxId(doc, "challengeCard");
        assertTrue(styleClasses(challenge).contains("challenge-card"));
        Element status = findFxId(doc, "statusCard");
        assertTrue(styleClasses(status).contains("status-card"));
    }

    @Test
    void stylesheetShipsOnTheClasspath() throws Exception {
        try (InputStream in = LivenessChallengeOverlayFxmlTest.class.getResourceAsStream(CSS)) {
            assertNotNull(in, "the overlay stylesheet must ship on the classpath");
            String css = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(css.contains(".liveness-overlay"), "root style present");
            assertTrue(css.contains(".liveness-oval-guide"), "oval guide style present");
            assertTrue(css.contains(".liveness-overlay.passed .liveness-oval-guide"),
                    "green PASSED accent style present");
        }
    }

    // ------------------------------------------------------------ helpers

    private static Document parse(String resource) throws Exception {
        try (InputStream in = LivenessChallengeOverlayFxmlTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "classpath resource " + resource + " must exist");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static boolean hasFxId(Document doc, String fxId) {
        return findFxId(doc, fxId) != null;
    }

    private static Element findFxId(Document doc, String fxId) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (fxId.equals(e.getAttribute("fx:id"))) {
                return e;
            }
        }
        return null;
    }

    private static Set<String> styleClasses(Element element) {
        Set<String> classes = new HashSet<>();
        String raw = element.getAttribute("styleClass");
        if (raw.isEmpty()) {
            Node attr = element.getAttributes().getNamedItem("styleClass");
            if (attr != null) {
                raw = attr.getNodeValue();
            }
        }
        for (String cls : raw.split(",")) {
            String trimmed = cls.trim();
            if (!trimmed.isEmpty()) {
                classes.add(trimmed);
            }
        }
        return classes;
    }
}
