"""Drive one retry-exhaustion scenario through this backend's real API.

The cross-backend contract check runs this script as a subprocess
(`src/test/java/io/mosip/liveness/contract/CrossBackendContractTest.java` in the
Spring project) and compares the JSON it prints with what the Java service
answers for the same policy, so the two implementations cannot report a spent
retry budget differently. `tests/test_cross_backend_scenario.py` runs the script
too, which keeps the artifact covered by this project's own suite.

Prints exactly one JSON object on stdout:

    {"policy", "action", "failure_reason", "may_retry_session", "session_status",
     "challenge_status", "message", "challenges_issued", "intermediate_actions"}

There is no server here on purpose: the app runs in-process over `TestClient`
with the same in-memory SQLite database and stub engines the API tests use. The
contract under test is the response shape, not PostgreSQL or the model.
"""
import argparse
import base64
import json
import sys
from pathlib import Path
from unittest.mock import patch

# `python tests/cross_backend_scenario.py` puts this file's directory on
# sys.path, not the project root the app package lives under.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cv2  # noqa: E402  (import after the sys.path fix, deliberately)
import numpy as np  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from sqlalchemy import create_engine  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402
from sqlalchemy.pool import StaticPool  # noqa: E402

import app.services.decision_engine as decision_engine  # noqa: E402
from app.db.base import Base  # noqa: E402
from app.db.session import get_db  # noqa: E402
from app.main import app  # noqa: E402
from app.models.enums import FailurePolicy  # noqa: E402
from app.services.liveness_engine import FaceObservation  # noqa: E402
from app.services.pad_engine import PADResult  # noqa: E402

# Below every policy's passive threshold, so the first frame escalates.
PASSIVE_SCORE = 0.4


class _MissedChallengeEngine:
    """A face that is always seen and never performs the requested action."""

    def observe_face(self, frame):
        return FaceObservation(
            face_detected=True, multiple_faces=False, face_quality=0.9, bbox=(0, 0, 32, 32)
        )

    def score_passive(self, frame, observation):
        return PASSIVE_SCORE

    def validate_active(self, challenge_type, frames):
        return False


class _NoAttackEngine:
    """Never flags an attack, so the PAD gate cannot decide the scenario."""

    def detect(self, frame):
        return PADResult(is_attack=False, attack_type=None, confidence=0.95)


def _frame_base64() -> str:
    """A decodable frame; the stub engines ignore what is in it."""
    image = np.full((64, 64, 3), 128, dtype=np.uint8)
    encoded, data = cv2.imencode(".png", image)
    assert encoded, "cv2 must encode the synthetic frame"
    return base64.b64encode(data).decode("ascii")


def _client() -> TestClient:
    engine = create_engine(
        "sqlite+pysqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine, autoflush=False, autocommit=False)

    def override_get_db():
        with session_factory() as db:
            yield db

    app.dependency_overrides[get_db] = override_get_db
    return TestClient(app)


def run(policy: str, max_retries: int) -> dict:
    """Spend the retry budget at `policy` and report the terminal shape."""
    frame_b64 = _frame_base64()

    with patch.object(decision_engine, "get_liveness_engine", lambda: _MissedChallengeEngine()), \
            patch.object(decision_engine, "get_pad_engine", lambda: _NoAttackEngine()), \
            _client() as client:
        configured = client.put(
            "/api/v1/config/resident",
            json={"max_retry_count": max_retries, "on_repeated_failure": policy},
        )
        assert configured.status_code == 200, configured.text

        created = client.post(
            "/api/v1/sessions", json={"workflow_type": "resident", "device_id": "cross-backend"}
        )
        assert created.status_code == 201, created.text
        session_id = created.json()["id"]

        verdicts = []
        for _ in range(max_retries):
            # The frame endpoint is what issues a challenge here, as it is in
            # the Java service.
            frame = client.post(
                f"/api/v1/sessions/{session_id}/frames", json={"frame_base64": frame_b64}
            )
            assert frame.status_code == 200, frame.text
            assert frame.json()["action"] == "escalate_to_active", frame.text

            validation = client.post(
                f"/api/v1/sessions/{session_id}/challenges/validate",
                json={
                    "challenge_id": frame.json()["challenge"]["challenge_id"],
                    "frames_base64": [frame_b64],
                },
            )
            assert validation.status_code == 200, validation.text
            verdicts.append(validation.json())

        session = client.get(f"/api/v1/sessions/{session_id}")
        assert session.status_code == 200, session.text
        terminal = verdicts[-1]

    return {
        "policy": policy,
        "action": terminal["action"],
        "failure_reason": session.json()["failure_reason"],
        "may_retry_session": terminal["may_retry_session"],
        "session_status": session.json()["status"],
        "challenge_status": terminal["challenge"]["status"],
        "message": terminal["message"],
        "challenges_issued": len(verdicts),
        "intermediate_actions": [verdict["action"] for verdict in verdicts[:-1]],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--policy", required=True, choices=[policy.value for policy in FailurePolicy])
    parser.add_argument("--max-retries", type=int, default=2)
    args = parser.parse_args()

    json.dump(run(args.policy, args.max_retries), sys.stdout)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
