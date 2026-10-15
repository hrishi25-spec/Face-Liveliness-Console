package io.mosip.liveness.audit;

import io.mosip.liveness.core.WorkflowType;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class AuditEventTest {

    private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");

    @Test
    void field_nonSensitiveKey_isLogged() {
        AuditEvent event = AuditEvent.of(
                System.currentTimeMillis(),
                "session-123",
                WorkflowType.RESIDENT_REGISTRATION,
                AuditEventType.SESSION_STARTED)
                .field("policy", "some-policy");

        assertEquals("some-policy", event.fields().get("policy"));
    }

    @Test
    void field_sensitiveKey_isHashed() {
        AuditEvent event = AuditEvent.of(
                System.currentTimeMillis(),
                "session-123",
                WorkflowType.RESIDENT_REGISTRATION,
                AuditEventType.SESSION_STARTED)
                .field("secret-key", "my-secret")
                .field("apiToken", "abc123")
                .field("password", "mypass")
                .field("authorization", "Bearer token");

        assertThatHash(event.fields().get("secret-key"));
        assertThatHash(event.fields().get("apiToken"));
        assertThatHash(event.fields().get("password"));
        assertThatHash(event.fields().get("authorization"));
    }

    @Test
    void publicSigningKeyIdIsPreservedWhileKeySecretsAreHashed() {
        AuditEvent event = AuditEvent.of(
                System.currentTimeMillis(),
                null,
                null,
                AuditEventType.MODEL_UPDATED)
                .field("keyId", "public-key-fingerprint")
                .field("apiKey", "secret-token");

        assertEquals("public-key-fingerprint", event.fields().get("keyId"));
        assertThatHash(event.fields().get("apiKey"));
    }

    private void assertThatHash(String value) {
        assertNotNull(value, "Hashed value should not be null");
        assertTrue(HEX_64.matcher(value.toLowerCase()).matches(),
                "Expected a 64-character hex string, got: " + value);
    }

    @Test
    void field_caseInsensitive() {
        AuditEvent event = AuditEvent.of(
                System.currentTimeMillis(),
                "session-123",
                WorkflowType.RESIDENT_REGISTRATION,
                AuditEventType.SESSION_STARTED)
                .field("SECRET", "value")
                .field("Key", "value")
                .field("TOKEN", "value")
                .field("Password", "value");

        assertThatHash(event.fields().get("SECRET"));
        assertThatHash(event.fields().get("Key"));
        assertThatHash(event.fields().get("TOKEN"));
        assertThatHash(event.fields().get("Password"));
    }

    @Test
    void field_controlCharactersAreSanitized() {
        AuditEvent event = AuditEvent.of(
                System.currentTimeMillis(),
                "session-123",
                WorkflowType.RESIDENT_REGISTRATION,
                AuditEventType.SESSION_STARTED)
                .field("message", "hello\nworld\t");

        // newline and tab should be replaced by space
        assertEquals("hello world ", event.fields().get("message"));
    }

    @Test
    void field_originalEventIsImmutable() {
        AuditEvent original = AuditEvent.of(
                System.currentTimeMillis(),
                "session-123",
                WorkflowType.RESIDENT_REGISTRATION,
                AuditEventType.SESSION_STARTED);

        AuditEvent copy = original.field("secret", "value");

        // original should not have the field
        assertNull(original.fields().get("secret"));
        // copy should have the field (hashed)
        assertThatHash(copy.fields().get("secret"));
    }

    @Test
    void field_nullValue_isNotAdded() {
        AuditEvent event = AuditEvent.of(
                System.currentTimeMillis(),
                "session-123",
                WorkflowType.RESIDENT_REGISTRATION,
                AuditEventType.SESSION_STARTED)
                .field("nullField", null);

        assertNull(event.fields().get("nullField"));
    }
}
