import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, JSON, String, UUID, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base_class import Base


class AuditLog(Base):
    """
    Structured, append-only audit trail for every liveness/PAD decision.
    Kept separate from FrameEvent so it can capture higher-level events
    (session created, challenge issued, session closed, PAD rejection)
    without being tied to a single frame.
    """
    __tablename__ = "audit_logs"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    session_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("liveness_sessions.id"), nullable=False, index=True)

    event_type: Mapped[str] = mapped_column(String(64), nullable=False)  # e.g. SESSION_CREATED, PAD_REJECTED, CHALLENGE_ISSUED
    details: Mapped[dict] = mapped_column(JSON().with_variant(JSONB, "postgresql"), nullable=False, default=dict)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    session: Mapped["LivenessSession"] = relationship(back_populates="audit_logs")
