package io.mosip.registration.liveness;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.view.TextureRegistry;
import io.mosip.liveness.android.AndroidLivenessOrchestrator;
import io.mosip.liveness.android.AndroidLivenessPolicyProvider;
import io.mosip.liveness.android.FaceFrameSource;
import io.mosip.liveness.android.LivenessEvidenceSigner;
import io.mosip.liveness.android.LivenessFinalResult;
import io.mosip.liveness.android.LivenessListener;
import io.mosip.liveness.android.LivenessRole;
import io.mosip.liveness.android.LivenessStateEvent;
import io.mosip.liveness.backend.LivenessBackend;
import io.mosip.liveness.engine.FaceLivenessEngine;
import io.mosip.liveness.engine.LivenessPipeline;
import io.mosip.registration.liveness.pigeon.Liveness;

/** Android bridge for the generated Pigeon contract and native orchestrator. */
public final class LivenessPigeonBridge implements AutoCloseable {

    private final Context context;
    private final FlutterEngine engine;
    private final LifecycleOwner lifecycleOwner;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextureRegistry.SurfaceTextureEntry textureEntry;
    private AndroidLivenessOrchestrator orchestrator;
    private ExecutorService executor;
    private Liveness.LivenessFlutterApi flutterApi;

    public LivenessPigeonBridge(Context context, FlutterEngine engine,
                                LifecycleOwner lifecycleOwner) {
        this.context = context.getApplicationContext();
        this.engine = Objects.requireNonNull(engine);
        this.lifecycleOwner = Objects.requireNonNull(lifecycleOwner);
    }

    /**
     * Build the native gate and register its typed Pigeon handlers. The MOSIP
     * host supplies the shared pipeline, backend, policy, and device stores.
     */
    public void setUp(LivenessPipeline pipeline, LivenessBackend backend,
                      AndroidLivenessPolicyProvider policyProvider,
                      LivenessEvidenceSigner signer,
                      io.mosip.liveness.android.LockoutStore lockoutStore,
                      io.mosip.liveness.android.ModelStore modelStore) {
        if (!(pipeline instanceof FaceLivenessEngine engineImpl)) {
            throw new IllegalArgumentException("Unsupported liveness pipeline");
        }
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "liveness-orchestrator");
            thread.setDaemon(true);
            return thread;
        });
        orchestrator = new AndroidLivenessOrchestrator(engineImpl, backend,
                policyProvider, null, null, signer, lockoutStore, modelStore, executor, false);
        orchestrator.setListener(new LivenessListener() {
            @Override
            public void onState(LivenessStateEvent event) {
                dispatchState(event);
            }

            @Override
            public void onFinal(LivenessFinalResult result) {
                dispatchFinal(result);
            }
        });
        textureEntry = engine.getRenderer().createSurfaceTexture();
        flutterApi = new Liveness.LivenessFlutterApi(
                engine.getDartExecutor().getBinaryMessenger());
        Liveness.LivenessHostApi.setup(
                engine.getDartExecutor().getBinaryMessenger(), new HostApiHandler());
    }

    private final class HostApiHandler implements Liveness.LivenessHostApi {
        @Override
        public void startSession(@NonNull String role, @Nullable String userId,
                                 @NonNull Liveness.Result<Liveness.LivenessStartResult> result) {
            if (orchestrator == null) {
                result.success(startResult("DEVICE_UNAVAILABLE"));
                return;
            }

            final LivenessRole parsedRole;
            try {
                parsedRole = LivenessRole.valueOf(role);
            } catch (IllegalArgumentException invalidRole) {
                result.success(startResult("DEVICE_UNAVAILABLE"));
                return;
            }

            try {
                FaceFrameSource source = new CameraXFaceSource(context, lifecycleOwner);
                orchestrator.start(parsedRole, userId, source,
                        new FaceFrameSource.SourceConfig(640, 480, 15, true));
                result.success(startResult(null));
            } catch (RuntimeException startFailure) {
                result.success(startResult("DEVICE_UNAVAILABLE"));
            }
        }

        @Override
        public void cancelSession() {
            if (orchestrator != null) orchestrator.cancel();
        }

        @NonNull
        @Override
        public Boolean isGateValid() {
            return orchestrator != null && orchestrator.isGateValid(lastSessionId());
        }

        @NonNull
        @Override
        public Map<String, String> diagnosticsSnapshot() {
            return Collections.emptyMap();
        }
    }

    private Liveness.LivenessStartResult startResult(@Nullable String errorCode) {
        Long textureId = textureEntry == null ? null : textureEntry.id();
        return new Liveness.LivenessStartResult.Builder()
                .setSessionId(lastSessionId())
                .setPreviewTextureId(textureId)
                .setErrorCode(errorCode)
                .build();
    }

    private String lastSessionId() {
        return orchestrator == null ? null : orchestrator.currentSessionId().orElse(null);
    }

    /** Forward only state and generic UI metadata; frame bytes never cross Pigeon. */
    public void dispatchState(LivenessStateEvent event) {
        Liveness.LivenessStateEvent pigeonEvent = new Liveness.LivenessStateEvent.Builder()
                .setSessionId(event.sessionId())
                .setState(event.state() == null ? null : event.state().name())
                .setChallenge(event.challenge())
                .setHint(event.hint() == null ? null : event.hint().name())
                .setUiMessageKey(event.uiMessageKey())
                .setAttemptsUsed((long) event.attemptsUsed())
                .setAttemptsMax((long) event.attemptsMax())
                .setChallengeIndex(asLong(event.challengeIndex()))
                .setChallengeTotal(asLong(event.challengeTotal()))
                .setProgress(event.progress())
                .setFailCategory(event.failCategory() == null
                        ? null : event.failCategory().name())
                .build();
        mainHandler.post(() -> {
            if (flutterApi != null) flutterApi.onState(pigeonEvent, ignored -> { });
        });
    }

    /** Forward terminal status without scores, PAD detail, or frame data. */
    public void dispatchFinal(LivenessFinalResult result) {
        Liveness.LivenessFinalResult pigeonResult = new Liveness.LivenessFinalResult.Builder()
                .setSessionId(result.sessionId())
                .setOutcome(result.outcome() == null ? null : result.outcome().name())
                .setNextAction(result.nextAction() == null ? null : result.nextAction().name())
                .setLockoutSeconds(result.lockoutSeconds()
                        .map(Integer::longValue).orElse(null))
                .setValidForSeconds((long) result.validForSeconds())
                .build();
        mainHandler.post(() -> {
            if (flutterApi != null) flutterApi.onFinal(pigeonResult, ignored -> { });
        });
    }

    /** Surface consumed by the preview pipeline; analysis frames remain native. */
    public Surface previewSurface() {
        SurfaceTexture surfaceTexture = textureEntry == null
                ? null : textureEntry.surfaceTexture();
        return surfaceTexture == null ? null : new Surface(surfaceTexture);
    }

    @Override
    public void close() {
        Liveness.LivenessHostApi.setup(
                engine.getDartExecutor().getBinaryMessenger(), null);
        if (orchestrator != null) orchestrator.close();
        if (textureEntry != null) textureEntry.release();
        if (executor != null) executor.shutdown();
        flutterApi = null;
    }

    /** Prevent screenshots and screen recording while a liveness gate is open. */
    public static void applyFlagSecure(android.app.Activity activity) {
        activity.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }

    private static Long asLong(Integer value) {
        return value == null ? null : value.longValue();
    }
}
