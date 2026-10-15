# MOSIP Face Liveness & PAD Engine — Backend Swapping Guide

> **Interoperability evidence:** the formal mock / ONNX / MediaPipe comparison
> across the full test suite lives in
> [`backend-interoperability-report.md`](backend-interoperability-report.md),
> backed by the shared contract test `LivenessBackendInteroperabilityTest`.

## The `LivenessBackend` Interface

All liveness/PAD inference is behind a single pluggable interface:

```java
public interface LivenessBackend {
    String id();
    void initialize(Map<String, String> options);
    FaceSignals analyzeFrame(Frame frame);
    double scorePassiveLiveness(Frame frame, FaceSignals signals);
    PadVerdict assessPad(Frame frame, FaceSignals signals);
    void shutdown();
}
```

The engine **never** calls vendor SDKs directly. Swap the backend by implementing this interface and passing it to `FaceLivenessEngine`.

---

## Built-in Backends

### 1. `MockLivenessBackend` (testing/CI)

- Fully scriptable — tests drive a `Subject` to simulate face presence, blink, smile, head pose, gaze, and PAD verdicts.
- No model loading, no native dependencies.
- Supports per-frame signal overrides via `enqueueSignal()` for challenge evaluation tests.
- Use for: unit tests, CI regression, attack-scenario harness.

```java
MockLivenessBackend mock = new MockLivenessBackend();
mock.subject().livenessScore = 0.90;
mock.subject().padVerdict = PadVerdict.bonaFide(0.97);

FaceLivenessEngine engine = new FaceLivenessEngine(config, mock);
```

### 2. `TfLiteMiniFasNetBackend` (real PAD)

- Backed by [Silent-Face-Anti-Spoofing](https://github.com/minivision-ai/Silent-Face-Anti-Spoofing) MiniFASNet model (~1-2 MB INT8).
- Uses TensorFlow Lite via reflection (zero compile-time dependency on TFLite).
- Activate by adding `org.tensorflow:tensorflow-lite` to the classpath and providing the `.tflite` model file.
- Supports NNAPI/GPU delegates on Android.

```java
// Requires TFLite on classpath + model file
Map<String, String> options = Map.of(
    "modelPath", "/path/to/mini_fasnet.tflite",
    "inputSize", "80",
    "threads", "2",
    "delegate", "cpu"  // or "nnapi" / "gpu" on Android
);

TfLiteMiniFasNetBackend backend = new TfLiteMiniFasNetBackend();
backend.initialize(options);

FaceLivenessEngine engine = new FaceLivenessEngine(config, backend);
```

Uses OpenCV `CascadeClassifier` for face detection + eye detection, with geometric landmark estimation for EAR, pose, and gaze.

### 3. `MediaPipeFaceMeshBackend` (high-fidelity landmarks)

- Runs the MediaPipe FaceMesh TFLite model directly (468-point 3D landmark extraction).
- No MediaPipe Java SDK dependency — uses TFLite runtime + OpenCV for face detection.
- Computes precise signals from 468 landmarks:
  - **EAR** from 6 eye-contour points per eye
  - **Smile** from mouth width/height ratio
  - **Head pose** (yaw/pitch) via geometric estimation with 6 reference landmarks
  - **Gaze** from eye-contour geometry
  - **Face quality** from landmark spread + size + centrality
- Model: `face_mesh.tflite` (~5 MB) — download from MediaPipe model zoo.

```java
Map<String, String> options = Map.of(
    "faceMeshModelPath", "/path/to/face_mesh.tflite",
    "faceCascadePath", "/path/to/haarcascade_frontalface_alt.xml",
    "threads", "4"
);

MediaPipeFaceMeshBackend backend = new MediaPipeFaceMeshBackend();
backend.initialize(options);

FaceLivenessEngine engine = new FaceLivenessEngine(config, backend);
```

**When to use**: Choose `MediaPipeFaceMeshBackend` over `TfLiteMiniFasNetBackend` when:
- You need precise EAR for blink detection (active liveness challenges)
- You need accurate head pose for turn-left/turn-right challenges
- You want landmark-based gaze estimation
- You have the FaceMesh model available (~5 MB)

---

## Swapping to a Licensed SDK

To integrate a commercial liveness/PAD SDK (e.g., FaceTec, iProov, Neurotechnology):

### Step 1: Implement `LivenessBackend`

```java
public class VendorSdkBackend implements LivenessBackend {

    private VendorLivenessSDK sdk;

    @Override
    public String id() { return "vendor-sdk-v2"; }

    @Override
    public void initialize(Map<String, String> options) {
        sdk = new VendorLivenessSDK.Builder()
            .apiKey(options.get("apiKey"))
            .build();
    }

    @Override
    public FaceSignals analyzeFrame(Frame frame) {
        VendorFaceResult result = sdk.analyze(preprocess(frame));
        return new FaceSignals(
            result.getFaceCount(),
            result.getQualityScore(),
            result.getEarLeft(),
            result.getEarRight(),
            result.getSmileScore(),
            result.getYawDegrees(),
            result.getPitchDegrees(),
            result.getGazeX(),
            result.getGazeY()
        );
    }

    @Override
    public double scorePassiveLiveness(Frame frame, FaceSignals signals) {
        return sdk.getLivenessScore(preprocess(frame));
    }

    @Override
    public PadVerdict assessPad(Frame frame, FaceSignals signals) {
        VendorPadResult pad = sdk.detectPresentationAttack(preprocess(frame));
        if (pad.isAttack()) {
            return PadVerdict.attack(mapAttackType(pad.getType()), pad.getConfidence());
        }
        return PadVerdict.bonaFide(pad.getConfidence());
    }

    @Override
    public void shutdown() {
        if (sdk != null) sdk.release();
    }

    // Helper methods for vendor-specific format conversion...
}
```

### Step 2: Register and Use

```java
LivenessBackend backend = new VendorSdkBackend();
backend.initialize(vendorOptions);

FaceLivenessEngine engine = new FaceLivenessEngine(config, backend);
// Same API, same engine logic — no changes needed elsewhere.
```

### Step 3: Tune PAD Thresholds

The vendor SDK may have its own internal PAD thresholds. Expose these via the `options` map in `initialize()` and map them to the `PadVerdict` confidence returned by `assessPad()`.

---

## Android-Specific Considerations

### Hardware Acceleration

For TFLite backends on Android:

```java
// In your Android device adapter:
Map<String, String> options = Map.of(
    "modelPath", assetPath("mini_fasnet.tflite"),
    "threads", "4",
    "delegate", hasNNAPI() ? "nnapi" : "cpu"
);
```

### Flutter Integration

The engine is pure Java. For Flutter, use a platform channel:

```kotlin
// Android (Kotlin) side
MethodChannel("io.mosip.liveness").setMethodCallHandler { call, result ->
    when (call.method) {
        "initSession" -> result.success(engine.initSession(workflow))
        "pushFrame" -> result.success(engine.pushFrame(sessionId, frame))
        "requestChallenge" -> result.success(engine.requestChallenge(sessionId))
        "validateChallenge" -> result.success(engine.validateChallenge(sessionId, frames))
        "closeSession" -> result.success(engine.closeSession(sessionId))
    }
}
```

### Model Distribution

For offline operation on Android:
1. Bundle the `.tflite` model in `assets/` (adds ~1-2 MB to APK).
2. Or download model updates when online, verify integrity (SHA-256 + signature), and store in app-internal storage.
3. Never load models from external storage (security risk).

---

## Backend Selection Matrix

| Backend | Offline | PAD Types | Model Size | Latency (mobile) | License |
|---------|---------|-----------|------------|-------------------|---------|
| MockLivenessBackend | ✓ | scripted | 0 MB | <1ms | Apache-2.0 |
| TfLiteMiniFasNetBackend | ✓ | printed, screen | ~1.5 MB | 5-15ms | Apache-2.0 |
| MediaPipeFaceMeshBackend | ✓ | printed, screen | ~5 MB | 8-20ms | Apache-2.0 |
| Vendor SDK (licensed) | depends | all | varies | 10-50ms | commercial |

---

## Testing with Swapped Backends

The attack-scenario harness works with any backend. To evaluate a real model:

1. Record labeled captures (bona fide + attacks) from the target device.
2. Create a `RecordingBackend` that replays recorded frames through the real backend.
3. Run `AttackScenarioHarness.report()` to get APCER/BPCER/ACER.
4. Compare against ISO/IEC 30107-3 acceptance criteria.

```java
// Example: evaluate real model with recorded captures
LivenessBackend realBackend = new TfLiteMiniFasNetBackend();
realBackend.initialize(Map.of("modelPath", modelPath));

LivenessConfig config = LivenessConfig.builder().build();
AttackScenarioHarness harness = new AttackScenarioHarness(config, seed);

// Run with real backend (replace MockLivenessBackend in harness)
List<PresentationResult> results = harness.runWithBackend(realBackend, 100);
ScenarioReport report = AttackScenarioHarness.report(results);
System.out.println(report);
```
