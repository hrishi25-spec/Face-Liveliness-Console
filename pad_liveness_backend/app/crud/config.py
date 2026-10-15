from sqlalchemy.orm import Session as DBSession

from app.core.config import settings
from app.models.config_policy import ConfigPolicy
from app.models.enums import WorkflowType, FailurePolicy
from app.schemas.config import ConfigPolicyUpdate


def get_or_seed_policy(db: DBSession, workflow_type: WorkflowType) -> ConfigPolicy:
    policy = db.query(ConfigPolicy).filter(ConfigPolicy.workflow_type == workflow_type).one_or_none()
    if policy is not None:
        return policy

    policy = ConfigPolicy(
        workflow_type=workflow_type,
        liveness_enabled=True,
        passive_threshold=settings.DEFAULT_PASSIVE_THRESHOLD,
        active_liveness_enabled=settings.DEFAULT_ACTIVE_LIVENESS_ENABLED,
        min_challenge_count=settings.DEFAULT_MIN_CHALLENGE_COUNT,
        challenge_types=settings.DEFAULT_CHALLENGE_TYPES,
        challenge_timeout_ms=settings.DEFAULT_CHALLENGE_TIMEOUT_MS,
        max_retry_count=settings.DEFAULT_MAX_RETRY_COUNT,
        on_repeated_failure=FailurePolicy.LOCK,
    )
    db.add(policy)
    db.commit()
    db.refresh(policy)
    return policy


def update_policy(db: DBSession, workflow_type: WorkflowType, update: ConfigPolicyUpdate) -> ConfigPolicy:
    policy = get_or_seed_policy(db, workflow_type)
    for field, value in update.model_dump().items():
        setattr(policy, field, value)
    db.commit()
    db.refresh(policy)
    return policy
