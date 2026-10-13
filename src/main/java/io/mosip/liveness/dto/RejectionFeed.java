package io.mosip.liveness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response of {@code GET /api/v1/rejections}: the recently rejected sessions
 * and the rejection events recorded for them.
 *
 * <p>Two lists rather than one merged one because the rows are different things
 * — a session verdict against an event that happened inside a session — and a
 * caller that wants a single timeline can concatenate them by
 * {@code createdAt}. Each list is newest first and holds at most
 * {@link #limit} entries: a rejection is often both a failed session and one or
 * more stored events, so one shared cap over two lists would answer a request
 * for fifty rejections with an arbitrary split between them.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RejectionFeed {

    /**
     * The page size the service actually applied (1..200), echoed so a client
     * can tell how many rows it is looking at rather than assuming its own
     * parameter was honoured.
     */
    private int limit;

    /** Failed sessions, newest first, at most {@link #limit} of them. */
    private List<SessionListItem> sessions;

    /**
     * {@code PAD_REJECTED} and {@code CHALLENGE_FAILED} audit events, newest
     * first, at most {@link #limit} of them.
     */
    private List<RejectionEvent> events;
}
