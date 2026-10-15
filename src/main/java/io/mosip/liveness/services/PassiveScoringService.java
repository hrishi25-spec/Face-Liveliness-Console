package io.mosip.liveness.services;

import io.mosip.liveness.backend.LivenessBackend;
import io.mosip.liveness.backend.LivenessBackendSelection;
import io.mosip.liveness.backend.OnnxMiniFasNetBackend;
import io.mosip.liveness.core.FaceSignals;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.LivenessException;
import io.mosip.liveness.core.PadVerdict;
import io.mosip.liveness.core.ProbabilityCalibration;
import io.mosip.liveness.metrics.PipelineTimers;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Produces the passive liveness score and (optional) model PAD verdict for
 * the HTTP decision path.
 *
 * <p>Two scorers, one façade:</p>
 * <ul>
 *   <li><b>{@code auto}</b> (default) — MiniFASNet-V2 via
 *       {@link OnnxMiniFasNetBackend} when the runtime and model load; the
 *       score is then the model's live-class probability, de-saturated by
 *       {@code mosip.liveness.score-temperature} so an over-confident genuine
 *       face does not simply read {@code 1.000} on every frame (see
 *       {@link ProbabilityCalibration}).</li>
 *   <li><b>{@code heuristic}</b> — the original OpenCV quality formula
 *       (sharpness + brightness + eye symmetry), kept as an explicit fallback
 *       so tests, model-less CI and a missing/corrupt model degrade to
 *       today's behaviour instead of failing startup.</li>
 * </ul>
 *
 * <p>Configured by {@code mosip.liveness.backend} — the single selection key
 * shared with the SPI bean (interop report F5): {@code auto|heuristic} as
 * above, or an explicit backend id ({@code mock},
 * {@code onnx-minifasnet-v2}, {@code mediapipe-facemesh},
 * {@code tflite-minifasnet}). Explicit ids are strict — if the chosen backend
 * cannot load, scoring fails closed with a coded error instead of silently
 * scoring with a different one. {@code mosip.liveness.model-path} optionally
 * overrides the bundled classpath model (ONNX selections only).</p>
 *
 * <p><b>Nothing loads at construction.</b> The OpenCV natives (~6 s of
 * extraction) and the ONNX model used to load in the constructor, which made
 * every boot — and every test context — pay for a model the first frame may
 * never need. Both now settle on the first caller that asks for a verdict
 * ({@link #score}, {@link #assessPad}, {@link #isModelAvailable()},
 * {@link #scorerId()}), exactly once, behind {@link #resolveScorer()}.</p>
 */
@Service
public class PassiveScoringService {

    private static final Logger log = LoggerFactory.getLogger(PassiveScoringService.class);

    /** Use the model when it loads; fall back to the heuristic otherwise. */
    public static final String MODE_AUTO = "auto";
    /** Never load the model — always use the OpenCV heuristic. */
    public static final String MODE_HEURISTIC = "heuristic";

    /**
     * Default log-odds temperature for the passive score. The ONNX model's raw
     * live-class probability pins at ~1.0 on a genuine face, so the score says
     * nothing; dividing the log-odds above the default threshold by this spread
     * them into a readable band without moving the operating point. {@code 1}
     * disables the calibration and reports the raw model confidence.
     */
    public static final double DEFAULT_SCORE_TEMPERATURE = 4.0;

    private final LivenessEngineService heuristicScorer;
    private final String requestedMode;
    private final LivenessBackendSelection selection;
    private final String modelPath;
    /** Applied to the model's live-class probability; 1 means raw confidence. */
    private final double scoreTemperature;
    /** Threads one ONNX inference may use; 0 derives it from the core count. */
    private final int onnxIntraOpThreads;

    /** The resolved backend scorer; null when the heuristic is selected. */
    private volatile LivenessBackend model;
    private volatile boolean modelAvailable;
    /** Set when an explicit selection could not load — rethrown, never retried. */
    private volatile RuntimeException resolutionFailure;
    /** Volatile so the per-frame fast path can skip the monitor entirely. */
    private volatile boolean resolved;

    @Autowired
    public PassiveScoringService(LivenessEngineService heuristicScorer,
                                 @Value("${mosip.liveness.backend:auto}") String mode,
                                 @Value("${mosip.liveness.model-path:}") String modelPath,
                                 @Value("${mosip.liveness.score-temperature:" + DEFAULT_SCORE_TEMPERATURE + "}")
                                 double scoreTemperature,
                                 @Value("${mosip.liveness.onnx-intra-op-threads:0}")
                                 int onnxIntraOpThreads) {
        this.heuristicScorer = heuristicScorer;
        this.requestedMode = mode;
        this.selection = LivenessBackendSelection.parse(mode);  // unknown value fails at bean creation
        this.modelPath = modelPath;
        this.scoreTemperature = Double.isFinite(scoreTemperature) && scoreTemperature >= 1.0
                ? scoreTemperature : DEFAULT_SCORE_TEMPERATURE;
        // 0 means "derive it": the backend then uses max(1, cores/2), which is
        // one thread on the 2-core target. A positive value is passed through
        // and re-validated by the backend. A negative value is refused here:
        // treating it as "derive" would look like a deliberate setting while
        // doing something else, and a non-integer never gets this far (Spring's
        // binding stops the boot first).
        if (onnxIntraOpThreads < 0) {
            throw new IllegalArgumentException("mosip.liveness.onnx-intra-op-threads must be 0 "
                    + "(derive from the core count) or a positive integer, was " + onnxIntraOpThreads);
        }
        this.onnxIntraOpThreads = onnxIntraOpThreads;
        // Deliberately loads nothing here: bean creation must stay cheap,
        // and bean order is not guaranteed, so a constructor load would
        // also race the AppConfig warm-up. First use pays instead.
    }

    /**
     * Convenience constructor for direct (non-Spring) wiring — the eval tools
     * and the unit tests build the service by hand — defaulting the score
     * calibration to {@link #DEFAULT_SCORE_TEMPERATURE}.
     */
    public PassiveScoringService(LivenessEngineService heuristicScorer,
                                 String mode, String modelPath) {
        this(heuristicScorer, mode, modelPath, DEFAULT_SCORE_TEMPERATURE, 0);
    }

    /**
     * Resolves the scorer exactly once, on the first caller that needs it.
     *
     * <p>Safe to call concurrently: the first thread performs the OpenCV
     * barrier and the model load while the rest block on the monitor, and
     * {@code resolved} is flipped only after both fields are set, so no
     * caller ever observes a half-initialised scorer.</p>
     *
     * <p>A failed resolution is final — the same one-shot semantics the
     * constructor used to have — so a missing model degrades to the
     * heuristic instead of retrying (and re-paying the load) per frame.</p>
     */
    private void resolveScorer() {
        if (resolved) {
            if (resolutionFailure != null) {
                throw resolutionFailure;          // one-shot: never re-pay the load
            }
            return;
        }
        synchronized (this) {
            if (resolved) {
                if (resolutionFailure != null) {
                    throw resolutionFailure;
                }
                return;
            }
            try {
                this.model = openSelectedBackend();
                this.modelAvailable = model != null;
                this.resolved = true;
                log.info("Passive liveness scorer: {} (requested mode '{}')",
                        modelAvailable ? model.id() : "opencv-heuristic", requestedMode);
            } catch (RuntimeException e) {
                // An explicit backend that cannot load is a configuration
                // error, not a licence to score with a different one: cache
                // the failure so every later call fails closed the same way.
                this.resolutionFailure = e;
                this.resolved = true;
                throw e;
            }
        }
    }

    /**
     * Builds whatever the selection names, exactly once. {@code auto} and
     * {@code heuristic} keep today's degrade-to-the-heuristic behaviour; an
     * explicit id either loads that backend or throws a coded
     * {@link LivenessException} naming the key and the cause.
     */
    private LivenessBackend openSelectedBackend() {
        // The native library is the one hard dependency: the model
        // backend builds a Haar cascade, and without the natives that
        // is an UnsatisfiedLinkError. AppConfig's guard makes the load
        // idempotent JVM-wide, so this stays the service's only OpenCV
        // entry point, and it blocks only if the background warm-up
        // has not settled yet.
        boolean opencvReady = io.mosip.liveness.app.config.AppConfig.ensureOpenCvLoaded();

        switch (selection) {
            case HEURISTIC:
                log.info("Passive liveness model disabled by config (mosip.liveness.backend={})",
                        requestedMode);
                return null;
            case AUTO:
                if (!opencvReady) {
                    log.warn("OpenCV natives unavailable; not attempting to load the liveness model");
                    return null;
                }
                return loadOnnx(true);           // auto: degrade to the heuristic
            case ONNX_MINIFASNET_V2:
                if (!opencvReady) {
                    throw unavailable("OpenCV natives could not be loaded");
                }
                return loadOnnx(false);          // explicit: no heuristic escape hatch
            default:
                // mock / mediapipe-facemesh / tflite-minifasnet: scored through
                // the SPI itself. model-path applies to ONNX selections only.
                if (!opencvReady) {
                    throw unavailable("OpenCV natives could not be loaded");
                }
                LivenessBackend backend = selection.createBackend();
                try {
                    backend.initialize(Map.of());
                } catch (LivenessException e) {
                    throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                            unavailablePrefix() + e.getMessage(), e);
                } catch (Throwable t) {
                    throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                            unavailablePrefix() + t, t);
                }
                return backend;
        }
    }

    /**
     * ONNX loader shared by {@code auto} (fallback allowed — degrade to the
     * heuristic) and the explicit {@code onnx-minifasnet-v2} selection
     * (no fallback: a load failure becomes a coded refusal).
     */
    private LivenessBackend loadOnnx(boolean fallbackAllowed) {
        if (!OnnxMiniFasNetBackend.isRuntimeAvailable()) {
            if (fallbackAllowed) {
                log.warn("ONNX Runtime is not on the classpath; falling back to the heuristic scorer");
                return null;
            }
            throw unavailable("ONNX Runtime is not on the classpath");
        }
        try {
            OnnxMiniFasNetBackend backend = new OnnxMiniFasNetBackend();
            Map<String, String> options = new HashMap<>();
            if (modelPath != null && !modelPath.isBlank()) {
                options.put(OnnxMiniFasNetBackend.OPTION_MODEL_PATH, modelPath.trim());
            }
            // De-saturate the over-confident live-class probability above the
            // default threshold (see ProbabilityCalibration). The SPI backends
            // built from `selection` below are unaffected by this option.
            options.put(OnnxMiniFasNetBackend.OPTION_TEMPERATURE,
                    String.valueOf(scoreTemperature));
            if (onnxIntraOpThreads > 0) {
                options.put(OnnxMiniFasNetBackend.OPTION_INTRA_OP_THREADS,
                        String.valueOf(onnxIntraOpThreads));
            }
            backend.initialize(options);
            return backend;
        } catch (LivenessException e) {
            if (!fallbackAllowed) {
                throw unavailable(e.getMessage());
            }
            log.warn("Liveness model unavailable ({}); falling back to the heuristic scorer",
                    e.getMessage());
            return null;
        } catch (Throwable t) {
            if (!fallbackAllowed) {
                throw unavailable(t.toString());
            }
            log.warn("Liveness model failed to load ({}); falling back to the heuristic scorer",
                    t.toString());
            return null;
        }
    }

    private LivenessException unavailable(String why) {
        return new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                unavailablePrefix() + why);
    }

    private String unavailablePrefix() {
        return "mosip.liveness.backend=" + selection.configValue()
                + " selected explicitly but unavailable: ";
    }

    /**
     * True when a configured backend scorer (not the heuristic) is active —
     * the MiniFASNet model under {@code auto}, or whichever backend the key
     * names. Triggers the one-time resolution, so the first call may block
     * on the native load and the model.
     */
    public boolean isModelAvailable() {
        resolveScorer();
        return modelAvailable;
    }

    /**
     * Stable identifier for audit records: which scorer produced a score.
     * Triggers the one-time resolution like {@link #isModelAvailable()}.
     */
    public String scorerId() {
        resolveScorer();
        return modelAvailable ? model.id() : "opencv-heuristic";
    }

    /**
     * Passive liveness score in [0,1] for one frame.
     *
     * <p>The first call also resolves the scorer (native library + model),
     * so it can be slow; every subsequent call is the steady-state
     * pipeline.
     *
     * <p>With the model: live-class probability of MiniFASNet-V2 on the face
     * crop. Without: the OpenCV quality heuristic. If the model cannot analyse
     * the frame (no/multiple faces seen at model level even though the HTTP
     * face gate accepted it, or an inference error), this frame is scored by
     * the heuristic rather than being punished with a zero.</p>
     */
    public double score(Mat frame, LivenessEngineService.FaceObservation observation, ImageUtils imageUtils) {
        resolveScorer();
        if (!modelAvailable) {
            return PipelineTimers.timed(PipelineTimers.HEURISTIC_SCORE,
                    () -> heuristicScorer.scorePassive(frame, observation, imageUtils));
        }
        // The model and the fallback are timed separately: they are different
        // pipelines with very different costs, and merging them would make an
        // `auto` deployment that has silently fallen back look like it is
        // paying ONNX prices for heuristic work.
        return PipelineTimers.timed(PipelineTimers.ONNX_SCORE, () -> {
            try {
                Frame coreFrame = toCoreFrame(frame);
                FaceSignals signals = toSignals(observation);
                if (model.analyzeFrame(coreFrame).faceCount() != 1) {
                    return heuristicScorer.scorePassive(frame, observation, imageUtils);
                }
                return model.scorePassiveLiveness(coreFrame, signals);
            } catch (RuntimeException e) {
                log.debug("Model scoring failed, using heuristic for this frame: {}", e.toString());
                return heuristicScorer.scorePassive(frame, observation, imageUtils);
            }
        });
    }

    /**
     * Model PAD opinion for one frame, empty when no model is loaded.
     * Triggers the one-time resolution like {@link #score}.
     *
     * <p>MiniFASNet classifies a live class (index 1) plus two attack classes
     * (index 0 = print, 2 = replay) from the same inference the liveness score
     * comes from, so the decision path ORs this
     * with the FFT/texture heuristics in {@link PadEngineService}: either
     * source flagging an attack is terminal.</p>
     */
    public Optional<PadVerdict> assessPad(Mat frame, LivenessEngineService.FaceObservation observation) {
        resolveScorer();
        if (!modelAvailable) {
            return Optional.empty();
        }
        return PipelineTimers.timed(PipelineTimers.PAD_ONNX, () -> {
            try {
                Frame coreFrame = toCoreFrame(frame);
                FaceSignals signals = toSignals(observation);
                if (model.analyzeFrame(coreFrame).faceCount() != 1) {
                    return Optional.empty();
                }
                return Optional.of(model.assessPad(coreFrame, signals));
            } catch (RuntimeException e) {
                log.debug("Model PAD failed, heuristic PAD only for this frame: {}", e.toString());
                return Optional.empty();
            }
        });
    }

    /** The HTTP path's face gate has already accepted exactly one face. */
    private static FaceSignals toSignals(LivenessEngineService.FaceObservation observation) {
        double quality = observation.faceQuality() != null ? observation.faceQuality() : 0.0;
        return new FaceSignals(observation.multipleFaces() ? 2 : (observation.faceDetected() ? 1 : 0),
                quality, null, null, null, null, null, null, null);
    }

    /**
     * OpenCV Mat (BGR, as produced by {@code Imgcodecs.imdecode}) &rarr; the
     * platform-neutral {@link Frame} (RGB_888) the backend SPI speaks. The
     * backend converts back to BGR for the model, keeping the SPI contract
     * correct for any implementation, not just this one.
     */
    private static Frame toCoreFrame(Mat frame) {
        Mat rgb = new Mat();
        try {
            Imgproc.cvtColor(frame, rgb, Imgproc.COLOR_BGR2RGB);
            byte[] data = new byte[rgb.rows() * rgb.cols() * rgb.channels()];
            rgb.get(0, 0, data);   // cvtColor output is freshly allocated ⇒ continuous
            return Frame.of(data, rgb.cols(), rgb.rows(), Frame.Format.RGB_888,
                    System.currentTimeMillis(), 0);
        } finally {
            rgb.release();
        }
    }
}
