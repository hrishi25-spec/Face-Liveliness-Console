package io.mosip.liveness.backend;

import io.mosip.liveness.core.FaceSignals;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.LivenessException;
import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.core.PadVerdict;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Real PAD backend backed by the MiniFASNet model (Silent-Face-Anti-Spoofing,
 * Apache-2.0) running on TensorFlow Lite — fully offline, ~1-2 MB INT8 model.
 *
 * <p>Uses OpenCV ({@code org.openpnp:opencv} on the classpath) for:
 * <ul>
 *   <li>Face detection via {@code CascadeClassifier} with Haar/LBP cascade XML</li>
 *   <li>Eye detection via {@code CascadeClassifier} for Eye Aspect Ratio (EAR)</li>
 *   <li>Geometric yaw/pitch estimation from face bounding-box position</li>
 *   <li>Mouth-region brightness analysis for smile scoring</li>
 *   <li>Gaze estimation from eye-center relative to face center</li>
 * </ul>
 *
 * <p>Options keys:</p>
 * <ul>
 *   <li>{@code modelPath} – filesystem/asset path to the .tflite model (required)</li>
 *   <li>{@code inputSize} – model input edge length (default 80)</li>
 *   <li>{@code threads} – CPU threads (default 2)</li>
 *   <li>{@code delegate} – cpu | nnapi | gpu (default cpu; Android only for nnapi/gpu)</li>
 *   <li>{@code faceCascadePath} – path to face Haar/LBP cascade XML (auto-detected if absent)</li>
 *   <li>{@code eyeCascadePath} – path to eye Haar cascade XML (optional, improves EAR accuracy)</li>
 *   <li>{@code minFaceSize} – minimum face size in pixels (default 40)</li>
 *   <li>{@code scaleFactor} – cascade detection scale factor (default 1.1)</li>
 *   <li>{@code minNeighbors} – cascade detection min neighbors (default 3)</li>
 * </ul>
 */
public final class TfLiteMiniFasNetBackend implements LivenessBackend {

    public static final String OPTION_MODEL_PATH = "modelPath";
    public static final String OPTION_INPUT_SIZE = "inputSize";
    public static final String OPTION_THREADS = "threads";
    public static final String OPTION_DELEGATE = "delegate";
    public static final String OPTION_FACE_CASCADE_PATH = "faceCascadePath";
    public static final String OPTION_EYE_CASCADE_PATH = "eyeCascadePath";
    public static final String OPTION_MIN_FACE_SIZE = "minFaceSize";
    public static final String OPTION_SCALE_FACTOR = "scaleFactor";
    public static final String OPTION_MIN_NEIGHBORS = "minNeighbors";

    private static final float MEAN = 0.5f;
    private static final float STD = 0.5f;

    // TFLite
    private Object interpreter;
    private Method runMethod;
    private int inputSize = 80;
    private float[][] output;
    private double lastPadConfidence = 0.99;

    // OpenCV — loaded via reflection to avoid compile-time dep on the core engine
    private Object faceClassifier;   // org.opencv.objdetect.CascadeClassifier
    private Object eyeClassifier;    // org.opencv.objdetect.CascadeClassifier (nullable)
    private int minFaceSize = 40;
    private boolean opencvReady;

    // Reflection handles for OpenCV types (avoid compile-time import)
    private Class<?> matCls;
    private Class<?> rectCls;
    private Class<?> sizeCls;
    private Class<?> cascadeCls;
    private Class<?> matOfRectCls;
    private Constructor<?> matCtor3;
    private Constructor<?> matCtor1;
    private Constructor<?> rectCtor;
    private Constructor<?> sizeCtor;
    private Method mCreate;
    private Method mRelease;
    private Method mRows;
    private Method mCols;
    private Method mGet;
    private Method mPut;
    private Method mSubmat;
    private Method cDetect;
    private Method cLoad;
    private Method cSetMinSize;
    private Method rX, rY, rW, rH;
    private Method morToArray;

    /** @return true when the TFLite runtime is present on the classpath. */
    public static boolean isTfLiteAvailable() {
        try { Class.forName("org.tensorflow.lite.Interpreter"); return true; }
        catch (ClassNotFoundException e) { return false; }
    }

    /** @return true when OpenCV Java bindings are present on the classpath. */
    public static boolean isOpenCvAvailable() {
        try { Class.forName("org.opencv.core.Mat"); return true; }
        catch (ClassNotFoundException e) { return false; }
    }

    @Override public String id() { return "tflite-minifasnet"; }

    // ===================================================================
    // Initialization
    // ===================================================================

    @Override
    public void initialize(Map<String, String> options) {
        initTfLite(options);
        initOpenCv(options);
    }

    private void initTfLite(Map<String, String> options) {
        if (!isTfLiteAvailable()) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "TensorFlow Lite runtime not on classpath; add org.tensorflow:tensorflow-lite");
        }
        String modelPath = options.get(OPTION_MODEL_PATH);
        if (modelPath == null || !Files.exists(Path.of(modelPath))) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "PAD model file missing: " + modelPath);
        }
        if (options.containsKey(OPTION_INPUT_SIZE)) {
            inputSize = Integer.parseInt(options.get(OPTION_INPUT_SIZE));
        }
        try {
            Class<?> interpCls = Class.forName("org.tensorflow.lite.Interpreter");
            Class<?> optsCls   = Class.forName("org.tensorflow.lite.Interpreter$Options");
            Object opts = optsCls.getDeclaredConstructor().newInstance();
            int threads = Integer.parseInt(options.getOrDefault(OPTION_THREADS, "2"));
            optsCls.getMethod("setNumThreads", int.class).invoke(opts, threads);
            applyDelegate(optsCls, opts, options.getOrDefault(OPTION_DELEGATE, "cpu"));

            interpreter = interpCls.getConstructor(java.io.File.class, optsCls)
                    .newInstance(new java.io.File(modelPath), opts);
            runMethod = interpCls.getMethod("run", Object.class, Object.class);
            output = new float[1][1];
        } catch (LivenessException e) { throw e; }
        catch (Exception e) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "Failed to load PAD model: " + e.getMessage(), e);
        }
    }

    private void applyDelegate(Class<?> optsCls, Object opts, String delegate) throws Exception {
        switch (delegate == null ? "cpu" : delegate) {
            case "nnapi" -> optsCls.getMethod("setUseNNAPI", boolean.class).invoke(opts, true);
            case "gpu" -> {
                Class<?> gpuCls = Class.forName("org.tensorflow.lite.gpu.GpuDelegate");
                Object gpu = gpuCls.getDeclaredConstructor().newInstance();
                optsCls.getMethod("addDelegate", gpuCls.getInterfaces()[0]).invoke(opts, gpu);
            }
            default -> { /* CPU */ }
        }
    }

    private void initOpenCv(Map<String, String> options) {
        opencvReady = isOpenCvAvailable();
        if (!opencvReady) return;

        try {
            // Resolve OpenCV classes via reflection
            matCls      = Class.forName("org.opencv.core.Mat");
            rectCls     = Class.forName("org.opencv.core.Rect");
            sizeCls     = Class.forName("org.opencv.core.Size");
            cascadeCls  = Class.forName("org.opencv.objdetect.CascadeClassifier");
            matOfRectCls = Class.forName("org.opencv.core.MatOfRect");

            matCtor3  = matCls.getConstructor(int.class, int.class, int.class);
            matCtor1  = matCls.getConstructor(int.class, int.class);
            rectCtor  = rectCls.getConstructor(int.class, int.class, int.class, int.class);
            sizeCtor  = sizeCls.getConstructor(double.class, double.class);

            mCreate    = matCls.getMethod("create", int.class, int.class, int.class);
            mRelease   = matCls.getMethod("release");
            mRows      = matCls.getMethod("rows");
            mCols      = matCls.getMethod("cols");
            mGet       = matCls.getMethod("get", int.class, int.class, byte[].class);
            mPut       = matCls.getMethod("put", int.class, int.class, byte[].class);
            mSubmat    = matCls.getMethod("submat", rectCls);

            cDetect     = cascadeCls.getMethod("detectMultiScale", matCls, matOfRectCls);
            cLoad       = cascadeCls.getMethod("load", String.class);
            cSetMinSize = cascadeCls.getMethod("setMinSize", sizeCls);

            rX = rectCls.getMethod("x");
            rY = rectCls.getMethod("y");
            rW = rectCls.getMethod("width");
            rH = rectCls.getMethod("height");

            morToArray = matOfRectCls.getMethod("toArray");

            // Load face cascade
            String facePath = options.get(OPTION_FACE_CASCADE_PATH);
            if (facePath == null) facePath = findCascade("haarcascade_frontalface_alt.xml");
            if (facePath == null) facePath = findCascade("haarcascade_frontalface_default.xml");
            if (facePath == null) {
                throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                        "Face cascade not found. Set " + OPTION_FACE_CASCADE_PATH
                                + " or place haarcascade_frontalface_alt.xml in models/");
            }

            faceClassifier = cascadeCls.getDeclaredConstructor().newInstance();
            cLoad.invoke(faceClassifier, facePath);

            if (options.containsKey(OPTION_MIN_FACE_SIZE)) {
                minFaceSize = Integer.parseInt(options.get(OPTION_MIN_FACE_SIZE));
            }
            Object minSz = sizeCtor.newInstance((double) minFaceSize, (double) minFaceSize);
            cSetMinSize.invoke(faceClassifier, minSz);

            // Load eye cascade (optional)
            String eyePath = options.get(OPTION_EYE_CASCADE_PATH);
            if (eyePath == null) eyePath = findCascade("haarcascade_eye.xml");
            if (eyePath != null && Files.exists(Path.of(eyePath))) {
                eyeClassifier = cascadeCls.getDeclaredConstructor().newInstance();
                cLoad.invoke(eyeClassifier, eyePath);
            }
        } catch (LivenessException e) { throw e; }
        catch (Exception e) {
            opencvReady = false;
            // Silently degrade — analyzeFrame will throw if called
        }
    }

    /** Search well-known paths for a cascade XML file. */
    private String findCascade(String name) {
        String[] dirs = {
            "models", "data/haarcascades",
            "/usr/share/opencv4/haarcascades",
            "/usr/local/share/opencv4/haarcascades",
            System.getProperty("opencv.haarcascade.dir", "")
        };
        for (String d : dirs) {
            if (d.isEmpty()) continue;
            Path p = Path.of(d, name);
            if (Files.exists(p)) return p.toString();
        }
        // Try classpath
        try {
            var in = getClass().getResourceAsStream("/haarcascades/" + name);
            if (in == null) in = getClass().getResourceAsStream("/org/opencv/data/haarcascades/" + name);
            if (in != null) {
                Path tmp = Files.createTempFile("cascade_", ".xml");
                tmp.toFile().deleteOnExit();
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                in.close();
                return tmp.toString();
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ===================================================================
    // analyzeFrame — face detection + landmark extraction
    // ===================================================================

    @Override
    public FaceSignals analyzeFrame(Frame frame) {
        if (!opencvReady || faceClassifier == null) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "OpenCV face detector not initialized. Ensure org.openpnp:opencv is on "
                            + "the classpath and face cascade XML is available.");
        }
        try {
            Object bgr = frameToBgrMat(frame);
            int rows = (int) mRows.invoke(bgr);
            int cols = (int) mCols.invoke(bgr);

            // Detect faces
            Object faceRects = matOfRectCls.getDeclaredConstructor().newInstance();
            cDetect.invoke(faceClassifier, bgr, faceRects);
            Object[] faces = (Object[]) morToArray.invoke(faceRects);

            if (faces.length == 0) {
                mRelease.invoke(bgr);
                return FaceSignals.noFace(0.0);
            }

            // Pick the largest face
            int bestIdx = 0;
            int bestArea = 0;
            for (int i = 0; i < faces.length; i++) {
                int a = (int) rW.invoke(faces[i]) * (int) rH.invoke(faces[i]);
                if (a > bestArea) { bestArea = a; bestIdx = i; }
            }
            Object face = faces[bestIdx];

            int fx = (int) rX.invoke(face), fy = (int) rY.invoke(face);
            int fw = (int) rW.invoke(face), fh = (int) rH.invoke(face);

            double quality = faceQuality(fx, fy, fw, fh, rows, cols);
            double yaw     = faceYaw(fx, fw, cols);
            double pitch   = facePitch(fy, fh, rows);

            // Eye detection within face ROI
            double[] ear = detectEars(bgr, fx, fy, fw, fh);

            // Smile from mouth region
            double smile = mouthBrightness(bgr, fx, fy, fw, fh);

            // Gaze from eye position relative to face
            double[] gaze = gazeFromPosition(fx, fy, fw, fh, ear, cols, rows);

            mRelease.invoke(bgr);

            return new FaceSignals(
                    faces.length, quality,
                    ear[0], ear[1],
                    smile, yaw, pitch,
                    gaze[0], gaze[1]);
        } catch (LivenessException e) { throw e; }
        catch (Exception e) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "analyzeFrame failed: " + e.getMessage(), e);
        }
    }

    // ---- Frame → Mat (3-channel BGR) ----

    private Object frameToBgrMat(Frame frame) throws Exception {
        int w = frame.width(), h = frame.height();
        byte[] data = frame.data();
        Object mat = mCreate.invoke(null, h, w, 0x10); // CV_8UC3 = 16

        byte[] bgr = new byte[w * h * 3];
        switch (frame.format()) {
            case RGB_888 -> {
                for (int i = 0, s = 0; i < w * h; i++, s += 3) {
                    bgr[s]     = data[s + 2]; // B
                    bgr[s + 1] = data[s + 1]; // G
                    bgr[s + 2] = data[s];     // R
                }
            }
            case RGB_GRAY -> {
                for (int i = 0; i < w * h; i++) {
                    bgr[i * 3] = bgr[i * 3 + 1] = bgr[i * 3 + 2] = data[i];
                }
            }
            case NV21, YUV420 -> {
                for (int i = 0; i < w * h; i++) {
                    byte y = i < data.length ? data[i] : (byte) 128;
                    bgr[i * 3] = bgr[i * 3 + 1] = bgr[i * 3 + 2] = y;
                }
            }
        }
        mPut.invoke(mat, 0, 0, bgr);
        return mat;
    }

    // ---- Face quality heuristic ----

    private double faceQuality(int fx, int fy, int fw, int fh, int rows, int cols) {
        double frameArea = (double) rows * cols;
        double faceArea  = (double) fw * fh;
        double sizeRatio = faceArea / frameArea;

        // Centrality
        double dx = (fx + fw / 2.0 - cols / 2.0) / (cols / 2.0);
        double dy = (fy + fh / 2.0 - rows / 2.0) / (rows / 2.0);
        double centrality = 1.0 - Math.sqrt(dx * dx + dy * dy) / 1.4142;

        // Size score: sweet spot 10-60% of frame
        double sizeScore;
        if      (sizeRatio < 0.03)  sizeScore = 0.0;
        else if (sizeRatio < 0.10)  sizeScore = (sizeRatio - 0.03) / 0.07 * 0.6;
        else if (sizeRatio <= 0.60) sizeScore = 0.6 + (sizeRatio - 0.10) / 0.50 * 0.4;
        else if (sizeRatio <= 0.85) sizeScore = 1.0;
        else                        sizeScore = Math.max(0.0, 1.0 - (sizeRatio - 0.85) / 0.15);

        return clamp01(0.4 * sizeScore + 0.6 * Math.max(0.0, centrality));
    }

    // ---- Eye Aspect Ratio via Haar eye cascade ----

    private double[] detectEars(Object bgr, int fx, int fy, int fw, int fh) throws Exception {
        if (eyeClassifier == null) return new double[]{Double.NaN, Double.NaN};

        // Crop face region
        int rows = (int) mRows.invoke(bgr);
        int cols = (int) mCols.invoke(bgr);
        Object roi = mSubmat.invoke(bgr, rectCtor.newInstance(
                clampInt(0, cols, fx), clampInt(0, rows, fy),
                clampInt(1, cols - fx, fw), clampInt(1, rows - fy, fh)));

        Object eyeRects = matOfRectCls.getDeclaredConstructor().newInstance();
        cDetect.invoke(eyeClassifier, roi, eyeRects);
        Object[] eyes = (Object[]) morToArray.invoke(eyeRects);
        mRelease.invoke(roi);

        if (eyes.length < 2) return new double[]{Double.NaN, Double.NaN};

        // Sort by x (left eye first)
        if ((int) rX.invoke(eyes[0]) > (int) rX.invoke(eyes[1])) {
            Object tmp = eyes[0]; eyes[0] = eyes[1]; eyes[1] = tmp;
        }

        // EAR ≈ height/width of eye bounding box (open ≈ 0.25-0.35, closed ≈ 0.03-0.08)
        return new double[]{
                clamp(0.02, 0.50, (double)(int)rH.invoke(eyes[0]) / Math.max(1, (int)rW.invoke(eyes[0]))),
                clamp(0.02, 0.50, (double)(int)rH.invoke(eyes[1]) / Math.max(1, (int)rW.invoke(eyes[1])))
        };
    }

    // ---- Head pose from face geometry ----

    private double faceYaw(int fx, int fw, int cols) {
        double cx = fx + fw / 2.0;
        double norm = (cx - cols / 2.0) / (cols / 2.0);  // -1..1
        return clamp(-45.0, 45.0, norm * 45.0);
    }

    private double facePitch(int fy, int fh, int rows) {
        double cy = fy + fh / 2.0;
        double norm = (cy - rows / 2.0) / (rows / 2.0);
        return clamp(-30.0, 30.0, norm * 30.0);
    }

    // ---- Smile from mouth region brightness ----

    private double mouthBrightness(Object bgr, int fx, int fy, int fw, int fh) throws Exception {
        // Mouth region: bottom 30% of face, centered horizontally
        int rows = (int) mRows.invoke(bgr);
        int cols = (int) mCols.invoke(bgr);
        int my = clampInt(0, rows - 1, fy + (int)(fh * 0.65));
        int mh = clampInt(1, rows - my, (int)(fh * 0.25));
        int mx = clampInt(0, cols - 1, fx + (int)(fw * 0.15));
        int mw = clampInt(1, cols - mx, (int)(fw * 0.70));

        Object mouthRoi = mSubmat.invoke(bgr, rectCtor.newInstance(mx, my, mw, mh));
        int mr = (int) mRows.invoke(mouthRoi);
        int mc = (int) mCols.invoke(mouthRoi);
        int nPixels = mr * mc;
        if (nPixels <= 0) { mRelease.invoke(mouthRoi); return 0.05; }

        byte[] buf = new byte[nPixels * 3];
        mGet.invoke(mouthRoi, 0, 0, buf);
        mRelease.invoke(mouthRoi);

        long sum = 0;
        for (int i = 0; i < nPixels; i++) sum += (buf[i * 3] & 0xFF);
        double avg = (double) sum / nPixels / 255.0;

        // Smiling mouths are brighter (teeth): dark→0.05, bright→0.85
        return clamp01(0.05 + (avg - 0.30) * 1.5);
    }

    // ---- Gaze from face/eye position ----

    private double[] gazeFromPosition(int fx, int fy, int fw, int fh,
                                       double[] ear, int cols, int rows) {
        // Without per-eye landmark positions, approximate from face offset.
        // A production system should use iris detection or a gaze-estimation DNN.
        double faceCx = fx + fw / 2.0;
        double faceCy = fy + fh / 2.0;
        double gx = clamp(-1.0, 1.0, (faceCx - cols / 2.0) / (cols / 2.0));
        double gy = clamp(-1.0, 1.0, (faceCy - rows / 2.0) / (rows / 2.0));
        return new double[]{gx, gy};
    }

    // ===================================================================
    // Passive liveness scoring (MiniFASNet inference)
    // ===================================================================

    @Override
    public double scorePassiveLiveness(Frame frame, FaceSignals signals) {
        float[][][][] input = preprocess(frame);
        try {
            runMethod.invoke(interpreter, input, output);
            return clamp01(output[0][0]);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "PAD inference failed", unwrap(e));
        }
    }

    @Override
    public PadVerdict assessPad(Frame frame, FaceSignals signals) {
        double score = scorePassiveLiveness(frame, signals);
        double padThreshold = 0.5;
        lastPadConfidence = 1.0 - Math.abs(score - padThreshold);
        if (score < padThreshold) {
            return PadVerdict.attack(PadAttackType.PRINTED_PHOTO, 1.0 - score);
        }
        return PadVerdict.bonaFide(score);
    }

    public double lastPadConfidence() { return lastPadConfidence; }

    @Override
    public void shutdown() {
        if (interpreter != null) {
            try { interpreter.getClass().getMethod("close").invoke(interpreter); }
            catch (ReflectiveOperationException ignored) { }
            interpreter = null;
        }
        faceClassifier = null;
        eyeClassifier = null;
    }

    // ---- preprocessing ----

    private float[][][][] preprocess(Frame frame) {
        int channels = frame.format() == Frame.Format.RGB_GRAY ? 1 : 3;
        float[][][][] tensor = new float[1][inputSize][inputSize][channels];
        byte[] data = frame.data();
        long needed = (long) inputSize * inputSize * channels;
        if (data.length < needed) {
            throw new LivenessException(LivenessErrorCode.INVALID_FRAME_DATA,
                    "frame too small for model input: " + data.length + " < " + needed);
        }
        int i = 0;
        for (int y = 0; y < inputSize; y++)
            for (int x = 0; x < inputSize; x++)
                for (int c = 0; c < channels; c++)
                    tensor[0][y][x][c] = ((data[i++] & 0xFF) / 255f - MEAN) / STD;
        return tensor;
    }

    // ---- utility ----

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static double clamp(double lo, double hi, double v) { return Math.max(lo, Math.min(hi, v)); }
    private static int clampInt(int lo, int hi, int v) { return Math.max(lo, Math.min(hi, v)); }

    private static Throwable unwrap(Exception e) {
        return e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null
                ? ite.getCause() : e;
    }
}
