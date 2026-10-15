"""Deterministic image-fixture tests for the real OpenCV heuristics.

The API behaviour tests (`test_liveness_api.py`) stub both engines, so they
never execute the OpenCV scoring code. These tests build frames procedurally
and drive `MockPADEngine` / `MockLivenessEngine` themselves:

  * PAD: one fixture per verdict branch (screen replay, print, video replay)
    plus benign fixtures that must pass — the heuristics once flagged *every*
    frame as a screen replay, and a benign frame is what catches that.
  * Liveness: passive scoring on a sharp/blurred pair, the documented blend
    formula, and the fail-closed paths (no face, short challenge sequence).
  * Frame decoding: the base64 -> ndarray round trip is bit-deterministic, and
    `MockPADEngine.detect` runs on a genuinely decoded frame — the path that
    raised `cv2.error` on the float32 Laplacian before this suite existed.

Fixtures are 100% synthetic (gradients, shapes, periodic patterns and seeded
noise). No real faces, biometric samples or downloaded images are involved, so
the tests stay offline, reproducible run-to-run, and safe to commit. They
exercise the *placeholder* heuristics — they say nothing about model accuracy
or APCER/BPCER, which need an evaluated model and a real attack corpus.

Note on face detection: the Haar cascades do not fire on synthetic content
(verified: `detect_faces` returns zero boxes for every fixture here). The
no-face paths are therefore tested against the real detector, and the one test
that needs a face region stubs the detector while keeping all scoring math
real. Real capture remains an on-device validation item.
"""
import base64

import cv2
import numpy as np
import pytest

from app.models.enums import ChallengeType
from app.services.image_utils import (
    brightness_score,
    decode_base64_frame,
    detect_eyes,
    detect_faces,
    sharpness_score,
)
from app.services.liveness_engine import FaceObservation, MockLivenessEngine
from app.services.pad_engine import MockPADEngine

SIZE = 240
SEED = 20261008


# --------------------------------------------------------------------------
# Synthetic fixtures
# --------------------------------------------------------------------------

def photo_like_frame() -> np.ndarray:
    """Smooth gradient with soft shapes: the 'ordinary live capture' stand-in."""
    gradient = np.linspace(105, 150, SIZE, dtype=np.float32)
    frame = np.repeat(gradient[None, :], SIZE, axis=0)
    noise = np.random.default_rng(SEED).normal(0.0, 1.0, frame.shape)
    frame += noise
    cv2.circle(frame, (SIZE // 2, SIZE // 2), SIZE // 5, 150.0, -1)
    cv2.rectangle(frame, (SIZE // 3, SIZE // 2 + 18), (SIZE // 3 + 60, SIZE // 2 + 34), 70.0, -1)
    return _to_bgr(frame)


def periodic_screen_frame() -> np.ndarray:
    """4-pixel checkerboard: the fine periodic structure of a re-photographed screen."""
    cells = np.indices((SIZE, SIZE)).sum(axis=0) % 4
    checker = np.where(cells < 2, 40, 215).astype(np.uint8)
    return cv2.cvtColor(checker, cv2.COLOR_GRAY2BGR)


def flat_print_frame() -> np.ndarray:
    """Blurred sheet with a bright paper border: flat, low-texture print attack."""
    sheet = np.full((SIZE, SIZE), 150, dtype=np.float32)
    cv2.rectangle(sheet, (10, 10), (SIZE - 10, SIZE - 10), 235.0, -1)
    cv2.circle(sheet, (SIZE // 2, SIZE // 2), SIZE // 3, 190.0, -1)
    blurred = cv2.GaussianBlur(sheet, (0, 0), 6.0)
    return _to_bgr(blurred)


def dark_textured_frame() -> np.ndarray:
    """Dark frame that still carries structure: an under-exposed replay."""
    frame = np.full((SIZE, SIZE), 10, dtype=np.float32)
    rng = np.random.default_rng(SEED + 1)
    for _ in range(26):
        x = int(rng.integers(4, SIZE - 10))
        y = int(rng.integers(4, SIZE - 10))
        frame[y:y + 5, x:x + 5] = 42.0
    return _to_bgr(frame)


def sheet_frame(blur_sigma: float = 0.0) -> np.ndarray:
    """Crisp sheet of shapes, optionally blurred; used for sharpness comparisons."""
    sheet = np.full((SIZE, SIZE), 120, dtype=np.float32)
    cv2.rectangle(sheet, (30, 30), (SIZE - 30, SIZE - 30), 165.0, -1)
    cv2.line(sheet, (40, 60), (SIZE - 40, 60), 60.0, 3)
    cv2.line(sheet, (40, 120), (SIZE - 40, 150), 80.0, 3)
    if blur_sigma:
        sheet = cv2.GaussianBlur(sheet, (0, 0), blur_sigma)
    return _to_bgr(sheet)


def blank_frame() -> np.ndarray:
    """Uniform mid-gray: no detectable face, no texture."""
    return np.full((64, 64, 3), 128, dtype=np.uint8)


def _to_bgr(gray_like: np.ndarray) -> np.ndarray:
    gray = np.clip(gray_like, 0, 255).astype(np.uint8)
    return cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)


def encode(frame: np.ndarray, extension: str = ".png") -> str:
    ok, buffer = cv2.imencode(extension, frame)
    assert ok
    return base64.b64encode(buffer).decode("ascii")


def face_observation_covering(frame: np.ndarray) -> FaceObservation:
    """Observation as the detector would report it, with the whole frame as the face."""
    height, width = frame.shape[:2]
    return FaceObservation(
        face_detected=True,
        multiple_faces=False,
        face_quality=0.5,
        bbox=(10, 10, width - 20, height - 20),
    )


# --------------------------------------------------------------------------
# Fixture determinism
# --------------------------------------------------------------------------

def test_fixtures_are_bit_identical_across_builds():
    for builder in (photo_like_frame, periodic_screen_frame, flat_print_frame, dark_textured_frame):
        assert np.array_equal(builder(), builder()), builder.__name__


def test_decoded_frame_is_bit_identical_across_decodes():
    encoded = encode(photo_like_frame())

    first = decode_base64_frame(encoded)
    second = decode_base64_frame(encoded)

    assert np.array_equal(first, second)
    assert np.array_equal(first, photo_like_frame())


# --------------------------------------------------------------------------
# PAD heuristics
# --------------------------------------------------------------------------

def test_pad_flags_periodic_screen_fixture_as_screen_replay():
    verdict = MockPADEngine().detect(periodic_screen_frame())

    assert verdict.is_attack is True
    assert verdict.attack_type == "screen_replay"
    assert 0.0 < verdict.confidence <= 1.0


def test_pad_passes_benign_fixtures():
    """Regression: the FFT ratio reached ~0.9 for every image, so all frames were rejected."""
    engine = MockPADEngine()

    for name, frame in (("photo_like", photo_like_frame()), ("crisp_sheet", sheet_frame())):
        verdict = engine.detect(frame)
        assert verdict.is_attack is False, name
        assert verdict.attack_type is None, name
        assert verdict.confidence >= 0.5, name


def test_pad_flags_flat_print_fixture_as_print():
    frame = flat_print_frame()

    # Precondition, so a fixture that drifts out of the print band fails loudly
    # instead of silently testing another branch.
    assert cv2.Laplacian(cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY), cv2.CV_64F).var() < (
        MockPADEngine._TEXTURE_VARIANCE_ATTACK_THRESHOLD
    )

    verdict = MockPADEngine().detect(frame)

    assert verdict.is_attack is True
    assert verdict.attack_type == "print"
    assert verdict.confidence > 0.0


def test_pad_flags_dark_textured_fixture_as_video_replay():
    frame = dark_textured_frame()

    # Under-exposure is the branch's condition; the frame must still carry
    # enough texture to miss the (earlier) print branch.
    assert brightness_score(frame) < 0.15
    assert cv2.Laplacian(cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY), cv2.CV_64F).var() >= (
        MockPADEngine._TEXTURE_VARIANCE_ATTACK_THRESHOLD
    )

    verdict = MockPADEngine().detect(frame)

    assert verdict.is_attack is True
    assert verdict.attack_type == "video_replay"


def test_pad_verdicts_are_deterministic_across_engine_instances():
    frame = periodic_screen_frame()

    first = MockPADEngine().detect(frame)
    second = MockPADEngine().detect(frame)
    decoded = MockPADEngine().detect(decode_base64_frame(encode(frame)))

    assert (first.is_attack, first.attack_type, first.confidence) == (
        second.is_attack,
        second.attack_type,
        second.confidence,
    )
    assert decoded.attack_type == first.attack_type


# --------------------------------------------------------------------------
# Liveness heuristics
# --------------------------------------------------------------------------

def test_pad_and_passive_scoring_run_on_a_decoded_frame_without_raising():
    """Regression: cv2.Laplacian on the float32 copy raised for every frame."""
    decoded = decode_base64_frame(encode(photo_like_frame()))

    verdict = MockPADEngine().detect(decoded)
    observation = MockLivenessEngine().observe_face(decoded)

    assert verdict.is_attack is False
    assert observation.face_detected is False  # synthetic content, no Haar face
    assert observation.bbox is None


def test_liveness_passive_score_prefers_the_sharper_frame():
    engine = MockLivenessEngine()
    crisp = sheet_frame()
    blurred = sheet_frame(blur_sigma=8.0)

    assert sharpness_score(crisp) > sharpness_score(blurred)

    observation = face_observation_covering(crisp)
    crisp_score = engine.score_passive(crisp, observation)
    blurred_score = engine.score_passive(blurred, observation)

    assert crisp_score > blurred_score + 0.05
    for score in (crisp_score, blurred_score):
        assert 0.0 <= score <= 1.0


def test_liveness_no_face_frame_scores_zero_and_reports_no_quality():
    frame = blank_frame()
    engine = MockLivenessEngine()

    assert detect_faces(frame) == []  # the detector agrees: nothing face-like here
    observation = engine.observe_face(frame)

    assert observation.face_detected is False
    assert observation.multiple_faces is False
    assert observation.face_quality is None
    assert observation.bbox is None
    assert engine.score_passive(frame, observation) == 0.0


def test_liveness_active_challenge_fails_closed_without_faces():
    engine = MockLivenessEngine()
    frame = photo_like_frame()

    assert engine.validate_active(ChallengeType.BLINK, [frame]) is False
    assert engine.validate_active(ChallengeType.BLINK, [frame, frame]) is False
    assert engine.validate_active(ChallengeType.TURN_LEFT, [frame, frame]) is False


def test_liveness_observe_face_computes_documented_quality_with_detector_stubbed(monkeypatch):
    """Detector stubbed (Haar cannot fire on synthetic frames); scoring math left real."""
    import app.services.liveness_engine as liveness_engine

    frame = photo_like_frame()
    bbox = (10, 10, 200, 200)
    monkeypatch.setattr(liveness_engine, "detect_faces", lambda _frame: [bbox])

    engine = MockLivenessEngine()
    observation = engine.observe_face(frame)

    assert observation.face_detected is True
    assert observation.multiple_faces is False
    assert observation.bbox == bbox
    expected_quality = 0.5 * sharpness_score(frame) + 0.5 * brightness_score(frame)
    assert observation.face_quality == pytest.approx(expected_quality)

    # The blend is the documented one: quality, detected-eye symmetry, sharpness.
    face_roi = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)[bbox[1]: bbox[1] + bbox[3], bbox[0]: bbox[0] + bbox[2]]
    eye_symmetry = 1.0 if len(detect_eyes(face_roi)) >= 2 else 0.4
    expected_score = 0.4 * expected_quality + 0.4 * eye_symmetry + 0.2 * sharpness_score(frame)
    assert engine.score_passive(frame, observation) == pytest.approx(min(1.0, expected_score))
