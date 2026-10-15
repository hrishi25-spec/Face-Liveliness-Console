"""
Selects the next active-liveness challenge. Dynamic/unpredictable by design
per spec: the user never picks the challenge, and the same type should not
repeat back-to-back within a session.
"""
import random

from app.models.enums import ChallengeType


def select_challenge(allowed_types: list[str], previous_type: str | None = None) -> ChallengeType:
    candidates = [ChallengeType(t) for t in allowed_types] or list(ChallengeType)
    if previous_type and len(candidates) > 1:
        candidates = [c for c in candidates if c.value != previous_type] or candidates
    return random.choice(candidates)
