from enum import Enum
from dataclasses import dataclass


@dataclass(frozen=True)
class RepeatedFailureAction:
    """Single source of truth for repeated failure action used by:
    - Java service (io.mosip.liveness.shared.definitions)
    - FastAPI backend (this file)
    - Documentation (docs/configuration.md)

    Behavior applied when the retry budget for active challenges is exhausted.
    All three are terminal and none issues another challenge — extra attempts
    belong in `max_retry_count`, not in the failure action. They differ only
    in the outcome signal and whether the client may open a fresh session.
    The messages are the generic, user-facing strings from the failure table
    in `docs/configuration.md`: they name no detection method and reveal
    nothing about why the challenge failed.
    """

    action: str                 # the terminal action the client reads
    failure_reason: str         # the closed session's failure_reason
    may_retry_session: bool     # may the client open a fresh session
    message: str                # generic, user-facing message


# Single source of truth instances
LOCK_OUT = RepeatedFailureAction(
    action="locked",
    failure_reason="max_retries_exceeded:locked_out",
    may_retry_session=False,
    message="Face verification failed. Please contact an operator for assistance."
)

FALLBACK = RepeatedFailureAction(
    action="failed",
    failure_reason="max_retries_exceeded",
    may_retry_session=True,
    message="We could not verify face liveness. Please try again."
)

ESCALATE_TO_OPERATOR = RepeatedFailureAction(
    action="escalate_to_operator",
    failure_reason="max_retries_exceeded:escalation_required",
    may_retry_session=False,
    message="Face verification could not be completed. An operator will assist you."
)


class WorkflowType(Enum):
    """Workflow as exposed over the REST API and stored in the database.

    Deliberately distinct from io.mosip.liveness.core.WorkflowType (engine layer);
    ConfigService owns the conversion between the two.
    """
    RESIDENT = "resident"
    OPERATOR = "operator"
    SUPERVISOR = "supervisor"


class SessionStatus(Enum):
    """Lifecycle state of a liveness session."""
    ACTIVE = "active"
    PASSED = "passed"
    FAILED = "failed"
    EXPIRED = "expired"


class LivenessStage(Enum):
    """Which part of the liveness pipeline a frame belongs to."""
    PASSIVE = "passive"
    ACTIVE = "active"
    COMPLETED = "completed"


class ChallengeType(Enum):
    """Active-liveness challenge types as exposed over the REST API and stored in
    the challenges table.

    Values are the hyphenated wire values used by the config API, e.g.
    "turn_left".
    """
    BLINK = "blink"
    SMILE = "smile"
    TURN_LEFT = "turn_left"
    TURN_RIGHT = "turn_right"
    LOOK_DIRECTION = "look_direction"
    LOOK_UP = "look_up"
    LOOK_DOWN = "look_down"
    LOOK_LEFT = "look_left"
    LOOK_RIGHT = "look_right"


class ChallengeStatus(Enum):
    """Lifecycle state of a single active-liveness challenge."""
    ISSUED = "ISSUED"
    PASSED = "PASSED"
    FAILED = "FAILED"
    TIMEOUT = "TIMEOUT"


class FailurePolicy(Enum):
    """What to do once the active-challenge retry budget is exhausted."""
    LOCK = "LOCK"
    ESCALATE = "ESCALATE"
    ALLOW_RETRY = "ALLOW_RETRY"