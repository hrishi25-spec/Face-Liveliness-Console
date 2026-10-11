package io.mosip.liveness.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The ownership rule, pinned where it can be read in one screen: who may act on
 * a session, what an ownerless session means, and the one input that used to
 * crash instead of refusing.
 *
 * <p>The routes that call this are covered end to end elsewhere
 * ({@code SessionsControllerTest}, {@code FramesControllerTest},
 * {@code ChallengesControllerTest}, {@code AuditControllerTest}); this test is
 * about the decision itself, so a route cannot quietly acquire a different one.</p>
 */
class UserSessionGuardTest {

    private static final String HEADER = UserSessionGuard.HEADER;

    @Test
    void matchingSubjectRef_isAllowed() {
        assertDoesNotThrow(() -> UserSessionGuard.require("resident-42", "resident-42"));
    }

    @Test
    void aDifferentOwner_isRefused() {
        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> UserSessionGuard.require("resident-42", "resident-43"));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
    }

    @Test
    void aGuessThatOnlySharesAPrefix_isRefused() {
        // The case a short-circuiting compare would leak the difference on.
        assertThrows(ResponseStatusException.class,
                () -> UserSessionGuard.require("resident-42", "resident-4"));
        assertThrows(ResponseStatusException.class,
                () -> UserSessionGuard.require("resident-42", "resident-42x"));
    }

    @Test
    void anOwnedSessionCannotBeReachedByOmission() {
        // The old code's first branch, preserved: silence is not a claim to
        // ownership.
        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> UserSessionGuard.require("resident-42", null));
        assertEquals(HttpStatus.FORBIDDEN, missing.getStatusCode());
        assertThrows(ResponseStatusException.class,
                () -> UserSessionGuard.require("resident-42", ""));
        assertThrows(ResponseStatusException.class,
                () -> UserSessionGuard.require("resident-42", "   "));
    }

    @Test
    void anOwnerlessSession_doesNotCrashAndStaysReachable() {
        // `sessionSubjectRef.equals(...)` threw a NullPointerException here, which
        // surfaced as a 500 on a route whose contract is 403 — reachable by any
        // caller against a session created without a subjectRef. An anonymous
        // session has no owner to protect (an attacker can create one of their
        // own), so it stays reachable rather than failing closed on nothing.
        assertDoesNotThrow(() -> UserSessionGuard.require(null, null));
        assertDoesNotThrow(() -> UserSessionGuard.require(null, ""));
        assertDoesNotThrow(() -> UserSessionGuard.require(null, "anybody"));
        assertDoesNotThrow(() -> UserSessionGuard.require("", "anybody"));
        assertDoesNotThrow(() -> UserSessionGuard.require("   ", "anybody"));
    }

    @Test
    void theHeaderNameIsTheOneTheClientsSend() {
        // Referenced by AppConfig's CORS allow-list and the console; pinned so a
        // rename is a decision rather than a silently blocked preflight.
        assertEquals("X-Subject-Ref", HEADER);
    }
}
