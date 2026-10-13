package io.mosip.liveness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One rejection audit event in the operator feed: {@code PAD_REJECTED} or
 * {@code CHALLENGE_FAILED}, with the session it belongs to.
 *
 * <p>The payload is the stored {@code details} map unchanged — exactly what
 * {@code GET /api/v1/sessions/{id}/audit} already answers for that session, so
 * the feed tells an operator no more than the session's own trail does. It is
 * the operator key, not the feed, that decides who may read it.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RejectionEvent {

    private UUID id;
    private String eventType;
    /** The rejected session; rejection events always carry one. */
    private UUID sessionId;
    /**
     * The stored event payload ({@code attackType}/{@code confidence}/{@code stage}
     * for a PAD rejection, {@code challengeType}/{@code manual} for a failed
     * challenge). Never rewritten here.
     */
    private Map<String, Object> details;
    private OffsetDateTime createdAt;
}
