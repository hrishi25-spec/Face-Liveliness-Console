from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session as DBSession

from app.db.session import get_db
from app.api.deps import get_active_session
from app.models.liveness_session import LivenessSession
from app.schemas.frame import FrameSubmitRequest, FrameProcessResult, ChallengeInfo
from app.services.image_utils import decode_base64_frame, InvalidFrameError
from app.services.decision_engine import process_frame

router = APIRouter(prefix="/sessions/{session_id}/frames", tags=["frames"])


@router.post("", response_model=FrameProcessResult)
def submit_frame(
    req: FrameSubmitRequest,
    sess: LivenessSession = Depends(get_active_session),
    db: DBSession = Depends(get_db),
):
    """
    Push a single face frame from the L0/L1 device stream. Runs passive
    liveness + PAD; if the passive score is below the configured threshold,
    the session is escalated to an active challenge (see the `challenge`
    field in the response) per the hybrid passive/active flow.
    """
    try:
        frame = decode_base64_frame(req.frame_base64)
    except InvalidFrameError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)) from exc

    decision = process_frame(db, sess, frame)

    challenge = None
    if decision.challenge_id is not None:
        challenge = ChallengeInfo(
            challenge_id=decision.challenge_id,
            challenge_type=decision.challenge_type,
            timeout_ms=decision.challenge_timeout_ms,
            attempt_number=decision.attempt_number,
        )

    return FrameProcessResult(
        session_id=sess.id,
        stage=decision.stage,
        face_detected=decision.face_detected,
        multiple_faces=decision.multiple_faces,
        face_quality=decision.face_quality,
        liveness_score=decision.liveness_score,
        pad_flag=decision.pad_flag,
        pad_attack_type=decision.pad_attack_type,
        action=decision.action,
        challenge=challenge,
        message=decision.message,
    )
