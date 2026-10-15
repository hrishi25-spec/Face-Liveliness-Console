"""
Convenience bootstrap for local/dev use: creates all tables directly from
the SQLAlchemy models and seeds the default per-workflow config policies.

For anything beyond local dev, use Alembic migrations instead:
    alembic revision --autogenerate -m "init"
    alembic upgrade head
"""
import logging

from app.db.base import Base
from app.db.session import engine, SessionLocal
from app.crud.config import get_or_seed_policy
from app.models.enums import WorkflowType

logger = logging.getLogger("pad_liveness.init_db")


def init_db() -> None:
    Base.metadata.create_all(bind=engine)
    db = SessionLocal()
    try:
        for workflow in WorkflowType:
            get_or_seed_policy(db, workflow)
            logger.info("Seeded default config policy for workflow=%s", workflow.value)
    finally:
        db.close()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    init_db()
    print("Database initialized and default policies seeded.")
