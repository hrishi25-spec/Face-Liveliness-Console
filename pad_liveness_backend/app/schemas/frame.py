import uuid
from datetime import datetime
from pydantic import BaseModel, Field

from app.models.enums import LivenessStage


class FrameSubmitRequest(BaseModel):
    frame_base64: str = Field(..., description="Single JPEG/PNG frame, base64-encoded")


class ChallengeInfo(BaseModel):
    challenge_id: uuid.UUID
    challenge_type: str
    timeout_ms: int
    attempt_number: int


class FrameProcessResult(BaseModel):
    session_id: uuid.UUID
    stage: LivenessStage
    face_detected: bool
    multiple_faces: bool
    face_quality: float | None
    liveness_score: float | None
    pad_flag: bool
    pad_attack_type: str | None
    action: str = Field(
        ..., description="One of: proceed | escalate_to_active | reject | retry_passive"
    )
    challenge: ChallengeInfo | None = None
    message: str

    class Config:
        from_attributes = True


class FrameEventResponse(BaseModel):
    id: uuid.UUID
    session_id: uuid.UUID
    stage: LivenessStage
    face_detected: bool
    multiple_faces: bool
    face_quality: float | None
    liveness_score: float | None
    pad_flag: bool
    pad_attack_type: str | None
    pad_confidence: float | None
    created_at: datetime

    class Config:
        from_attributes = True
