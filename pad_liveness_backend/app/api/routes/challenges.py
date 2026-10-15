from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session as DBSession

from app.db.session import get_db
from app.api.deps import get_active_session
from app.crud import session as session_crud
from app.models.liveness_session import LivenessSession
from app.models.enums import ChallengeStatus
from app.schemas.challenge import ChallengeValidateRequest, ChallengeValidationResult, ChallengeResponse
from app.services.image_utils import decode_base64_frame, InvalidFrameError
from app.services.decision_engine import process_challenge_validation

router = APIRouter(prefix="/sessions/{session_id}/challenges", tags=["challenges"])


@router.post("/validate", response_model=ChallengeValidationResult)
def validate_challenge(
    req: ChallengeValidateRequest,
    sess: LivenessSession = Depends(get_active_session),
    db: DBSession = Depends(get_db),
):
    """
    Submit the frame stream captured while an active-liveness challenge was
    displayed (e.g. the frames captured during "please blink"). Returns
    whether the requested facial action was detected and the next action
    the client should take: `proceed`, `retry_challenge` (a new challenge was
    issued), or the terminal action the session's repeated-failure policy
    selects — `locked`, `escalate_to_operator` or `failed` (with
    `may_retry_session` saying whether a fresh session is allowed).
    """
    challenge = session_crud.get_challenge(db, req.challenge_id)
    if challenge is None or challenge.session_id != sess.id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Challenge not found for this session")
    if challenge.status != ChallengeStatus.ISSUED:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Challenge is not awaiting validation (status={challenge.status.value})",
        )

    try:
        frames = [decode_base64_frame(f) for f in req.frames_base64]
    except InvalidFrameError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc

    result = process_challenge_validation(db, sess, challenge, frames)
    db.refresh(challenge)

    return ChallengeValidationResult(
        session_id=sess.id,
        challenge=ChallengeResponse.model_validate(challenge),
        passed=result.passed,
        action=result.action,
        may_retry_session=result.may_retry_session,
        message=result.message,
    )
