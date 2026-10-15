package io.mosip.registration.liveness;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;

import io.mosip.liveness.android.LivenessEvidenceSigner;

/**
 * Android Keystore evidence signer (spec §2 LivenessEvidenceSigner, §10
 * security): RSA-2048 / SHA256withRSA, non-exportable, TEE/StrongBox backed
 * where available. Evidence proves the gate ran on this device; a copied
 * evidence blob fails signature verification on the receiving side.
 *
 * <p>The engine-side reference implementation for JVM tests is
 * {@code LivenessEvidenceSigner.InProcessRsaSigner}; this is the production
 * swap for the same interface.</p>
 */
public final class AndroidKeystoreEvidenceSigner implements LivenessEvidenceSigner {

    private static final String KEY_ALIAS = "mosip_liveness_evidence";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private final boolean userAuthenticationRequired;

    public AndroidKeystoreEvidenceSigner() {
        this(false);
    }

    /** @param userAuthenticationRequired set true for supervisor-grade keys. */
    public AndroidKeystoreEvidenceSigner(boolean userAuthenticationRequired) {
        this.userAuthenticationRequired = userAuthenticationRequired;
        ensureKey();
    }

    private void ensureKey() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) {
                return;
            }
            // RSA key pairs come from KeyPairGenerator; KeyGenerator only
            // builds symmetric keys and has no KeyGenParameterSpec overload.
            KeyPairGenerator kg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE);
            kg.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setUserAuthenticationRequired(userAuthenticationRequired)
                    .setInvalidatedByBiometricEnrollment(userAuthenticationRequired)
                    .build());
            kg.generateKeyPair();
        } catch (Exception e) {
            // Fail closed: signing will throw and the orchestrator treats the
            // gate as unprovable (evidence withheld, audited EVIDENCE_SIGN_FAILED).
            throw new IllegalStateException("Keystore key generation failed", e);
        }
    }

    @Override
    public String sign(String canonicalPayload) throws LivenessSigningException {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            PrivateKey key = (PrivateKey) ks.getKey(KEY_ALIAS, null);
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(key);
            sig.update(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            return hex(sig.sign());
        } catch (Exception e) {
            throw new LivenessSigningException("keystore signing failed", e);
        }
    }

    @Override
    public boolean verify(String canonicalPayload, String signatureHex) {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            Certificate cert = ks.getCertificate(KEY_ALIAS);
            if (cert == null) {
                return false;
            }
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(cert.getPublicKey());
            sig.update(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            return sig.verify(unhex(signatureHex));
        } catch (Exception e) {
            return false;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
              .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
