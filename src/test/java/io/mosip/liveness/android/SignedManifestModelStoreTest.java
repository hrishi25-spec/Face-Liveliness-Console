package io.mosip.liveness.android;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tamper tests for the signed-manifest model update path (spec §13: manifest
 * {modelId, version, sha256, signature, minAppVersion} → verify signature +
 * hash → atomic swap → keep previous → rollback). Every way of subverting the
 * update — mutated model bytes, rewritten manifest fields, flipped signature
 * hex, a different signing key, unsigned or malformed input — must be refused
 * fail-closed with the previously active model left untouched.
 */
class SignedManifestModelStoreTest {

    private KeyPair vendorKeys;
    private KeyPair attackerKeys;
    private SignedManifestModelStore store;    // minAppVersion gate configured: app "1.0.0"
    private byte[] modelBytes;

    @BeforeEach
    void setUp() throws Exception {
        vendorKeys = rsa();
        attackerKeys = rsa();
        store = new SignedManifestModelStore(vendorKeys.getPublic(), "1.0.0");
        modelBytes = "model-payload-v1".getBytes(StandardCharsets.UTF_8);
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private SignedModelManifest vendorSign(String sha256Hex, String minAppVersion) {
        return SignedModelManifest.sign("minifasnet", "2026.10.1", sha256Hex, minAppVersion,
                vendorKeys.getPrivate());
    }

    private SignedModelManifest goodManifest() {
        return vendorSign(InMemoryModelStore.sha256Hex(modelBytes), "1.0.0");
    }

    private static byte[] tampered(byte[] original) {
        byte[] copy = original.clone();
        copy[0] ^= 0x01;
        return copy;
    }

    @Test
    void signedManifestActivates() {
        assertTrue(store.activate(goodManifest(), modelBytes));

        ModelStore.ActiveModel active = store.activeModel().orElseThrow();
        assertEquals("minifasnet", active.modelId());
        assertEquals("2026.10.1", active.version());
        assertEquals(InMemoryModelStore.sha256Hex(modelBytes), active.sha256Hex());
    }

    @Test
    void unsignedActivationIsRefused() {
        assertFalse(store.activate("minifasnet", "2026.10.1", modelBytes),
                "the bare ModelStore path must fail closed — no signature, no install");
        assertTrue(store.activeModel().isEmpty(), "nothing may be active without verification");
    }

    @Test
    void mutatedModelBytesAreRefusedAndPreviousModelKept() {
        assertTrue(store.activate(goodManifest(), modelBytes));          // v1 active

        assertFalse(store.activate(goodManifest(), tampered(modelBytes)),
                "payload no longer matches the signed sha256");
        assertEquals(InMemoryModelStore.sha256Hex(modelBytes),
                store.activeModel().orElseThrow().sha256Hex(),
                "a refused update must leave the previous model active");
    }

    @Test
    void rewrittenManifestDigestBreaksTheSignature() {
        // Attacker swaps the model file AND rewrites the manifest's sha256 to
        // match it, keeping the vendor's signature — but the digest is inside
        // the signed payload, so the signature no longer verifies.
        SignedModelManifest original = goodManifest();
        byte[] tamperedBytes = tampered(modelBytes);
        SignedModelManifest forged = new SignedModelManifest(
                original.modelId(), original.version(),
                InMemoryModelStore.sha256Hex(tamperedBytes),
                original.minAppVersion(), original.signatureHex());

        assertFalse(forged.verifySignature(vendorKeys.getPublic()));
        assertFalse(store.activate(forged, tamperedBytes));
        assertTrue(store.activeModel().isEmpty());
    }

    @Test
    void mutatedManifestFieldsInvalidateTheSignature() {
        SignedModelManifest original = goodManifest();
        List<SignedModelManifest> tampered = List.of(
                new SignedModelManifest("evil-model", original.version(), original.sha256Hex(),
                        original.minAppVersion(), original.signatureHex()),
                new SignedModelManifest(original.modelId(), "999.0-hijack", original.sha256Hex(),
                        original.minAppVersion(), original.signatureHex()),
                new SignedModelManifest(original.modelId(), original.version(), original.sha256Hex(),
                        "99.0.0", original.signatureHex()));

        for (SignedModelManifest forged : tampered) {
            assertFalse(forged.verifySignature(vendorKeys.getPublic()),
                    "any field change must invalidate the signature: " + forged);
            assertFalse(store.activate(forged, modelBytes));
        }
        assertTrue(store.activeModel().isEmpty());
    }

    @Test
    void tamperedOrMalformedSignatureIsRefused() {
        SignedModelManifest original = goodManifest();
        String signature = original.signatureHex();
        List<SignedModelManifest> tampered = List.of(
                new SignedModelManifest(original.modelId(), original.version(),
                        original.sha256Hex(), original.minAppVersion(),
                        (signature.charAt(0) == '0' ? "1" : "0") + signature.substring(1)),
                new SignedModelManifest(original.modelId(), original.version(),
                        original.sha256Hex(), original.minAppVersion(), signature.substring(1)),
                new SignedModelManifest(original.modelId(), original.version(),
                        original.sha256Hex(), original.minAppVersion(), ""));

        for (SignedModelManifest forged : tampered) {
            assertFalse(forged.verifySignature(vendorKeys.getPublic()),
                    "flipped/odd-length/empty signature must fail closed: "
                            + forged.signatureHex());
            assertFalse(store.activate(forged, modelBytes));
        }
        assertTrue(store.activeModel().isEmpty());
    }

    @Test
    void signatureFromAnotherKeyIsRejected() {
        SignedModelManifest forged = SignedModelManifest.sign("minifasnet", "2026.10.1",
                InMemoryModelStore.sha256Hex(modelBytes), "1.0.0",
                attackerKeys.getPrivate());
        // Fully self-consistent — signed by the wrong key.
        assertTrue(forged.verifySignature(attackerKeys.getPublic()),
                "sanity: the attacker's own signature verifies under their key");
        assertFalse(store.activate(forged, modelBytes),
                "only the vendor key may authorise a model");
        assertTrue(store.activeModel().isEmpty());
    }

    @Test
    void nullEmptyAndBlankInputsFailClosed() {
        assertFalse(store.activate((SignedModelManifest) null, modelBytes));
        assertFalse(store.activate(goodManifest(), null));
        assertFalse(store.activate(goodManifest(), new byte[0]));

        SignedModelManifest blankId = new SignedModelManifest("", "2026.10.1",
                InMemoryModelStore.sha256Hex(modelBytes), "1.0.0",
                goodManifest().signatureHex());
        assertFalse(blankId.verifySignature(vendorKeys.getPublic()),
                "blank required fields fail closed even with a present signature");
        assertFalse(store.activate(blankId, modelBytes));
    }

    @Test
    void minAppVersionGateRefusesNewerModels() {
        String sha = InMemoryModelStore.sha256Hex(modelBytes);
        SignedModelManifest tooNew = vendorSign(sha, "99.0.0");
        assertFalse(store.activate(tooNew, modelBytes),
                "a model requiring a newer app must be refused");
        assertTrue(store.activeModel().isEmpty());

        assertTrue(store.activate(vendorSign(sha, "1.0.0"), modelBytes),
                "a model at exactly the current app version is allowed");

        // The gate only applies when the store knows its app version…
        SignedManifestModelStore unconfigured = new SignedManifestModelStore(vendorKeys.getPublic());
        assertTrue(unconfigured.activate(vendorSign(sha, "99.0.0"), modelBytes),
                "no configured app version means no min-version gate");

        // …and a malformed minAppVersion fails closed when it is configured.
        assertFalse(store.activate(vendorSign(sha, "banana"), modelBytes),
                "malformed minAppVersion must be refused, not parsed loosely");
    }

    @Test
    void rollbackRestoresPreviousAfterFailedHealthCheck() {
        byte[] v1 = modelBytes;
        byte[] v2 = "model-payload-v2".getBytes(StandardCharsets.UTF_8);
        SignedModelManifest m1 = SignedModelManifest.sign("minifasnet", "2026.10.1",
                InMemoryModelStore.sha256Hex(v1), "1.0.0", vendorKeys.getPrivate());
        SignedModelManifest m2 = SignedModelManifest.sign("minifasnet", "2026.10.2",
                InMemoryModelStore.sha256Hex(v2), "1.0.0", vendorKeys.getPrivate());
        assertTrue(store.activate(m1, v1));
        assertTrue(store.activate(m2, v2));
        assertEquals("2026.10.2", store.activeModel().orElseThrow().version());

        store.rollback();   // health check failed — spec §13 auto-rollback

        ModelStore.ActiveModel restored = store.activeModel().orElseThrow();
        assertEquals("2026.10.1", restored.version(), "rollback must restore the previous model");
        assertEquals(InMemoryModelStore.sha256Hex(v1), restored.sha256Hex());
    }
}
