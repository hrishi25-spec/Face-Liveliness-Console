package io.mosip.liveness.tools;

import io.mosip.liveness.android.InMemoryModelStore;
import io.mosip.liveness.android.SignedModelManifest;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Properties;

/**
 * Deployment-side generator for the vendor-signed model manifest (spec §13
 * {@code manifest {modelId, version, sha256, signature, minAppVersion}}): the
 * file format {@code AndroidModelStore.install(...)} reads on the client.
 *
 * <p>{@code sign} hashes the model artifact (SHA-256, the exact digest the
 * store re-derives at activation), signs {@code modelId|version|sha256|
 * minAppVersion} with SHA256withRSA and writes the five-key properties file —
 * deterministic byte-for-byte so two runs over the same inputs diff clean.
 * Before returning it re-reads what it wrote and verifies the signature
 * against the key it just used, so an escaping or I/O bug fails the command
 * instead of shipping a manifest the device will refuse.</p>
 *
 * <p>{@code keygen} bootstraps a fresh RSA vendor keypair (PKCS#8 / X.509
 * PEM). Both commands fail closed: usage problems exit 2, operational
 * problems exit 1, and neither ever leaves a half-written manifest behind.</p>
 *
 * <pre>
 *   java -cp target/pad-liveness-backend-*.jar \
 *       io.mosip.liveness.tools.ModelManifestTool sign \
 *       --model-id minifasnet --version 2026.10.1 \
 *       --key vendor-private.pem --out model.manifest.properties model.onnx
 * </pre>
 */
public final class ModelManifestTool {

    private ModelManifestTool() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Exit codes: 0 ok, 2 usage, 1 operational failure. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            return usage(err);
        }
        switch (args[0]) {
            case "sign" -> {
                return sign(args, out, err);
            }
            case "keygen" -> {
                return keygen(args, out, err);
            }
            default -> {
                return usage(err);
            }
        }
    }

    private static int sign(String[] args, PrintStream out, PrintStream err) {
        String modelId = null;
        String version = null;
        String minAppVersion = "";
        Path keyFile = null;
        Path outFile = null;
        Path artifact = null;
        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--model-id" -> modelId = args[++i];
                    case "--version" -> version = args[++i];
                    case "--min-app-version" -> minAppVersion = args[++i];
                    case "--key" -> keyFile = Path.of(args[++i]);
                    case "--out" -> outFile = Path.of(args[++i]);
                    default -> {
                        if (args[i].startsWith("--")) {
                            err.println("unknown option: " + args[i]);
                            return usage(err);
                        }
                        if (artifact != null) {
                            err.println("only one model artifact may be given");
                            return usage(err);
                        }
                        artifact = Path.of(args[i]);
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException | java.nio.file.InvalidPathException e) {
            err.println("bad arguments: " + e);
            return usage(err);
        }
        if (modelId == null || version == null || keyFile == null || outFile == null || artifact == null) {
            err.println("sign requires --model-id, --version, --key, --out and a model artifact");
            return usage(err);
        }
        if (!Files.isRegularFile(artifact)) {
            err.println("model artifact not found: " + artifact);
            return 1;
        }
        try {
            byte[] payload = Files.readAllBytes(artifact);
            if (payload.length == 0) {
                err.println("model artifact is empty: " + artifact);
                return 1;
            }
            PrivateKey privateKey = loadPrivateKey(keyFile);
            String sha256 = InMemoryModelStore.sha256Hex(payload);
            SignedModelManifest manifest =
                    SignedModelManifest.sign(modelId, version, sha256, minAppVersion, privateKey);
            byte[] text = propertiesText(manifest).getBytes(StandardCharsets.US_ASCII);
            Files.write(outFile, text, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // Self-check: re-read the bytes on disk and verify them under the
            // signing key, so a write/escaping defect fails here, not on-device.
            Properties reloaded = new Properties();
            try (var in = Files.newInputStream(outFile)) {
                reloaded.load(in);
            }
            SignedModelManifest reread = fromProperties(reloaded);
            if (!manifest.keyId().equals(reread.keyId())
                    || !reread.verifySignature(SignedModelManifest.publicKeyOf(privateKey))) {
                Files.deleteIfExists(outFile);
                err.println("self-check failed: written manifest does not verify — nothing shipped");
                return 1;
            }
            out.println("wrote " + outFile);
            out.println("  modelId=" + modelId + " version=" + version + " sha256=" + sha256);
            return 0;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            err.println("sign failed: " + e);
            try {
                Files.deleteIfExists(outFile);
            } catch (IOException ignored) {
                // best effort — the failure is already reported
            }
            return 1;
        }
    }

    private static int keygen(String[] args, PrintStream out, PrintStream err) {
        Path privateFile = null;
        Path publicFile = null;
        int bits = 2048;
        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--private" -> privateFile = Path.of(args[++i]);
                    case "--public" -> publicFile = Path.of(args[++i]);
                    case "--bits" -> bits = Integer.parseInt(args[++i]);
                    default -> {
                        err.println("unknown option: " + args[i]);
                        return usage(err);
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException | java.nio.file.InvalidPathException e) {
            err.println("bad arguments: " + e);
            return usage(err);
        }
        if (privateFile == null || publicFile == null) {
            err.println("keygen requires --private and --public output paths");
            return usage(err);
        }
        if (bits < 2048) {
            err.println("refusing " + bits + "-bit RSA keys: use at least 2048");
            return 1;
        }
        if (Files.exists(privateFile) || Files.exists(publicFile)) {
            err.println("refusing to overwrite an existing key file");
            return 1;
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            KeyPair pair = generator.generateKeyPair();
            writePem(privateFile, "PRIVATE KEY", pair.getPrivate().getEncoded(), true);
            writePem(publicFile, "PUBLIC KEY", pair.getPublic().getEncoded(), false);
            out.println("wrote " + privateFile + " (PKCS#8, " + bits + "-bit) and " + publicFile);
            return 0;
        } catch (IOException | GeneralSecurityException e) {
            err.println("keygen failed: " + e);
            return 1;
        }
    }

    /**
     * The exact file {@code AndroidModelStore} loads: the five keys it reads,
     * in fixed order, ASCII-escaped. {@code minAppVersion} is always present —
     * even when empty — because the client reads a <em>missing</em> key as
     * {@code null}, which the store's signature check refuses (null field),
     * while an empty value means "no version floor" and passes.
     */
    static String propertiesText(SignedModelManifest manifest) {
        StringBuilder sb = new StringBuilder();
        sb.append("# MOSIP liveness signed model manifest (spec 13) - written by ModelManifestTool.\n");
        sb.append("# Signature: SHA256withRSA over modelId|version|sha256|minAppVersion.\n");
        sb.append("# keyId: sha256 hex of the signing key's X.509 encoding; selects the verifying\n");
        sb.append("# key after a signing-key rotation. Blank/absent = legacy manifest.\n");
        sb.append("modelId=").append(escape(manifest.modelId())).append('\n');
        sb.append("version=").append(escape(manifest.version())).append('\n');
        sb.append("sha256=").append(escape(manifest.sha256Hex())).append('\n');
        sb.append("minAppVersion=").append(escape(manifest.minAppVersion())).append('\n');
        sb.append("keyId=").append(escape(manifest.keyId())).append('\n');
        sb.append("signature=").append(escape(manifest.signatureHex())).append('\n');
        return sb.toString();
    }

    /** Rebuild a manifest the way `AndroidModelStore` does (null on a missing key). */
    static SignedModelManifest fromProperties(Properties properties) {
        return new SignedModelManifest(
                properties.getProperty("modelId"),
                properties.getProperty("version"),
                properties.getProperty("sha256"),
                properties.getProperty("minAppVersion"),
                properties.getProperty("signature"),
                properties.getProperty("keyId"));
    }

    /**
     * java.util.Properties escaping: every space, the separators, control
     * characters and anything non-ASCII are escaped, so a hostile modelId or
     * version label cannot forge a {@code key=value} line or a comment — and
     * the value loads back byte-identical, which the signature depends on.
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() * 2);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case ' ' -> sb.append("\\ ");
                case '=', ':', '#', '!' -> sb.append('\\').append(c);
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        sb.append(String.format("\\u%04X", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Load an RSA private key from PKCS#8 ({@code BEGIN PRIVATE KEY}) or PKCS#1 ({@code BEGIN RSA PRIVATE KEY}) PEM. */
    static PrivateKey loadPrivateKey(Path file) throws IOException, GeneralSecurityException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        byte[] der;
        if (text.contains("-----BEGIN RSA PRIVATE KEY-----")) {
            der = wrapPkcs1(pemBlock(text, "-----BEGIN RSA PRIVATE KEY-----", "-----END RSA PRIVATE KEY-----"));
        } else if (text.contains("-----BEGIN PRIVATE KEY-----")) {
            der = pemBlock(text, "-----BEGIN PRIVATE KEY-----", "-----END PRIVATE KEY-----");
        } else {
            throw new IOException(file + ": expected a PEM private key (PKCS#8 or PKCS#1)");
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** Load the vendor public key (X.509 {@code BEGIN PUBLIC KEY} PEM) — what the device trusts. */
    static PublicKey loadPublicKey(Path file) throws IOException, GeneralSecurityException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        byte[] der = pemBlock(text, "-----BEGIN PUBLIC KEY-----", "-----END PUBLIC KEY-----");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static byte[] pemBlock(String text, String begin, String end) throws IOException {
        int s = text.indexOf(begin);
        int e = text.indexOf(end);
        if (s < 0 || e < s) {
            throw new IOException("missing " + begin + " block");
        }
        String base64 = text.substring(s + begin.length(), e).replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException bad) {
            throw new IOException("malformed base64 in " + begin + " block");
        }
    }

    /**
     * Wrap a PKCS#1 {@code RSAPrivateKey} in the PKCS#8 structure
     * {@code SEQ { 0, rsaEncryption, OCTET STRING pkcs1 }} so legacy keys from
     * {@code openssl genrsa} load like PKCS#8 ones.
     */
    static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        byte[] octet = concat(new byte[]{0x04}, derLength(pkcs1.length), pkcs1);
        byte[] content = concat(concat(version, algorithm), octet);
        return concat(new byte[]{0x30}, derLength(content.length), content);
    }

    private static byte[] derLength(int length) {
        if (length < 0x80) {
            return new byte[]{(byte) length};
        }
        if (length > 0xffff) {
            throw new IllegalArgumentException("DER item too long: " + length);
        }
        return new byte[]{(byte) 0x82, (byte) (length >> 8), (byte) length};
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

    private static void writePem(Path file, String label, byte[] der, boolean ownerOnly)
            throws IOException {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        String text = "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
        Files.write(file, text.getBytes(StandardCharsets.US_ASCII), StandardOpenOption.CREATE_NEW);
        if (ownerOnly) {
            try {
                Files.setPosixFilePermissions(file,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException notPosix) {
                // non-POSIX filesystem — the key file still exists with default permissions
            }
        }
    }

    private static int usage(PrintStream err) {
        err.println("usage:");
        err.println("  ModelManifestTool sign --model-id <id> --version <ver> --key <private.pem>"
                + " --out <manifest.properties> [--min-app-version <ver>] <model-artifact>");
        err.println("  ModelManifestTool keygen --private <out.pem> --public <out.pem> [--bits <n>]");
        err.println("sign   writes the properties manifest AndroidModelStore installs (sha256 + RSA signature)");
        err.println("keygen bootstraps an RSA vendor keypair (PKCS#8 private / X.509 public PEM)");
        return 2;
    }
}
