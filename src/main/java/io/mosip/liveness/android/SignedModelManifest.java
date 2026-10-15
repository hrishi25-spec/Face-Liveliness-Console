package io.mosip.liveness.android;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.HexFormat;

/**
 * Vendor-signed model manifest (spec §13 {@code manifest {modelId, version,
 * sha256, signature, minAppVersion}} — "verify signature + hash → atomic
 * swap"). The canonical payload covers every other field, so any tamper —
 * model bytes (via the embedded sha256), version label, or the min-app gate —
 * invalidates the signature and the store refuses the update fail-closed.
 *
 * <p><b>Key ids and rotation.</b> {@code keyId} names the vendor key that
 * signed the manifest — derived as the SHA-256 (hex) of the key's X.509
 * {@code SubjectPublicKeyInfo} DER, so deployments re-derive it externally
 * ({@code openssl pkey -pubin -in vendor-public.pem -outform DER | sha256sum})
 * and it is stable across PEM encodings. It deliberately sits <em>outside</em>
 * {@link #canonicalPayload()}: the payload stays spec §13's four fields, so
 * manifests written before key ids verify unchanged, and old clients (which
 * never read the field) keep verifying new manifests. The id selects which
 * trusted key the store verifies against — a wrong selection fails the
 * signature check under that key, an unknown id is refused without falling
 * back, and rotating a key out of the trusted set revokes everything it
 * signed. A blank id means a legacy manifest: the store may verify it against
 * any key still trusted (retire a key only once its legacy manifests are
 * re-signed).
 *
 * <p>Without this, a stored sha256 proves nothing: whoever can rewrite the
 * model file can rewrite its recorded hash too. The vendor signature is what
 * anchors the digest.
 *
 * @param modelId       vendor model identifier
 * @param version       model version label (recorded in evidence)
 * @param sha256Hex     hex digest of the exact model payload this manifest authorises
 * @param minAppVersion minimum app version the model requires (blank = no floor)
 * @param signatureHex  SHA256withRSA signature over {@link #canonicalPayload()}
 * @param keyId         id of the signing key (see {@link #keyIdOf}; blank = legacy manifest)
 */
public record SignedModelManifest(
        String modelId,
        String version,
        String sha256Hex,
        String minAppVersion,
        String signatureHex,
        String keyId) {

    /**
     * Legacy five-field form — a manifest written before key ids existed
     * (and the shape pre-keyid readers still construct). Blank key id: the
     * store verifies it against any key it still trusts.
     */
    public SignedModelManifest(String modelId, String version, String sha256Hex,
                               String minAppVersion, String signatureHex) {
        this(modelId, version, sha256Hex, minAppVersion, signatureHex, "");
    }

    public SignedModelManifest {
        // A missing keyId line in an old manifest file loads as null and must
        // mean "legacy", not "refuse" — unlike minAppVersion, whose null
        // (missing key) still fails closed in verifySignature.
        keyId = keyId == null ? "" : keyId;
    }

    /** Canonical payload the vendor signs and the store re-derives. */
    public String canonicalPayload() {
        return String.join("|", modelId, version, sha256Hex, minAppVersion == null ? "" : minAppVersion);
    }

    /**
     * Sign a manifest (vendor / offline-sideload side, spec §13 both paths),
     * stamping the key id derived from the signing key so the manifest names
     * the key it needs at verification time.
     *
     * @throws IllegalArgumentException on null/blank required fields
     */
    public static SignedModelManifest sign(String modelId, String version, String sha256Hex,
                                           String minAppVersion, PrivateKey vendorKey) {
        return sign(modelId, version, sha256Hex, minAppVersion, keyIdOf(vendorKey), vendorKey);
    }

    /**
     * Sign with an explicit key id — pass {@code ""} to produce the legacy
     * kid-less form (still verified under any trusted key after rotation).
     *
     * @throws IllegalArgumentException on null/blank required fields
     */
    public static SignedModelManifest sign(String modelId, String version, String sha256Hex,
                                           String minAppVersion, String keyId, PrivateKey vendorKey) {
        if (isBlank(modelId) || isBlank(version) || isBlank(sha256Hex)) {
            throw new IllegalArgumentException("modelId, version and sha256 are required");
        }
        if (vendorKey == null) {
            throw new IllegalArgumentException("vendorKey is required");
        }
        String payload = String.join("|", modelId, version, sha256Hex,
                minAppVersion == null ? "" : minAppVersion);
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(vendorKey);
            sig.update(payload.getBytes(StandardCharsets.UTF_8));
            return new SignedModelManifest(modelId, version, sha256Hex,
                    minAppVersion == null ? "" : minAppVersion, HexFormat.of().formatHex(sig.sign()),
                    keyId == null ? "" : keyId);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("manifest signing failed", e);
        }
    }

    /**
     * Verify the signature under the vendor key. Fail closed: null key,
     * missing required fields, or malformed signature hex all return false —
     * never throw. When a key id is present it must name <em>this</em> key
     * (defence in depth: the store already selects the key by id).
     */
    public boolean verifySignature(PublicKey vendorKey) {
        if (vendorKey == null || isBlank(modelId) || isBlank(version)
                || isBlank(sha256Hex) || minAppVersion == null || signatureHex == null) {
            return false;
        }
        if (!keyId.isEmpty() && !keyId.equals(keyIdOf(vendorKey))) {
            return false;
        }
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(vendorKey);
            sig.update(canonicalPayload().getBytes(StandardCharsets.UTF_8));
            return sig.verify(HexFormat.of().parseHex(signatureHex));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Key id: SHA-256 hex of the key's X.509 encoding — the same value
     * {@code sign} stamps, so a store built from the public half finds the
     * manifest it signed. Null/unencodable keys yield {@code ""} (legacy).
     */
    public static String keyIdOf(PublicKey key) {
        if (key == null) {
            return "";
        }
        byte[] encoded = key.getEncoded();
        return encoded == null ? "" : InMemoryModelStore.sha256Hex(encoded);
    }

    /** Derive the key id stamped by {@link #sign(String, String, String, String, PrivateKey)}. */
    private static String keyIdOf(PrivateKey key) {
        try {
            PublicKey publicKey = publicKeyOf(key);
            return publicKey == null ? "" : keyIdOf(publicKey);
        } catch (GeneralSecurityException e) {
            return "";   // non-derivable key (non-CRT): sign a legacy kid-less manifest
        }
    }

    /**
     * Public half of an RSA CRT private key — lets the signing side derive its
     * own key id, and the tool self-check verify what it wrote.
     *
     * @throws GeneralSecurityException for non-RSA or non-CRT keys
     */
    public static PublicKey publicKeyOf(PrivateKey key) throws GeneralSecurityException {
        if (key instanceof RSAPrivateCrtKey crt) {
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        }
        throw new GeneralSecurityException("not an RSA CRT private key — cannot derive the public key");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
