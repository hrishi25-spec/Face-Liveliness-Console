import uuid
from datetime import datetime

from sqlalchemy import Float, DateTime, Enum, Boolean, ForeignKey, String, Uuid, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base_class import Base
from app.models.enums import LivenessStage


class FrameEvent(Base):
    """
    Result of processing a single face frame (or frame batch) through the
    passive liveness + PAD engines. One row per evaluated frame/batch.
    """
    __tablename__ = "frame_events"

    id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True, default=uuid.uuid4)
    session_id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), ForeignKey("liveness_sessions.id"), nullable=False, index=True)

    stage: Mapped[LivenessStage] = mapped_column(Enum(LivenessStage, name="frame_stage"), nullable=False)

    face_detected: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    multiple_faces: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    face_quality: Mapped[float | None] = mapped_column(Float, nullable=True)

    liveness_score: Mapped[float | None] = mapped_column(Float, nullable=True)

    pad_flag: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    pad_attack_type: Mapped[str | None] = mapped_column(String(64), nullable=True)
    pad_confidence: Mapped[float | None] = mapped_column(Float, nullable=True)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    session: Mapped["LivenessSession"] = relationship(back_populates="frame_events")
