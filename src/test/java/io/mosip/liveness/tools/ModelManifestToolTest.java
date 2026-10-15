package io.mosip.liveness.tools;

import io.mosip.liveness.android.InMemoryModelStore;
import io.mosip.liveness.android.ModelStore;
import io.mosip.liveness.android.SignedManifestModelStore;
import io.mosip.liveness.android.SignedModelManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deployment-side proof for {@link ModelManifestTool}: what the tool writes is
 * byte-for-byte the format {@code AndroidModelStore} reads (same five keys,
 * same {@code Properties.load} call), and what it writes activates in the
 * engine's {@link SignedManifestModelStore} — while tampering, rewritten
 * fields, missing files and usage mistakes all fail closed with no manifest
 * left behind.
 */
class ModelManifestToolTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    private int run(String... args) {
        return ModelManifestTool.run(args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }

    private String errText() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- helpers

    private Path keygen(String name) throws IOException {
        Path privatePem = dir.resolve(name + "-private.pem");
        Path publicPem = dir.resolve(name + "-public.pem");
        assertEquals(0, run("keygen", "--private", privatePem.toString(),
                "--public", publicPem.toString()), errText());
        return privatePem;
    }

    private Path writeArtifact(String name, byte[] payload) throws IOException {
        Path artifact = dir.resolve(name);
        Files.write(artifact, payload);
        return artifact;
    }

    /** Loads the manifest exactly the way AndroidModelStore.install does. */
    private Properties loadManifest(Path manifestFile) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(manifestFile)) {
            properties.load(in);
        }
        return properties;
    }

    private static SignedModelManifest fromProperties(Properties properties) {
        return new SignedModelManifest(
                properties.getProperty("modelId"),
                properties.getProperty("version"),
                properties.getProperty("sha256"),
                properties.getProperty("minAppVersion"),
                properties.getProperty("signature"),
                properties.getProperty("keyId"));   // null loads from pre-keyid files → legacy ""
    }

    /** Builds the PKCS#1 RSAPrivateKey DER (legacy {@code openssl genrsa}) from a CRT key. */
    private static byte[] pkcs1Bytes(RSAPrivateCrtKey key) {
        byte[] content = concat(
                derInt(BigInteger.ZERO), derInt(key.getModulus()), derInt(key.getPublicExponent()),
                derInt(key.getPrivateExponent()), derInt(key.getPrimeP()), derInt(key.getPrimeQ()),
                derInt(key.getPrimeExponentP()), derInt(key.getPrimeExponentQ()),
                derInt(key.getCrtCoefficient()));
        if (content.length < 0x80) {
            return concat(new byte[]{0x30, (byte) content.length}, content);
        }
        return concat(new byte[]{0x30, (byte) 0x82, (byte) (content.length >> 8), (byte) content.length},
                content);
    }

    private static byte[] derInt(BigInteger value) {
        byte[] raw = value.toByteArray();          // already minimal two's-complement INTEGER
        if (raw.length < 0x80) {
            return concat(new byte[]{0x02, (byte) raw.length}, raw);
        }
        return concat(new byte[]{0x02, (byte) 0x82, (byte) (raw.length >> 8), (byte) raw.length}, raw);
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void signWritesThePropertiesManifestAndroidModelStoreReads() throws Exception {
        Path privatePem = keygen("vendor");
        byte[] payload = "FAKE-ONNX-MODEL-BYTES-v2026.10.1".getBytes(StandardCharsets.UTF_8);
        Path artifact = writeArtifact("model.onnx", payload);
        Path manifestFile = dir.resolve("model.manifest.properties");

        assertEquals(0, run("sign", "--model-id", "minifasnet", "--version", "2026.10.1",
                "--key", privatePem.toString(), "--out", manifestFile.toString(),
                artifact.toString()), errText());

        // The exact key set AndroidModelStore reads — all six present, no extras.
        Properties properties = loadManifest(manifestFile);
        assertEquals("minifasnet", properties.getProperty("modelId"));
        assertEquals("2026.10.1", properties.getProperty("version"));
        assertEquals(InMemoryModelStore.sha256Hex(payload), properties.getProperty("sha256"));
        assertNotNull(properties.getProperty("minAppVersion"),
                "minAppVersion must be present even when empty — a missing key loads as null "
                        + "and the store's signature check refuses null fields");
        assertEquals("", properties.getProperty("minAppVersion"));
        assertNotNull(properties.getProperty("keyId"),
                "keyId must be present — it selects the verifying key after a rotation");
        assertEquals(SignedModelManifest.keyIdOf(
                        ModelManifestTool.loadPublicKey(dir.resolve("vendor-public.pem"))),
                properties.getProperty("keyId"),
                "the stamped id must be the one derivable from the public key the device trusts");
        assertNotNull(properties.getProperty("signature"));

        // ...and it activates in the engine store under the public half of the key.
        SignedManifestModelStore store = new SignedManifestModelStore(
                ModelManifestTool.loadPublicKey(dir.resolve("vendor-public.pem")), "1.0.0");
        assertTrue(store.activate(fromProperties(properties), payload),
                "a freshly signed manifest must install: " + errText());
        ModelStore.ActiveModel active = store.activeModel().orElseThrow();
        assertEquals("minifasnet", active.modelId());
        assertEquals("2026.10.1", active.version());
        assertEquals(InMemoryModelStore.sha256Hex(payload), active.sha256Hex());
    }

    @Test
    void tamperedArtifactIsRefusedAndTheActiveModelIsKept() throws Exception {
        Path privatePem = keygen("vendor");
        byte[] payload = "ORIGINAL-MODEL-BYTES".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "TAMPERED-MODEL-BYTES".getBytes(StandardCharsets.UTF_8);
        Path manifestFile = dir.resolve("model.manifest.properties");
        assertEquals(0, run("sign", "--model-id", "minifasnet", "--version", "2026.10.1",
                "--key", privatePem.toString(), "--out", manifestFile.toString(),
                writeArtifact("model.onnx", payload).toString()), errText());

        SignedManifestModelStore store = new SignedManifestModelStore(
                ModelManifestTool.loadPublicKey(dir.resolve("vendor-public.pem")), "1.0.0");
        assertTrue(store.activate(fromProperties(loadManifest(manifestFile)), payload));
        String activeSha = store.activeModel().orElseThrow().sha256Hex();

        assertFalse(store.activate(fromProperties(loadManifest(manifestFile)), tampered),
                "same manifest, different bytes — the digest binding must refuse it");
        assertEquals(activeSha, store.activeModel().orElseThrow().sha256Hex(),
                "a refused update must leave the active model untouched");
    }

    @Test
    void rewritingAManifestFieldInTheFileBreaksTheSignature() throws Exception {
        Path privatePem = keygen("vendor");
        Path artifact = writeArtifact("model.onnx", "MODEL-BYTES".getBytes(StandardCharsets.UTF_8));
        Path manifestFile = dir.resolve("model.manifest.properties");
        assertEquals(0, run("sign", "--model-id", "minifasnet", "--version", "2026.10.1",
                "--key", privatePem.toString(), "--out", manifestFile.toString(),
                artifact.toString()), errText());

        String text = Files.readString(manifestFile, StandardCharsets.US_ASCII);
        Files.writeString(manifestFile, text.replace("2026.10.1", "2099.0.0"),
                StandardCharsets.US_ASCII);

        SignedManifestModelStore store = new SignedManifestModelStore(
                ModelManifestTool.loadPublicKey(dir.resolve("vendor-public.pem")), "1.0.0");
        assertFalse(store.activate(fromProperties(loadManifest(manifestFile)),
                        Files.readAllBytes(artifact)),
                "an attacker-edited version label must not survive re-reading the file");
    }

    @Test
    void hostileFieldValuesRoundTripByteForByteThroughTheEscaper() throws Exception {
        Path privatePem = keygen("vendor");
        String modelId = "minifasnet: v2 #beta=a\\b c=d é 模型";
        String version = "2026.10.1 rc1 ";
        byte[] payload = "MODEL-BYTES".getBytes(StandardCharsets.UTF_8);
        Path manifestFile = dir.resolve("model.manifest.properties");

        assertEquals(0, run("sign", "--model-id", modelId, "--version", version,
                "--key", privatePem.toString(), "--out", manifestFile.toString(),
                writeArtifact("model.onnx", payload).toString()), errText());

        Properties properties = loadManifest(manifestFile);
        assertEquals(modelId, properties.getProperty("modelId"),
                "separators, comments, backslashes and non-ASCII must load back identical");
        assertEquals(version, properties.getProperty("version"),
                "trailing space must survive Properties.load");

        SignedManifestModelStore store = new SignedManifestModelStore(
                ModelManifestTool.loadPublicKey(dir.resolve("vendor-public.pem")), "1.0.0");
        assertTrue(store.activate(fromProperties(properties), payload),
                "escaping must not perturb the signed canonical payload: " + errText());
    }

    @Test
    void minAppVersionFlagDrivesTheClientsGate() throws Exception {
        Path privatePem = keygen("vendor");
        byte[] payload = "MODEL-BYTES".getBytes(StandardCharsets.UTF_8);
        Path artifact = writeArtifact("model.onnx", payload);
        Path manifestFile = dir.resolve("model.manifest.properties");

        assertEquals(0, run("sign", "--model-id", "minifasnet", "--version", "2026.10.1",
                "--min-app-version", "2.0.0",
                "--key", privatePem.toString(), "--out", manifestFile.toString(),
                artifact.toString()), errText());
        assertEquals("2.0.0", loadManifest(manifestFile).getProperty("minAppVersion"));

        java.security.PublicKey publicKey = ModelManifestTool.loadPublicKey(dir.resolve("vendor-public.pem"));
        SignedModelManifest manifest = fromProperties(loadManifest(manifestFile));
        assertFalse(new SignedManifestModelStore(publicKey, "1.0.0").activate(manifest, payload),
                "app below the floor must refuse the model");
        assertTrue(new SignedManifestModelStore(publicKey, "2.1.0").activate(manifest, payload),
                "app at/above the floor must activate it");
        assertTrue(new SignedManifestModelStore(publicKey, null).activate(manifest, payload),
                "gate disabled (no current app version) must activate");
    }

    @Test
    void legacyPkcs1PemKeysAreAccepted() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();

        Path pkcs1Pem = dir.resolve("legacy-private.pem");
        byte[] pkcs1 = pkcs1Bytes((RSAPrivateCrtKey) pair.getPrivate());
        String base64 = java.util.Base64.getMimeEncoder(64,
                "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pkcs1);
        Files.writeString(pkcs1Pem,
                "-----BEGIN RSA PRIVATE KEY-----\n" + base64 + "\n-----END RSA PRIVATE KEY-----\n",
                StandardCharsets.US_ASCII);

        byte[] payload = "MODEL-BYTES".getBytes(StandardCharsets.UTF_8);
        Path manifestFile = dir.resolve("model.manifest.properties");
        assertEquals(0, run("sign", "--model-id", "minifasnet", "--version", "2026.10.1",
                "--key", pkcs1Pem.toString(), "--out", manifestFile.toString(),
                writeArtifact("model.onnx", payload).toString()), errText());

        SignedManifestModelStore store = new SignedManifestModelStore(pair.getPublic(), "1.0.0");
        assertTrue(store.activate(fromProperties(loadManifest(manifestFile)), payload),
                "a PKCS#1 key must sign like PKCS#8: " + errText());
    }

    @Test
    void usageProblemsExitTwoAndOperationalProblemsExitOneWithNoManifest() throws Exception {
        Path privatePem = keygen("vendor");
        Path manifestFile = dir.resolve("model.manifest.properties");

        // Missing required option → usage (2), nothing written.
        assertEquals(2, run("sign", "--model-id", "minifasnet", "--version", "2026.10.1",
                "--out", manifestFile.toString(), "some-artifact"), "missing --key: " + errText());
        assertTrue(errText().contains("--key"), errText());
        assertFalse(Files.exists(manifestFile));

        // Unknown option → usage (2).
        assertEquals(2, run("sign", "--bogus", "x"), "unknown option: " + errText());

        // Missing artifact → operational (1), message, no manifest.
        assertEquals(1, run("sign", "--model-id", "m", "--version", "v",
                "--key", privatePem.toString(), "--out", manifestFile.toString(),
                dir.resolve("does-not-exist.onnx").toString()), "missing artifact: " + errText());
        assertTrue(errText().contains("not found"), errText());
        assertFalse(Files.exists(manifestFile));

        // Empty artifact → operational (1), no manifest.
        Path emptyArtifact = Files.createFile(dir.resolve("empty.onnx"));
        assertEquals(1, run("sign", "--model-id", "m", "--version", "v",
                "--key", privatePem.toString(), "--out", manifestFile.toString(),
                emptyArtifact.toString()), "empty artifact: " + errText());
        assertFalse(Files.exists(manifestFile));

        // Non-key file as --key → operational (1), no manifest.
        assertEquals(1, run("sign", "--model-id", "m", "--version", "v",
                "--key", emptyArtifact.toString(), "--out", manifestFile.toString(),
                writeArtifact("real.onnx", "BYTES".getBytes(StandardCharsets.UTF_8)).toString()),
                "non-PEM key: " + errText());
        assertFalse(Files.exists(manifestFile));
    }

    @Test
    void keygenRefusesWeakKeysAndOverwrites() throws Exception {
        Path privatePem = dir.resolve("k-private.pem");
        Path publicPem = dir.resolve("k-public.pem");

        assertEquals(1, run("keygen", "--private", privatePem.toString(),
                "--public", publicPem.toString(), "--bits", "1024"),
                "sub-2048 keys must be refused: " + errText());
        assertFalse(Files.exists(privatePem));

        assertEquals(0, run("keygen", "--private", privatePem.toString(),
                "--public", publicPem.toString()), errText());
        String privateText = Files.readString(privatePem, StandardCharsets.US_ASCII);
        assertTrue(privateText.startsWith("-----BEGIN PRIVATE KEY-----"), privateText);
        assertTrue(Files.readString(publicPem, StandardCharsets.US_ASCII)
                .startsWith("-----BEGIN PUBLIC KEY-----"));

        // Never clobber an existing key.
        assertEquals(1, run("keygen", "--private", privatePem.toString(),
                "--public", publicPem.toString()), "overwrite refusal: " + errText());
        assertEquals(privateText, Files.readString(privatePem, StandardCharsets.US_ASCII),
                "the original key must be untouched");
    }

    @Test
    void keyIdInTheFileSelectsTheSigningKeyAcrossRotation() throws Exception {
        Path oldPrivate = keygen("vendor");
        keygen("vendor-next");   // second vendor keypair for the rotation window
        byte[] payload = "MODEL-BYTES".getBytes(StandardCharsets.UTF_8);
        Path artifact = writeArtifact("model.onnx", payload);
        Path manifestFile = dir.resolve("model.manifest.properties");

        assertEquals(0, run("sign", "--model-id", "minifasnet", "--version", "2026.10.1",
                "--key", oldPrivate.toString(), "--out", manifestFile.toString(),
                artifact.toString()), errText());

        SignedModelManifest manifest = fromProperties(loadManifest(manifestFile));
        java.security.PublicKey oldPublic =
                ModelManifestTool.loadPublicKey(dir.resolve("vendor-public.pem"));
        java.security.PublicKey nextPublic =
                ModelManifestTool.loadPublicKey(dir.resolve("vendor-next-public.pem"));
        assertEquals(SignedModelManifest.keyIdOf(oldPublic), manifest.keyId());

        // Rotation window: both keys trusted — the old-signed manifest installs.
        assertTrue(new SignedManifestModelStore("1.0.0", nextPublic, oldPublic)
                        .activate(manifest, payload),
                "both keys trusted during the window: " + errText());
        // After rotation only the new key is trusted — the very same file is refused.
        assertFalse(new SignedManifestModelStore("1.0.0", nextPublic)
                        .activate(manifest, payload),
                "once the signing key leaves the ring its manifests must be refused");
    }

    @Test
    void manifestFromBeforeKeyIdStillInstalls() throws Exception {
        Path privatePem = keygen("vendor");
        byte[] payload = "MODEL-BYTES".getBytes(StandardCharsets.UTF_8);
        Path artifact = writeArtifact("model.onnx", payload);
        Path manifestFile = dir.resolve("model.manifest.properties");
        assertEquals(0, run("sign", "--model-id", "minifasnet", "--version", "2026.10.1",
                "--key", privatePem.toString(), "--out", manifestFile.toString(),
                artifact.toString()), errText());

        // Rewrite the file the way a pre-keyid deployment shipped it: no keyId line.
        String text = Files.readString(manifestFile, StandardCharsets.US_ASCII);
        String legacy = text.replaceAll("(?m)^keyId=.*\n", "");
        assertNotEquals(text, legacy,
                "the generated file must contain a keyId line for this test to strip");
        Files.writeString(manifestFile, legacy, StandardCharsets.US_ASCII);

        Properties properties = loadManifest(manifestFile);
        assertNull(properties.getProperty("keyId"), "old files simply lack the key");
        SignedManifestModelStore store = new SignedManifestModelStore(
                ModelManifestTool.loadPublicKey(dir.resolve("vendor-public.pem")), "1.0.0");
        assertTrue(store.activate(fromProperties(properties), payload),
                "a pre-keyid manifest must keep installing: " + errText());
    }
}
