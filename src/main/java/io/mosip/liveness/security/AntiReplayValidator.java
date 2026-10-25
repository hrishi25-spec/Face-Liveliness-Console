package io.mosip.liveness.security;

import io.mosip.liveness.dto.ChallengeValidateRequest;
import io.mosip.liveness.dto.FrameSubmitRequest;
import io.mosip.liveness.models.entity.ChallengeEntity;
import io.mosip.liveness.models.entity.LivenessSession;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates frame freshness, monotonic sequencing, cryptographic nonces, and
 * detects exact and perceptual-hash duplicate frames (replay attacks).
 *
 * <p>The cross-session check is exact-digest only. A perceptual hash over a
 * downscaled 9x8 image is a weak identity: two different people in similar light
 * can land within the one-bit threshold, and comparing every frame against every
 * hash ever seen made the per-frame cost grow with the process uptime. The
 * in-session perceptual check (the same capture stream repeating a frame) stays,
 * because there the candidate set is one session and the signal is meaningful.</p>
 */
@Component
@Slf4j
public class AntiReplayValidator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long MAX_FRESHNESS_WINDOW_MS = 60_000L;
    private static final long MAX_CLOCK_SKEW_FUTURE_MS = 10_000L;

    /** In-memory cache of perceptual hashes seen per session (evicts after session completion). */
    private final Map<UUID, Set<Long>> sessionPerceptualHashes = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> sessionFrameDigests = new ConcurrentHashMap<>();

    /**
     * Cross-session cache of exact frame digests to origin sessionId. Bounded:
     * the entries for a finished session are dropped by
     * {@link #cleanupSession(UUID)}, and the map is cleared at the ceiling so a
     * long-lived process cannot retain every frame digest it has ever seen.
     */
    private static final int MAX_GLOBAL_DIGESTS = 50_000;
    private final Map<String, UUID> globalFrameDigests = new ConcurrentHashMap<>();

    /** Generates a 32-character hex cryptographic nonce. */
    public static String generateNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Validates frame freshness, sequence, digest, and anti-replay constraints.
     */
    public void validateFrame(LivenessSession session, FrameSubmitRequest req, Mat frame) {
        if (session == null || req == null) {
            return;
        }

        boolean hasAntiReplay = (req.getNonce() != null && !req.getNonce().isBlank())
                || req.getFrameSeq() != null
                || req.getCapturedAtEpochMs() != null
                || (req.getFrameSha256() != null && !req.getFrameSha256().isBlank());

        if (!hasAntiReplay) {
            return;
        }

        // 1. Nonce check: if the client provides a nonce, it must match the session nonce
        if (req.getNonce() != null && !req.getNonce().isBlank()) {
            if (session.getNonce() != null && !session.getNonce().equalsIgnoreCase(req.getNonce())) {
                log.warn("Session {}: frame nonce mismatch (provided={}, expected={})",
                        session.getId(), req.getNonce(), session.getNonce());
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid frame nonce: does not match session nonce");
            }
        }

        // 2. Monotonic frame sequence check
        if (req.getFrameSeq() != null) {
            long seq = req.getFrameSeq();
            Long lastSeq = session.getLastFrameSeq();
            if (lastSeq != null && seq <= lastSeq) {
                log.warn("Session {}: non-monotonic frame sequence (seq={}, lastSeq={})",
                        session.getId(), seq, lastSeq);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Non-monotonic frame sequence: frameSeq " + seq + " <= lastSeq " + lastSeq);
            }
            session.setLastFrameSeq(seq);
        }

        // 3. Timestamp freshness window check
        if (req.getCapturedAtEpochMs() != null) {
            long now = System.currentTimeMillis();
            long capturedAt = req.getCapturedAtEpochMs();
            long age = now - capturedAt;
            if (age > MAX_FRESHNESS_WINDOW_MS) {
                log.warn("Session {}: stale frame rejected (age={}ms, max={}ms)",
                        session.getId(), age, MAX_FRESHNESS_WINDOW_MS);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Frame timestamp too old: " + age + "ms stale");
            }
            if (age < -MAX_CLOCK_SKEW_FUTURE_MS) {
                log.warn("Session {}: future frame timestamp rejected (capturedAt={}, now={})",
                        session.getId(), capturedAt, now);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Frame timestamp is in the future");
            }
        }

        // 4. Exact digest verification
        if (req.getFrameSha256() != null && !req.getFrameSha256().isBlank()) {
            String calculated = sha256Hex(req.getFrameBase64().getBytes(StandardCharsets.UTF_8));
            if (!calculated.equalsIgnoreCase(req.getFrameSha256())) {
                log.warn("Session {}: frame digest mismatch (provided={}, calculated={})",
                        session.getId(), req.getFrameSha256(), calculated);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Frame sha256 digest mismatch");
            }
        }

        // 5. Perceptual and exact duplicate detection (cross-session and in-session)
        if (req.getFrameBase64() != null) {
            String digest = sha256Hex(req.getFrameBase64().getBytes(StandardCharsets.UTF_8));
            if (globalFrameDigests.size() >= MAX_GLOBAL_DIGESTS) {
                globalFrameDigests.clear();
            }
            UUID originSession = globalFrameDigests.putIfAbsent(digest, session.getId());
            if (originSession != null && !originSession.equals(session.getId())) {
                log.warn("Session {}: cross-session replay detected of frame from session {}", session.getId(), originSession);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate frame detected (cross-session replay attempt)");
            }
            Set<String> seenDigests = sessionFrameDigests.computeIfAbsent(session.getId(), k -> ConcurrentHashMap.newKeySet());
            if (!seenDigests.add(digest)) {
                log.warn("Session {}: exact duplicate frame payload detected", session.getId());
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate frame detected (replay attempt)");
            }
        }

        if (frame != null && !frame.empty()) {
            long pHash = computePerceptualHash(frame);
            Set<Long> seenHashes = sessionPerceptualHashes.computeIfAbsent(session.getId(), k -> ConcurrentHashMap.newKeySet());
            for (Long seen : seenHashes) {
                if (hammingDistance(pHash, seen) <= 1) {
                    log.warn("Session {}: duplicate or replayed frame detected (pHash={})", session.getId(), pHash);
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate frame detected (replay attempt)");
                }
            }
            seenHashes.add(pHash);
        }
    }

    /**
     * Validates challenge validation freshness and prompt ordering.
     */
    public void validateChallenge(LivenessSession session, ChallengeEntity challenge, ChallengeValidateRequest req) {
        if (session == null || challenge == null || req == null) {
            return;
        }

        if (req.getNonce() != null && !req.getNonce().isBlank()) {
            if (session.getNonce() != null && !session.getNonce().equalsIgnoreCase(req.getNonce())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid challenge nonce: does not match session nonce");
            }
        }

        // Ensure challenge frames were captured AFTER the challenge prompt was issued
        if (req.getCapturedAtEpochMs() != null && !req.getCapturedAtEpochMs().isEmpty() && challenge.getIssuedAt() != null) {
            long issuedEpoch = challenge.getIssuedAt().toInstant().toEpochMilli();
            for (Long capturedAt : req.getCapturedAtEpochMs()) {
                if (capturedAt != null && capturedAt < issuedEpoch - 2000L) { // 2s grace for clock skew
                    log.warn("Challenge {}: frame captured at {} before prompt issued at {}",
                            challenge.getId(), capturedAt, issuedEpoch);
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Challenge frames were captured before the challenge was issued");
                }
            }
        }
    }

    public void cleanupSession(UUID sessionId) {
        sessionPerceptualHashes.remove(sessionId);
        sessionFrameDigests.remove(sessionId);
        // The cross-session digest cache is deliberately NOT pruned here: a frame
        // recorded in a session that has just ended is exactly what a replay in
        // the next session presents. It stays until the bounded ceiling clears
        // it wholesale.
    }

    /**
     * Computes a 64-bit difference hash (dHash) on a downscaled 9x8 grayscale image.
     */
    public static long computePerceptualHash(Mat bgr) {
        if (bgr == null || bgr.empty()) return 0L;
        Mat gray = new Mat();
        Mat small = new Mat();
        try {
            if (bgr.channels() >= 3) {
                Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                bgr.copyTo(gray);
            }
            Imgproc.resize(gray, small, new Size(9, 8));
            byte[] pixels = new byte[72];
            small.get(0, 0, pixels);

            long hash = 0L;
            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 8; col++) {
                    int left = pixels[row * 9 + col] & 0xFF;
                    int right = pixels[row * 9 + col + 1] & 0xFF;
                    if (left > right) {
                        hash |= (1L << (row * 8 + col));
                    }
                }
            }
            return hash;
        } finally {
            gray.release();
            small.release();
        }
    }

    public static int hammingDistance(long hash1, long hash2) {
        return Long.bitCount(hash1 ^ hash2);
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
