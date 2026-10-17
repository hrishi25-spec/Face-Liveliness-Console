package io.mosip.liveness.backend;

import java.util.Locale;

/**
 * The single backend-selection vocabulary for {@code mosip.liveness.backend},
 * honoured by <b>both</b> wiring paths from the interoperability report's
 * finding F5 — the HTTP scoring path ({@code PassiveScoringService}) and the
 * SPI bean ({@code AppConfig.livenessBackend()}, consumed by
 * {@code FaceLivenessEngine} and embedding callers). One key, so the two
 * paths cannot silently diverge the way a service-only key allowed.
 *
 * <p>Per-path meaning of each value:</p>
 * <ul>
 *   <li><b>{@code auto}</b> (default) — service: the bundled ONNX model when
 *       it loads, heuristic otherwise (unchanged); SPI: the scripted mock
 *       (unchanged — neither value selects a model on this path today).</li>
 *   <li><b>{@code heuristic}</b> — service: force the OpenCV quality
 *       heuristic (unchanged); SPI: the mock — the SPI has no heuristic
 *       implementation, and the mock is its no-model choice, which is what
 *       this path returned before the key applied here.</li>
 *   <li><b>{@code mock}</b> — both paths: {@link MockLivenessBackend}.</li>
 *   <li><b>{@code onnx-minifasnet-v2}, {@code mediapipe-facemesh},
 *       {@code tflite-minifasnet}</b> — both paths: that backend, named by
 *       the same audit id the contract pins. Explicit ids are honoured
 *       strictly: the service refuses to fall back to the heuristic if the
 *       backend will not load, and the engine maps an unavailable backend to
 *       its fail-closed device error.</li>
 * </ul>
 *
 * <p>Unknown values fail at parse time (bean construction) — a typo must
 * stop startup loudly, not quietly pick a different scorer: that silent
 * divergence is exactly what F5 is about.</p>
 */
public enum LivenessBackendSelection {

    AUTO("auto"),
    HEURISTIC("heuristic"),
    MOCK("mock"),
    ONNX_MINIFASNET_V2("onnx-minifasnet-v2"),
    MEDIAPIPE_FACE_MESH("mediapipe-facemesh"),
    TFLITE_MINIFASNET("tflite-minifasnet");

    private final String configValue;

    LivenessBackendSelection(String configValue) {
        this.configValue = configValue;
    }

    /** The exact {@code mosip.liveness.backend} value this selection reads. */
    public String configValue() {
        return configValue;
    }

    /**
     * Parse a {@code mosip.liveness.backend} value (trimmed,
     * case-insensitive).
     *
     * @throws IllegalArgumentException on unknown values — fail fast at
     *         configuration time, listing the valid vocabulary
     */
    public static LivenessBackendSelection parse(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        for (LivenessBackendSelection selection : values()) {
            if (selection.configValue.equals(normalized)) {
                return selection;
            }
        }
        throw new IllegalArgumentException("Unknown mosip.liveness.backend value '"
                + raw + "'; expected one of: auto, heuristic, mock, "
                + "onnx-minifasnet-v2, mediapipe-facemesh, tflite-minifasnet");
    }

    /**
     * SPI path: the backend instance this selection names. {@code auto} and
     * {@code heuristic} yield the scripted mock (this path's behaviour before
     * the key applied, preserved). Construction only — initialisation is the
     * engine's job ({@code FaceLivenessEngine} calls {@code initialize()}
     * itself), so selecting a model backend loads nothing at boot.
     */
    public LivenessBackend createBackend() {
        switch (this) {
            case ONNX_MINIFASNET_V2:
                return new OnnxMiniFasNetBackend();
            case MEDIAPIPE_FACE_MESH:
                return new MediaPipeFaceMeshBackend();
            case TFLITE_MINIFASNET:
                return new TfLiteMiniFasNetBackend();
            default:
                // AUTO, HEURISTIC, MOCK — the no-model choice on the SPI path.
                return new MockLivenessBackend();
        }
    }
}
