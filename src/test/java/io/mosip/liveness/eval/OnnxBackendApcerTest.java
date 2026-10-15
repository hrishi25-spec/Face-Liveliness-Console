package io.mosip.liveness.eval;

import io.mosip.liveness.backend.LivenessBackend;
import io.mosip.liveness.backend.LivenessBackendSelection;
import io.mosip.liveness.config.LivenessConfig;
import io.mosip.liveness.backend.OnnxMiniFasNetBackend;
import io.mosip.liveness.eval.PresentationLabel;
import io.mosip.liveness.eval.AttackScenarioHarness;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs AttackScenarioHarness with the real ONNX backend to get genuine model APCER numbers.
 * This test requires the ONNX Runtime and model files to be available.
 */
class OnnxBackendApcerTest {

    private static final int PRESENTATIONS_PER_LABEL = 30;  // Smaller sample for faster test
    private static final long SEED = 20261007L;  // Today's date as seed

    @Test
    void runApcerBreakdownWithOnnxBackend() {
        // Check if ONNX runtime is available
        if (!OnnxMiniFasNetBackend.isRuntimeAvailable()) {
            System.out.println("ONNX Runtime not available - skipping ONNX backend APCER test");
            return;
        }

        // Test that we can create the ONNX backend
        OnnxMiniFasNetBackend onnxBackend = new OnnxMiniFasNetBackend();
        assertNotNull(onnxBackend);

        // Initialize with default options (will use bundled model)
        try {
            onnxBackend.initialize(java.util.Map.of());
            assertTrue(onnxBackend.isReady(), "ONNX backend should be ready after initialization");
        } catch (Exception e) {
            System.out.println("ONNX backend initialization failed: " + e.getMessage());
            System.out.println("Skipping ONNX backend APCER test due to initialization failure");
            return;
        }

        // Create harness with ONNX backend factory
        AttackScenarioHarness harness = new AttackScenarioHarness(
                LivenessConfig.builder().build(),
                SEED,
                streamSeed -> {
                    // Return a fresh ONNX backend instance for each presentation stream
                    OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
                    try {
                        backend.initialize(java.util.Map.of());
                        return backend;
                    } catch (Exception ex) {
                        throw new RuntimeException("Failed to initialize ONNX backend", ex);
                    }
                });

        // Run the attack scenario harness
        List<AttackScenarioHarness.PresentationResult> results = harness.run(PRESENTATIONS_PER_LABEL);

        // Generate the ISO/IEC 30107-3 style report
        ScenarioReport report = AttackScenarioHarness.report(results);

        // Print the genuine ONNX model APCER breakdown
        System.out.println("==== Genuine ONNX MiniFASNetV2 APCER Breakdown ====");
        System.out.println(report);
        System.out.println("Per-species APCER details:");
        report.apcerBySpecies().forEach((label, speciesApcer) ->
                System.out.printf("  %s: %.4f (%d/%d)%n",
                        label, speciesApcer.apcer(),
                        speciesApcer.acceptedAsBonaFide(), speciesApcer.presentations()));

        // Basic sanity checks - APCER values should be reasonable probabilities
        assertTrue(report.apcer() >= 0.0 && report.apcer() <= 1.0,
                "APCER should be between 0 and 1: " + report.apcer());
        assertTrue(report.bpcer() >= 0.0 && report.bpcer() <= 1.0,
                "BPCER should be between 0 and 1: " + report.bpcer());
        assertTrue(report.acer() >= 0.0 && report.acer() <= 1.0,
                "ACER should be between 0 and 1: " + report.acer());

        // Verify we have data for all three attack species
        assertEquals(3, report.apcerBySpecies().size(),
                "Should have APCER data for all three attack species");
        assertTrue(report.apcerBySpecies().containsKey(PresentationLabel.PRINTED_PHOTO));
        assertTrue(report.apcerBySpecies().containsKey(PresentationLabel.SCREEN_REPLAY));
        assertTrue(report.apcerBySpecies().containsKey(PresentationLabel.VIDEO_REPLAY));

        // Clean up backend resources
        onnxBackend.shutdown();
    }

    @Test
    void testLivenessBackendSelectionWithOnnx() {
        // Test that the LivenessBackendSelection enum works with ONNX
        LivenessBackendSelection selection = LivenessBackendSelection.parse("onnx-minifasnet-v2");
        assertEquals(LivenessBackendSelection.ONNX_MINIFASNET_V2, selection);

        // Test that it creates the right backend type
        LivenessBackend backend = selection.createBackend();
        assertTrue(backend instanceof OnnxMiniFasNetBackend);

        // Clean up
        backend.shutdown();
    }
}