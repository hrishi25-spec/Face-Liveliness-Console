import uuid
from datetime import datetime

from sqlalchemy import Float, Integer, Boolean, DateTime, Enum, JSON, String, Uuid, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base_class import Base
from app.models.enums import WorkflowType, FailurePolicy


class ConfigPolicy(Base):
    """
    Configurable liveness/PAD policy, one row per workflow (resident,
    operator, supervisor). Editable at runtime via the config API without
    a redeploy of the Registration Client.
    """
    __tablename__ = "config_policies"

    id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True, default=uuid.uuid4)
    workflow_type: Mapped[WorkflowType] = mapped_column(
        Enum(WorkflowType, name="config_workflow_type"), unique=True, nullable=False
    )

    liveness_enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    passive_threshold: Mapped[float] = mapped_column(Float, nullable=False, default=0.75)

    active_liveness_enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    min_challenge_count: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    challenge_types: Mapped[list] = mapped_column(JSON().with_variant(JSONB, "postgresql"), nullable=False, default=list)
    challenge_timeout_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=8000)

    max_retry_count: Mapped[int] = mapped_column(Integer, nullable=False, default=3)
    on_repeated_failure: Mapped[FailurePolicy] = mapped_column(
        Enum(FailurePolicy, name="failure_policy"), nullable=False, default=FailurePolicy.LOCK
    )

    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())
