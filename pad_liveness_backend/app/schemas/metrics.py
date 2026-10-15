from pydantic import BaseModel


class OperationalMetrics(BaseModel):
    total_sessions: int
    passed_sessions: int
    failed_sessions: int
    active_sessions: int
    pass_rate: float
    avg_passive_to_active_escalation_rate: float
    avg_frames_per_session: float
    avg_challenges_per_session: float
    liveness_retry_rate: float
    liveness_failure_rate: float
    pad_rejection_rate: float
