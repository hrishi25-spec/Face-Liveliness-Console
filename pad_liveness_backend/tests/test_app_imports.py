"""
Regression tests for the application import graph.

``uvicorn app.main:app`` and ``python -m app.db.init_db`` are both documented
entry points, and each has to work in a *fresh* interpreter.

They did not. ``Base`` used to be defined inside ``app/db/base.py``, a module
that also imports every model, so whichever model module was imported first
ended up importing a partially initialized one and raised
``ImportError: cannot import name 'LivenessSession' from partially initialized
module``. Anything that imported ``app.db.base`` first — the test suite, Alembic,
``init_db`` — hid the cycle completely, which is why the broken
``uvicorn app.main:app`` command went unnoticed.

These tests therefore import in a subprocess. Importing in-process would be
pointless: pytest has already loaded the whole package by then, so every module
would resolve out of ``sys.modules``.
"""
import subprocess
import sys
from pathlib import Path

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]

EXPECTED_TABLES = {
    "audit_logs",
    "challenges",
    "config_policies",
    "frame_events",
    "liveness_sessions",
}


def run_in_fresh_interpreter(source: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, "-c", source],
        cwd=BACKEND_ROOT,
        capture_output=True,
        text=True,
    )


# app.main is the ASGI target the README and docker-compose pass to uvicorn;
# the others are the entry points operators run directly.
@pytest.mark.parametrize(
    "module",
    ["app.main", "app.db.init_db", "app.db.base", "app.db.session", "app.crud.audit"],
)
def test_entry_points_import_in_a_fresh_interpreter(module):
    result = run_in_fresh_interpreter(f"import {module}")

    assert result.returncode == 0, result.stderr


def test_metadata_hub_registers_every_table():
    """
    ``app.db.base`` must still expose a complete ``Base.metadata`` — Alembic
    autogenerate and any ``create_all`` depend on those imports surviving the
    module split.
    """
    result = run_in_fresh_interpreter(
        "import app.db.base as base; print(','.join(sorted(base.Base.metadata.tables)))"
    )

    assert result.returncode == 0, result.stderr
    assert set(result.stdout.strip().split(",")) == EXPECTED_TABLES
