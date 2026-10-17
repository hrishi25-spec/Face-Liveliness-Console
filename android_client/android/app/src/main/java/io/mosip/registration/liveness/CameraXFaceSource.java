package io.mosip.registration.liveness;

import android.content.Context;
import android.graphics.ImageFormat;
import android.media.Image;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.LifecycleOwner;

import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.mosip.liveness.android.FaceFrameSource;
import io.mosip.liveness.android.LivenessDeviceError;
import io.mosip.liveness.core.Frame;

/**
 * CameraX frame source (spec §11 CameraXFaceSource, §12 resource targets):
 * front camera, keep-only-latest backpressure, YUV_420_888 analysis at
 * ≤ 640x480, frames delivered to the orchestrator's serial executor. Vendor
 * neutral — an SBI stream adapter implements the same {@link FaceFrameSource}
 * SPI without any change above the adapter (F3 vendor independence).
 *
 * <p>This class touches Android APIs only and lives in the client embedding;
 * the engine-side tests use {@code MockFaceFrameSource} against the same SPI.</p>
 */
public final class CameraXFaceSource implements FaceFrameSource {

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private ImageAnalysis analysis;
    private ProcessCameraProvider cameraProvider;
    private Listener listener;
    private final AtomicBoolean started = new AtomicBoolean();

    public CameraXFaceSource(Context context, LifecycleOwner lifecycleOwner) {
        this.context = context.getApplicationContext();
        this.lifecycleOwner = lifecycleOwner;
    }

    @Override
    public SourceCapabilities capabilities() {
        Set<Frame.Format> formats = EnumSet.of(Frame.Format.YUV420, Frame.Format.NV21);
        return new SourceCapabilities("camerax-front", true, false, 15, formats);
    }

    @Override
    public void open(SourceConfig config) throws LivenessSourceException {
        try {
            cameraProvider = ProcessCameraProvider.getInstance(context).get();
        } catch (ExecutionException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new LivenessSourceException(LivenessDeviceError.CAMERA_UNAVAILABLE,
                    "CameraX provider unavailable", e);
        }
        ResolutionSelector selector = new ResolutionSelector.Builder()
                .setResolutionStrategy(new ResolutionStrategy(
                        android.util.Size.parseSize(config.targetWidth() + "x" + config.targetHeight()),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER))
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build();
        analysis = new ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
    }

    @Override
    public void start(Listener l) {
        this.listener = l;
        if (!started.compareAndSet(false, true)) {
            return;
        }
        analysis.setAnalyzer(Executors.newSingleThreadExecutor(), this::analyze);
        Preview preview = new Preview.Builder().build();
        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);
        } catch (RuntimeException e) {
            listener.onSourceError(LivenessDeviceError.CAMERA_UNAVAILABLE,
                    "bindToLifecycle failed: " + e.getMessage());
        }
    }

    private void analyze(@NonNull ImageProxy proxy) {
        try (ImageProxy p = proxy) {
            Image image = p.getImage();
            if (image == null || listener == null) {
                return;
            }
            Frame frame = toFrame(p, image);
            if (frame != null) {
                listener.onFrame(frame);
            }
        } catch (IllegalStateException e) {
            // image already closed — next frame continues
        }
    }

    /**
     * Convert YUV_420_888 to the engine's NV21 layout (see
     * {@code PixelFormats} in the engine for the desktop equivalent).
     */
    private Frame toFrame(ImageProxy proxy, Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        ByteBuffer y = image.getPlanes()[0].getBuffer();
        ByteBuffer u = image.getPlanes()[1].getBuffer();
        ByteBuffer v = image.getPlanes()[2].getBuffer();
        int chromaHeight = height / 2;
        int ySize = y.remaining();
        int chromaSize = u.remaining() + v.remaining();
        byte[] nv21 = new byte[ySize + chromaSize];
        y.get(nv21, 0, ySize);
        int pos = ySize;
        v.get(nv21, pos, v.remaining());
        pos += v.remaining();
        u.get(nv21, pos, u.remaining());
        long ts = System.nanoTime();
        return new Frame(nv21, width, height, Frame.Format.NV21,
                ts / 1_000_000L, (int) (ts & 0x7FFFFFFF));
    }

    @Override
    public void stop() {
        if (analysis != null) {
            analysis.clearAnalyzer();
        }
        started.set(false);
    }

    @Override
    public void close() {
        stop();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        analysis = null;
        listener = null;
    }
}
