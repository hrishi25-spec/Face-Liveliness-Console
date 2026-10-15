package io.mosip.liveness.android;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Key-rotation semantics for the signed-manifest path (spec §13 + key ids):
 * manifests stay verifiable while keys rotate — a keyring trusts the previous
 * and current vendor key, a manifest's key id selects exactly its signer, a
 * kid-less legacy manifest may verify under any still-trusted key, and
 * removing a key from the ring revokes everything it signed. Every rewrite of
 * the id (hop to another trusted key, point at an unknown key) fails closed.
 */
class SignedManifestRotationTest {

    private KeyPair key1;                 // previous vendor key
    private KeyPair key2;                 // current vendor key
    private byte[] modelBytes;
    private String sha256;

    @BeforeEach
    void setUp() throws Exception {
        key1 = rsa();
        key2 = rsa();
        modelBytes = "model-payload-v1".getBytes(StandardCharsets.UTF_8);
        sha256 = InMemoryModelStore.sha256Hex(modelBytes);
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static SignedModelManifest signed(KeyPair by, String version) {
        return SignedModelManifest.sign("minifasnet", version, InMemoryModelStore.sha256Hex(
                "model-payload-v1".getBytes(StandardCharsets.UTF_8)), "1.0.0", by.getPrivate());
    }

    /** Same signature/key, attacker-chosen id — what a manifest rewrite looks like. */
    private static SignedModelManifest withKeyId(SignedModelManifest manifest, String keyId) {
        return new SignedModelManifest(manifest.modelId(), manifest.version(),
                manifest.sha256Hex(), manifest.minAppVersion(), manifest.signatureHex(), keyId);
    }

    private static SignedManifestModelStore ring(String appVersion, PublicKey... keys) {
        return new SignedManifestModelStore(appVersion, keys);
    }

    @Test
    void signDerivesAStableKeyIdFromTheSigningKey() {
        SignedModelManifest manifest = signed(key1, "2026.10.1");

        assertEquals(SignedModelManifest.keyIdOf(key1.getPublic()), manifest.keyId(),
                "the id must be derivable from the public half the device holds");
        assertFalse(manifest.keyId().isBlank());
        assertNotEquals(SignedModelManifest.keyIdOf(key2.getPublic()), manifest.keyId(),
                "different vendor keys must get different ids");

        SignedModelManifest legacy = SignedModelManifest.sign("minifasnet", "2026.10.1",
                sha256, "1.0.0", "", key1.getPrivate());
        assertEquals("", legacy.keyId(), "explicit empty id produces the legacy shape");
        assertTrue(legacy.verifySignature(key1.getPublic()),
                "kid-less manifests keep verifying under their key");
    }

    @Test
    void keyringTrustsBothThePreviousAndTheCurrentKey() {
        SignedManifestModelStore store = ring("1.0.0", key1.getPublic(), key2.getPublic());

        assertTrue(store.activate(signed(key1, "2026.10.1"), modelBytes),
                "manifest from the previous key still verifies during the rotation window");
        assertEquals("2026.10.1", store.activeModel().orElseThrow().version());

        assertTrue(store.activate(signed(key2, "2026.10.2"), modelBytes),
                "manifest from the current key verifies too");
        assertEquals("2026.10.2", store.activeModel().orElseThrow().version());
    }

    @Test
    void legacyManifestWithoutKeyIdVerifiesUnderAnyTrustedKey() {
        SignedModelManifest legacy = SignedModelManifest.sign("minifasnet", "2026.10.1",
                sha256, "1.0.0", "", key1.getPrivate());

        SignedManifestModelStore window = ring("1.0.0", key1.getPublic(), key2.getPublic());
        assertTrue(window.activate(legacy, modelBytes),
                "a pre-keyid manifest stays installable while its key is still trusted");
        assertFalse(window.activate(legacy, new byte[0]),
                "the empty-payload guard still applies");
    }

    @Test
    void revokingARotatedOutKeyRefusesEverythingItSigned() {
        SignedManifestModelStore onlyCurrent = ring("1.0.0", key2.getPublic());

        assertFalse(onlyCurrent.activate(signed(key1, "2026.10.1"), modelBytes),
                "a manifest naming the retired key must be refused once the key is gone");
        SignedModelManifest legacy = SignedModelManifest.sign("minifasnet", "2026.10.1",
                sha256, "1.0.0", "", key1.getPrivate());
        assertFalse(onlyCurrent.activate(legacy, modelBytes),
                "kid-less manifests signed by the retired key must be refused too");
        assertTrue(onlyCurrent.activeModel().isEmpty(), "nothing may install after revocation");

        // …while the very same manifest still verifies during the window.
        assertTrue(ring("1.0.0", key1.getPublic(), key2.getPublic())
                        .activate(signed(key1, "2026.10.1"), modelBytes),
                "revocation is ring membership, not a property of the manifest");
    }

    @Test
    void rewrittenKeyIdCannotHopToAnotherTrustedKey() {
        SignedModelManifest forged = withKeyId(signed(key1, "2026.10.1"),
                SignedModelManifest.keyIdOf(key2.getPublic()));

        assertFalse(forged.verifySignature(key1.getPublic()),
                "id names key2, so the manifest must not verify under key1 either");
        assertFalse(forged.verifySignature(key2.getPublic()),
                "id matches key2 but the signature is key1's");

        SignedManifestModelStore store = ring("1.0.0", key1.getPublic(), key2.getPublic());
        assertFalse(store.activate(forged, modelBytes),
                "an id rewritten to another trusted key must not validate the original signature");
        assertTrue(store.activeModel().isEmpty());
    }

    @Test
    void unknownKeyIdIsRefusedWithoutFallingBackToOtherKeys() throws Exception {
        KeyPair unknown = rsa();
        SignedModelManifest forged = withKeyId(signed(key1, "2026.10.1"),
                SignedModelManifest.keyIdOf(unknown.getPublic()));

        SignedManifestModelStore store = ring("1.0.0", key1.getPublic(), key2.getPublic());
        assertFalse(store.activate(forged, modelBytes),
                "the id names no trusted key — refuse, even though the signature "
                        + "would verify under key1 which is trusted");
        assertTrue(store.activeModel().isEmpty());
    }

    @Test
    void emptyOrNullTrustedKeyRingsAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new SignedManifestModelStore("1.0.0"),
                "an empty keyring is a configuration error, not a silent refuse-everything");
        assertThrows(NullPointerException.class,
                () -> new SignedManifestModelStore("1.0.0", (PublicKey) null),
                "a null trusted key is a configuration error");
    }
}
