"""
PostgreSQL-backed tests for session persistence.

These run against a real PostgreSQL 16 database whose schema was produced by
``alembic upgrade head`` — never by ``Base.metadata.create_all`` — so a drifted
migration fails here as well as in ``test_postgres_migrations.py``. Reading is
done through a *second* engine so assertions prove the rows are durable in
PostgreSQL rather than resident in the ORM identity map.

Skipped when Docker is unavailable, failed when
``PAD_LIVENESS_REQUIRE_POSTGRES=1`` is set.
"""
import base64
import uuid

import cv2
import numpy as np
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, sessionmaker

from app.core.config import settings
from app.crud import audit as audit_crud
from app.crud import config as config_crud
from app.crud import session as session_crud
from app.db.session import get_db
from app.main import app
from app.models.challenge import Challenge
from app.models.config_policy import ConfigPolicy
from app.models.enums import ChallengeStatus, ChallengeType, FailurePolicy, LivenessStage, SessionStatus, WorkflowType
from app.models.frame_event import FrameEvent
from app.models.liveness_session import LivenessSession
from app.schemas.config import ConfigPolicyUpdate
from app.schemas.session import SessionCreateRequest

TRUNCATABLE_TABLES = "audit_logs, challenges, frame_events, liveness_sessions, config_policies"


@pytest.fixture(scope="module")
def database_url(create_postgres_database, run_alembic) -> str:
    """
    A throwaway database migrated to head with the real Alembic CLI.

    Persistence is asserted against the schema the migration produces, so the
    migration and the ORM can never drift apart unnoticed.
    """
    url = create_postgres_database()
    run_alembic(url, "upgrade", "head")
    return url


@pytest.fixture(scope="module")
def engine(database_url):
    connection_engine = create_engine(database_url, pool_pre_ping=True, future=True)
    try:
        yield connection_engine
    finally:
        connection_engine.dispose()


@pytest.fixture(scope="module")
def independent_engine(database_url):
    """A second engine: separate pool, therefore separate PostgreSQL connections."""
    connection_engine = create_engine(database_url, pool_pre_ping=True, future=True)
    try:
        yield connection_engine
    finally:
        connection_engine.dispose()


@pytest.fixture(autouse=True)
def empty_schema(engine):
    """Start every test from empty tables, whatever the previous test left behind."""
    with engine.begin() as connection:
        connection.execute(text(f"TRUNCATE TABLE {TRUNCATABLE_TABLES} CASCADE"))
    yield


@pytest.fixture
def db(engine):
    with Session(engine) as session:
        yield session


@pytest.fixture
def verifier(independent_engine):
    """Reads committed state back over a separate connection."""
    with Session(independent_engine) as session:
        yield session


@pytest.fixture
def client(engine):
    session_factory = sessionmaker(bind=engine, autoflush=False, autocommit=False, future=True)

    def override_get_db():
        with session_factory() as session:
            yield session

    app.dependency_overrides[get_db] = override_get_db
    try:
        with TestClient(app) as test_client:
            yield test_client
    finally:
        app.dependency_overrides.pop(get_db, None)


class StubLivenessEngine:
    """Deterministic stand-in for the OpenCV engine, mirroring test_liveness_api.py."""

    def __init__(self, score=0.9):
        self.score = score
        self.active_passes = True

    def observe_face(self, frame):
        from app.services.liveness_engine import FaceObservation

        return FaceObservation(face_detected=True, multiple_faces=False, face_quality=0.9, bbox=(0, 0, 32, 32))

    def score_passive(self, frame, observation):
        return self.score

    def validate_active(self, challenge_type, frames):
        return self.active_passes


class StubPADEngine:
    def detect(self, frame):
        from app.services.pad_engine import PADResult

        return PADResult(is_attack=False, attack_type=None, confidence=0.95)


@pytest.fixture
def stub_engines(monkeypatch):
    import app.services.decision_engine as decision_engine

    liveness = StubLivenessEngine()
    monkeypatch.setattr(decision_engine, "get_liveness_engine", lambda: liveness)
    monkeypatch.setattr(decision_engine, "get_pad_engine", lambda: StubPADEngine())
    monkeypatch.setattr(decision_engine, "select_challenge", lambda allowed, previous: ChallengeType.BLINK)
    return liveness


@pytest.fixture
def frame_base64():
    image = np.full((64, 64, 3), 128, dtype=np.uint8)
    encoded, data = cv2.imencode(".png", image)
    assert encoded
    return base64.b64encode(data).decode("ascii")


def raw_rows(connection_engine, sql: str, **parameters):
    """
    Run raw SQL on the engine rather than on an ORM session.

    Going through someone else's session would close its connection when the
    block exits, leaving it unusable for the assertions that follow.
    """
    with connection_engine.connect() as connection:
        return connection.execute(text(sql), parameters).all()


def raw_scalar(connection_engine, sql: str, **parameters):
    with connection_engine.connect() as connection:
        return connection.execute(text(sql), parameters).scalar()


def create_session(db, device_id="L1-CAM-01", workflow_type=WorkflowType.RESIDENT) -> LivenessSession:
    return session_crud.create_session(
        db, SessionCreateRequest(workflow_type=workflow_type, device_id=device_id)
    )


def test_session_round_trips_through_postgres_with_native_types(db, verifier):
    created = create_session(db, device_id="L1-CAM-01")

    read_back = verifier.get(LivenessSession, created.id)

    assert read_back is not None
    assert read_back.id == created.id
    assert read_back.device_id == "L1-CAM-01"
    # Native enum columns come back as the Python enum, not as a string.
    assert read_back.workflow_type is WorkflowType.RESIDENT
    assert read_back.status is SessionStatus.ACTIVE
    assert read_back.current_stage is LivenessStage.PASSIVE
    assert read_back.retry_count == 0
    assert read_back.online is True
    assert read_back.final_result is None
    # timestamptz keeps the offset, unlike the naive datetimes SQLite returns.
    assert read_back.created_at.tzinfo is not None
    assert read_back.created_at.utcoffset().total_seconds() == 0


def test_session_children_persist_with_their_foreign_keys(db, verifier):
    session = create_session(db)
    session_crud.add_frame_event(
        db,
        session.id,
        stage=LivenessStage.PASSIVE,
        face_detected=True,
        multiple_faces=False,
        face_quality=0.9,
        liveness_score=0.88,
        pad_flag=False,
    )
    session_crud.add_challenge(db, session.id, challenge_type=ChallengeType.BLINK, timeout_ms=8000)
    audit_crud.log_event(db, session.id, "CHALLENGE_ISSUED", {"challenge_type": "blink", "attempt": 1})

    frames = verifier.query(FrameEvent).filter(FrameEvent.session_id == session.id).all()
    challenges = verifier.query(Challenge).filter(Challenge.session_id == session.id).all()
    audit = audit_crud.get_session_audit_log(verifier, session.id)

    assert [f.stage for f in frames] == [LivenessStage.PASSIVE]
    assert [c.challenge_type for c in challenges] == [ChallengeType.BLINK]
    assert [c.status for c in challenges] == [ChallengeStatus.ISSUED]
    assert len(audit) == 1
    # JSONB round trip: the audit details must come back as a real dict.
    assert audit[0].details == {"challenge_type": "blink", "attempt": 1}
    assert session_crud.count_frame_events(verifier, session.id) == 1
    assert session_crud.count_challenges(verifier, session.id) == 1


def test_deleting_a_session_cascades_to_its_children(db, verifier):
    session = create_session(db)
    session_crud.add_frame_event(
        db, session.id, stage=LivenessStage.PASSIVE, face_detected=True, multiple_faces=False, pad_flag=False
    )
    session_crud.add_challenge(db, session.id, challenge_type=ChallengeType.SMILE, timeout_ms=8000)
    audit_crud.log_event(db, session.id, "SESSION_CREATED", {})
    session_id = session.id

    db.delete(db.get(LivenessSession, session_id))
    db.commit()

    assert verifier.get(LivenessSession, session_id) is None
    for model in (FrameEvent, Challenge):
        assert verifier.query(model).filter(model.session_id == session_id).count() == 0
    assert audit_crud.get_session_audit_log(verifier, session_id) == []


def test_config_policy_seeding_is_unique_per_workflow(db, verifier):
    first = config_crud.get_or_seed_policy(db, WorkflowType.RESIDENT)
    second = config_crud.get_or_seed_policy(db, WorkflowType.RESIDENT)

    assert first.id == second.id
    assert verifier.query(ConfigPolicy).count() == 1
    assert first.challenge_types == settings.DEFAULT_CHALLENGE_TYPES
    assert first.on_repeated_failure is FailurePolicy.LOCK

    # The column is unique in PostgreSQL; an ORM-level duplicate is rejected by
    # the database, not silently accepted.
    db.add(ConfigPolicy(workflow_type=WorkflowType.RESIDENT))
    with pytest.raises(IntegrityError):
        db.commit()
    db.rollback()


def test_config_policy_update_persists_jsonb_and_scalars(db, verifier, independent_engine):
    config_crud.update_policy(
        db,
        WorkflowType.OPERATOR,
        ConfigPolicyUpdate(passive_threshold=0.81, challenge_types=["smile"], max_retry_count=1),
    )

    reloaded = config_crud.get_or_seed_policy(verifier, WorkflowType.OPERATOR)

    assert reloaded.passive_threshold == pytest.approx(0.81)
    assert reloaded.challenge_types == ["smile"]
    assert reloaded.max_retry_count == 1
    stored = raw_scalar(
        independent_engine,
        "SELECT challenge_types::text FROM config_policies WHERE workflow_type = 'OPERATOR'",
    )
    assert stored == '["smile"]'


def test_session_lifecycle_over_http_is_visible_on_a_separate_connection(client, verifier):
    created = client.post(
        "/api/v1/sessions",
        json={"workflow_type": "resident", "device_id": "L1-CAM-02", "subject_ref": "resident-42"},
    )
    assert created.status_code == 201
    session_id = created.json()["id"]

    # Separate connection: the row is committed, not just pending in the API's session.
    row = verifier.get(LivenessSession, uuid.UUID(session_id))
    assert row is not None
    assert row.device_id == "L1-CAM-02"
    assert row.subject_ref == "resident-42"
    assert row.status is SessionStatus.ACTIVE

    assert client.get(f"/api/v1/sessions/{session_id}").json()["status"] == "active"

    closed = client.post(f"/api/v1/sessions/{session_id}/close")

    assert closed.status_code == 200
    assert closed.json()["status"] == "expired"
    # duration_ms depends on timestamptz arithmetic across two transactions.
    assert isinstance(closed.json()["duration_ms"], int)
    assert closed.json()["duration_ms"] >= 0
    verifier.expire_all()
    assert verifier.get(LivenessSession, row.id).status is SessionStatus.EXPIRED
    assert [entry.event_type for entry in audit_crud.get_session_audit_log(verifier, row.id)] == [
        "SESSION_CREATED",
        "SESSION_EXPIRED",
    ]


def test_submitting_a_frame_persists_native_enum_names(client, verifier, independent_engine, stub_engines, frame_base64):
    stub_engines.score = 0.4  # below the passive threshold, so the flow escalates
    session_id = client.post(
        "/api/v1/sessions", json={"workflow_type": "resident", "device_id": "L1-CAM-03"}
    ).json()["id"]

    frame_response = client.post(f"/api/v1/sessions/{session_id}/frames", json={"frame_base64": frame_base64})
    assert frame_response.status_code == 200
    challenge_id = frame_response.json()["challenge"]["challenge_id"]

    validation = client.post(
        f"/api/v1/sessions/{session_id}/challenges/validate",
        json={"challenge_id": challenge_id, "frames_base64": [frame_base64, frame_base64]},
    )
    assert validation.status_code == 200
    assert validation.json()["passed"] is True

    # Raw SQL: the columns hold the enum member names PostgreSQL was told to use,
    # which is what makes the ORM read them back as Python enums.
    rows = raw_rows(
        independent_engine,
        "SELECT s.status::text, s.current_stage::text,"
        "       count(DISTINCT f.id) AS frames, min(f.stage::text) AS frame_stage,"
        "       min(c.status::text) AS challenge_status, min(c.challenge_type::text) AS challenge_type"
        " FROM liveness_sessions s"
        " JOIN frame_events f ON f.session_id = s.id"
        " JOIN challenges c ON c.session_id = s.id"
        " WHERE s.id = :session_id"
        " GROUP BY s.status, s.current_stage",
        session_id=session_id,
    )
    assert len(rows) == 1
    stored = rows[0]

    assert stored.status == "PASSED"
    assert stored.current_stage == "COMPLETED"
    assert stored.frames == 1
    assert stored.frame_stage == "PASSIVE"
    assert stored.challenge_status == "PASSED"
    assert stored.challenge_type == "BLINK"
    assert [entry.event_type for entry in audit_crud.get_session_audit_log(verifier, session_id)] == [
        "SESSION_CREATED",
        "CHALLENGE_ISSUED",
        "CHALLENGE_PASSED",
        "SESSION_PASSED",
    ]


def test_metrics_aggregate_sessions_from_postgres(client, db):
    active = create_session(db, device_id="cam-active")
    passed = create_session(db, device_id="cam-passed")
    rejected = create_session(db, device_id="cam-attack")

    passed.status = SessionStatus.PASSED
    passed.final_result = True
    rejected.status = SessionStatus.FAILED
    rejected.failure_reason = "presentation_attack:screen_replay"
    rejected.retry_count = 2
    session_crud.add_frame_event(
        db, passed.id, stage=LivenessStage.PASSIVE, face_detected=True, multiple_faces=False, pad_flag=False
    )
    session_crud.add_frame_event(
        db, passed.id, stage=LivenessStage.ACTIVE, face_detected=True, multiple_faces=False, pad_flag=False
    )
    challenge = session_crud.add_challenge(db, active.id, challenge_type=ChallengeType.SMILE, timeout_ms=8000)
    challenge.status = ChallengeStatus.FAILED
    db.commit()

    metrics = client.get("/api/v1/metrics").json()

    assert metrics["total_sessions"] == 3
    assert metrics["passed_sessions"] == 1
    assert metrics["failed_sessions"] == 1
    assert metrics["active_sessions"] == 1
    assert metrics["avg_frames_per_session"] == pytest.approx(round(2 / 3, 4))
    assert metrics["avg_challenges_per_session"] == pytest.approx(round(1 / 3, 4))
    assert metrics["liveness_retry_rate"] == pytest.approx(round(2 / 3, 4))
    assert metrics["pass_rate"] == pytest.approx(round(1 / 3, 4))
    # Counted with ILIKE on failure_reason, the PAD rejection path.
    assert metrics["pad_rejection_rate"] == pytest.approx(round(1 / 3, 4))
