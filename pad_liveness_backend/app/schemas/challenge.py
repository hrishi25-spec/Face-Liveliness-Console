import uuid
from datetime import datetime
from typing import Annotated
from pydantic import BaseModel, Field

from app.models.enums import ChallengeType, ChallengeStatus


FrameBase64 = Annotated[str, Field(max_length=8_000_000)]


class ChallengeValidateRequest(BaseModel):
    challenge_id: uuid.UUID
    frames_base64: list[FrameBase64] = Field(
        ...,
        min_length=1,
        max_length=60,
        description="Ordered frame stream captured while the challenge was active",
    )


class ChallengeResponse(BaseModel):
    id: uuid.UUID
    session_id: uuid.UUID
    challenge_type: ChallengeType
    status: ChallengeStatus
    attempt_number: int
    timeout_ms: int
    issued_at: datetime
    completed_at: datetime | None

    class Config:
        from_attributes = True


class ChallengeValidationResult(BaseModel):
    session_id: uuid.UUID
    challenge: ChallengeResponse
    passed: bool
    action: str = Field(
        ...,
        description="One of: proceed | retry_challenge | reject | locked | escalate_to_operator | failed",
    )
    may_retry_session: bool | None = Field(
        None,
        description=(
            "Set only on a terminal budget-exhausted verdict: true when the policy lets the "
            "client open a fresh session (failed), false for locked / escalate_to_operator. "
            "Never a grant of another challenge inside this session."
        ),
    )
    message: str
