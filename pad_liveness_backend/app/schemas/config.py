import uuid
from datetime import datetime
from pydantic import BaseModel, Field

from app.models.enums import WorkflowType, FailurePolicy


class ConfigPolicyBase(BaseModel):
    liveness_enabled: bool = True
    passive_threshold: float = Field(0.75, ge=0.0, le=1.0)
    active_liveness_enabled: bool = True
    min_challenge_count: int = Field(1, ge=1, le=5)
    challenge_types: list[str] = Field(default_factory=lambda: ["blink", "smile", "turn_left", "turn_right"])
    challenge_timeout_ms: int = Field(8000, ge=1000, le=60000)
    max_retry_count: int = Field(3, ge=0, le=10)
    on_repeated_failure: FailurePolicy = FailurePolicy.LOCK


class ConfigPolicyUpdate(ConfigPolicyBase):
    pass


class ConfigPolicyResponse(ConfigPolicyBase):
    id: uuid.UUID
    workflow_type: WorkflowType
    updated_at: datetime

    class Config:
        from_attributes = True
