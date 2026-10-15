import hmac
import uuid

from fastapi import Depends, Header, HTTPException, status
from sqlalchemy.orm import Session as DBSession

from app.core.config import settings
from app.db.session import get_db
from app.crud import session as session_crud
from app.models.liveness_session import LivenessSession
from app.models.enums import SessionStatus

# Header carrying the admin key; the same name the Spring service used, so a
# client does not need a second spelling for the two backends.
ADMIN_KEY_HEADER = "X-Admin-API-Key"


def require_admin_key(
    x_admin_api_key: str = Header(default="", alias=ADMIN_KEY_HEADER),
) -> None:
    """Refuse a privileged write unless the caller presents the admin API key.

    A dependency rather than a check inside the handler, because a measure that
    lives in a handler is a measure the next handler can forget; anything added
    to a keyed route opts in by listing this dependency.

    **Fail closed.** With no key configured the route is refused, never left
    open. ``PUT /api/v1/config/{workflow_type}`` is the highest-consequence call
    this service has: an unauthenticated caller could set ``passive_threshold``
    to 0 and defeat PAD for every session, which is exactly the failure a
    reference deployment must not ship with.

    The comparison is ``hmac.compare_digest`` over bytes rather than ``==``,
    because both sides are shared secrets and an early-exit comparison leaks the
    configured key's prefix to anyone who can time the response. Both sides are
    encoded explicitly so a non-ASCII header value is refused instead of raising
    a ``TypeError`` that would answer with a 500.

    The refusal names no header on the wrong-key path and nothing derived from
    the key on either path — no fingerprint, no length, no comparison result.
    """
    configured = settings.MOSIP_ADMIN_API_KEY
    if not configured:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "This endpoint is disabled: configure MOSIP_ADMIN_API_KEY "
                "on the service to enable it."
            ),
        )
    if not hmac.compare_digest(
        configured.encode("utf-8"), x_admin_api_key.encode("utf-8")
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="Invalid admin API key."
        )


def get_active_session(session_id: uuid.UUID, db: DBSession = Depends(get_db)) -> LivenessSession:
    sess = session_crud.get_session(db, session_id)
    if sess is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Session not found")
    if sess.status != SessionStatus.ACTIVE:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Session is not active (status={sess.status.value})",
        )
    return sess
