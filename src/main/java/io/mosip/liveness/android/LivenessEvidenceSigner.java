package io.mosip.liveness.android;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Arrays;

/**
 * Signs {@link LivenessEvidence} canonical payloads (spec §2
 * {@code LivenessEvidenceSigner}). On Android, the production implementation
 * (see {@code android_client/}) delegates to an Android Keystore
 * {@code StrongBoxUnavailable → TEE} RSA key with
 * {@code setUserAuthenticationRequired(false)} so signing never prompts; the
 * desktop/test implementation here uses an in-process key pair.
 */
public interface LivenessEvidenceSigner {

    /** Sign the canonical payload; hex-encoded signature (fail closed on error). */
    String sign(String canonicalPayload) throws LivenessSigningException;

    /** Verify a signature over the payload (used by the binding check and tests). */
    boolean verify(String canonicalPayload, String signatureHex);

    class LivenessSigningException extends Exception {
        public LivenessSigningException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Reference signer for JVM tests / desktop parity runs. */
    final class InProcessRsaSigner implements LivenessEvidenceSigner {

        private final KeyPair keyPair;

        public InProcessRsaSigner() {
            try {
                KeyPairGenerator kg = KeyPairGenerator.getInstance("RSA");
                kg.initialize(2048);
                this.keyPair = kg.generateKeyPair();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("RSA unavailable in this JRE", e);
            }
        }

        @Override
        public String sign(String canonicalPayload) throws LivenessSigningException {
            try {
                Signature sig = Signature.getInstance("SHA256withRSA");
                sig.initSign(keyPair.getPrivate());
                sig.update(canonicalPayload.getBytes(StandardCharsets.UTF_8));
                return hex(sig.sign());
            } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
                throw new LivenessSigningException("evidence signing failed", e);
            }
        }

        @Override
        public boolean verify(String canonicalPayload, String signatureHex) {
            try {
                Signature sig = Signature.getInstance("SHA256withRSA");
                sig.initVerify(keyPair.getPublic());
                sig.update(canonicalPayload.getBytes(StandardCharsets.UTF_8));
                return sig.verify(unhex(signatureHex));
            } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException
                    | IllegalArgumentException e) {
                return false;
            }
        }

        private static String hex(byte[] bytes) {
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        }

        private static byte[] unhex(String s) {
            if (s == null || s.length() % 2 != 0) {
                throw new IllegalArgumentException("bad signature hex");
            }
            byte[] out = new byte[s.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        }
    }

    /** Test double that fails signing, proving the orchestrator fails closed. */
    final class AlwaysFailingSigner implements LivenessEvidenceSigner {
        @Override
        public String sign(String canonicalPayload) throws LivenessSigningException {
            throw new LivenessSigningException("keystore unavailable", new RuntimeException("stub"));
        }

        @Override
        public boolean verify(String canonicalPayload, String signatureHex) {
            return false;
        }
    }

    /** Null-object used when evidence signing is disabled by explicit test opt-out. */
    final class NoopSigner implements LivenessEvidenceSigner {
        @Override
        public String sign(String canonicalPayload) {
            return Arrays.equals(new byte[0], new byte[0]) ? "noop" : "noop";
        }

        @Override
        public boolean verify(String canonicalPayload, String signatureHex) {
            return true;
        }
    }
}
