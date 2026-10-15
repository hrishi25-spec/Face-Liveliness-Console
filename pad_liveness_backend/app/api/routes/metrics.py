from fastapi import APIRouter, Depends
from sqlalchemy import func
from sqlalchemy.orm import Session as DBSession

from app.db.session import get_db
from app.models.liveness_session import LivenessSession
from app.models.frame_event import FrameEvent
from app.models.challenge import Challenge
from app.models.enums import SessionStatus, LivenessStage, ChallengeStatus
from app.schemas.metrics import OperationalMetrics

router = APIRouter(prefix="/metrics", tags=["metrics"])


@router.get("", response_model=OperationalMetrics)
def get_operational_metrics(db: DBSession = Depends(get_db)):
    """
    Anonymized, aggregate operational metrics (no biometric data, no
    subject identifiers) — average processing figures and pass/fail/retry
    rates, suitable for dashboards.
    """
    total_sessions = db.query(func.count(LivenessSession.id)).scalar() or 0
    passed = db.query(func.count(LivenessSession.id)).filter(LivenessSession.status == SessionStatus.PASSED).scalar() or 0
    failed = db.query(func.count(LivenessSession.id)).filter(LivenessSession.status == SessionStatus.FAILED).scalar() or 0
    active = db.query(func.count(LivenessSession.id)).filter(LivenessSession.status == SessionStatus.ACTIVE).scalar() or 0

    escalated = (
        db.query(func.count(func.distinct(FrameEvent.session_id)))
        .filter(FrameEvent.stage == LivenessStage.ACTIVE)
        .scalar()
        or 0
    )

    total_frames = db.query(func.count(FrameEvent.id)).scalar() or 0
    total_challenges = db.query(func.count(Challenge.id)).scalar() or 0
    failed_challenges = db.query(func.count(Challenge.id)).filter(Challenge.status == ChallengeStatus.FAILED).scalar() or 0

    total_retries = db.query(func.coalesce(func.sum(LivenessSession.retry_count), 0)).scalar() or 0

    pad_rejections = db.query(func.count(LivenessSession.id)).filter(
        LivenessSession.failure_reason.ilike("presentation_attack:%")
    ).scalar() or 0

    def safe_div(a, b):
        return round(a / b, 4) if b else 0.0

    return OperationalMetrics(
        total_sessions=total_sessions,
        passed_sessions=passed,
        failed_sessions=failed,
        active_sessions=active,
        pass_rate=safe_div(passed, total_sessions),
        avg_passive_to_active_escalation_rate=safe_div(escalated, total_sessions),
        avg_frames_per_session=safe_div(total_frames, total_sessions),
        avg_challenges_per_session=safe_div(total_challenges, total_sessions),
        liveness_retry_rate=safe_div(total_retries, total_sessions),
        liveness_failure_rate=safe_div(failed, total_sessions),
        pad_rejection_rate=safe_div(pad_rejections, total_sessions),
    )
