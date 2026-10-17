package io.mosip.liveness.android;

import java.util.Optional;

/**
 * Return of {@code LivenessHostApi.startSession} (spec §7
 * {@code LivenessStartResult}). The preview texture id is created by the
 * Android glue layer and handed to Dart so it can show
 * {@code Texture(textureId)}; frames never cross the bridge (R2).
 */
public record LivenessStartResult(
        String sessionId,
        Optional<Long> previewTextureId,
        Optional<String> errorCode) {
}
