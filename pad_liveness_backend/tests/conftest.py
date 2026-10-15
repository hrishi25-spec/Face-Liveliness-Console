"""
Shared pytest fixtures.

Two of the test modules need nothing but Python: ``test_liveness_api.py`` runs
the API against in-memory SQLite, and ``test_opencv_heuristics.py`` runs the real
PAD/liveness heuristics over procedurally generated frames.

The PostgreSQL integration tests (``test_postgres_*.py``) are different. They
start a real ``postgres:16-alpine`` container, apply the Alembic migrations to
it, and then exercise persistence against the schema those migrations produced.
Each module gets its own throwaway database inside that single container.

Those tests need a reachable Docker daemon. When Docker is unavailable they
skip, so the suite still runs on a machine without it. Set
``PAD_LIVENESS_REQUIRE_POSTGRES=1`` (as CI does) to fail instead of skipping, so
a broken Docker setup cannot silently drop the PostgreSQL coverage.
"""
import os
import subprocess
import sys
import uuid
from pathlib import Path
from typing import Callable, NoReturn

import pytest
from sqlalchemy.engine import make_url

BACKEND_ROOT = Path(__file__).resolve().parents[1]

# Mirrors the image pinned in docker-compose.yml, so migrations are exercised on
# the same PostgreSQL major version the service actually runs against.
POSTGRES_IMAGE = os.environ.get("PAD_LIVENESS_TEST_POSTGRES_IMAGE", "postgres:16-alpine")
REQUIRE_POSTGRES_ENV_VAR = "PAD_LIVENESS_REQUIRE_POSTGRES"


def postgres_required() -> bool:
    """True when a missing PostgreSQL must fail the run instead of skipping it."""
    return os.environ.get(REQUIRE_POSTGRES_ENV_VAR, "").strip().lower() in {"1", "true", "yes"}


def postgres_unavailable(reason: str) -> NoReturn:
    """Skip the PostgreSQL tests, or fail them when they are declared required."""
    if postgres_required():
        pytest.fail(f"{REQUIRE_POSTGRES_ENV_VAR} is set, but PostgreSQL is unavailable: {reason}")
    pytest.skip(f"PostgreSQL integration test needs a working Docker daemon: {reason}")


def _stop_quietly(container) -> None:
    try:
        container.stop()
    except Exception:  # pragma: no cover - best-effort teardown
        pass


@pytest.fixture(scope="session")
def postgres_container():
    """A running ``postgres:16-alpine`` container, shared by every PG test module."""
    PostgresContainer = None
    failure = None
    try:
        from testcontainers.community.postgres import PostgresContainer
    except ImportError as exc:  # pragma: no cover - depends on the local env
        failure = f"testcontainers is not installed ({exc})"

    # Both the constructor and start() talk to the Docker daemon, so both belong
    # inside the try.
    container = None
    if failure is None:
        try:
            container = PostgresContainer(POSTGRES_IMAGE)
            container.start()
        except Exception as exc:
            # Any failure here means "this machine cannot give us PostgreSQL": no
            # daemon, unreachable socket, image not pullable, broken credentials
            # helper. The concrete exception type varies with the cause.
            failure = f"could not start {POSTGRES_IMAGE}: {type(exc).__name__}: {exc}"
            if container is not None:
                _stop_quietly(container)

    # Raised outside the except block on purpose: inside it, Python would chain
    # the Docker traceback and bury this message in urllib3 internals.
    if failure is not None:
        postgres_unavailable(failure)

    try:
        yield container
    finally:
        _stop_quietly(container)


@pytest.fixture(scope="session")
def create_postgres_database(postgres_container) -> Callable[[], str]:
    """
    Factory returning a freshly created, empty database inside the container.

    Isolation matters here: the migration tests deliberately downgrade the
    schema, so they must not share a database with the persistence tests. Every
    database created this way is dropped when the session ends.
    """
    import psycopg2

    admin_url = make_url(postgres_container.get_connection_url())
    created: list[str] = []

    def _connect(database: str):
        return psycopg2.connect(
            host=admin_url.host,
            port=admin_url.port,
            user=admin_url.username,
            password=admin_url.password,
            dbname=database,
        )

    def _create() -> str:
        name = f"pad_liveness_test_{uuid.uuid4().hex[:10]}"
        connection = _connect(admin_url.database)
        try:
            connection.autocommit = True
            with connection.cursor() as cursor:
                cursor.execute(f'CREATE DATABASE "{name}"')
        finally:
            connection.close()
        created.append(name)
        return admin_url.set(database=name).render_as_string(hide_password=False)

    try:
        yield _create
    finally:
        for name in created:
            try:
                connection = _connect(admin_url.database)
                try:
                    connection.autocommit = True
                    with connection.cursor() as cursor:
                        cursor.execute(f'DROP DATABASE IF EXISTS "{name}" WITH (FORCE)')
                finally:
                    connection.close()
            except Exception:  # pragma: no cover - container may already be gone
                pass


@pytest.fixture(scope="session")
def run_alembic() -> Callable[..., subprocess.CompletedProcess]:
    """
    Factory that runs the real ``alembic`` CLI against a database URL.

    ``alembic/env.py`` builds its connection string from the ``POSTGRES_*``
    settings, so the URL is translated back into those variables for the child
    process. This exercises the same entry point an operator uses
    (``python -m alembic upgrade head``) rather than calling Alembic in-process,
    which would skip ``alembic.ini`` resolution and the settings round trip.
    """

    def _run(database_url: str, *args: str, check: bool = True) -> subprocess.CompletedProcess:
        url = make_url(database_url)
        env = {
            **os.environ,
            "POSTGRES_USER": url.username or "",
            "POSTGRES_PASSWORD": url.password or "",
            "POSTGRES_HOST": url.host or "localhost",
            "POSTGRES_PORT": str(url.port or 5432),
            "POSTGRES_DB": url.database or "",
        }
        result = subprocess.run(
            [sys.executable, "-m", "alembic", *args],
            cwd=BACKEND_ROOT,
            env=env,
            capture_output=True,
            text=True,
        )
        if check and result.returncode != 0:
            raise AssertionError(
                f"alembic {' '.join(args)} exited {result.returncode}\n"
                f"--- stdout ---\n{result.stdout}\n--- stderr ---\n{result.stderr}"
            )
        return result

    return _run
