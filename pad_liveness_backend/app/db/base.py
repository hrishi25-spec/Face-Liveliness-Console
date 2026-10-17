"""
Import hub: re-exports ``Base`` and pulls in every model so that
``Base.metadata`` is complete for Alembic autogenerate and ``create_all``.

Because this module imports all models, models themselves must import ``Base``
from :mod:`app.db.base_class` instead — importing it from here would re-create
the cycle that this split exists to avoid.
"""
from app.db.base_class import Base  # noqa: F401

# Import all models so Base.metadata is aware of them for Alembic autogenerate.
from app.models.liveness_session import LivenessSession  # noqa: E402,F401
from app.models.frame_event import FrameEvent  # noqa: E402,F401
from app.models.challenge import Challenge  # noqa: E402,F401
from app.models.config_policy import ConfigPolicy  # noqa: E402,F401
from app.models.audit_log import AuditLog  # noqa: E402,F401
