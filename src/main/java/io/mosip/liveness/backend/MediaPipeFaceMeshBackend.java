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
 * High-fidelity face analysis backend using the MediaPipe FaceMesh TFLite model.
 *
 * <p>Runs the FaceMesh landmark model directly via TFLite (no Android/MediaPipe
 * Java SDK dependency). Extracts all 468 3D facial landmarks and computes:</p>
 * <ul>
 *   <li><b>EAR</b> (Eye Aspect Ratio) from precise eye-contour landmarks</li>
 *   <li><b>Smile score</b> from mouth-contour aspect ratio</li>
 *   <li><b>Head pose</b> (yaw/pitch/roll) via solvePnP with 6 reference points</li>
 *   <li><b>Gaze</b> from iris landmark position relative to eye contour</li>
 *   <li><b>Face quality</b> from landmark spread + face size + centrality</li>
 * </ul>
 *
 * <p>Face detection is performed by OpenCV CascadeClassifier (same as
 * {@link TfLiteMiniFasNetBackend}) — the detected face is cropped and resized
 * to192×192 before running FaceMesh.</p>
 *
 * <p>Options keys:</p>
 * <ul>
 *   <li>{@code faceMeshModelPath} – path to face_mesh.tflite (required)</li>
 *   <li>{@code faceCascadePath} – path to face cascade XML (required for face detection)</li>
 *   <li>{@code eyeCascadePath} – path to eye cascade XML (optional)</li>
 *   <li>{@code modelInputSize} – model input edge length (default 192)</li>
 *   <li>{@code useIrisMesh} – if true, expect 478 landmarks with iris points (default false)</li>
 * </ul>
 */
public final class MediaPipeFaceMeshBackend implements LivenessBackend {

    public static final String OPTION_FACE_MESH_MODEL_PATH = "faceMeshModelPath";
    public static final String OPTION_FACE_CASCADE_PATH = "faceCascadePath";
    public static final String OPTION_EYE_CASCADE_PATH = "eyeCascadePath";
    public static final String OPTION_MODEL_INPUT_SIZE = "modelInputSize";
    public static final String OPTION_USE_IRIS_MESH = "useIrisMesh";
    public static final String OPTION_BUFFER_SIZE = "temporalBufferSize";
    public static final String OPTION_MIN_BUFFER_FRAMES = "temporalMinFrames";

    private static final int DEFAULT_INPUT_SIZE = 192;
    private static final int NUM_LANDMARKS = 468;
    private static final int NUM_LANDMARKS_WITH_IRIS = 478;

    // Iris landmark indices (478-point model only)
    // Left iris: center=468, top=469, right=470, bottom=471, left=472
    private static final int LEFT_IRIS_CENTER  = 468;
    private static final int LEFT_IRIS_TOP     = 469;
    private static final int LEFT_IRIS_RIGHT   = 470;
    private static final int LEFT_IRIS_BOTTOM  = 471;
    private static final int LEFT_IRIS_LEFT    = 472;
    // Right iris: center=473 (the surrounding 474-477 points are unused, so they
    // are not declared here — a constant nobody reads is a claim about the model
    // nothing checks)
    private static final int RIGHT_IRIS_CENTER = 473;

    // MediaPipe FaceMesh landmark indices (468-point model)
    // Left eye contour (6 points for EAR)
    private static final int[] LEFT_EYE = {33, 160, 158, 133, 153, 144};
    // Right eye contour (6 points for EAR)
    private static final int[] RIGHT_EYE = {362, 385, 387, 263, 373, 380};
    // Nose tip
    private static final int NOSE_TIP = 1;
    // Chin
    private static final int CHIN = 152;
    // Left eye outer corner
    private static final int LEFT_EYE_OUTER = 33;
    // Right eye outer corner
    private static final int RIGHT_EYE_OUTER = 263;
    // Forehead center
    private static final int FOREHEAD = 10;
    // Left temple
    private static final int LEFT_TEMPLE = 234;
    // Right temple
    private static final int RIGHT_TEMPLE = 454;

    // TFLite
    private Object interpreter;
    private Method runMethod;
    private int modelInputSize = DEFAULT_INPUT_SIZE;
    private int numLandmarks = NUM_LANDMARKS;
    private boolean useIris;
    private float[][][] meshOutput;   // [1][numLandmarks][5]

    // Temporal liveness buffer
    private static final int DEFAULT_BUFFER_SIZE = 15;
    private static final int DEFAULT_MIN_BUFFER_FRAMES = 8;
    private int bufferSize = DEFAULT_BUFFER_SIZE;
    private int minBufferFrames = DEFAULT_MIN_BUFFER_FRAMES;
    private final TemporalBuffer temporalBuffer = new TemporalBuffer();

    // Face detection via OpenCV (reused from TfLiteMiniFasNetBackend pattern)
    private Object faceClassifier;
    private Object eyeClassifier;
    private boolean opencvReady;
    private boolean meshReady;

    // Reflection handles for OpenCV
    private Class<?> matCls, rectCls, sizeCls, cascadeCls, matOfRectCls, matOfDoubleCls;
    private Class<?> calib3dCls;
    private Constructor<?> rectCtor, sizeCtor, matCtor3i, matCtor4i, matOfDoubleCtor;
    private Method mCreate, mRelease, mRows, mCols, mGet, mPut, mSubmat;
    private Method mGetD;
    private Method cDetect, cLoad, cSetMinSize;
    private Method rX, rY, rW, rH;
    private Method morToArray;
    private Method solvePnPMethod, rodriguesMethod, mEye;  // solvePnP + Rodrigues

    @Override public String id() { return "mediapipe-facemesh"; }

    /** @return true when TFLite is available on the classpath. */
    public static boolean isTfLiteAvailable() {
        try { Class.forName("org.tensorflow.lite.Interpreter"); return true; }
        catch (ClassNotFoundException e) { return false; }
    }

    /** @return true when OpenCV is available (needed for face detection). */
    public static boolean isOpenCvAvailable() {
        try { Class.forName("org.opencv.core.Mat"); return true; }
        catch (ClassNotFoundException e) { return false; }
    }

    // ===================================================================
    // Initialization
    // ===================================================================

    @Override
    public void initialize(Map<String, String> options) {
        initFaceMeshModel(options);
        initOpenCv(options);
    }

    private void initFaceMeshModel(Map<String, String> options) {
        if (!isTfLiteAvailable()) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "TensorFlow Lite not on classpath; add org.tensorflow:tensorflow-lite");
        }

        String modelPath = options.get(OPTION_FACE_MESH_MODEL_PATH);
        if (modelPath == null || !Files.exists(Path.of(modelPath))) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "FaceMesh model file missing: " + modelPath);
        }

        if (options.containsKey(OPTION_MODEL_INPUT_SIZE)) {
            modelInputSize = Integer.parseInt(options.get(OPTION_MODEL_INPUT_SIZE));
        }

        useIris = Boolean.parseBoolean(options.getOrDefault(OPTION_USE_IRIS_MESH, "false"));
        numLandmarks = useIris ? NUM_LANDMARKS_WITH_IRIS : NUM_LANDMARKS;
        if (options.containsKey(OPTION_BUFFER_SIZE)) {
            bufferSize = Integer.parseInt(options.get(OPTION_BUFFER_SIZE));
        }
        if (options.containsKey(OPTION_MIN_BUFFER_FRAMES)) {
            minBufferFrames = Integer.parseInt(options.get(OPTION_MIN_BUFFER_FRAMES));
        }

        try {
            Class<?> interpCls = Class.forName("org.tensorflow.lite.Interpreter");
            Class<?> optsCls   = Class.forName("org.tensorflow.lite.Interpreter$Options");
            Object opts = optsCls.getDeclaredConstructor().newInstance();
            int threads = Integer.parseInt(options.getOrDefault("threads", "4"));
            optsCls.getMethod("setNumThreads", int.class).invoke(opts, threads);

            interpreter = interpCls.getConstructor(java.io.File.class, optsCls)
                    .newInstance(new java.io.File(modelPath), opts);
            runMethod = interpCls.getMethod("run", Object.class, Object.class);

            // Allocate output: [1][landmarks][5] depending on model
            meshOutput = new float[1][numLandmarks][5];
            meshReady = true;
        } catch (LivenessException e) { throw e; }
        catch (Exception e) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "Failed to load FaceMesh model: " + e.getMessage(), e);
        }
    }

    private void initOpenCv(Map<String, String> options) {
        opencvReady = isOpenCvAvailable();
        if (!opencvReady) return;

        try {
            matCls      = Class.forName("org.opencv.core.Mat");
            rectCls     = Class.forName("org.opencv.core.Rect");
            sizeCls     = Class.forName("org.opencv.core.Size");
            cascadeCls  = Class.forName("org.opencv.objdetect.CascadeClassifier");
            matOfRectCls = Class.forName("org.opencv.core.MatOfRect");

            rectCtor = rectCls.getConstructor(int.class, int.class, int.class, int.class);
            sizeCtor = sizeCls.getConstructor(double.class, double.class);

            mCreate   = matCls.getMethod("create", int.class, int.class, int.class);
            mRelease  = matCls.getMethod("release");
            mRows     = matCls.getMethod("rows");
            mCols     = matCls.getMethod("cols");
            mGet      = matCls.getMethod("get", int.class, int.class, byte[].class);
            mPut      = matCls.getMethod("put", int.class, int.class, byte[].class);
            mSubmat   = matCls.getMethod("submat", rectCls);

            cDetect     = cascadeCls.getMethod("detectMultiScale", matCls, matOfRectCls);
            cLoad       = cascadeCls.getMethod("load", String.class);
            cSetMinSize = cascadeCls.getMethod("setMinSize", sizeCls);

            rX = rectCls.getMethod("x");  rY = rectCls.getMethod("y");
            rW = rectCls.getMethod("width"); rH = rectCls.getMethod("height");
            morToArray = matOfRectCls.getMethod("toArray");

            // solvePnP + Rodrigues reflection
            matOfDoubleCls = Class.forName("org.opencv.core.MatOfDouble");
            calib3dCls = Class.forName("org.opencv.calib3d.Calib3d");
            matCtor3i = matCls.getConstructor(int.class, int.class, int.class);            // CV_64FC1
            matCtor4i = matCls.getConstructor(int.class, int.class, int.class, double.class);
            matOfDoubleCtor = matOfDoubleCls.getConstructor(double[].class);
            mGetD = matCls.getMethod("get", int.class, int.class, double[].class);
            mEye = matCls.getMethod("eye", int.class, int.class, int.class);
            solvePnPMethod = calib3dCls.getMethod("solvePnP",
                    matCls, matCls, matCls, matCls, matCls, matCls, boolean.class);
            rodriguesMethod = calib3dCls.getMethod("Rodrigues", matCls, matCls);

            // Load face cascade
            String facePath = options.get(OPTION_FACE_CASCADE_PATH);
            if (facePath == null) facePath = findCascade("haarcascade_frontalface_alt.xml");
            if (facePath == null) facePath = findCascade("haarcascade_frontalface_default.xml");
            if (facePath == null) {
                throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                        "Face cascade not found. Set " + OPTION_FACE_CASCADE_PATH);
            }

            faceClassifier = cascadeCls.getDeclaredConstructor().newInstance();
            cLoad.invoke(faceClassifier, facePath);
            Object minSz = sizeCtor.newInstance(40.0, 40.0);
            cSetMinSize.invoke(faceClassifier, minSz);

            // Eye cascade (optional)
            String eyePath = options.get(OPTION_EYE_CASCADE_PATH);
            if (eyePath == null) eyePath = findCascade("haarcascade_eye.xml");
            if (eyePath != null && Files.exists(Path.of(eyePath))) {
                eyeClassifier = cascadeCls.getDeclaredConstructor().newInstance();
                cLoad.invoke(eyeClassifier, eyePath);
            }
        } catch (LivenessException e) { throw e; }
        catch (Exception e) { opencvReady = false; }
    }

    private String findCascade(String name) {
        String[] dirs = {"models", "data/haarcascades",
                "/usr/share/opencv4/haarcascades", "/usr/local/share/opencv4/haarcascades"};
        for (String d : dirs) {
            Path p = Path.of(d, name);
            if (Files.exists(p)) return p.toString();
        }
        try {
            var in = getClass().getResourceAsStream("/haarcascades/" + name);
            if (in == null) in = getClass().getResourceAsStream("/org/opencv/data/haarcascades/" + name);
            if (in != null) {
                Path tmp = Files.createTempFile("cascade_", ".xml");
                tmp.toFile().deleteOnExit();
                java.nio.file.Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                in.close();
                return tmp.toString();
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ===================================================================
    // analyzeFrame — MediaPipe FaceMesh landmark extraction
    // ===================================================================

    @Override
    public FaceSignals analyzeFrame(Frame frame) {
        if (!meshReady) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "FaceMesh model not initialized. Set " + OPTION_FACE_MESH_MODEL_PATH);
        }
        if (!opencvReady || faceClassifier == null) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "OpenCV face detector not initialized for face cropping.");
        }

        try {
            // 1. Detect face via OpenCV cascade
            Object bgr = frameToBgrMat(frame);
            int frameRows = (int) mRows.invoke(bgr);
            int frameCols = (int) mCols.invoke(bgr);

            Object faceRects = matOfRectCls.getDeclaredConstructor().newInstance();
            cDetect.invoke(faceClassifier, bgr, faceRects);
            Object[] faces = (Object[]) morToArray.invoke(faceRects);

            if (faces.length == 0) {
                mRelease.invoke(bgr);
                return FaceSignals.noFace(0.0);
            }

            // Pick largest face
            Object face = largestFace(faces);
            int fx = (int) rX.invoke(face), fy = (int) rY.invoke(face);
            int fw = (int) rW.invoke(face), fh = (int) rH.invoke(face);

            // 2. Crop face region and resize to model input size
            float[][][] landmarks = detectLandmarks(bgr, fx, fy, fw, fh, frameRows, frameCols);
            mRelease.invoke(bgr);

            if (landmarks == null) {
                return FaceSignals.noFace(0.0);
            }

            // 3. Compute signals from 468 landmarks
            double quality  = computeQuality(landmarks, fx, fy, fw, fh, frameRows, frameCols);
            double earLeft  = computeEar(landmarks, LEFT_EYE);
            double earRight = computeEar(landmarks, RIGHT_EYE);
            double smile    = computeSmile(landmarks);
            double[] pose   = computePose(landmarks, frameCols, frameRows);
            double[] gaze   = computeGaze(landmarks);

            return new FaceSignals(
                    faces.length, quality,
                    earLeft, earRight,
                    smile, pose[0], pose[1],
                    gaze[0], gaze[1]);
        } catch (LivenessException e) { throw e; }
        catch (Exception e) {
            throw new LivenessException(LivenessErrorCode.ENGINE_INTERNAL_ERROR,
                    "analyzeFrame failed: " + e.getMessage(), e);
        }
    }

    // ---- Face detection + landmark inference ----

    /**
     * Crops the detected face, resizes to modelInputSize, runs FaceMesh,
     * and returns normalized [468][5] landmarks (x, y, z, visibility, presence).
     */
    private float[][][] detectLandmarks(Object bgr, int fx, int fy, int fw, int fh,
                                         int frameRows, int frameCols) throws Exception {
        // Expand face bounding box by 30% for better landmark coverage
        int pad = (int)(Math.max(fw, fh) * 0.3);
        int cx = clampInt(0, frameCols - 1, fx + fw / 2);
        int cy = clampInt(0, frameRows - 1, fy + fh / 2);
        int halfSize = Math.max(fw, fh) / 2 + pad;

        int cropX = clampInt(0, frameCols - 1, cx - halfSize);
        int cropY = clampInt(0, frameRows - 1, cy - halfSize);
        int cropW = clampInt(1, frameCols - cropX, halfSize * 2);
        int cropH = clampInt(1, frameRows - cropY, halfSize * 2);

        // Crop face ROI
        Object roi = mSubmat.invoke(bgr, rectCtor.newInstance(cropX, cropY, cropW, cropH));

        // Resize to model input size (nearest-neighbor via byte copy)
        float[][][][] input = preprocessRoi(roi, cropW, cropH);

        // Run FaceMesh inference — output is [1][numLandmarks][5]
        float[][][] output = new float[1][numLandmarks][5];
        try {
            runMethod.invoke(interpreter, input, output);
        } catch (Exception e) {
            mRelease.invoke(roi);
            return null;
        }
        mRelease.invoke(roi);

        // Scale landmarks back to frame coordinates
        float scaleX = (float) cropW / modelInputSize;
        float scaleY = (float) cropH / modelInputSize;
        for (int i = 0; i < numLandmarks; i++) {
            output[0][i][0] = output[0][i][0] * scaleX + cropX;  // x in frame coords
            output[0][i][1] = output[0][i][1] * scaleY + cropY;  // y in frame coords
        }

        return output;
    }

    /**
     * Crops an OpenCV Mat ROI and resizes to modelInputSize × modelInputSize float tensor.
     * Input format: RGB float normalized to [0,1].
     */
    private float[][][][] preprocessRoi(Object roi, int roiW, int roiH) throws Exception {
        float[][][][] tensor = new float[1][modelInputSize][modelInputSize][3];

        // Read ROI bytes (BGR)
        byte[] bgr = new byte[roiW * roiH * 3];
        mGet.invoke(roi, 0, 0, bgr);

        // Nearest-neighbor resize + BGR→RGB + normalize to [0,1]
        for (int ty = 0; ty < modelInputSize; ty++) {
            int sy = Math.min(ty * roiH / modelInputSize, roiH - 1);
            for (int tx = 0; tx < modelInputSize; tx++) {
                int sx = Math.min(tx * roiW / modelInputSize, roiW - 1);
                int srcIdx = (sy * roiW + sx) * 3;
                tensor[0][ty][tx][0] = (bgr[srcIdx + 2] & 0xFF) / 255.0f;  // R
                tensor[0][ty][tx][1] = (bgr[srcIdx + 1] & 0xFF) / 255.0f;  // G
                tensor[0][ty][tx][2] = (bgr[srcIdx]     & 0xFF) / 255.0f;  // B
            }
        }
        return tensor;
    }

    private Object largestFace(Object[] faces) throws Exception {
        Object best = faces[0];
        int bestArea = (int) rW.invoke(best) * (int) rH.invoke(best);
        for (int i = 1; i < faces.length; i++) {
            int a = (int) rW.invoke(faces[i]) * (int) rH.invoke(faces[i]);
            if (a > bestArea) { best = faces[i]; bestArea = a; }
        }
        return best;
    }

    // ===================================================================
    // Signal computation from 468 landmarks
    // ===================================================================

    /**
     * Compute Eye Aspect Ratio from 6 eye-contour landmarks.
     * EAR = (||p2-p6|| + ||p3-pp5||) / (2 * ||p1-p4||)
     * where p1..p6 are ordered around the eye contour.
     */
    private double computeEar(float[][][] lm, int[] indices) {
        // indices: [outer_corner, upper_outer, upper_inner, inner_corner, lower_inner, lower_outer]
        float[] p1 = lm[0][indices[0]]; // outer corner
        float[] p2 = lm[0][indices[1]]; // upper outer
        float[] p3 = lm[0][indices[2]]; // upper inner
        float[] p4 = lm[0][indices[3]]; // inner corner
        float[] p5 = lm[0][indices[4]]; // lower inner
        float[] p6 = lm[0][indices[5]]; // lower outer

        double v1 = dist(p2, p6);
        double v2 = dist(p3, p5);
        double h  = dist(p1, p4);

        if (h < 1e-6) return 0.0;
        double ear = (v1 + v2) / (2.0 * h);
        return clamp(0.02, 0.50, ear);
    }

    /**
     * Compute smile score from mouth-contour landmarks.
     * Smile = mouth width / face width ratio, normalized.
     * A smile widens the mouth relative to the face.
     */
    private double computeSmile(float[][][] lm) {
        // Mouth width: distance between left and right mouth corners
        float[] mouthLeft  = lm[0][61];   // left mouth corner
        float[] mouthRight = lm[0][291];  // right mouth corner
        double mouthWidth = dist(mouthLeft, mouthRight);

        // Mouth height: distance between upper and lower lip centers
        float[] upperLip = lm[0][13];     // upper lip center
        float[] lowerLip = lm[0][14];     // lower lip center
        double mouthHeight = dist(upperLip, lowerLip);

        // Face width for normalization
        float[] leftFace  = lm[0][234];   // left temple
        float[] rightFace = lm[0][454];   // right temple
        double faceWidth = dist(leftFace, rightFace);

        if (faceWidth < 1e-6) return 0.05;

        // Smile ratio: wider mouth relative to face = more smile
        double ratio = mouthWidth / faceWidth;

        // Typical ratios: neutral ≈ 0.30-0.35, smile ≈ 0.40-0.55
        // Map to [0, 1] score
        double smile = clamp01((ratio - 0.25) / 0.25);
        return Math.max(0.05, smile);
    }

    /**
     * Estimate head pose (yaw, pitch, roll) using OpenCV solvePnP with 6
     * facial reference landmarks and known 3D model points.
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Map 6 MediaPipe landmarks to 3D model coordinates (mm)</li>
     *   <li>Extract corresponding 2D image coordinates from landmarks</li>
     *   <li>Call OpenCV solvePnP to get rotation vector (rvec) + translation vector (tvec)</li>
     *   <li>Convert rvec → Euler angles via Rodrigues decomposition</li>
     * </ol>
     *
     * <p>Falls back to geometric estimation when OpenCV solvePnP is unavailable.</p>
     *
     * @return [yawDegrees, pitchDegrees, rollDegrees]
     */
    private double[] computePose(float[][][] lm, int frameCols, int frameRows) {
        if (!opencvReady || solvePnPMethod == null) {
            double[] geo = geometricPose(lm, frameCols, frameRows);
            return new double[]{geo[0], geo[1], 0.0}; // no roll from geometry
        }
        try {
            return solvePnpPose(lm, frameCols, frameRows);
        } catch (Exception e) {
            double[] geo = geometricPose(lm, frameCols, frameRows);
            return new double[]{geo[0], geo[1], 0.0};
        }
    }

    /**
     * Solve head pose via OpenCV solvePnP.
     * Returns [yaw, pitch, roll] in degrees.
     */
    private double[] solvePnpPose(float[][][] lm, int frameCols, int frameRows) throws Exception {
        // ---- 3D model points (mm) ----
        // Nose tip as origin, +Y up, +X right, +Z toward camera
        double[] modelPts = {
                0.0,      0.0,      0.0,       // 0: Nose tip
                0.0,     -63.6,    -12.5,      // 1: Chin
               -43.3,     32.7,    -26.0,      // 2: Left eye outer
                43.3,     32.7,    -26.0,      // 3: Right eye outer
               -80.0,    -20.0,    -15.0,      // 4: Left temple
                80.0,    -20.0,    -15.0       // 5: Right temple
        };

        // ---- 2D image points from landmarks ----
        int[] idx = {NOSE_TIP, CHIN, LEFT_EYE_OUTER, RIGHT_EYE_OUTER, LEFT_TEMPLE, RIGHT_TEMPLE};
        double[] imagePts = new double[12]; // 6 points × 2 coords
        for (int i = 0; i < 6; i++) {
            imagePts[i * 2]     = lm[0][idx[i]][0];
            imagePts[i * 2 + 1] = lm[0][idx[i]][1];
        }

        // ---- Build OpenCV Mat objects ----
        // objectPoints: 6×1 CV_64FC3
        Object objPtsMat = matCtor3i.newInstance(6, 1, 0x12 /* CV_64FC3 */);
        // For CV_64FC3, we need to write 3 doubles per point
        // Each point is stored as (x, y, z) in interleaved format
        double[] objPtsFlat = new double[6 * 3];
        for (int i = 0; i < 6; i++) {
            objPtsFlat[i * 3]     = modelPts[i * 3];
            objPtsFlat[i * 3 + 1] = modelPts[i * 3 + 1];
            objPtsFlat[i * 3 + 2] = modelPts[i * 3 + 2];
        }
        putMatC3(objPtsMat, objPtsFlat, 6);

        // imagePoints: 6×1 CV_64FC2
        Object imgPtsMat = matCtor3i.newInstance(6, 1, 0x14 /* CV_64FC2 */);
        putMatC2(imgPtsMat, imagePts, 6);

        // cameraMatrix: 3×3 CV_64FC1
        double focalLen = frameCols;  // focal length ≈ image width
        double cx = frameCols / 2.0;
        double cy = frameRows / 2.0;
        Object camMat = matCtor3i.newInstance(3, 3, 0x12 /* CV_64FC1 */);
        putMatD(camMat, new double[]{
                focalLen, 0, cx,
                0, focalLen, cy,
                0, 0, 1
        }, 3);

        // distCoeffs: 5×1 CV_64FC1 (zero distortion)
        Object distMat = matCtor3i.newInstance(5, 1, 0x12 /* CV_64FC1 */);
        putMatD(distMat, new double[]{0, 0, 0, 0, 0}, 5);

        // rvec + tvec: 3×1 CV_64FC1
        Object rvec = matCtor3i.newInstance(3, 1, 0x12 /* CV_64FC1 */);
        Object tvec = matCtor3i.newInstance(3, 1, 0x12 /* CV_64FC1 */);

        // ---- Call solvePnP ----
        boolean success = (boolean) solvePnPMethod.invoke(null,
                objPtsMat, imgPtsMat, camMat, distMat, rvec, tvec, false);

        if (!success) {
            releaseMats(objPtsMat, imgPtsMat, camMat, distMat, rvec, tvec);
            double[] geo = geometricPose(lm, frameCols, frameRows);
            return new double[]{geo[0], geo[1], 0.0};
        }

        // ---- Extract rotation vector ----
        double[] rvecArr = new double[3];
        mGetD.invoke(rvec, 0, 0, rvecArr);
        double rx = rvecArr[0], ry = rvecArr[1], rz = rvecArr[2];

        releaseMats(objPtsMat, imgPtsMat, camMat, distMat, rvec, tvec);

        // ---- Convert rotation vector to Euler angles ----
        // rvec encodes rotation axis × angle (Rodrigues format)
        // |rvec| = angle in radians, rvec/|rvec| = axis
        double angle = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (angle < 1e-8) {
            return new double[]{0, 0, 0};
        }

        // Normalize axis
        double ax = rx / angle, ay = ry / angle, az = rz / angle;

        // Convert to Euler angles (ZYX convention: Yaw-Pitch-Roll)
        // Using the closed-form from rotation vector
        double sinA = Math.sin(angle);
        double cosA = Math.cos(angle);
        double ONE_COS = 1.0 - cosA;

        // Build rotation matrix R from axis-angle
        double r00 = cosA + ax * ax * ONE_COS;
        double r01 = ax * ay * ONE_COS - az * sinA;
        double r02 = ax * az * ONE_COS + ay * sinA;
        double r10 = ay * ax * ONE_COS + az * sinA;
        double r11 = cosA + ay * ay * ONE_COS;
        double r12 = ay * az * ONE_COS - ax * sinA;
        double r20 = az * ax * ONE_COS - ay * sinA;
        double r21 = az * ay * ONE_COS + ax * sinA;
        double r22 = cosA + az * az * ONE_COS;

        // Euler angles from rotation matrix (ZYX convention)
        // Yaw (Y-axis rotation): atan2(r10, r00)
        double yawDeg = Math.toDegrees(Math.atan2(r10, r00));
        // Pitch (X-axis rotation): atan2(-r20, sqrt(r21² + r22²))
        double pitchDeg = Math.toDegrees(Math.atan2(-r20, Math.sqrt(r21 * r21 + r22 * r22)));
        // Roll (Z-axis rotation): atan2(r21, r22)
        double rollDeg = Math.toDegrees(Math.atan2(r21, r22));

        return new double[]{
                clamp(-90.0, 90.0, yawDeg),
                clamp(-90.0, 90.0, pitchDeg),
                clamp(-90.0, 90.0, rollDeg)
        };
    }

    /** Write a CV_64FC3 Mat (3 channels, interleaved). */
    private void putMatC3(Object mat, double[] data, int rows) throws Exception {
        // For CV_64FC3, put takes rows×cols doubles; here 1 col, 3 channels
        // The data is interleaved: [x0,y0,z0, x1,y1,z1, ...]
        mPut.invoke(mat, 0, 0, data);
    }

    /** Write a CV_64FC2 Mat (2 channels, interleaved). */
    private void putMatC2(Object mat, double[] data, int rows) throws Exception {
        mPut.invoke(mat, 0, 0, data);
    }

    /** Write a CV_64FC1 Mat (single channel). */
    private void putMatD(Object mat, double[] data, int rows) throws Exception {
        mPut.invoke(mat, 0, 0, data);
    }

    private void releaseMats(Object... mats) {
        for (Object m : mats) {
            try { mRelease.invoke(m); } catch (Exception ignored) { }
        }
    }

    /** Geometric pose estimation from landmark positions (fallback). */
    private double[] geometricPose(float[][][] lm, int frameCols, int frameRows) {
        float[] noseTip    = lm[0][NOSE_TIP];
        float[] leftTemple  = lm[0][LEFT_TEMPLE];
        float[] rightTemple = lm[0][RIGHT_TEMPLE];
        float[] forehead    = lm[0][FOREHEAD];
        float[] chin        = lm[0][CHIN];

        double faceCx = (leftTemple[0] + rightTemple[0]) / 2.0;
        double noseOffset = noseTip[0] - faceCx;
        double faceHalfW = (rightTemple[0] - leftTemple[0]) / 2.0;
        double yaw = faceHalfW > 1e-6 ? clamp(-45.0, 45.0, (noseOffset / faceHalfW) * 45.0) : 0.0;

        double faceCy = (forehead[1] + chin[1]) / 2.0;
        double noseVertOffset = noseTip[1] - faceCy;
        double faceHalfH = (chin[1] - forehead[1]) / 2.0;
        double pitch = faceHalfH > 1e-6 ? clamp(-30.0, 30.0, (noseVertOffset / faceHalfH) * 30.0) : 0.0;

        return new double[]{yaw, pitch};
    }

    /**
     * Estimate gaze direction. When the 478-point iris model is active,
     * uses actual iris center position relative to eye contour boundaries.
     * Falls back to geometric approximation from eye-contour landmarks.
     * Returns normalized [gazeX, gazeY] in [-1, 1].
     */
    private double[] computeGaze(float[][][] lm) {
        if (useIris && numLandmarks >= NUM_LANDMARKS_WITH_IRIS) {
            return irisGaze(lm);
        }
        return geometricGaze(lm);
    }

    /**
     * Precise gaze from iris center landmarks (478-point model).
     * 
     * The iris center position relative to the eye's bounding box tells us
     * where the person is looking:
     * - Iris centered in eye → looking straight ahead (gaze ≈ 0)
     * - Iris shifted left in eye → looking left (gazeX < 0)
     * - Iris shifted right in eye → looking right (gazeX > 0)
     * - Iris shifted up in eye → looking up (gazeY < 0)
     * - Iris shifted down in eye → looking down (gazeY > 0)
     */
    private double[] irisGaze(float[][][] lm) {
        // Left iris gaze
        float[] leftIrisCenter  = lm[0][LEFT_IRIS_CENTER];
        float[] leftIrisTop     = lm[0][LEFT_IRIS_TOP];
        float[] leftIrisBottom  = lm[0][LEFT_IRIS_BOTTOM];
        float[] leftIrisLeft    = lm[0][LEFT_IRIS_LEFT];
        float[] leftIrisRight   = lm[0][LEFT_IRIS_RIGHT];

        // Left eye boundary from contour landmarks
        float[] leftOuter = lm[0][LEFT_EYE[0]];  // outer corner
        float[] leftInner = lm[0][LEFT_EYE[3]];  // inner corner
        float[] leftUpper = lm[0][LEFT_EYE[1]];  // upper lid
        float[] leftLower = lm[0][LEFT_EYE[4]];  // lower lid

        double leftGazeX = irisOffsetX(leftIrisCenter, leftOuter, leftInner);
        double leftGazeY = irisOffsetY(leftIrisCenter, leftUpper, leftLower);

        // Right iris gaze
        float[] rightIrisCenter = lm[0][RIGHT_IRIS_CENTER];
        float[] rightOuter = lm[0][RIGHT_EYE[0]];
        float[] rightInner = lm[0][RIGHT_EYE[3]];
        float[] rightUpper = lm[0][RIGHT_EYE[1]];
        float[] rightLower = lm[0][RIGHT_EYE[4]];

        double rightGazeX = irisOffsetX(rightIrisCenter, rightOuter, rightInner);
        double rightGazeY = irisOffsetY(rightIrisCenter, rightUpper, rightLower);

        // Average both eyes for final gaze
        double gazeX = clamp(-1.0, 1.0, (leftGazeX + rightGazeX) / 2.0);
        double gazeY = clamp(-1.0, 1.0, (leftGazeY + rightGazeY) / 2.0);

        return new double[]{gazeX, gazeY};
    }

    /**
     * Horizontal iris offset: iris center position relative to eye corners.
     * Returns [-1, 1] where -1 = looking toward outer corner, +1 = toward inner.
     */
    private double irisOffsetX(float[] irisCenter, float[] outerCorner, float[] innerCorner) {
        double eyeW = Math.abs(innerCorner[0] - outerCorner[0]);
        if (eyeW < 1e-6) return 0.0;
        double eyeCx = (outerCorner[0] + innerCorner[0]) / 2.0;
        // Positive offset = iris toward inner corner (nasal side)
        double offset = irisCenter[0] - eyeCx;
        return clamp(-1.0, 1.0, offset / (eyeW * 0.5));
    }

    /**
     * Vertical iris offset: iris center position relative to upper/lower lids.
     * Returns [-1, 1] where -1 = looking up, +1 = looking down.
     */
    private double irisOffsetY(float[] irisCenter, float[] upperLid, float[] lowerLid) {
        double eyeH = Math.abs(lowerLid[1] - upperLid[1]);
        if (eyeH < 1e-6) return 0.0;
        double eyeCy = (upperLid[1] + lowerLid[1]) / 2.0;
        double offset = irisCenter[1] - eyeCy;
        return clamp(-1.0, 1.0, offset / (eyeH * 0.5));
    }

    /**
     * Fallback gaze estimation from eye-contour geometry (no iris landmarks).
     * Uses upper/lower lid midpoint relative to eye center as pupil proxy.
     */
    private double[] geometricGaze(float[][][] lm) {
        double leftGazeX = eyeGazeComponent(lm, LEFT_EYE);
        double leftGazeY = eyeGazeYComponent(lm, LEFT_EYE);
        double rightGazeX = eyeGazeComponent(lm, RIGHT_EYE);
        double rightGazeY = eyeGazeYComponent(lm, RIGHT_EYE);

        double gazeX = clamp(-1.0, 1.0, (leftGazeX + rightGazeX) / 2.0);
        double gazeY = clamp(-1.0, 1.0, (leftGazeY + rightGazeY) / 2.0);
        return new double[]{gazeX, gazeY};
    }

    private double eyeGazeComponent(float[][][] lm, int[] eyeIndices) {
        float[] outer = lm[0][eyeIndices[0]];
        float[] inner = lm[0][eyeIndices[3]];
        float[] upper = lm[0][eyeIndices[1]];
        float[] lower = lm[0][eyeIndices[4]];
        double eyeCx = (outer[0] + inner[0]) / 2.0;
        double eyeW = Math.abs(inner[0] - outer[0]);
        if (eyeW < 1e-6) return 0.0;
        double pupilOffset = (upper[0] + lower[0]) / 2.0 - eyeCx;
        return clamp(-1.0, 1.0, pupilOffset / (eyeW * 0.5));
    }

    private double eyeGazeYComponent(float[][][] lm, int[] eyeIndices) {
        float[] upper = lm[0][eyeIndices[1]];
        float[] lower = lm[0][eyeIndices[4]];
        double eyeH = Math.abs(lower[1] - upper[1]);
        if (eyeH < 1e-6) return 0.0;
        double eyeCy = (upper[1] + lower[1]) / 2.0;
        double pupilVert = (upper[1] + lower[1]) / 2.0 - eyeCy;
        return clamp(-1.0, 1.0, pupilVert / (eyeH * 0.5));
    }

    // ---- Face quality from landmarks ----

    private double computeQuality(float[][][] lm, int fx, int fy, int fw, int fh,
                                   int frameRows, int frameCols) {
        // Landmark spread: a well-detected face has landmarks spread across the face
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < numLandmarks; i++) {
            float x = lm[0][i][0], y = lm[0][i][1];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        double spread = (maxX - minX) * (maxY - minY);
        double frameArea = (double) frameRows * frameCols;
        double spreadRatio = spread / frameArea;

        // Size ratio
        double sizeRatio = (double)(fw * fh) / frameArea;

        // Centrality
        double dx = (fx + fw / 2.0 - frameCols / 2.0) / (frameCols / 2.0);
        double dy = (fy + fh / 2.0 - frameRows / 2.0) / (frameRows / 2.0);
        double centrality = 1.0 - Math.sqrt(dx * dx + dy * dy) / 1.4142;

        // Combined quality score
        double sizeScore = clamp01(sizeRatio < 0.05 ? sizeRatio / 0.05 :
                sizeRatio < 0.60 ? 0.6 + (sizeRatio - 0.05) / 0.55 * 0.4 :
                sizeRatio < 0.85 ? 1.0 : Math.max(0.0, 1.0 - (sizeRatio - 0.85) / 0.15));

        return clamp01(0.3 * sizeScore + 0.4 * Math.max(0.0, centrality) + 0.3 * clamp01(spreadRatio * 10));
    }

    // ===================================================================
    // Passive liveness scoring — temporal analysis from frame buffer
    // ===================================================================

    /**
     * Passive liveness score using temporal analysis across recent frames.
     *
     * <p>A live face exhibits characteristic temporal patterns:</p>
     * <ul>
     *   <li><b>EAR variance</b>: blinks cause EAR to dip and recover —
     *       live faces show high EAR variance, photos/screens are constant</li>
     *   <li><b>Blink detection</b>: EAR crossing below a close threshold
     *       and then recovering — impossible for a static photo</li>
     *   <li><b>Pose micro-movements</b>: natural head sway causes yaw/pitch
     *       to fluctuate — photos are perfectly rigid</li>
     *   <li><b>Smile variation</b>: subtle smile changes over time —
     *       printed photos have constant mouth shape</li>
     * </ul>
     *
     * <p>Each metric contributes a weighted component to the final score.</p>
     */
    @Override
    public double scorePassiveLiveness(Frame frame, FaceSignals signals) {
        if (!signals.hasLandmarks()) {
            // No landmarks available — can't do temporal analysis
            return clamp01(signals.qualityScore());
        }

        // Push current signals into the temporal buffer
        temporalBuffer.push(signals);

        // Not enough frames yet — return quality-based estimate
        if (temporalBuffer.size() < minBufferFrames) {
            return clamp01(signals.qualityScore() + 0.05 * temporalBuffer.size() / minBufferFrames);
        }

        // Compute temporal metrics
        double earVar       = temporalBuffer.earVariance();
        int blinks          = temporalBuffer.blinkCount();
        double poseVar      = temporalBuffer.poseVariance();
        double smileVar     = temporalBuffer.smileVariance();
        double gazeVar      = temporalBuffer.gazeVariance();

        // ---- EAR variance score ----
        // Live: EAR varies between 0.25-0.35 normally, dips to 0.05-0.10 on blink
        // Photo: EAR is constant (variance ≈ 0)
        // Threshold: variance > 0.001 suggests real eye movement
        double earScore = clamp01(earVar < 0.0005 ? earVar / 0.0005 * 0.3 :
                earVar < 0.002 ? 0.3 + (earVar - 0.0005) / 0.0015 * 0.5 : 0.8);

        // ---- Blink score ----
        // Even one blink is strong evidence of liveness
        // A static photo never blinks
        double blinkScore = blinks == 0 ? 0.0 : clamp01(0.5 + blinks * 0.15);

        // ---- Pose micro-movement score ----
        // Live: yaw/pitch vary by 0.5-3° due to natural sway
        // Photo: yaw/pitch are constant (variance ≈ 0)
        double poseScore = clamp01(poseVar < 0.01 ? poseVar / 0.01 * 0.2 :
                poseVar < 0.1 ? 0.2 + (poseVar - 0.01) / 0.09 * 0.5 : 0.7);

        // ---- Smile variation score ----
        // Live: subtle smile changes (variance > 0)
        // Photo: constant mouth shape
        double smileScore = clamp01(smileVar < 0.001 ? smileVar / 0.001 * 0.3 :
                smileVar < 0.005 ? 0.3 + (smileVar - 0.001) / 0.004 * 0.4 : 0.7);

        // ---- Gaze variation score ----
        // Live: eyes shift slightly (variance > 0)
        // Photo: gaze is fixed
        double gazeScore = clamp01(gazeVar < 0.002 ? gazeVar / 0.002 * 0.3 :
                gazeVar < 0.01 ? 0.3 + (gazeVar - 0.002) / 0.008 * 0.4 : 0.7);

        // ---- Weighted combination ----
        // EAR and blinks are the strongest liveness signals
        double temporalScore = 0.30 * earScore
                             + 0.25 * blinkScore
                             + 0.20 * poseScore
                             + 0.15 * smileScore
                             + 0.10 * gazeScore;

        // Blend with per-frame quality (spatial confidence)
        double spatialScore = clamp01(signals.qualityScore());
        double finalScore = 0.4 * temporalScore + 0.6 * spatialScore;

        return clamp01(finalScore);
    }

    @Override
    public PadVerdict assessPad(Frame frame, FaceSignals signals) {
        double score = scorePassiveLiveness(frame, signals);
        double padThreshold = 0.5;
        if (score < padThreshold) {
            return PadVerdict.attack(PadAttackType.SCREEN_REPLAY, 1.0 - score);
        }
        return PadVerdict.bonaFide(score);
    }

    // ===================================================================
    // Temporal buffer — rolling window of FaceSignals for liveness scoring
    // ===================================================================

    /**
     * Fixed-size rolling buffer of recent FaceSignals.
     * Computes temporal statistics: variance, blink count, movement metrics.
     */
    private class TemporalBuffer {
        private final double[] earLeft  = new double[bufferSize];
        private final double[] earRight = new double[bufferSize];
        private final double[] yaw      = new double[bufferSize];
        private final double[] pitch    = new double[bufferSize];
        private final double[] smile    = new double[bufferSize];
        private final double[] gazeX    = new double[bufferSize];
        private final double[] gazeY    = new double[bufferSize];
        private int count;
        private int writeIdx;

        void push(FaceSignals s) {
            earLeft[writeIdx]  = s.minEyeAspectRatio().orElse(0.30);
            earRight[writeIdx] = s.minEyeAspectRatio().orElse(0.30);
            yaw[writeIdx]      = s.yawDegrees().orElse(0.0);
            pitch[writeIdx]    = s.pitchDegrees().orElse(0.0);
            smile[writeIdx]    = s.smileScore().orElse(0.05);
            gazeX[writeIdx]    = s.gazeX().orElse(0.0);
            gazeY[writeIdx]    = s.gazeY().orElse(0.0);
            writeIdx = (writeIdx + 1) % bufferSize;
            count = Math.min(count + 1, bufferSize);
        }

        int size() { return count; }

        /** Variance of EAR across the buffer window. */
        double earVariance() {
            return variance(earLeft, count);
        }

        /** Count of blink events: EAR dipping below close threshold then recovering. */
        int blinkCount() {
            int blinks = 0;
            boolean eyesClosed = false;
            int readStart = (writeIdx - count + bufferSize) % bufferSize;
            for (int i = 0; i < count; i++) {
                int idx = (readStart + i) % bufferSize;
                double ear = earLeft[idx];
                if (!eyesClosed && ear < 0.12) {
                    eyesClosed = true;
                } else if (eyesClosed && ear > 0.22) {
                    blinks++;
                    eyesClosed = false;
                }
            }
            return blinks;
        }

        /** Combined yaw+pitch variance (head movement). */
        double poseVariance() {
            return variance(yaw, count) + variance(pitch, count);
        }

        /** Smile score variance across the window. */
        double smileVariance() {
            return variance(smile, count);
        }

        /** Gaze direction variance (horizontal + vertical). */
        double gazeVariance() {
            return variance(gazeX, count) + variance(gazeY, count);
        }

        private double variance(double[] arr, int n) {
            if (n < 2) return 0.0;
            int start = (writeIdx - n + bufferSize) % bufferSize;
            double sum = 0, sumSq = 0;
            for (int i = 0; i < n; i++) {
                double v = arr[(start + i) % bufferSize];
                sum += v;
                sumSq += v * v;
            }
            double mean = sum / n;
            return sumSq / n - mean * mean;
        }
    }

    @Override
    public void shutdown() {
        if (interpreter != null) {
            try { interpreter.getClass().getMethod("close").invoke(interpreter); }
            catch (ReflectiveOperationException ignored) { }
            interpreter = null;
        }
        faceClassifier = null;
        eyeClassifier = null;
        meshReady = false;
    }

    // ---- Frame → Mat conversion ----

    private Object frameToBgrMat(Frame frame) throws Exception {
        int w = frame.width(), h = frame.height();
        byte[] data = frame.data();
        Object mat = mCreate.invoke(null, h, w, 0x10); // CV_8UC3

        byte[] bgr = new byte[w * h * 3];
        switch (frame.format()) {
            case RGB_888 -> {
                for (int i = 0, s = 0; i < w * h; i++, s += 3) {
                    bgr[s] = data[s + 2]; bgr[s + 1] = data[s + 1]; bgr[s + 2] = data[s];
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

    // ---- Utility ----

    private static double dist(float[] a, float[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1];
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static double clamp(double lo, double hi, double v) { return Math.max(lo, Math.min(hi, v)); }
    private static int clampInt(int lo, int hi, int v) { return Math.max(lo, Math.min(hi, v)); }
}
