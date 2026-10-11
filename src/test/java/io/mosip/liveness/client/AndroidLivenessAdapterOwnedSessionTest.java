package io.mosip.liveness.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.mosip.liveness.client.LivenessHttpClientWireTest.LivenessClientSpy;

/**
 * Test that AndroidLivenessAdapter now supports owned sessions via subjectRef parameter.
 */
class AndroidLivenessAdapterOwnedSessionTest {

    private LivenessClientSpy clientSpy;
    private AndroidLivenessAdapter adapter;

    @BeforeEach
    void setUp() {
        clientSpy = new LivenessClientSpy();
        adapter = new AndroidLivenessAdapter(clientSpy);
    }

    @Test
    void startSessionWithSubjectRefRecordsOwnerForHeaders() {
        // Start an owned session
        UUID sessionId = adapter.startSession("RESIDENT", "L1-CAM-01", "resident-42");

        // Verify session was created with subjectRef in body
        assertEquals(1, clientSpy.createdSessions.size());
        LivenessClientSpy.SessionCreation creation = clientSpy.createdSessions.get(0);
        assertEquals("RESIDENT", creation.workflowType);
        assertEquals("L1-CAM-01", creation.deviceId);
        assertEquals("resident-42", creation.subjectRef);

        // Verify adapter stored the session ID
        assertEquals(sessionId, adapter.getCurrentSessionId());
    }

    @Test
    void submitFrameWithOwnedSessionIncludesSubjectRefHeader() {
        // Setup: create owned session
        UUID sessionId = adapter.startSession("RESIDENT", "L1-CAM-01", "resident-42");

        // Exercise: submit a frame
        adapter.submitFrame(sessionId, new byte[]{1, 2, 3});

        // Verify: the submitFrame call included the subjectRef header
        assertEquals(1, clientSpy.frameSubmissions.size());
        LivenessClientSpy.FrameSubmission submission = clientSpy.frameSubmissions.get(0);
        assertEquals("resident-42", submission.subjectRef);
    }

    @Test
    void validateChallengeWithOwnedSessionIncludesSubjectRefHeader() {
        // Setup: create owned session
        UUID sessionId = adapter.startSession("RESIDENT", "L1-CAM-01", "resident-42");

        // Exercise: validate a challenge
        adapter.validateChallenge(sessionId, UUID.randomUUID(), List.of(new byte[]{4, 5}));

        // Verify: the validateChallenge call included the subjectRef header
        assertEquals(1, clientSpy.challengeValidations.size());
        LivenessClientSpy.ChallengeValidation validation = clientSpy.challengeValidations.get(0);
        assertEquals("resident-42", validation.subjectRef);
    }

    @Test
    void closeSessionWithOwnedSessionIncludesSubjectRefHeader() {
        // Setup: create owned session
        UUID sessionId = adapter.startSession("RESIDENT", "L1-CAM-01", "resident-42");

        // Exercise: close the session
        adapter.closeSession();

        // Verify: the closeSession call included the subjectRef header
        assertEquals(1, clientSpy.sessionClosures.size());
        LivenessClientSpy.SessionClosure closure = clientSpy.sessionClosures.get(0);
        assertEquals("resident-42", closure.subjectRef);
    }

    @Test
    void startSessionWithoutSubjectRefCreatesAnonymousSession() {
        // Start an anonymous session (no subjectRef)
        UUID sessionId = adapter.startSession("RESIDENT", "L1-CAM-01");

        // Verify session was created without subjectRef in body
        assertEquals(1, clientSpy.createdSessions.size());
        LivenessClientSpy.SessionCreation creation = clientSpy.createdSessions.get(0);
        assertEquals("RESIDENT", creation.workflowType);
        assertEquals("L1-CAM-01", creation.deviceId);
        assertNull(creation.subjectRef);

        // Verify adapter stored the session ID
        assertEquals(sessionId, adapter.getCurrentSessionId());
    }

    @Test
    void submitFrameWithAnonymousSessionOmitsSubjectRefHeader() {
        // Setup: create anonymous session
        adapter.startSession("RESIDENT", "L1-CAM-01");

        // Exercise: submit a frame
        adapter.submitFrame(UUID.randomUUID(), new byte[]{1, 2, 3});

        // Verify: the submitFrame call did NOT include subjectRef header
        assertEquals(1, clientSpy.frameSubmissions.size());
        LivenessClientSpy.FrameSubmission submission = clientSpy.frameSubmissions.get(0);
        assertNull(submission.subjectRef);
    }
}