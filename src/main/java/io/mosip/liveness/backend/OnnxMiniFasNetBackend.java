package io.mosip.liveness.backend;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.mosip.liveness.core.FaceSignals;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.LivenessException;
import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.core.PadVerdict;
import io.mosip.liveness.core.ProbabilityCalibration;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;

/**
 * Real passive-liveness + PAD backend backed by <b>MiniFASNet-V2</b>
 * ({@code 2.7_80x80_MiniFASNetV2}, Silent-Face-Anti-Spoofing, Apache-2.0)
 * running on <b>ONNX Runtime</b> — fully offline, ~1.7 MB model.
 *
 * <p>Why ONNX and not TFLite: the official TFLite runtime is an Android-only
 * AAR with no desktop artifact carrying {@code org.tensorflow.lite.Interpreter}
 * (see {@code docs/status-report.md} §4), while ONNX Runtime is first-class on
 * the desktop JVM and Android alike. The weights are bit-equivalent to the
 * upstream {@code .pth}; only the serialization format differs.</p>
 *
 * <p>Model contract — <b>verified empirically against the upstream inference
 * code and the bundled file's SHA-256, not taken on faith from the ONNX model
 * card</b> (the card is wrong on both counts below):</p>
 * <ul>
 *   <li>Input {@code (1, 3, 80, 80)} float32, <b>BGR</b>, range {@code [0, 255]}
 *       (raw pixel values, <b>not</b> divided by 255 — upstream
 *       {@code ToTensor} has {@code .div(255)} commented out and every
 *       maintained ONNX port feeds {@code astype(np.float32)}). Feeding [0,1]
 *       makes all frames look near-black and pushes genuine faces into an
 *       attack class.</li>
 *   <li>Face crop with a <b>2.7&times;</b> scale margin around the bbox centre</li>
 *   <li>Output: 3-class logits; softmax applied here. The <b>live class is
 *       index 1</b> (upstream {@code test.py} and the ONNX ports all do
 *       {@code label == 1 ? "Real" : "Fake"}); indices 0 and 2 are the two
 *       attack types. So liveness = {@code p[1]}, not {@code p[0]}.</li>
 * </ul>
 *
 * <p>Face detection uses the same vendored Haar cascade as the rest of the
 * service (resolved from the classpath first, filesystem second).</p>
 *
 * <p>Options keys:</p>
 * <ul>
 *   <li>{@code modelPath} – filesystem path to an {@code .tflite}/{@code .onnx} model (takes precedence)</li>
 *   <li>{@code modelResource} – classpath resource (default {@code models/minifasnet_v2.onnx})</li>
 *   <li>{@code expectedSha256} – hex digest the model bytes must match; defaults to the
 *       published digest when the bundled resource is used, and is skipped for a
 *       custom {@code modelPath} unless supplied explicitly</li>
 *   <li>{@code faceCascadePath} – path to the face Haar cascade XML (auto-detected if absent)</li>
 *   <li>{@code temperature} – optional log-odds temperature that de-saturates the
 *       live-class probability above the default threshold; {@code 1} (the
 *       default) leaves the raw model confidence untouched. See
 *       {@link ProbabilityCalibration} for why the raw value is unusable as a
 *       score (it pins at 1.000 on a genuine face).</li>
 *   <li>{@code intraOpThreads} – positive integer; threads one inference may
 *       use. Absent, it derives from the core count as {@code max(1, cores/2)},
 *       which is exactly 1 on the 2-core target hardware (see
 *       {@link #intraOpThreads(Map)}).</li>
 * </ul>
 */
public final class OnnxMiniFasNetBackend implements LivenessBackend {

    public static final String OPTION_MODEL_PATH = "modelPath";
    public static final String OPTION_MODEL_RESOURCE = "modelResource";
    public static final String OPTION_EXPECTED_SHA256 = "expectedSha256";
    public static final String OPTION_FACE_CASCADE_PATH = "faceCascadePath";

    /**
     * Optional log-odds temperature that de-saturates the live-class
     * probability above {@link ProbabilityCalibration#DEFAULT_PIVOT} without
     * moving that operating point. Absent (or {@code 1}) leaves every score
     * exactly as the model produced it.
     */
    public static final String OPTION_TEMPERATURE = "temperature";

    /**
     * Optional intra-op thread count for the ONNX session. Absent, it derives
     * from the core count; a configured value must be a positive integer.
     */
    public static final String OPTION_INTRA_OP_THREADS = "intraOpThreads";

    /** Bundled model location on the classpath. */
    public static final String DEFAULT_MODEL_RESOURCE = "models/minifasnet_v2.onnx";

    /** Published SHA-256 of {@code minifasnet_v2.onnx} (see its model card). */
    public static final String DEFAULT_MODEL_SHA256 =
            "d7b3cd9ba8a7ceb13baa8c4720902e27ca3112eff52f926c08804af6b6eecc7b";

    static final int INPUT_SIZE = 80;
    static final double CROP_MARGIN = 2.7;
    private static final String FACE_CASCADE_RESOURCE = "haarcascade_frontalface_default.xml";

    /**
     * Class indices of the 3-class output, matching the upstream reference
     * ({@code label == 1} is a real/live face). Best-effort attack typing for
     * the two non-live classes: 0 = printed photo, 2 = screen replay. The
     * attack <em>decision</em> gates a session; the type is informational.
     *
     * <p><b>The print class does not fire in practice.</b> Measured on the
     * committed fixtures, a printed photo and a phone-screen replay both arrive
     * through class 2 ({@code p[print]} ≈ 0.0000, {@code p[replay]} ≈ 0.999 and
     * ≈ 0.990). Callers must not use this class to tell a print from a screen;
     * {@code DecisionEngineService} keeps the texture rule's
     * {@link PadAttackType#PRINTED_PHOTO} for that, and this backend names the
     * replay.</p>
     */
    private static final int CLASS_PRINT = 0;
    private static final int CLASS_LIVE = 1;
    private static final int CLASS_REPLAY = 2;

    /**
     * How far the winning attack class must beat the live class before
     * {@link #assessPad} calls a frame an attack.
     *
     * <p>A three-class softmax lands near a three-way tie on ordinary capture
     * conditions — motion, an exposure hunt, a face at the crop edge. At that
     * point the ranking between print, replay and live is noise, not evidence,
     * yet the previous rule (&ldquo;any class ranked above live&rdquo;) called a
     * 0.36 / 0.34 / 0.30 split a presentation attack and reported it with
     * confidence 0.36. That ranked-noise verdict is the largest source of false
     * rejects on real cameras; a genuine attack puts its class far above live.</p>
     */
    static final double PAD_ATTACK_MARGIN = 0.15;

    /**
     * Minimum winning-class probability before a frame is called an attack, so
     * an almost-uniform softmax is never a verdict even when it beats live by
     * {@link #PAD_ATTACK_MARGIN}.
     */
    static final double PAD_ATTACK_MIN_CONFIDENCE = 0.60;

    /**
     * Minimum replay-class probability before a frame is called a screen
     * replay, with no margin requirement beyond beating the live class.
     *
     * <p>Replay is the class a phone or monitor playing a video lands in, and a
     * genuine phone replay can sit in the 0.45–0.60 range where the stricter
     * print rule would miss it. The 0.45 floor still rejects the ranked-noise
     * near-tie that made the old "any class above live" rule unsafe (a
     * 0.36 / 0.34 / 0.30 split no longer fires). A false positive is not a
     * rejection any more: a confirmed hit escalates to the active challenge, so
     * the cost of the extra sensitivity is one live action, not a failed
     * person.</p>
     */
    static final double PAD_REPLAY_MIN_CONFIDENCE = 0.45;

    private OrtEnvironment env;
    private OrtSession session;
    private CascadeClassifier faceCascade;
    /** Applied to the live-class probability in {@link #scorePassiveLiveness}; identity by default. */
    private ProbabilityCalibration calibration = ProbabilityCalibration.identity();
    private volatile boolean ready;

    /** The graph's single input name, resolved from the session once. */
    private String inputName;

    /**
     * Bumped by every (re)initialisation and by {@link #shutdown()}. A memo
     * entry stores the generation it was computed under, so a frame analysed
     * before a reload can never satisfy a lookup after one: the cached numbers
     * belong to the previous model and calibration.
     */
    private long generation;

    /**
     * The last frame this thread looked at, keyed by its <b>pixel content</b>
     * rather than by object identity: the HTTP path rebuilds a fresh
     * {@link Frame} from the same pixels for each of its calls into this
     * backend, so only a content key lets those calls share one detection and
     * one inference.
     *
     * <p>Holds derived values only — a {@link Rect}, two doubles and the
     * 3-class probability vector — and never the pixel buffer itself: one slot
     * per thread must not pin megabytes of frame data on a 2 GB device.</p>
     */
    private final ThreadLocal<FrameMemo> lastFrame = ThreadLocal.withInitial(FrameMemo::new);

    /** Face detections performed by this instance (diagnostic; see {@link #detections()}). */
    private int detections;
    /** Model inferences performed by this instance (diagnostic; see {@link #inferences()}). */
    private int inferences;

    /**
     * Face detections performed by this instance since construction — the
     * diagnostic the reuse proof reads to show one detection per frame is
     * enough. Not synchronized and deliberately not part of the SPI.
     */
    int detections() {
        return detections;
    }

    /** Model inferences performed by this instance. Same caveats as {@link #detections()}. */
    int inferences() {
        return inferences;
    }

    /** @return true when the ONNX Runtime classes are present on the classpath. */
    public static boolean isRuntimeAvailable() {
        try {
            Class.forName("ai.onnxruntime.OrtEnvironment");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** @return true once {@link #initialize(Map)} has completed successfully. */
    public boolean isReady() {
        return ready;
    }

    @Override
    public String id() {
        return "onnx-minifasnet-v2";
    }

    // =====================================================================
    // Initialization
    // =====================================================================

    @Override
    public void initialize(Map<String, String> options) {
        if (ready) return;
        if (!isRuntimeAvailable()) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "ONNX Runtime is not on the classpath");
        }
        try {
            generation++;                        // any cached frame analysis is now stale
            boolean customPath = options != null && options.containsKey(OPTION_MODEL_PATH);
            calibration = parseCalibration(options);
            byte[] model = readModel(options);

            String expected = options != null ? options.get(OPTION_EXPECTED_SHA256) : null;
            if (expected == null && !customPath) {
                expected = DEFAULT_MODEL_SHA256;   // bundled resource: verify provenance
            }
            if (expected != null && !sha256(model).equalsIgnoreCase(expected)) {
                throw new LivenessException(LivenessErrorCode.MODEL_INTEGRITY,
                        "Liveness model checksum mismatch");
            }

            env = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
                opts.setIntraOpNumThreads(intraOpThreads(options));
                session = env.createSession(model, opts);
            }

            faceCascade = loadCascade(options != null ? options.get(OPTION_FACE_CASCADE_PATH) : null);
            if (faceCascade == null || faceCascade.empty()) {
                throw new LivenessException(LivenessErrorCode.MODEL_INTEGRITY,
                        "Face cascade unavailable for the liveness model");
            }
            ready = true;
        } catch (LivenessException e) {
            throw e;
        } catch (Throwable t) {
            ready = false;
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "Failed to load the liveness model: " + t.getMessage());
        }
    }

    /**
     * How many threads one inference may use.
     *
     * <p>Unset, this keeps the historical derivation {@code max(1, cores / 2)}:
     * <b>exactly one thread on the 2-core target hardware</b>. One inference at a
     * time is what a 2-core device can afford — the other core is busy decoding,
     * quality-checking and detecting the next frame, and a second inference
     * thread inside this one starves that work rather than helping it. On wider
     * machines the derived value scales with the core count, and an operator who
     * knows their load can override it.</p>
     *
     * <p>A configured value must be a positive integer. It is validated rather
     * than clamped: {@code 0} would hand the decision back to ORT's own default
     * (all cores) while looking like a deliberate setting, and a typo would do
     * the same silently. A configuration error here is a startup failure with a
     * coded message, consistent with the other options.</p>
     */
    static int intraOpThreads(Map<String, String> options) {
        String raw = options != null ? options.get(OPTION_INTRA_OP_THREADS) : null;
        if (raw == null || raw.isBlank()) {
            return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        }
        int threads;
        try {
            threads = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "Invalid " + OPTION_INTRA_OP_THREADS + " value '" + raw + "'");
        }
        if (threads < 1) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    OPTION_INTRA_OP_THREADS + " must be a positive integer, was " + threads);
        }
        return threads;
    }

    /**
     * Reads {@link #OPTION_TEMPERATURE} (defaulting to the identity) into a
     * {@link ProbabilityCalibration}. A malformed or sub-1 value is a
     * configuration error, not a quiet fallback: it would otherwise look
     * exactly like "calibration is on" while doing nothing.
     */
    private static ProbabilityCalibration parseCalibration(Map<String, String> options) {
        String raw = options != null ? options.get(OPTION_TEMPERATURE) : null;
        if (raw == null || raw.isBlank()) {
            return ProbabilityCalibration.identity();
        }
        double temperature;
        try {
            temperature = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "Invalid " + OPTION_TEMPERATURE + " value '" + raw + "'");
        }
        try {
            return ProbabilityCalibration.ofTemperature(temperature);
        } catch (IllegalArgumentException e) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "Invalid " + OPTION_TEMPERATURE + " value: " + e.getMessage());
        }
    }

    private byte[] readModel(Map<String, String> options) throws Exception {
        if (options != null && options.containsKey(OPTION_MODEL_PATH)) {
            Path p = Path.of(options.get(OPTION_MODEL_PATH));
            if (!Files.isRegularFile(p)) {
                throw new LivenessException(LivenessErrorCode.MODEL_INTEGRITY,
                        "Liveness model not found at " + p);
            }
            return Files.readAllBytes(p);
        }
        String resource = options != null && options.containsKey(OPTION_MODEL_RESOURCE)
                ? options.get(OPTION_MODEL_RESOURCE)
                : DEFAULT_MODEL_RESOURCE;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new LivenessException(LivenessErrorCode.MODEL_INTEGRITY,
                        "Liveness model resource not found: " + resource);
            }
            return in.readAllBytes();
        }
    }

    static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private CascadeClassifier loadCascade(String explicitPath) {
        // 1) explicit path from options
        if (explicitPath != null && !explicitPath.isBlank()) {
            CascadeClassifier c = new CascadeClassifier(explicitPath);
            if (!c.empty()) return c;
        }
        // 2) classpath (copied to a temp file — CascadeClassifier needs a path)
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FACE_CASCADE_RESOURCE)) {
            if (in != null) {
                Path tmp = Files.createTempFile("cascade-", ".xml");
                try {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    CascadeClassifier c = new CascadeClassifier(tmp.toString());
                    if (!c.empty()) return c;
                } finally {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (Exception e) {
                        tmp.toFile().deleteOnExit();
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to the filesystem
        }
        // 3) filesystem / working directory
        CascadeClassifier c = new CascadeClassifier(FACE_CASCADE_RESOURCE);
        return c.empty() ? null : c;
    }

    // =====================================================================
    // Frame → BGR Mat
    // =====================================================================

    private Mat toBgrMat(Frame frame) {
        Mat m;
        switch (frame.format()) {
            case RGB_888 -> {
                m = new Mat(frame.height(), frame.width(), CvType.CV_8UC3);
                m.put(0, 0, frame.data());
                Imgproc.cvtColor(m, m, Imgproc.COLOR_RGB2BGR);
                return m;
            }
            case RGB_GRAY -> {
                m = new Mat(frame.height(), frame.width(), CvType.CV_8UC1);
                m.put(0, 0, frame.data());
                Imgproc.cvtColor(m, m, Imgproc.COLOR_GRAY2BGR);
                return m;
            }
            case NV21 -> {
                m = new Mat(frame.height() * 3 / 2, frame.width(), CvType.CV_8UC1);
                m.put(0, 0, frame.data());
                Imgproc.cvtColor(m, m, Imgproc.COLOR_YUV2BGR_NV21);
                return m;
            }
            case YUV420 -> {
                // Planar Y-U-V (I420), the layout produced by PixelFormats.
                m = new Mat(frame.height() * 3 / 2, frame.width(), CvType.CV_8UC1);
                m.put(0, 0, frame.data());
                Imgproc.cvtColor(m, m, Imgproc.COLOR_YUV2BGR_I420);
                return m;
            }
            default -> throw new LivenessException(LivenessErrorCode.INVALID_FRAME_DATA,
                    "Unsupported frame format: " + frame.format());
        }
    }

    /** Face boxes in the given frame — identical detection parameters to
     *  {@code ImageUtils.detectFaces} (same cascade file, scaleFactor 1.1,
     *  minNeighbors 5, min 60&times;60) so the HTTP face gate and the model's
     *  own detection agree frame-for-frame. */
    private Rect[] detectFaces(Mat bgr) {
        detections++;
        Mat gray = new Mat();
        MatOfRect faces = new MatOfRect();
        try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
            faceCascade.detectMultiScale(gray, faces, 1.1, 5, 0, new org.opencv.core.Size(60, 60));
            return faces.toArray();
        } finally {
            // MatOfRect *is* a Mat: without this every detection leaked a native
            // rect buffer to the finalizer, once per frame call.
            faces.release();
            gray.release();
        }
    }

    // =====================================================================
    // Inference
    // =====================================================================

    /**
     * Runs the model on the face region of {@code bgr} and returns the 3-class
     * probability vector {@code [print, live, replay]} — <b>live is index 1</b>
     * (softmax applied), or {@code null} when no single face is present.
     */
    private double[] inferProbabilities(Mat bgr, Rect face) throws Exception {
        Rect crop = cropRect(face, bgr.cols(), bgr.rows());
        Mat roi = bgr.submat(crop);

        Mat resized = new Mat();
        Imgproc.resize(roi, resized, new org.opencv.core.Size(INPUT_SIZE, INPUT_SIZE), 0, 0, Imgproc.INTER_LINEAR);
        roi.release();

        try {
            byte[] pixels = new byte[INPUT_SIZE * INPUT_SIZE * 3];
            resized.get(0, 0, pixels);           // HWC, BGR, uint8
            float[] nchw = packNchw(pixels, INPUT_SIZE);

            inferences++;
            try (OnnxTensor tensor = OnnxTensor.createTensor(env,
                    toFloatBuffer(nchw),
                    new long[]{1, 3, INPUT_SIZE, INPUT_SIZE},
                    ai.onnxruntime.OnnxJavaType.FLOAT)) {
                try (OrtSession.Result result = session.run(Map.of(inputName(), tensor))) {
                    // The exported graph returns shape [1, 3], so ORT hands back a
                    // float[][]; flatten whatever nesting the model uses rather
                    // than assuming a flat float[].
                    float[] logits = flatten(result.get(0).getValue());
                    return softmax(logits);
                }
            }
        } finally {
            resized.release();
        }
    }

    /**
     * The graph's single input name, resolved from the session once. Asking the
     * session per inference re-allocated a set every call — small next to the
     * model run itself, but pure waste on a per-frame path.
     */
    private String inputName() {
        String name = inputName;
        if (name == null) {
            Set<String> names = session.getInputNames();
            if (names.isEmpty()) {
                throw new IllegalStateException("The liveness model exposes no inputs");
            }
            inputName = name = names.iterator().next();
        }
        return name;
    }

    /**
     * The square face crop sent to the model: a {@value #CROP_MARGIN}&times; margin
     * around the face centre, <b>clamped so it always lies inside the frame</b>.
     *
     * <p>This mirrors the reference pipeline ({@code scale = min((h-1)/box_h,
     * (w-1)/box_w, 2.7)}). The previous implementation instead black-padded any
     * out-of-bounds region, so a large face near a frame edge produced a
     * heavily black-bordered crop — framing the model read as a presented image,
     * which surfaced as device-specific {@code SCREEN_REPLAY} false positives on
     * some cameras (e.g. a Mac webcam) but not others.</p>
     */
    static Rect cropRect(Rect face, int frameW, int frameH) {
        double cx = face.x + face.width / 2.0;
        double cy = face.y + face.height / 2.0;
        double box = Math.max(face.width, face.height);

        // Largest half-side that keeps the square crop inside the frame.
        int maxHalf = (int) Math.min(
                Math.min(cx, frameW - cx),
                Math.min(cy, frameH - cy));

        int half = (int) Math.round(box * CROP_MARGIN / 2.0);
        half = Math.min(half, maxHalf);                       // never exceed the frame
        half = Math.max(half, (int) Math.ceil(box / 2.0));    // never tighter than the face
        half = Math.min(half, maxHalf);                       // face may touch an edge
        half = Math.max(half, 1);

        int side = Math.min(half * 2, Math.min(frameW, frameH));
        int x = (int) Math.round(cx) - side / 2;
        int y = (int) Math.round(cy) - side / 2;
        x = Math.max(0, Math.min(x, frameW - side));
        y = Math.max(0, Math.min(y, frameH - side));
        return new Rect(x, y, side, side);
    }

    /** Flattens a (possibly nested) float array from an ORT result into a 1-D array. */
    static float[] flatten(Object value) {
        if (value instanceof float[] flat) {
            return flat;
        }
        if (value instanceof Object[] outer) {
            java.util.List<Float> acc = new java.util.ArrayList<>();
            for (Object o : outer) {
                for (float v : flatten(o)) acc.add(v);
            }
            float[] out = new float[acc.size()];
            for (int i = 0; i < out.length; i++) out[i] = acc.get(i);
            return out;
        }
        throw new IllegalArgumentException("Unsupported ONNX output type: "
                + (value == null ? "null" : value.getClass()));
    }

    /**
     * Packs an interleaved BGR uint8 buffer into an NCHW float array, preserving
     * the <b>raw 0..255</b> pixel values. Do <b>not</b> divide by 255: the model
     * expects 0..255 (upstream {@code ToTensor} has {@code .div(255)} commented
     * out), and dividing by 255 turned every genuine face into an attack class.
     * Package-private so a unit test pins the scaling.
     */
    static float[] packNchw(byte[] bgrInterleaved, int size) {
        int plane = size * size;
        float[] nchw = new float[3 * plane];
        for (int i = 0; i < plane; i++) {
            int c0 = i * 3;
            nchw[i] = (bgrInterleaved[c0] & 0xFF);            // B
            nchw[plane + i] = (bgrInterleaved[c0 + 1] & 0xFF);    // G
            nchw[2 * plane + i] = (bgrInterleaved[c0 + 2] & 0xFF); // R
        }
        return nchw;
    }

    private static java.nio.ByteBuffer toFloatBuffer(float[] values) {
        // OnnxTensor.createTensor(ByteBuffer, shape, type) requires a *direct*
        // buffer in native byte order. The element type MUST be passed explicitly:
        // the 3-arg overload assumes one byte per element and rejects a float
        // buffer with "requires N elements but the buffer has 4N elements", which
        // silently demoted every real frame to the heuristic fallback.
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(values.length * 4)
                .order(java.nio.ByteOrder.nativeOrder());
        buf.asFloatBuffer().put(values);
        buf.rewind();
        return buf;
    }

    static double[] softmax(float[] logits) {
        double max = Double.NEGATIVE_INFINITY;
        for (float l : logits) max = Math.max(max, l);
        double sum = 0;
        double[] out = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            out[i] = Math.exp(logits[i] - max);
            sum += out[i];
        }
        for (int i = 0; i < out.length; i++) out[i] /= sum;
        return out;
    }

    /**
     * Raw 3-class probabilities for one frame; null means "no single face".
     *
     * <p>One detection + one inference per <em>frame</em>, not per call. The
     * engine's per-frame conversation ({@code analyzeFrame} &rarr;
     * {@code scorePassiveLiveness} &rarr; {@code assessPad}) used to detect the
     * same face three times and infer twice, and the HTTP path rebuilt the same
     * frame for two of those calls; all of them now share one memo entry. The
     * values are the ones an uncached pass produces — Haar detection is
     * deterministic for a given pixel buffer — so this is reuse, not
     * approximation.</p>
     */
    private double[] probabilitiesFor(Frame frame) {
        requireReady();
        FrameMemo memo = memoFor(frame);
        if (!memo.hasProbabilities) inferInto(frame, memo);
        return memo.probabilities;
    }

    /** Runs the model on the memo's face box, detecting first when needed. */
    private void inferInto(Frame frame, FrameMemo memo) {
        if (!memo.detected) detectInto(frame, memo, "Liveness inference failed");
        if (memo.face == null) {
            memo.probabilities = null;     // no single face: the same neutral answer as before
            memo.hasProbabilities = true;
            return;
        }
        try {
            Mat bgr = toBgrMat(frame);
            try {
                memo.probabilities = inferProbabilities(bgr, memo.face);
            } finally {
                bgr.release();
            }
            memo.hasProbabilities = true;
        } catch (LivenessException e) {
            throw e;
        } catch (Throwable t) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "Liveness inference failed: " + t.getMessage());
        }
    }

    /**
     * Face count — and, for exactly one face, its box and quality — into the
     * memo. {@code failureContext} keeps each caller's error wording unchanged.
     */
    private void detectInto(Frame frame, FrameMemo memo, String failureContext) {
        try {
            Mat bgr = toBgrMat(frame);
            try {
                Rect[] faces = detectFaces(bgr);
                memo.faceCount = faces.length;
                memo.face = faces.length == 1 ? faces[0] : null;
                if (memo.face != null) memo.quality = faceQuality(bgr, memo.face);
            } finally {
                bgr.release();
            }
            memo.detected = true;          // only on success, so a failure is retried as before
        } catch (LivenessException e) {
            throw e;
        } catch (Throwable t) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    failureContext + ": " + t.getMessage());
        }
    }

    /**
     * The sharpness/brightness blend on the face region — the same formula the
     * heuristic scorer uses, so {@code minFaceQuality} gates behave identically
     * whichever backend produced the signals. No landmarks are available from a
     * classifier, so EAR/smile/pose stay null. Every native buffer is released.
     */
    private static double faceQuality(Mat bgr, Rect face) {
        Mat gray = new Mat();
        Mat lap = new Mat();
        MatOfDouble lapMean = new MatOfDouble();
        MatOfDouble lapStd = new MatOfDouble();
        MatOfDouble grayMean = new MatOfDouble();
        MatOfDouble grayStd = new MatOfDouble();
        Mat roi = null;
        try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
            roi = new Mat(gray, face);
            Imgproc.Laplacian(roi, lap, CvType.CV_64F);
            Core.meanStdDev(lap, lapMean, lapStd);
            double sd = lapStd.get(0, 0)[0];
            double sharpness = Math.min(1.0, (sd * sd) / 500.0);
            Core.meanStdDev(roi, grayMean, grayStd);
            double meanGray = grayMean.get(0, 0)[0];
            double brightness = 1.0 - Math.abs(meanGray - 128.0) / 128.0;
            return Math.max(0.0, Math.min(1.0, 0.5 * sharpness + 0.5 * brightness));
        } finally {
            if (roi != null) roi.release();
            gray.release();
            lap.release();
            lapMean.release();
            lapStd.release();
            grayMean.release();
            grayStd.release();
        }
    }

    /** {@link FaceSignals} carrying the memo's detection result, exactly as before. */
    private static FaceSignals signalsFrom(FrameMemo memo) {
        if (memo.faceCount == 0) return FaceSignals.noFace(0.0);
        if (memo.faceCount != 1) {
            return new FaceSignals(memo.faceCount, 0.0, null, null, null, null, null, null, null);
        }
        return new FaceSignals(1, memo.quality, null, null, null, null, null, null, null);
    }

    /**
     * The memo slot for {@code frame}, invalidated when its content is not what
     * the slot already holds. Per thread and single-entry: a thread alternating
     * between two cameras simply misses, it never grows.
     */
    private FrameMemo memoFor(Frame frame) {
        FrameMemo memo = lastFrame.get();
        long crc = crc32(frame.data());
        if (memo.matches(generation, crc, frame)) return memo;
        memo.reset(generation, crc, crc32c(frame.data()), frame);
        return memo;
    }

    /** 32-bit content checksum; hardware-accelerated (~1 ms for a 3.5 MiB frame). */
    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }

    /** Second, independent checksum (Castagnoli), consulted only on a candidate hit. */
    private static long crc32c(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }

    /**
     * One frame's reusable analysis, keyed by content: two independent 32-bit
     * checksums plus the geometry, stamped with the {@link #generation} it was
     * computed under. A different frame cannot present the same 64-bit
     * fingerprint, an in-place edit of a caller's buffer cannot produce a false
     * hit, and a reloaded model cannot inherit stale numbers.
     */
    private static final class FrameMemo {
        private boolean valid;
        private long generation = -1;
        private long crc;
        private long crcC;
        private int width;
        private int height;
        private Frame.Format format;

        /** Stage 1: detection (and quality) for the frame's pixels. */
        private boolean detected;
        private int faceCount;
        private Rect face;              // non-null iff faceCount == 1
        private double quality;

        /** Stage 2: raw [print, live, replay] probabilities for the same pixels. */
        private boolean hasProbabilities;
        private double[] probabilities; // null -> no single face

        private boolean matches(long currentGeneration, long candidateCrc, Frame frame) {
            return valid
                    && generation == currentGeneration
                    && crc == candidateCrc
                    && width == frame.width()
                    && height == frame.height()
                    && format == frame.format()
                    && crcC == crc32c(frame.data());   // second checksum, only on a candidate
        }

        private void reset(long currentGeneration, long crc, long crcC, Frame frame) {
            this.valid = true;
            this.generation = currentGeneration;
            this.crc = crc;
            this.crcC = crcC;
            this.width = frame.width();
            this.height = frame.height();
            this.format = frame.format();
            this.detected = false;
            this.faceCount = 0;
            this.face = null;
            this.quality = 0.0;
            this.hasProbabilities = false;
            this.probabilities = null;
        }
    }

    private void requireReady() {
        if (!ready) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "Backend " + id() + " is not initialized");
        }
    }

    // =====================================================================
    // LivenessBackend contract
    // =====================================================================

    /**
     * Detection plus face quality for the frame, computed once per frame
     * content: the engine asks for the analysis before scoring and PAD, and the
     * HTTP path rebuilds the frame for each call, so the second look is
     * answered from the memo rather than by scanning the face again.
     */
    @Override
    public FaceSignals analyzeFrame(Frame frame) {
        requireReady();
        FrameMemo memo = memoFor(frame);
        if (!memo.detected) detectInto(frame, memo, "Frame analysis failed");
        return signalsFrom(memo);
    }

    /**
     * Liveness score for the frame: the live-class probability, de-saturated by
     * the configured {@link #OPTION_TEMPERATURE calibration} (the identity when
     * unset). The PAD verdict in {@link #assessPad} deliberately keeps the raw
     * probabilities — it decides by class ranking, which a monotonic
     * temperature cannot change.
     */
    @Override
    public double scorePassiveLiveness(Frame frame, FaceSignals signals) {
        if (signals != null && signals.faceCount() != 1) {
            return 0.0;
        }
        double[] p = probabilitiesFor(frame);
        if (p == null) return 0.0;
        return calibration.apply(p[CLASS_LIVE]);
    }

    @Override
    public PadVerdict assessPad(Frame frame, FaceSignals signals) {
        if (signals != null && signals.faceCount() != 1) {
            // No single face to attack; the face-quality gate upstream owns
            // that verdict — do not fabricate an attack here.
            return PadVerdict.bonaFide(0.0);
        }
        double[] p = probabilitiesFor(frame);
        if (p == null) return PadVerdict.bonaFide(0.0);
        if (p[CLASS_PRINT] >= p[CLASS_REPLAY] && isAttack(p[CLASS_PRINT], p[CLASS_LIVE])) {
            return PadVerdict.attack(PadAttackType.PRINTED_PHOTO, p[CLASS_PRINT]);
        }
        if (isReplayAttack(p[CLASS_REPLAY], p[CLASS_LIVE])) {
            return PadVerdict.attack(PadAttackType.SCREEN_REPLAY, p[CLASS_REPLAY]);
        }
        return PadVerdict.bonaFide(p[CLASS_LIVE]);
    }

    /**
     * Whether an attack-class probability is a verdict rather than the winner of
     * a near-tie: it must clear {@link #PAD_ATTACK_MIN_CONFIDENCE} outright and
     * beat the live class by {@link #PAD_ATTACK_MARGIN}. Both bounds are checked
     * on the probability scale, so the rule reads the same whatever the model's
     * confidence looks like on a given camera.
     */
    private static boolean isAttack(double attackProbability, double liveProbability) {
        return attackProbability >= PAD_ATTACK_MIN_CONFIDENCE
                && attackProbability - liveProbability >= PAD_ATTACK_MARGIN;
    }

    /**
     * The replay rule: a clear replay-class confidence that also beats live.
     * The near-tie guard here is the floor, not the margin — a phone replay can
     * win narrowly, while an almost-uniform softmax never clears
     * {@link #PAD_REPLAY_MIN_CONFIDENCE}.
     */
    private static boolean isReplayAttack(double replayProbability, double liveProbability) {
        return replayProbability >= PAD_REPLAY_MIN_CONFIDENCE
                && replayProbability > liveProbability;
    }

    @Override
    public void shutdown() {
        ready = false;
        generation++;                      // every thread's memo entry is now stale
        lastFrame.remove();                // and this thread's slot goes with the session
        inputName = null;
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        try {
            if (env != null) env.close();
        } catch (Exception ignored) {
        }
        session = null;
        env = null;
    }
}
