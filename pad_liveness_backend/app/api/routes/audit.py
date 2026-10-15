import uuid

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session as DBSession

from app.db.session import get_db
from app.crud.audit import get_session_audit_log

router = APIRouter(prefix="/sessions/{session_id}/audit", tags=["audit"])


@router.get("")
def get_audit_trail(session_id: uuid.UUID, db: DBSession = Depends(get_db)):
    """
    Full structured audit trail for a session (session created, each
    decision, PAD rejections, challenges issued/passed/failed, final
    outcome) — for diagnostics and compliance review, not for the end-user UI.
    """
    entries = get_session_audit_log(db, session_id)
    return [
        {
            "id": str(e.id),
            "event_type": e.event_type,
            "details": e.details,
            "created_at": e.created_at.isoformat(),
        }
        for e in entries
    ]
