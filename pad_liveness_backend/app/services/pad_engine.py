"""
Presentation Attack Detection (PAD) engine interface.

This module defines the vendor-agnostic contract the rest of the system
codes against (BasePADEngine), plus a heuristic MockPADEngine that stands
in until a real ISO/IEC 30107-3 evaluated model/SDK is integrated.

To integrate a real PAD model (texture-based CNN, frequency-domain moire
detection, depth/IR sensor fusion, a licensed vendor SDK, etc.), implement
BasePADEngine and wire it up in get_pad_engine() below — nothing else in
the codebase needs to change.
"""
from abc import ABC, abstractmethod
from dataclasses import dataclass
from functools import lru_cache

import cv2
import numpy as np

from app.services.image_utils import brightness_score


@lru_cache(maxsize=8)
def _outer_band_mask(height: int, width: int) -> np.ndarray:
    """Read-only mask of the outer half of an (height, width) spectrum plane.

    Cached per frame size: building it costs ~40 ms on a 720p frame, which is
    most of the signal's cost, and the mask never changes for a given shape.
    """
    yy, xx = np.mgrid[0:height, 0:width]
    center_y, center_x = height // 2, width // 2
    return np.hypot(yy - center_y, xx - center_x) > (min(height, width) / 4.0)


@dataclass
class PADResult:
    is_attack: bool
    attack_type: str | None      # e.g. "print", "screen_replay", "video_replay"
    confidence: float            # 0..1 confidence in the verdict


class BasePADEngine(ABC):
    @abstractmethod
    def detect(self, frame: np.ndarray) -> PADResult:
        """Analyse a single frame and return a presentation-attack verdict."""
        raise NotImplementedError


class MockPADEngine(BasePADEngine):
    """
    Heuristic placeholder PAD engine. Uses two cheap, well-known signals as
    stand-ins for real PAD models:

      1. Moire / screen-replay proxy: high-frequency FFT energy ratio, which
         tends to spike when photographing a digital screen.
      2. Print-attack proxy: local color/texture variance, which tends to be
         lower and flatter on a printed photo than on live skin.

    These are NOT production-grade PAD signals — replace with a properly
    trained and ISO/IEC 30107-3 evaluated model before go-live.
    """

    # Placeholder calibration measured on the synthetic fixtures in
    # tests/test_opencv_heuristics.py: periodic screen structure lands at ~1.0,
    # smooth photo-like content at ~0.4, flat printed paper at ~0.1. Real
    # capture hardware needs its own band before this threshold means anything.
    _FREQ_ENERGY_ATTACK_THRESHOLD = 0.6
    _TEXTURE_VARIANCE_ATTACK_THRESHOLD = 15.0

    def detect(self, frame: np.ndarray) -> PADResult:
        gray_u8 = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        gray = gray_u8.astype(np.float32)

        # --- Screen-replay / moire proxy via high-frequency spectral energy ---
        # Share of the non-DC spectrum that sits in the outer half of the
        # frequency plane. Two earlier forms of this signal were unusable: the
        # log-compressed magnitude plus a whole-plane sum sat at ~0.9 for every
        # image (so the branch below fired on all frames, attack or not), and a
        # linear ratio over the whole plane barely moved between a smooth photo
        # and a checkerboard. Log compression destroys the comparison, and the
        # DC term has to be excluded, so keep the linear spectrum and normalise
        # against everything but DC.
        spectrum = np.abs(np.fft.fftshift(np.fft.fft2(gray)))
        h, w = spectrum.shape
        outer_band = _outer_band_mask(h, w)
        non_dc = float(spectrum.sum()) - float(spectrum[h // 2, w // 2])
        freq_ratio = float(spectrum[outer_band].sum() / (non_dc + 1e-6))

        # --- Print-attack proxy via local texture variance ---
        # The Laplacian needs a depth pair OpenCV supports (8U source to CV_64F
        # destination). On the float32 copy used for the FFT it raises
        # "Unsupported combination of source format ... destination format",
        # which nothing noticed while every caller stubbed this engine.
        texture_variance = float(cv2.Laplacian(gray_u8, cv2.CV_64F).var())

        # --- Sanity: extreme over/under exposure often accompanies a replay attack ---
        brightness = brightness_score(frame)

        if freq_ratio > self._FREQ_ENERGY_ATTACK_THRESHOLD:
            return PADResult(is_attack=True, attack_type="screen_replay", confidence=min(1.0, freq_ratio))

        if texture_variance < self._TEXTURE_VARIANCE_ATTACK_THRESHOLD:
            confidence = min(1.0, 1.0 - (texture_variance / self._TEXTURE_VARIANCE_ATTACK_THRESHOLD))
            return PADResult(is_attack=True, attack_type="print", confidence=confidence)

        if brightness < 0.15:
            return PADResult(is_attack=True, attack_type="video_replay", confidence=0.6)

        # Bona fide verdict; confidence expresses how comfortably it cleared the thresholds.
        margin = min(
            1.0,
            (self._FREQ_ENERGY_ATTACK_THRESHOLD - freq_ratio) / self._FREQ_ENERGY_ATTACK_THRESHOLD,
        )
        return PADResult(is_attack=False, attack_type=None, confidence=max(0.5, margin))


_engine_instance: BasePADEngine | None = None


def get_pad_engine() -> BasePADEngine:
    global _engine_instance
    if _engine_instance is None:
        _engine_instance = MockPADEngine()
    return _engine_instance
