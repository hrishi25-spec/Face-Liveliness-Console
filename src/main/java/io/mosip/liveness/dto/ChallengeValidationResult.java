package io.mosip.liveness.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Result of validating challenge frames.
 * Maps to the Python framework's ChallengeValidationResult schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChallengeValidationResult {

    private UUID sessionId;
    private ChallengeResponse challenge;
    private Boolean passed;
    // proceed | retry_challenge | retry_passive | continue | reject |
    // locked | escalate_to_operator | failed | manual_check_passed |
    // manual_check_failed
    private String action;
    /**
     * Set only on a terminal budget-exhausted verdict: true when the frozen
     * policy's repeated-failure action lets the client open a fresh session
     * ({@code FALLBACK}/ALLOW_RETRY), false for {@code locked} and
     * {@code escalate_to_operator}. Absent on every non-terminal verdict, and
     * never a grant of another challenge inside this session.
     */
    private Boolean mayRetrySession;
    /**
     * True when a presentation attack ended this check. Set only on the verdict
     * this stage itself produced (a PAD rejection), never on a plain action
     * miss: the client lists the attack next to the rejection, so the operator
     * learns what the engine saw instead of only "try again".
     */
    private Boolean padFlag;
    /** The attack class behind {@link #padFlag} (e.g. {@code SCREEN_REPLAY}). */
    private String padAttackType;
    private String message;
}
