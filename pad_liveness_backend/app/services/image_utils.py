"""
Shared helpers for decoding and quality-checking incoming frames.
Kept isolated from the engine implementations so engines only deal with
numpy arrays, not transport-layer concerns (base64, content-type, etc.).
"""
import base64
import binascii

import cv2
import numpy as np


class InvalidFrameError(ValueError):
    pass


def decode_base64_frame(frame_base64: str) -> np.ndarray:
    """Decode a base64-encoded JPEG/PNG string into a BGR numpy image array."""
    try:
        # Strip a data URL prefix if present, e.g. "data:image/jpeg;base64,...."
        if "," in frame_base64[:64]:
            frame_base64 = frame_base64.split(",", 1)[1]
        raw = base64.b64decode(frame_base64, validate=False)
    except (binascii.Error, ValueError) as exc:
        raise InvalidFrameError("Frame is not valid base64 data") from exc

    if not raw:
        raise InvalidFrameError("Empty frame payload")

    buffer = np.frombuffer(raw, dtype=np.uint8)
    image = cv2.imdecode(buffer, cv2.IMREAD_COLOR)
    if image is None:
        raise InvalidFrameError("Frame could not be decoded as an image")
    return image


_FACE_CASCADE = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
_EYE_CASCADE = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_eye.xml")


def detect_faces(image: np.ndarray) -> list[tuple[int, int, int, int]]:
    """Return bounding boxes (x, y, w, h) for detected faces. Placeholder detector —
    swap for a production-grade face detector (RetinaFace/MTCNN/vendor SDK) as needed."""
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    faces = _FACE_CASCADE.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5, minSize=(60, 60))
    return list(faces)


def detect_eyes(face_gray_roi: np.ndarray) -> list[tuple[int, int, int, int]]:
    return list(_EYE_CASCADE.detectMultiScale(face_gray_roi, scaleFactor=1.1, minNeighbors=8))


def sharpness_score(image: np.ndarray) -> float:
    """Variance of Laplacian — a simple, fast proxy for focus/blur quality (0..1 normalized)."""
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    variance = cv2.Laplacian(gray, cv2.CV_64F).var()
    # Empirical normalization band; tune against real capture hardware.
    return float(np.clip(variance / 500.0, 0.0, 1.0))


def brightness_score(image: np.ndarray) -> float:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    mean = float(np.mean(gray))
    # Penalize too dark / too bright, peak around mid-gray.
    return float(np.clip(1.0 - abs(mean - 128.0) / 128.0, 0.0, 1.0))
