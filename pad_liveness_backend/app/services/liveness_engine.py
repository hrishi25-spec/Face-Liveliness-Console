"""
Face liveness engine interface (passive scoring + active challenge validation).

Same pattern as pad_engine.py: BaseLivenessEngine is the contract the rest
of the system depends on. MockLivenessEngine is a heuristic stand-in.
Swap in a real liveness SDK/model by implementing BaseLivenessEngine and
updating get_liveness_engine().
"""
from abc import ABC, abstractmethod
from dataclasses import dataclass

import cv2
import numpy as np

from app.models.enums import ChallengeType
from app.services.image_utils import detect_faces, detect_eyes, sharpness_score, brightness_score


@dataclass
class FaceObservation:
    face_detected: bool
    multiple_faces: bool
    face_quality: float | None
    bbox: tuple[int, int, int, int] | None


@dataclass
class PassiveLivenessResult:
    observation: FaceObservation
    liveness_score: float  # 0..1


class BaseLivenessEngine(ABC):
    @abstractmethod
    def observe_face(self, frame: np.ndarray) -> FaceObservation:
        """Face detection + quality check, independent of liveness scoring."""
        raise NotImplementedError

    @abstractmethod
    def score_passive(self, frame: np.ndarray, observation: FaceObservation) -> float:
        """Return a passive liveness confidence score in [0, 1]."""
        raise NotImplementedError

    @abstractmethod
    def validate_active(self, challenge_type: ChallengeType, frames: list[np.ndarray]) -> bool:
        """Return True if the requested facial action was genuinely performed
        across the given ordered frame sequence."""
        raise NotImplementedError


class MockLivenessEngine(BaseLivenessEngine):
    """
    Heuristic placeholder liveness engine:
      - Passive score blends face-detection confidence, sharpness, brightness,
        and a simple 3D-cue proxy (eye symmetry) as stand-ins for a trained
        passive-liveness model.
      - Active validation uses frame-to-frame differencing to detect the
        requested motion (blink = eye-region change, turn = face-bbox
        horizontal shift, smile = mouth-region texture change).

    Replace with a properly trained/vendor liveness SDK before production use.
    """

    def observe_face(self, frame: np.ndarray) -> FaceObservation:
        faces = detect_faces(frame)
        if len(faces) == 0:
            return FaceObservation(face_detected=False, multiple_faces=False, face_quality=None, bbox=None)
        if len(faces) > 1:
            return FaceObservation(face_detected=True, multiple_faces=True, face_quality=None, bbox=None)

        x, y, w, h = faces[0]
        quality = 0.5 * sharpness_score(frame) + 0.5 * brightness_score(frame)
        return FaceObservation(face_detected=True, multiple_faces=False, face_quality=quality, bbox=(x, y, w, h))

    def score_passive(self, frame: np.ndarray, observation: FaceObservation) -> float:
        if not observation.face_detected or observation.multiple_faces:
            return 0.0

        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        x, y, w, h = observation.bbox
        face_roi = gray[y: y + h, x: x + w]
        eyes = detect_eyes(face_roi)
        eye_symmetry_score = 1.0 if len(eyes) >= 2 else 0.4

        score = (
            0.4 * (observation.face_quality or 0.0)
            + 0.4 * eye_symmetry_score
            + 0.2 * sharpness_score(frame)
        )
        return float(np.clip(score, 0.0, 1.0))

    def validate_active(self, challenge_type: ChallengeType, frames: list[np.ndarray]) -> bool:
        if len(frames) < 2:
            return False

        grays = [cv2.cvtColor(f, cv2.COLOR_BGR2GRAY) for f in frames]
        boxes = [self.observe_face(f).bbox for f in frames]
        if any(b is None for b in boxes):
            return False

        if challenge_type == ChallengeType.BLINK:
            # Look for a transient dip in eye-region pixel variance (closed-eye frame)
            eye_counts = []
            for g, box in zip(grays, boxes):
                x, y, w, h = box
                roi = g[y: y + h, x: x + w]
                eye_counts.append(len(detect_eyes(roi)))
            return 0 in eye_counts and max(eye_counts) >= 2

        if challenge_type in (ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT):
            xs = [b[0] for b in boxes]
            delta = xs[-1] - xs[0]
            if challenge_type == ChallengeType.TURN_LEFT:
                return delta < -15
            return delta > 15

        if challenge_type == ChallengeType.SMILE:
            # Proxy: lower-face region texture variance increases noticeably on a smile.
            variances = []
            for g, box in zip(grays, boxes):
                x, y, w, h = box
                mouth_roi = g[y + int(h * 0.6): y + h, x: x + w]
                variances.append(cv2.Laplacian(mouth_roi, cv2.CV_64F).var())
            return (max(variances) - min(variances)) > 50

        if challenge_type == ChallengeType.LOOK_DIRECTION:
            xs = [b[0] for b in boxes]
            return abs(xs[-1] - xs[0]) > 10

        return False


_engine_instance: BaseLivenessEngine | None = None


def get_liveness_engine() -> BaseLivenessEngine:
    global _engine_instance
    if _engine_instance is None:
        _engine_instance = MockLivenessEngine()
    return _engine_instance
