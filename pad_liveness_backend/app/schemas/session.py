import uuid
from datetime import datetime
from pydantic import BaseModel, Field

from app.models.enums import WorkflowType, SessionStatus, LivenessStage


class SessionCreateRequest(BaseModel):
    workflow_type: WorkflowType
    device_id: str = Field(..., description="Identifier of the L0/L1 biometric device")
    subject_ref: str | None = Field(None, description="Opaque reference to resident/operator/supervisor")
    online: bool = Field(True, description="Whether the Registration Client is online for this session")


class SessionResponse(BaseModel):
    id: uuid.UUID
    workflow_type: WorkflowType
    device_id: str
    status: SessionStatus
    current_stage: LivenessStage
    retry_count: int
    online: bool
    final_result: bool | None
    failure_reason: str | None
    created_at: datetime
    updated_at: datetime
    closed_at: datetime | None

    class Config:
        from_attributes = True


class SessionSummary(BaseModel):
    """Returned when a session is closed — the audit-friendly final outcome."""
    id: uuid.UUID
    workflow_type: WorkflowType
    status: SessionStatus
    final_result: bool | None
    failure_reason: str | None
    total_frames_evaluated: int
    total_challenges_issued: int
    retry_count: int
    duration_ms: int | None

    class Config:
        from_attributes = True
