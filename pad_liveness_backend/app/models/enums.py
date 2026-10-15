from enum import Enum


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