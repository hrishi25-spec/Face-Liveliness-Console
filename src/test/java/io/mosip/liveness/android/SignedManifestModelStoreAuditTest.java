package io.mosip.liveness.android;

import io.mosip.liveness.audit.AuditEvent;
import io.mosip.liveness.audit.AuditEventType;
import io.mosip.liveness.audit.AuditLogger;
import io.mosip.liveness.testing.RecordingAuditLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit emission for the model-update path (spec §10 {@code MODEL_UPDATED:
 * "old/new version, hash ok"}): every activation, refusal and rollback
 * produces exactly one event whose {@code oldVersion}/{@code newVersion}
 * tell whether anything swapped and whose {@code hashOk} independently
 * records the digest outcome — a tampered payload must read
 * {@code hashOk=false} while a wrong-key signature over the correct digest
 * must read {@code hashOk=true, reason=SIGNATURE}.
 */
class SignedManifestModelStoreAuditTest {

    private KeyPair vendorKeys;
    private KeyPair attackerKeys;
    private byte[] modelBytes;
    private RecordingAuditLogger audit;
    private SignedManifestModelStore store;   // audited single key, minAppVersion gate "1.0.0"

    @BeforeEach
    void setUp() throws Exception {
        vendorKeys = rsa();
        attackerKeys = rsa();
        modelBytes = "model-payload-v1".getBytes(StandardCharsets.UTF_8);
        audit = new RecordingAuditLogger();
        store = new SignedManifestModelStore(vendorKeys.getPublic(), "1.0.0", audit);
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private SignedModelManifest sign(String version, String minAppVersion, byte[] payload) {
        return SignedModelManifest.sign("minifasnet", version,
                InMemoryModelStore.sha256Hex(payload), minAppVersion, vendorKeys.getPrivate());
    }

    private SignedModelManifest good() {
        return sign("2026.10.1", "1.0.0", modelBytes);
    }

    private static byte[] tampered(byte[] original) {
        byte[] copy = original.clone();
        copy[0] ^= 0x01;
        return copy;
    }

    private AuditEvent event(int index) {
        return audit.events().get(index);
    }

    private static String field(AuditEvent event, String key) {
        return event.fields().get(key);
    }

    // ------------------------------------------------------------ swaps

    @Test
    void firstSwapIsAuditedWithNoneOldVersionAndHashOk() {
        assertTrue(store.activate(good(), modelBytes));

        assertEquals(1, audit.count(AuditEventType.MODEL_UPDATED));
        AuditEvent event = event(0);
        assertEquals(AuditEventType.MODEL_UPDATED, event.type());
        assertEquals("SWAP", field(event, "action"));
        assertEquals("none", field(event, "oldVersion"),
                "a first install has no previous version to report");
        assertEquals("2026.10.1", field(event, "newVersion"));
        assertEquals("true", field(event, "hashOk"),
                "the swap only happened because the digest matched");
        assertEquals("minifasnet", field(event, "modelId"));
        assertEquals(SignedModelManifest.keyIdOf(vendorKeys.getPublic()), field(event, "keyId"));
        assertNull(field(event, "reason"), "a successful swap has no failure reason");
        assertNull(event.sessionId(), "a model update carries no liveness session");
        assertNull(event.workflow(), "a model update is not a workflow decision");
    }

    @Test
    void secondSwapAuditsOldAndNewVersions() {
        byte[] v2 = "model-payload-v2".getBytes(StandardCharsets.UTF_8);
        assertTrue(store.activate(good(), modelBytes));
        assertTrue(store.activate(sign("2026.10.2", "1.0.0", v2), v2));

        assertEquals(2, audit.count(AuditEventType.MODEL_UPDATED));
        AuditEvent second = event(1);
        assertEquals("SWAP", field(second, "action"));
        assertEquals("2026.10.1", field(second, "oldVersion"));
        assertEquals("2026.10.2", field(second, "newVersion"));
        assertEquals("true", field(second, "hashOk"));
    }

    // ------------------------------------------------------------ refusals

    @Test
    void tamperedPayloadRefusalKeepsTheOldVersionAndReportsHashNotOk() {
        assertTrue(store.activate(good(), modelBytes));
        assertFalse(store.activate(good(), tampered(modelBytes)));

        assertEquals(2, audit.count(AuditEventType.MODEL_UPDATED));
        AuditEvent refusal = event(1);
        assertEquals("ROLLBACK", field(refusal, "action"));
        assertEquals("HASH", field(refusal, "reason"));
        assertEquals("false", field(refusal, "hashOk"),
                "the digest did not match — hashOk must say so, not paper over it");
        assertEquals("2026.10.1", field(refusal, "oldVersion"));
        assertEquals("2026.10.1", field(refusal, "newVersion"),
                "a refusal swaps nothing: old and new are the kept version");

        ModelStore.ActiveModel active = store.activeModel().orElseThrow();
        assertEquals("2026.10.1", active.version());
        assertEquals(InMemoryModelStore.sha256Hex(modelBytes), active.sha256Hex(),
                "the refused update must leave the active model untouched");
    }

    @Test
    void wrongSigningKeyRefusalKeepsHashOkIndependentOfTheReason() {
        // Attacker signs a manifest over the CORRECT digest with their own key:
        // hash matches, signature does not — the two fields must say so.
        SignedModelManifest forged = SignedModelManifest.sign("minifasnet", "2026.10.1",
                InMemoryModelStore.sha256Hex(modelBytes), "1.0.0", attackerKeys.getPrivate());
        assertFalse(store.activate(forged, modelBytes));

        AuditEvent refusal = event(0);
        assertEquals("ROLLBACK", field(refusal, "action"));
        assertEquals("SIGNATURE", field(refusal, "reason"));
        assertEquals("true", field(refusal, "hashOk"),
                "hashOk reports the digest outcome only — the refusal reason is separate");
        assertEquals("none", field(refusal, "oldVersion"));
        assertEquals("none", field(refusal, "newVersion"));
        assertEquals(SignedModelManifest.keyIdOf(attackerKeys.getPublic()), field(refusal, "keyId"));
        assertTrue(store.activeModel().isEmpty(), "nothing may install under the wrong key");
    }

    @Test
    void minAppVersionRefusalReportsHashOkAndKeepsTheOldVersion() {
        assertTrue(store.activate(good(), modelBytes));
        SignedModelManifest tooNew = sign("2026.10.1", "99.0.0", modelBytes);
        assertFalse(store.activate(tooNew, modelBytes));

        AuditEvent refusal = event(1);
        assertEquals("ROLLBACK", field(refusal, "action"));
        assertEquals("MIN_APP_VERSION", field(refusal, "reason"));
        assertEquals("true", field(refusal, "hashOk"),
                "signature and digest both passed — the gate is what refused");
        assertEquals("2026.10.1", field(refusal, "oldVersion"));
        assertEquals("2026.10.1", field(refusal, "newVersion"));
        assertEquals("2026.10.1", store.activeModel().orElseThrow().version());
    }

    // ------------------------------------------------------------ rollback

    @Test
    void rollbackAuditsTheUndoneAndRestoredVersions() {
        byte[] v2 = "model-payload-v2".getBytes(StandardCharsets.UTF_8);
        assertTrue(store.activate(good(), modelBytes));
        assertTrue(store.activate(sign("2026.10.2", "1.0.0", v2), v2));

        store.rollback();   // failed health check — spec §13 auto-rollback

        assertEquals(3, audit.count(AuditEventType.MODEL_UPDATED));
        AuditEvent rolled = event(2);
        assertEquals("ROLLBACK", field(rolled, "action"));
        assertEquals("HEALTH_CHECK", field(rolled, "reason"));
        assertEquals("2026.10.2", field(rolled, "oldVersion"), "the model being undone");
        assertEquals("2026.10.1", field(rolled, "newVersion"), "the model being restored");
        assertEquals("true", field(rolled, "hashOk"),
                "both states were digest-verified when swapped in — this failure is not a hash failure");
        assertEquals("minifasnet", field(rolled, "modelId"));
        assertEquals("2026.10.1", store.activeModel().orElseThrow().version());
    }

    @Test
    void rollbackWithNothingActiveStillAudits() {
        store.rollback();

        assertEquals(1, audit.count(AuditEventType.MODEL_UPDATED));
        AuditEvent rolled = event(0);
        assertEquals("ROLLBACK", field(rolled, "action"));
        assertEquals("HEALTH_CHECK", field(rolled, "reason"));
        assertEquals("none", field(rolled, "oldVersion"));
        assertEquals("none", field(rolled, "newVersion"));
        assertEquals("true", field(rolled, "hashOk"));
        assertTrue(store.activeModel().isEmpty());
    }

    @Test
    void failedRollbackAuditDoesNotChangeTheActiveModel() {
        AuditLogger failingAudit = event -> {
            if ("ROLLBACK".equals(event.fields().get("action"))) {
                throw new IllegalStateException("audit sink unavailable");
            }
        };
        SignedManifestModelStore auditedStore =
                new SignedManifestModelStore(vendorKeys.getPublic(), "1.0.0", failingAudit);
        byte[] secondModel = "model-payload-v2".getBytes(StandardCharsets.UTF_8);
        assertTrue(auditedStore.activate(good(), modelBytes));
        assertTrue(auditedStore.activate(
                SignedModelManifest.sign("minifasnet", "2026.10.2",
                        InMemoryModelStore.sha256Hex(secondModel), "1.0.0", vendorKeys.getPrivate()),
                secondModel));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, auditedStore::rollback);

        assertEquals("2026.10.2", auditedStore.activeModel().orElseThrow().version(),
                "a failed audit must leave the current model installed");
    }

    // ------------------------------------------------------------ bare interface

    @Test
    void unsignedPathIsAuditedRatherThanSilentlyRefused() {
        assertFalse(store.activate("minifasnet", "2026.10.1", modelBytes));

        assertEquals(1, audit.count(AuditEventType.MODEL_UPDATED));
        AuditEvent refusal = event(0);
        assertEquals("ROLLBACK", field(refusal, "action"));
        assertEquals("UNSIGNED", field(refusal, "reason"));
        assertEquals("false", field(refusal, "hashOk"), "nothing was verified at all");
        assertEquals("none", field(refusal, "oldVersion"));
        assertEquals("none", field(refusal, "newVersion"));
        assertEquals("minifasnet", field(refusal, "modelId"));
        assertNull(field(refusal, "keyId"), "an unsigned attempt names no signing key");
        assertTrue(store.activeModel().isEmpty());
    }

    @Test
    void malformedInputRefusalsAreAudited() {
        assertFalse(store.activate(good(), null));
        assertFalse(store.activate(good(), new byte[0]));
        assertFalse(store.activate((SignedModelManifest) null, modelBytes));

        assertEquals(3, audit.count(AuditEventType.MODEL_UPDATED));
        for (int i = 0; i < 3; i++) {
            AuditEvent refusal = event(i);
            assertEquals("ROLLBACK", field(refusal, "action"), "event " + i);
            assertEquals("INPUT", field(refusal, "reason"), "event " + i);
            assertEquals("false", field(refusal, "hashOk"), "event " + i);
            assertEquals("none", field(refusal, "oldVersion"), "event " + i);
            assertEquals("none", field(refusal, "newVersion"), "event " + i);
        }
        assertEquals("minifasnet", field(event(0), "modelId"), "manifest known, payload not");
        assertNull(field(event(2), "modelId"), "no manifest, no model id to report");
        assertTrue(store.activeModel().isEmpty());
    }
}
