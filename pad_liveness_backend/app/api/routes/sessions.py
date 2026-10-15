import uuid

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session as DBSession

from app.db.session import get_db
from app.crud import session as session_crud
from app.crud import audit as audit_crud
from app.api.deps import get_active_session
from app.models.enums import SessionStatus
from app.models.liveness_session import LivenessSession
from app.schemas.session import SessionCreateRequest, SessionResponse, SessionSummary

router = APIRouter(prefix="/sessions", tags=["sessions"])


@router.post("", response_model=SessionResponse, status_code=status.HTTP_201_CREATED)
def create_session(req: SessionCreateRequest, db: DBSession = Depends(get_db)):
    """
    Start a new liveness/PAD verification session for a resident face
    capture, or an operator/supervisor authentication attempt.
    """
    sess = session_crud.create_session(db, req)
    audit_crud.log_event(
        db, sess.id, "SESSION_CREATED",
        {"workflow_type": req.workflow_type.value, "device_id": req.device_id, "online": req.online},
    )
    return sess


@router.get("/{session_id}", response_model=SessionResponse)
def get_session(session_id: uuid.UUID, db: DBSession = Depends(get_db)):
    sess = session_crud.get_session(db, session_id)
    if sess is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Session not found")
    return sess


@router.post("/{session_id}/close", response_model=SessionSummary)
def close_session(session_id: uuid.UUID, db: DBSession = Depends(get_db)):
    """
    Explicitly close a session (e.g. user abandoned the flow, or the client
    wants a final summary record for its own audit trail).
    """
    sess = session_crud.get_session(db, session_id)
    if sess is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Session not found")

    if sess.status == SessionStatus.ACTIVE:
        from sqlalchemy import func
        sess.status = SessionStatus.EXPIRED
        sess.closed_at = func.now()
        db.commit()
        audit_crud.log_event(db, sess.id, "SESSION_EXPIRED", {})

    duration_ms = None
    if sess.closed_at and sess.created_at:
        duration_ms = int((sess.closed_at - sess.created_at).total_seconds() * 1000)

    return SessionSummary(
        id=sess.id,
        workflow_type=sess.workflow_type,
        status=sess.status,
        final_result=sess.final_result,
        failure_reason=sess.failure_reason,
        total_frames_evaluated=session_crud.count_frame_events(db, sess.id),
        total_challenges_issued=session_crud.count_challenges(db, sess.id),
        retry_count=sess.retry_count,
        duration_ms=duration_ms,
    )
