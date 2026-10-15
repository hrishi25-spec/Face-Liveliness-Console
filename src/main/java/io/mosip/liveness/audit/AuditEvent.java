package io.mosip.liveness.audit;

import io.mosip.liveness.core.WorkflowType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.HexFormat;

/** Immutable structured audit record for one pipeline decision. */
public final class AuditEvent {

    private final long timestampMillis;
    private final String sessionId;
    private final WorkflowType workflow;
    private final AuditEventType type;
    private final Map<String, String> fields;

    /** Patterns that trigger redaction (case-insensitive substring match). */
    private static final List<String> REDACTION_PATTERNS = loadRedactionPatterns();

    private static List<String> loadRedactionPatterns() {
        String env = System.getenv("AUDIT_REDACTION_KEYS");
        if (env != null && !env.isBlank()) {
            return Arrays.stream(env.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toList());
        }
        // default patterns
        return List.of("secret", "key", "token", "password", "auth");
    }

    public AuditEvent(long timestampMillis, String sessionId, WorkflowType workflow, AuditEventType type) {
        this.timestampMillis = timestampMillis;
        this.sessionId = sessionId;
        this.workflow = workflow;
        this.type = type;
        this.fields = new LinkedHashMap<>();
    }

    public static AuditEvent of(long tsMillis, String sessionId, WorkflowType workflow, AuditEventType type) {
        return new AuditEvent(tsMillis, sessionId, workflow, type);
    }

    /** Returns a copy with the extra field (immutable chaining). */
    public AuditEvent field(String key, Object value) {
        AuditEvent copy = new AuditEvent(timestampMillis, sessionId, workflow, type);
        copy.fields.putAll(this.fields);
        if (value != null) {
            String stringValue = value.toString();
            // sanitize: single line, no control characters in audit values
            stringValue = stringValue.replaceAll("[\\p{Cntrl}]", " ");
            // redact values for keys that match any pattern
            String lowerKey = key.toLowerCase(Locale.ROOT);
            boolean publicKeyId = lowerKey.equals("keyid");
            for (String pattern : REDACTION_PATTERNS) {
                if (!publicKeyId && lowerKey.contains(pattern)) {
                    // replace with SHA-256 hash of the sanitized value
                    try {
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        byte[] hash = digest.digest(stringValue.getBytes(StandardCharsets.UTF_8));
                        stringValue = HexFormat.of().formatHex(hash);
                    } catch (NoSuchAlgorithmException e) {
                        // This should never happen
                        throw new IllegalStateException("SHA-256 not available", e);
                    }
                    break;
                }
            }
            copy.fields.put(key, stringValue);
        }
        return copy;
    }

    public long timestampMillis() { return timestampMillis; }
    public String sessionId() { return sessionId; }
    public WorkflowType workflow() { return workflow; }
    public AuditEventType type() { return type; }
    public Map<String, String> fields() { return Map.copyOf(fields); }
}
