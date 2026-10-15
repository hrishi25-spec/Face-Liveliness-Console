package io.mosip.liveness.services;

import io.mosip.liveness.models.enums.ChallengeType;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;
import org.opencv.core.Rect;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Direction polarity for head-turn challenges.
 *
 * <p>Frames reach the engine as raw, un-mirrored camera pixels, where the
 * subject's own left appears on the image's right. A person turning their head to
 * <em>their own left</em> therefore moves the face box toward <b>larger x</b>.</p>
 *
 * <p>These assertions were inverted in the original implementation, so a user
 * following the on-screen "turn left" instruction was measured as turning right
 * and failed. A mirrored (selfie-style) preview is a display concern only and must
 * never change this mapping.</p>
 */
class LivenessEngineServiceTest {

    private final LivenessEngineService engine = new LivenessEngineService();

    /** Runs a turn/gaze challenge over two frames whose face box moves startX -> endX. */
    private boolean movesFaceBox(int startX, int endX, ChallengeType type) {
        ImageUtils utils = mock(ImageUtils.class);
        Mat first = mock(Mat.class);
        Mat last = mock(Mat.class);
        when(utils.detectFaces(first)).thenReturn(new Rect[]{new Rect(startX, 100, 80, 80)});
        when(utils.detectFaces(last)).thenReturn(new Rect[]{new Rect(endX, 100, 80, 80)});
        return engine.validateActive(type, List.of(first, last), utils);
    }

    @Test
    void turningToYourOwnLeft_movesTheFaceBoxTowardLargerX() {
        assertTrue(movesFaceBox(120, 180, ChallengeType.TURN_LEFT),
                "the subject turning to their own left must satisfy TURN_LEFT");
        assertFalse(movesFaceBox(120, 180, ChallengeType.TURN_RIGHT));
    }

    @Test
    void turningToYourOwnRight_movesTheFaceBoxTowardSmallerX() {
        assertTrue(movesFaceBox(180, 120, ChallengeType.TURN_RIGHT),
                "the subject turning to their own right must satisfy TURN_RIGHT");
        assertFalse(movesFaceBox(180, 120, ChallengeType.TURN_LEFT));
    }

    @Test
    void gazeLeftAndRightFollowTheSamePolarity() {
        assertTrue(movesFaceBox(120, 180, ChallengeType.LOOK_LEFT));
        assertTrue(movesFaceBox(180, 120, ChallengeType.LOOK_RIGHT));
    }

    @Test
    void movementBelowThresholdSatisfiesNothing() {
        assertFalse(movesFaceBox(120, 126, ChallengeType.TURN_LEFT));
        assertFalse(movesFaceBox(120, 126, ChallengeType.TURN_RIGHT));
    }

    @Test
    void sidewaysMovementDoesNotSatisfyAVerticalGazeChallenge() {
        assertFalse(movesFaceBox(120, 220, ChallengeType.LOOK_UP));
        assertFalse(movesFaceBox(120, 220, ChallengeType.LOOK_DOWN));
    }

    @Test
    void lookDirectionIsDirectionAgnostic() {
        assertTrue(movesFaceBox(120, 190, ChallengeType.LOOK_DIRECTION));
        assertTrue(movesFaceBox(190, 120, ChallengeType.LOOK_DIRECTION));
    }

    // ---- patience: real turns used to be missed for avoidable reasons ----

    @Test
    void aFaceLostForAFrameMidTurnDoesNotFailTheChallenge() {
        // Haar tracking regularly drops the face for a frame or two while the head
        // is turning. Rejecting the whole burst over that punished genuine turns.
        ImageUtils utils = mock(ImageUtils.class);
        Mat start = mock(Mat.class);
        Mat dropped = mock(Mat.class);
        Mat turned = mock(Mat.class);
        when(utils.detectFaces(start)).thenReturn(new Rect[]{new Rect(120, 100, 80, 80)});
        when(utils.detectFaces(dropped)).thenReturn(new Rect[0]);
        when(utils.detectFaces(turned)).thenReturn(new Rect[]{new Rect(200, 100, 80, 80)});

        assertTrue(engine.validateActive(ChallengeType.TURN_LEFT, List.of(start, dropped, turned), utils));
    }

    @Test
    void turnThatReturnsToCentreStillCounts() {
        // The old first-vs-last measurement saw 120 -> 120 and scored this as no
        // movement, even though the head clearly turned and came back.
        assertTrue(movesFaceBoxes(List.of(120, 200, 190, 160, 120), ChallengeType.TURN_LEFT));
        assertFalse(movesFaceBoxes(List.of(120, 200, 190, 160, 120), ChallengeType.TURN_RIGHT));
    }

    @Test
    void aHeldTurnCountsOnEverySubsequentWindow() {
        // Subject turned before the burst started and simply held the pose.
        assertTrue(movesFaceBoxes(List.of(120, 190, 195, 200, 200), ChallengeType.TURN_LEFT));
        assertFalse(movesFaceBoxes(List.of(120, 190, 195, 200, 200), ChallengeType.TURN_RIGHT));
    }

    @Test
    void requiredTravelScalesWithFaceWidth() {
        // A 60px shift is decisive for an 80px-wide face (bar = 20% = 16px)...
        assertTrue(movesFaceBox(100, 160, ChallengeType.TURN_LEFT));
        // ...but nowhere near enough for a 400px-wide close-up (bar = 80px), which
        // is exactly the case a fixed 15px threshold used to wave through.
        assertFalse(movesFaceBoxWithWidth(100, 160, 400, ChallengeType.TURN_LEFT));
        assertTrue(movesFaceBoxWithWidth(100, 260, 400, ChallengeType.TURN_LEFT));
    }

    @Test
    void aBurstWithNoDetectableFaceStillFails() {
        ImageUtils utils = mock(ImageUtils.class);
        Mat a = mock(Mat.class);
        Mat b = mock(Mat.class);
        when(utils.detectFaces(a)).thenReturn(new Rect[0]);
        when(utils.detectFaces(b)).thenReturn(new Rect[0]);

        assertFalse(engine.validateActive(ChallengeType.TURN_LEFT, List.of(a, b), utils));
    }

    /** Runs a challenge over frames whose face box x-coordinates are given. */
    private boolean movesFaceBoxes(List<Integer> xs, ChallengeType type) {
        return evaluate(xs, 80, type);
    }

    private boolean movesFaceBoxWithWidth(int startX, int endX, int width, ChallengeType type) {
        return evaluate(List.of(startX, endX), width, type);
    }

    private boolean evaluate(List<Integer> xs, int width, ChallengeType type) {
        ImageUtils utils = mock(ImageUtils.class);
        List<Mat> frames = new ArrayList<>();
        for (int x : xs) {
            Mat m = mock(Mat.class);
            when(utils.detectFaces(m)).thenReturn(new Rect[]{new Rect(x, 100, width, 80)});
            frames.add(m);
        }
        return engine.validateActive(type, frames, utils);
    }
}
