"""
Orchestrates the passive -> active liveness/PAD decision flow described in
the spec:

  1. Passive liveness + PAD run on every incoming frame while stage=PASSIVE.
  2. PAD attack detected at any stage -> immediate hard reject (no retry as
     a "quality miss" - logged distinctly, session marked FAILED unless
     retries remain per policy).
  3. Passive score >= threshold and no PAD -> proceed (session PASSED).
  4. Passive score < threshold -> escalate to ACTIVE stage, issue a
     dynamically selected challenge.
  5. Active challenge validated -> proceed (PASSED). Failed/timeout ->
     retry (new challenge) up to max_retry_count, then the terminal action
     the session's on_repeated_failure policy selects (`locked` / `failed` /
     `escalate_to_operator`, matching the Spring service).

This module is transport-agnostic: it takes decoded frames/DB session and
returns plain result objects; the API layer turns those into HTTP responses.
"""
import uuid
from dataclasses import dataclass

import numpy as np
from sqlalchemy.orm import Session as DBSession

from app.crud import session as session_crud
from app.crud import audit as audit_crud
from app.crud.config import get_or_seed_policy
from app.models.enums import LivenessStage, SessionStatus, ChallengeType, ChallengeStatus, FailurePolicy
from app.models.liveness_session import LivenessSession
from app.services.challenge_selector import select_challenge
from app.services.liveness_engine import get_liveness_engine
from app.services.pad_engine import get_pad_engine


@dataclass
class FrameDecision:
    stage: LivenessStage
    face_detected: bool
    multiple_faces: bool
    face_quality: float | None
    liveness_score: float | None
    pad_flag: bool
    pad_attack_type: str | None
    action: str  # proceed | escalate_to_active | reject | retry_passive
    message: str
    challenge_id: uuid.UUID | None = None
    challenge_type: str | None = None
    challenge_timeout_ms: int | None = None
    attempt_number: int | None = None


@dataclass
class ChallengeDecision:
    passed: bool
    action: str  # proceed | retry_challenge | reject | locked |
                 # escalate_to_operator | failed
    message: str
    # Set only on a terminal budget-exhausted verdict: True when the frozen
    # policy lets the client open a fresh session (ALLOW_RETRY), False for
    # LOCK/ESCALATE. None on every non-terminal verdict.
    may_retry_session: bool | None = None


@dataclass(frozen=True)
class RepeatedFailureAction:
    """Outcome of exhausting the retry budget."""
    action: str
    failure_reason: str
    may_retry_session: bool
    message: str


def _finalize(db: DBSession, sess: LivenessSession, passed: bool, reason: str | None = None) -> None:
    from sqlalchemy import func
    sess.final_result = passed
    sess.status = SessionStatus.PASSED if passed else SessionStatus.FAILED
    sess.current_stage = LivenessStage.COMPLETED
    sess.failure_reason = reason
    sess.closed_at = func.now()
    db.commit()
    audit_crud.log_event(
        db, sess.id, "SESSION_PASSED" if passed else "SESSION_FAILED", {"reason": reason}
    )


def process_frame(db: DBSession, sess: LivenessSession, frame: np.ndarray) -> FrameDecision:
    policy = get_or_seed_policy(db, sess.workflow_type)
    liveness_engine = get_liveness_engine()
    pad_engine = get_pad_engine()

    if not policy.liveness_enabled:
        session_crud.add_frame_event(
            db, sess.id, stage=sess.current_stage, face_detected=True, multiple_faces=False,
            liveness_score=1.0, pad_flag=False,
        )
        _finalize(db, sess, True, None)
        return FrameDecision(
            stage=LivenessStage.COMPLETED, face_detected=True, multiple_faces=False, face_quality=None,
            liveness_score=1.0, pad_flag=False, pad_attack_type=None, action="proceed",
            message="Liveness verification disabled by policy; capture accepted.",
        )

    observation = liveness_engine.observe_face(frame)
    pad_result = pad_engine.detect(frame)

    # --- Face-quality gate before scoring liveness ---
    if not observation.face_detected:
        session_crud.add_frame_event(
            db, sess.id, stage=sess.current_stage, face_detected=False, multiple_faces=False,
            pad_flag=pad_result.is_attack, pad_attack_type=pad_result.attack_type,
            pad_confidence=pad_result.confidence,
        )
        return FrameDecision(
            stage=sess.current_stage, face_detected=False, multiple_faces=False, face_quality=None,
            liveness_score=None, pad_flag=pad_result.is_attack, pad_attack_type=pad_result.attack_type,
            action="retry_passive", message="No face detected. Please position your face in the frame.",
        )

    if observation.multiple_faces:
        session_crud.add_frame_event(
            db, sess.id, stage=sess.current_stage, face_detected=True, multiple_faces=True,
            pad_flag=pad_result.is_attack, pad_attack_type=pad_result.attack_type,
            pad_confidence=pad_result.confidence,
        )
        return FrameDecision(
            stage=sess.current_stage, face_detected=True, multiple_faces=True, face_quality=None,
            liveness_score=None, pad_flag=pad_result.is_attack, pad_attack_type=pad_result.attack_type,
            action="retry_passive", message="Multiple faces detected. Only one person may be captured at a time.",
        )

    # --- PAD gate: hard reject on attack, independent of liveness score ---
    if pad_result.is_attack:
        session_crud.add_frame_event(
            db, sess.id, stage=sess.current_stage, face_detected=True, multiple_faces=False,
            face_quality=observation.face_quality, pad_flag=True, pad_attack_type=pad_result.attack_type,
            pad_confidence=pad_result.confidence,
        )
        audit_crud.log_event(
            db, sess.id, "PAD_REJECTED",
            {"attack_type": pad_result.attack_type, "confidence": pad_result.confidence, "stage": sess.current_stage.value},
        )
        _finalize(db, sess, False, f"presentation_attack:{pad_result.attack_type}")
        return FrameDecision(
            stage=LivenessStage.COMPLETED, face_detected=True, multiple_faces=False,
            face_quality=observation.face_quality, liveness_score=None, pad_flag=True,
            pad_attack_type=pad_result.attack_type, action="reject",
            message="Face verification could not be completed. Please try again.",
        )

    liveness_score = liveness_engine.score_passive(frame, observation)
    session_crud.add_frame_event(
        db, sess.id, stage=sess.current_stage, face_detected=True, multiple_faces=False,
        face_quality=observation.face_quality, liveness_score=liveness_score, pad_flag=False,
        pad_confidence=pad_result.confidence,
    )

    if liveness_score >= policy.passive_threshold:
        _finalize(db, sess, True, None)
        return FrameDecision(
            stage=LivenessStage.COMPLETED, face_detected=True, multiple_faces=False,
            face_quality=observation.face_quality, liveness_score=liveness_score, pad_flag=False,
            pad_attack_type=None, action="proceed", message="Liveness verified.",
        )

    # --- Escalate to active liveness ---
    if not policy.active_liveness_enabled:
        _finalize(db, sess, False, "passive_liveness_below_threshold")
        return FrameDecision(
            stage=LivenessStage.COMPLETED, face_detected=True, multiple_faces=False,
            face_quality=observation.face_quality, liveness_score=liveness_score, pad_flag=False,
            pad_attack_type=None, action="reject",
            message="We could not verify face liveness. Please try again.",
        )

    sess.current_stage = LivenessStage.ACTIVE
    db.commit()

    previous = session_crud.last_challenge(db, sess.id)
    challenge_type: ChallengeType = select_challenge(
        policy.challenge_types, previous.challenge_type.value if previous else None
    )
    attempt_number = (previous.attempt_number + 1) if previous else 1

    challenge = session_crud.add_challenge(
        db, sess.id, challenge_type=challenge_type, status=ChallengeStatus.ISSUED,
        attempt_number=attempt_number, timeout_ms=policy.challenge_timeout_ms,
    )
    audit_crud.log_event(
        db, sess.id, "CHALLENGE_ISSUED",
        {"challenge_type": challenge_type.value, "attempt_number": attempt_number},
    )

    return FrameDecision(
        stage=LivenessStage.ACTIVE, face_detected=True, multiple_faces=False,
        face_quality=observation.face_quality, liveness_score=liveness_score, pad_flag=False,
        pad_attack_type=None, action="escalate_to_active",
        message=f"Please {challenge_type.value.replace('_', ' ')}.",
        challenge_id=challenge.id, challenge_type=challenge_type.value,
        challenge_timeout_ms=policy.challenge_timeout_ms, attempt_number=attempt_number,
    )


def process_challenge_validation(
    db: DBSession, sess: LivenessSession, challenge, frames: list[np.ndarray]
) -> ChallengeDecision:
    policy = get_or_seed_policy(db, sess.workflow_type)
    liveness_engine = get_liveness_engine()
    pad_engine = get_pad_engine()

    # Re-run PAD across the challenge frames too — an attacker could pass PAD on
    # frame 1 and swap media mid-challenge.
    for frame in frames:
        pad_result = pad_engine.detect(frame)
        if pad_result.is_attack:
            challenge.status = ChallengeStatus.FAILED
            from sqlalchemy import func
            challenge.completed_at = func.now()
            db.commit()
            audit_crud.log_event(
                db, sess.id, "PAD_REJECTED",
                {"attack_type": pad_result.attack_type, "confidence": pad_result.confidence, "stage": "active"},
            )
            _finalize(db, sess, False, f"presentation_attack:{pad_result.attack_type}")
            return ChallengeDecision(
                passed=False, action="reject",
                message="Face verification could not be completed. Please try again.",
            )

    passed = liveness_engine.validate_active(challenge.challenge_type, frames)
    from sqlalchemy import func
    challenge.completed_at = func.now()

    if passed:
        challenge.status = ChallengeStatus.PASSED
        db.commit()
        audit_crud.log_event(db, sess.id, "CHALLENGE_PASSED", {"challenge_type": challenge.challenge_type.value})

        challenges_completed = session_crud.count_challenges(db, sess.id)
        if challenges_completed >= policy.min_challenge_count:
            _finalize(db, sess, True, None)
            return ChallengeDecision(passed=True, action="proceed", message="Liveness verified.")

        # Need more challenges to satisfy min_challenge_count.
        return ChallengeDecision(
            passed=True, action="retry_challenge",
            message="Action detected. One more check required.",
        )

    challenge.status = ChallengeStatus.FAILED
    db.commit()
    audit_crud.log_event(db, sess.id, "CHALLENGE_FAILED", {"challenge_type": challenge.challenge_type.value})

    sess.retry_count += 1
    db.commit()

    if sess.retry_count >= policy.max_retry_count:
        # The policy, not this branch, decides what the client is told: LOCK
        # locks the session out, ESCALATE flags it for an operator, and
        # ALLOW_RETRY is the only one that may open a fresh session. Reporting
        # all three as `reject` made a per-workflow on_repeated_failure stored
        # config rather than behaviour (the Spring service had the same gap).
        # Map FailurePolicy to RepeatedFailureAction for the terminal verdict
        policy_mapping = {
            FailurePolicy.LOCK: RepeatedFailureAction(
                action="locked",
                failure_reason="max_retries_exceeded:locked_out",
                may_retry_session=False,
                message="Face verification failed. Please contact an operator for assistance."
            ),
            FailurePolicy.ESCALATE: RepeatedFailureAction(
                action="escalate_to_operator",
                failure_reason="max_retries_exceeded:escalation_required",
                may_retry_session=False,
                message="Face verification could not be completed. An operator will assist you."
            ),
            FailurePolicy.ALLOW_RETRY: RepeatedFailureAction(
                action="failed",
                failure_reason="max_retries_exceeded",
                may_retry_session=True,
                message="We could not verify face liveness. Please try again."
            )
        }
        outcome = policy_mapping[policy.on_repeated_failure]
        _finalize(db, sess, False, outcome.failure_reason)
        return ChallengeDecision(
            passed=False, action=outcome.action, message=outcome.message,
            may_retry_session=outcome.may_retry_session,
        )

    return ChallengeDecision(
        passed=False, action="retry_challenge",
        message="We could not verify that action. Let's try a different one.",
    )
