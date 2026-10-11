package io.mosip.liveness.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ownership rule, pinned where it can be read in one screen: who may act on
 * a session, what an ownerless session means, and the one input that used to
 * crash instead of refusing.
 *
 * <p>The decision returns its reason rather than throwing, because
 * {@code SessionOwnershipFilter} — its one caller, and the reason no handler
 * calls this any more — runs outside Spring MVC's exception resolution and turns
 * that reason into the 403 body. So the refusal text asserted here is wire text.
 * The routes are covered end to end elsewhere ({@code SessionOwnershipFilterTest},
 * {@code SessionsControllerTest}, {@code FramesControllerTest},
 * {@code ChallengesControllerTest}, {@code AuditControllerTest}); this test is
 * about the decision itself, so a route cannot quietly acquire a different one.</p>
 */
class UserSessionGuardTest {

    private static final String HEADER = UserSessionGuard.HEADER;

    @Test
    void matchingSubjectRef_isAllowed() {
        assertNull(UserSessionGuard.refusal("resident-42", "resident-42"));
    }

    @Test
    void aDifferentOwner_isRefused() {
        assertEquals("Invalid subject reference for this session.",
                UserSessionGuard.refusal("resident-42", "resident-43"));
    }

    @Test
    void aGuessThatOnlySharesAPrefix_isRefused() {
        // The case a short-circuiting compare would leak the difference on.
        assertEquals("Invalid subject reference for this session.",
                UserSessionGuard.refusal("resident-42", "resident-4"));
        assertEquals("Invalid subject reference for this session.",
                UserSessionGuard.refusal("resident-42", "resident-42x"));
    }

    @Test
    void anOwnedSessionCannotBeReachedByOmission() {
        // The old code's first branch, preserved: silence is not a claim to
        // ownership. The refusal names the header, so a client that forgot it
        // learns what to send rather than only that it was refused.
        String refusal = UserSessionGuard.refusal("resident-42", null);
        assertTrue(refusal.contains(HEADER), "the refusal should name the header: " + refusal);
        assertEquals(refusal, UserSessionGuard.refusal("resident-42", ""));
        assertEquals(refusal, UserSessionGuard.refusal("resident-42", "   "));
    }

    @Test
    void theTwoRefusalsStayDistinguishable() {
        // A client can act on "you did not say who you are" (send the header) but
        // not on "you are not the owner" (give up) — so they cannot collapse into
        // one message without losing information the client acts on.
        assertNotEquals(UserSessionGuard.refusal("resident-42", null),
                UserSessionGuard.refusal("resident-42", "resident-43"));
    }

    @Test
    void anOwnerlessSession_doesNotCrashAndStaysReachable() {
        // `sessionSubjectRef.equals(...)` threw a NullPointerException here, which
        // surfaced as a 500 on a route whose contract is 403 — reachable by any
        // caller against a session created without a subjectRef. An anonymous
        // session has no owner to protect (an attacker can create one of their
        // own), so it stays reachable rather than failing closed on nothing.
        assertNull(UserSessionGuard.refusal(null, null));
        assertNull(UserSessionGuard.refusal(null, ""));
        assertNull(UserSessionGuard.refusal(null, "anybody"));
        assertNull(UserSessionGuard.refusal("", "anybody"));
        assertNull(UserSessionGuard.refusal("   ", "anybody"));
    }

    @Test
    void theHeaderNameIsTheOneTheClientsSend() {
        // Referenced by AppConfig's CORS allow-list and the console; pinned so a
        // rename is a decision rather than a silently blocked preflight.
        assertEquals("X-Subject-Ref", HEADER);
    }
}
