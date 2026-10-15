import uuid
from sqlalchemy.orm import Session as DBSession

from app.models.liveness_session import LivenessSession
from app.models.frame_event import FrameEvent
from app.models.challenge import Challenge
from app.schemas.session import SessionCreateRequest


def create_session(db: DBSession, req: SessionCreateRequest) -> LivenessSession:
    session = LivenessSession(
        workflow_type=req.workflow_type,
        device_id=req.device_id,
        subject_ref=req.subject_ref,
        online=req.online,
    )
    db.add(session)
    db.commit()
    db.refresh(session)
    return session


def get_session(db: DBSession, session_id: uuid.UUID) -> LivenessSession | None:
    return db.query(LivenessSession).filter(LivenessSession.id == session_id).one_or_none()


def add_frame_event(db: DBSession, session_id: uuid.UUID, **kwargs) -> FrameEvent:
    event = FrameEvent(session_id=session_id, **kwargs)
    db.add(event)
    db.commit()
    db.refresh(event)
    return event


def add_challenge(db: DBSession, session_id: uuid.UUID, **kwargs) -> Challenge:
    challenge = Challenge(session_id=session_id, **kwargs)
    db.add(challenge)
    db.commit()
    db.refresh(challenge)
    return challenge


def get_challenge(db: DBSession, challenge_id: uuid.UUID) -> Challenge | None:
    return db.query(Challenge).filter(Challenge.id == challenge_id).one_or_none()


def count_frame_events(db: DBSession, session_id: uuid.UUID) -> int:
    return db.query(FrameEvent).filter(FrameEvent.session_id == session_id).count()


def count_challenges(db: DBSession, session_id: uuid.UUID) -> int:
    return db.query(Challenge).filter(Challenge.session_id == session_id).count()


def last_challenge(db: DBSession, session_id: uuid.UUID) -> Challenge | None:
    return (
        db.query(Challenge)
        .filter(Challenge.session_id == session_id)
        .order_by(Challenge.issued_at.desc())
        .first()
    )
