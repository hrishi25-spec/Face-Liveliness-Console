import uuid
from sqlalchemy.orm import Session as DBSession

from app.models.audit_log import AuditLog


def log_event(db: DBSession, session_id: uuid.UUID, event_type: str, details: dict | None = None) -> AuditLog:
    entry = AuditLog(session_id=session_id, event_type=event_type, details=details or {})
    db.add(entry)
    db.commit()
    db.refresh(entry)
    return entry


def get_session_audit_log(db: DBSession, session_id: uuid.UUID) -> list[AuditLog]:
    return (
        db.query(AuditLog)
        .filter(AuditLog.session_id == session_id)
        .order_by(AuditLog.created_at.asc())
        .all()
    )
