import uuid
from datetime import datetime

from sqlalchemy import Integer, DateTime, Enum, ForeignKey, UUID, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base_class import Base
from app.models.enums import ChallengeType, ChallengeStatus


class Challenge(Base):
    """
    A single active-liveness challenge issued to the user (e.g. 'blink'),
    and the outcome of validating it against the subsequent frame stream.
    """
    __tablename__ = "challenges"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    session_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("liveness_sessions.id"), nullable=False, index=True)

    challenge_type: Mapped[ChallengeType] = mapped_column(Enum(ChallengeType, name="challenge_type"), nullable=False)
    status: Mapped[ChallengeStatus] = mapped_column(
        Enum(ChallengeStatus, name="challenge_status"), nullable=False, default=ChallengeStatus.ISSUED
    )
    attempt_number: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    timeout_ms: Mapped[int] = mapped_column(Integer, nullable=False)

    issued_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    session: Mapped["LivenessSession"] = relationship(back_populates="challenges")
