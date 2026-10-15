import uuid
from datetime import datetime

from sqlalchemy import String, DateTime, Integer, Enum, Boolean, UUID, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base_class import Base
from app.models.enums import WorkflowType, SessionStatus, LivenessStage


class LivenessSession(Base):
    """
    One liveness/PAD verification attempt for a resident face capture,
    or an operator/supervisor biometric authentication event.
    """
    __tablename__ = "liveness_sessions"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)

    workflow_type: Mapped[WorkflowType] = mapped_column(Enum(WorkflowType, name="workflow_type"), nullable=False, index=True)
    device_id: Mapped[str] = mapped_column(String(128), nullable=False)
    subject_ref: Mapped[str | None] = mapped_column(String(128), nullable=True)  # opaque ref to resident/operator/supervisor id

    status: Mapped[SessionStatus] = mapped_column(
        Enum(SessionStatus, name="session_status"), nullable=False, default=SessionStatus.ACTIVE
    )
    current_stage: Mapped[LivenessStage] = mapped_column(
        Enum(LivenessStage, name="liveness_stage"), nullable=False, default=LivenessStage.PASSIVE
    )

    retry_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    online: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)  # online vs offline capture context

    final_result: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    failure_reason: Mapped[str | None] = mapped_column(String(256), nullable=True)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    frame_events: Mapped[list["FrameEvent"]] = relationship(back_populates="session", cascade="all, delete-orphan")
    challenges: Mapped[list["Challenge"]] = relationship(back_populates="session", cascade="all, delete-orphan")
    audit_logs: Mapped[list["AuditLog"]] = relationship(back_populates="session", cascade="all, delete-orphan")
