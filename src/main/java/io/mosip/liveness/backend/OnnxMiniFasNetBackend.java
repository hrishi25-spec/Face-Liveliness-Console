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
     * the two non-live classes: 0 = printed photo, 2 = screen replay. Only the
     * attack <em>decision</em> (any index != live) gates a session; the type is
     * informational.
     */
    private static final int CLASS_PRINT = 0;
    private static final int CLASS_LIVE = 1;
    private static final int CLASS_REPLAY = 2;

    private OrtEnvironment env;
    private OrtSession session;
    private CascadeClassifier faceCascade;
    /** Applied to the live-class probability in {@link #scorePassiveLiveness}; identity by default. */
    private ProbabilityCalibration calibration = ProbabilityCalibration.identity();
    private volatile boolean ready;

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
                opts.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
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
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
            MatOfRect faces = new MatOfRect();
            faceCascade.detectMultiScale(gray, faces, 1.1, 5, 0, new org.opencv.core.Size(60, 60));
            return faces.toArray();
        } finally {
            gray.release();
        }
    }

    /** Face box in the given frame, or null when no single face is found. */
    private Rect detectFace(Mat bgr) {
        Rect[] faces = detectFaces(bgr);
        return faces.length == 1 ? faces[0] : null;
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

            try (OnnxTensor tensor = OnnxTensor.createTensor(env,
                    toFloatBuffer(nchw),
                    new long[]{1, 3, INPUT_SIZE, INPUT_SIZE},
                    ai.onnxruntime.OnnxJavaType.FLOAT)) {
                String inputName = session.getInputNames().iterator().next();
                try (OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
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

    /** One detection + inference pass; null probabilities mean "no single face". */
    private double[] probabilitiesFor(Frame frame) {
        requireReady();
        Mat bgr = toBgrMat(frame);
        try {
            Rect face = detectFace(bgr);
            if (face == null) return null;
            return inferProbabilities(bgr, face);
        } catch (LivenessException e) {
            throw e;
        } catch (Throwable t) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "Liveness inference failed: " + t.getMessage());
        } finally {
            bgr.release();
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

    @Override
    public FaceSignals analyzeFrame(Frame frame) {
        requireReady();
        Mat bgr = toBgrMat(frame);
        try {
            Rect[] faces = detectFaces(bgr);
            if (faces.length == 0) return FaceSignals.noFace(0.0);
            if (faces.length > 1) {
                return new FaceSignals(faces.length, 0.0, null, null, null, null, null, null, null);
            }
            // Quality = the same sharpness/brightness blend the heuristic scorer
            // uses, so minFaceQuality gates behave identically whichever
            // backend produced the signals. No landmarks are available here
            // (MiniFASNet is a classifier, not a mesh), so EAR/smile/pose stay
            // null and active-challenge evaluation falls back to the OpenCV
            // heuristics, as it does today.
            Rect f = faces[0];
            Mat gray = new Mat();
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
            Mat roi = new Mat(gray, f);
            Mat lap = new Mat();
            MatOfDouble lapStd = new MatOfDouble();
            Imgproc.Laplacian(roi, lap, CvType.CV_64F);
            Core.meanStdDev(lap, new MatOfDouble(), lapStd);
            double sd = lapStd.get(0, 0)[0];
            double sharpness = Math.min(1.0, (sd * sd) / 500.0);
            MatOfDouble grayMean = new MatOfDouble();
            MatOfDouble grayStd = new MatOfDouble();
            Core.meanStdDev(roi, grayMean, grayStd);
            double meanGray = grayMean.get(0, 0)[0];
            double brightness = 1.0 - Math.abs(meanGray - 128.0) / 128.0;
            double quality = Math.max(0.0, Math.min(1.0, 0.5 * sharpness + 0.5 * brightness));
            lap.release();
            lapStd.release();
            grayMean.release();
            grayStd.release();
            roi.release();
            gray.release();
            return new FaceSignals(1, quality, null, null, null, null, null, null, null);
        } catch (Throwable t) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "Frame analysis failed: " + t.getMessage());
        } finally {
            bgr.release();
        }
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
        if (p[CLASS_PRINT] >= p[CLASS_REPLAY] && p[CLASS_PRINT] > p[CLASS_LIVE]) {
            return PadVerdict.attack(PadAttackType.PRINTED_PHOTO, p[CLASS_PRINT]);
        }
        if (p[CLASS_REPLAY] > p[CLASS_LIVE]) {
            return PadVerdict.attack(PadAttackType.SCREEN_REPLAY, p[CLASS_REPLAY]);
        }
        return PadVerdict.bonaFide(p[CLASS_LIVE]);
    }

    @Override
    public void shutdown() {
        ready = false;
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
