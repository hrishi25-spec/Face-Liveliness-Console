package io.mosip.liveness.engine;

import io.mosip.liveness.core.Challenge;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.WorkflowType;

import java.util.List;

/**
 * The clean processing interface exposed to host applications (desktop
 * registration client, Android capture SDK, Flutter platform channel).
 * Identical liveness/PAD logic across all three workflows.
 *
 * <h3>Threading contract</h3>
 * <p>Each session must be accessed from a single thread only (the camera
 * capture thread). The engine guarantees that concurrent access to
 * <em>different</em> sessions is safe, but does not synchronize
 * per-session state. Implementations of backend inference must be
 * thread-safe for concurrent calls from different sessions.</p>
 *
 * <h3>Frame sampling</h3>
 * <p>Callers push frames at the device's native rate. The engine handles
 * internal sampling per the configured {@code frameSamplingRate} — callers
 * should not skip frames themselves.</p>
 */
public interface LivenessPipeline {

    /** Start a session; the effective policy is resolved for the given workflow. */
    String initSession(WorkflowType workflow);

    /**
     * Push one frame into the pipeline. The engine handles:
     * <ul>
     *   <li>Frame sampling (skips frames per config, but always checks session timeout)</li>
     *   <li>Session duration timeout enforcement</li>
     *   <li>Passive scoring, PAD, and active-phase evaluation including passive re-evaluation</li>
     * </ul>
     * @return assessment with status, liveness score, PAD flag, and (during challenges) progress
     */
    FrameAssessment pushFrame(String sessionId, Frame frame);

    /** Engine-selected (never user-chosen) next active challenge. */
    Challenge requestChallenge(String sessionId);

    /**
     * Validate frames captured during the challenge window (batch mode).
     * This is an alternative to frame-by-frame pushFrame during IN_CHALLENGE.
     * The batch mode evaluates all frames at once without real-time progress.
     */
    ValidationResult validateChallenge(String sessionId, List<Frame> frames);

    /** Close the session and return the audit summary record. */
    SessionSummary closeSession(String sessionId);
}
