package io.mosip.liveness.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.liveness.core.LivenessMessageKeys;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fail-closed localisation key coverage gate.
 *
 * <p>Verifies parity between:
 * <ol>
 *   <li>The single source of truth: {@link LivenessMessageKeys#ALL_KEYS}</li>
 *   <li>The Desktop presenter catalogue: {@link LivenessOverlayPresenter#DEFAULT_MESSAGES}</li>
 *   <li>The Android ARB file: {@code android_client/lib/l10n/app_en.arb}</li>
 *   <li>The Flutter view defaults: {@code android_client/lib/liveness/liveness_view.dart}</li>
 * </ol>
 * If any surface misses a key, this test fails closed.
 */
class LocalisationKeyGateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void presenterDefaultMessagesMatchesCanonicalKeys() {
        Set<String> presenterKeys = LivenessOverlayPresenter.DEFAULT_MESSAGES.keySet();
        assertEquals(LivenessMessageKeys.ALL_KEYS, presenterKeys,
                "LivenessOverlayPresenter.DEFAULT_MESSAGES must exactly match LivenessMessageKeys.ALL_KEYS");
    }

    @Test
    void arbFileContainsAllCanonicalKeys() throws IOException {
        Path arbPath = Path.of("android_client/lib/l10n/app_en.arb");
        assertTrue(Files.exists(arbPath), "app_en.arb must exist at " + arbPath.toAbsolutePath());

        JsonNode root = mapper.readTree(arbPath.toFile());
        Set<String> arbKeys = new HashSet<>();
        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            if (!field.startsWith("@")) {
                arbKeys.add(field);
            }
        }

        assertEquals(LivenessMessageKeys.ALL_KEYS, arbKeys,
                "app_en.arb must contain all keys in LivenessMessageKeys.ALL_KEYS");
    }

    @Test
    void flutterViewDefaultMessagesMatchesCanonicalKeys() throws IOException {
        Path dartPath = Path.of("android_client/lib/liveness/liveness_view.dart");
        assertTrue(Files.exists(dartPath), "liveness_view.dart must exist at " + dartPath.toAbsolutePath());

        String content = Files.readString(dartPath);
        Pattern pattern = Pattern.compile("'([a-z0-9_.]+)':\\s*'");
        Matcher matcher = pattern.matcher(content);
        Set<String> dartKeys = new HashSet<>();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (LivenessMessageKeys.FLUTTER_KEYS.contains(key)) {
                dartKeys.add(key);
            }
        }

        assertEquals(LivenessMessageKeys.FLUTTER_KEYS, dartKeys,
                "liveness_view.dart must declare all keys in LivenessMessageKeys.FLUTTER_KEYS");
    }
}
