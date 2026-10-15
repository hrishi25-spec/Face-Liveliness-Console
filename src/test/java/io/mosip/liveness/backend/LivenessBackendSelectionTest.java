package io.mosip.liveness.backend;

import io.mosip.liveness.app.config.AppConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single {@code mosip.liveness.backend} selection (interop report F5):
 * one vocabulary parsed by both wirings — the HTTP scoring service and the
 * SPI bean — so a deployment flips one key and both paths move together.
 */
class LivenessBackendSelectionTest {

    @Test
    void everyConfiguredValueParsesTrimmedAndCaseInsensitive() {
        assertEquals(LivenessBackendSelection.AUTO, LivenessBackendSelection.parse("auto"));
        assertEquals(LivenessBackendSelection.AUTO, LivenessBackendSelection.parse("  AUTO  "));
        assertEquals(LivenessBackendSelection.HEURISTIC, LivenessBackendSelection.parse("Heuristic"));
        assertEquals(LivenessBackendSelection.MOCK, LivenessBackendSelection.parse("mock"));
        assertEquals(LivenessBackendSelection.ONNX_MINIFASNET_V2,
                LivenessBackendSelection.parse("onnx-minifasnet-v2"));
        assertEquals(LivenessBackendSelection.MEDIAPIPE_FACE_MESH,
                LivenessBackendSelection.parse("mediapipe-facemesh"));
        assertEquals(LivenessBackendSelection.TFLITE_MINIFASNET,
                LivenessBackendSelection.parse("tflite-minifasnet"));
        for (LivenessBackendSelection selection : LivenessBackendSelection.values()) {
            assertEquals(selection, LivenessBackendSelection.parse(selection.configValue()),
                    "every value must round-trip through its own config spelling");
        }
    }

    @Test
    void unknownValuesFailFastListingTheVocabulary() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LivenessBackendSelection.parse("gibberish"),
                "a typo must stop configuration, not silently pick another scorer");
        assertTrue(e.getMessage().contains("mosip.liveness.backend"), e.getMessage());
        assertTrue(e.getMessage().contains("onnx-minifasnet-v2"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> LivenessBackendSelection.parse(""), "blank is not a selection");
        assertThrows(IllegalArgumentException.class,
                () -> LivenessBackendSelection.parse(null), "null is not a selection");
    }

    @Test
    void theSpiPathYieldsTheSelectedBackendByItsAuditId() {
        assertEquals("mock", LivenessBackendSelection.parse("mock").createBackend().id());
        assertEquals("onnx-minifasnet-v2",
                LivenessBackendSelection.parse("onnx-minifasnet-v2").createBackend().id());
        assertEquals("mediapipe-facemesh",
                LivenessBackendSelection.parse("mediapipe-facemesh").createBackend().id());
        assertEquals("tflite-minifasnet",
                LivenessBackendSelection.parse("tflite-minifasnet").createBackend().id());
    }

    @Test
    void autoAndHeuristicKeepTheSpiPathOnTheScriptedMock() {
        // F5 preserved: before this key reached the SPI path, the bean
        // returned the mock — 'auto'/'heuristic' still do.
        assertTrue(LivenessBackendSelection.parse("auto").createBackend()
                instanceof MockLivenessBackend);
        assertTrue(LivenessBackendSelection.parse("heuristic").createBackend()
                instanceof MockLivenessBackend);
        assertTrue(LivenessBackendSelection.parse("mock").createBackend()
                instanceof MockLivenessBackend);
    }

    @Test
    void theSpringBeanMapsTheSameKeyToTheSameBackend() {
        AppConfig config = new AppConfig();
        assertEquals("mock", config.livenessBackend("auto").id());
        assertEquals("mock", config.livenessBackend("heuristic").id());
        assertEquals("mock", config.livenessBackend("mock").id());
        assertEquals("onnx-minifasnet-v2", config.livenessBackend("onnx-minifasnet-v2").id());
        assertThrows(IllegalArgumentException.class, () -> config.livenessBackend("gibberish"),
                "the bean must fail loudly on an unknown value too");
    }
}
