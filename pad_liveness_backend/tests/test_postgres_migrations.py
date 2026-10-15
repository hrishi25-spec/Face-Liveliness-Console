"""
PostgreSQL-backed tests for the Alembic migration history.

These tests run the real ``python -m alembic`` CLI against a throwaway
PostgreSQL 16 database, so they cover the parts the in-memory SQLite API tests
cannot: native enum creation, JSONB columns, UUID/timestamptz column types,
``alembic check`` drift against the models, and the upgrade/downgrade round trip
that leaves no orphaned enum types behind.

Skipped when Docker is unavailable, failed when
``PAD_LIVENESS_REQUIRE_POSTGRES=1`` is set.
"""
import pytest
from sqlalchemy import create_engine, inspect, text

# Every table the baseline revision is expected to create, plus Alembic's own
# bookkeeping table.
ALEMBIC_TABLE = "alembic_version"
SCHEMA_TABLES = {"audit_logs", "challenges", "config_policies", "frame_events", "liveness_sessions"}

# Native PostgreSQL enum types created by the baseline revision. A column typed
# with any of these reads and writes the model's enum member *names*.
NATIVE_ENUM_TYPES = {
    "workflow_type",
    "config_workflow_type",
    "session_status",
    "liveness_stage",
    "frame_stage",
    "challenge_type",
    "challenge_status",
    "failure_policy",
}

# (table, column) -> (data_type, udt_name) as PostgreSQL reports them. These are
# exactly the declarations where the SQLite test database is not equivalent:
# SQLite would report "VARCHAR"/"BLOB"/"DATETIME" for all of them.
EXPECTED_NATIVE_COLUMNS = {
    ("liveness_sessions", "id"): ("uuid", "uuid"),
    ("liveness_sessions", "created_at"): ("timestamp with time zone", "timestamptz"),
    ("liveness_sessions", "closed_at"): ("timestamp with time zone", "timestamptz"),
    ("liveness_sessions", "status"): ("USER-DEFINED", "session_status"),
    ("liveness_sessions", "current_stage"): ("USER-DEFINED", "liveness_stage"),
    ("liveness_sessions", "workflow_type"): ("USER-DEFINED", "workflow_type"),
    ("frame_events", "stage"): ("USER-DEFINED", "frame_stage"),
    ("challenges", "challenge_type"): ("USER-DEFINED", "challenge_type"),
    ("challenges", "status"): ("USER-DEFINED", "challenge_status"),
    ("config_policies", "workflow_type"): ("USER-DEFINED", "config_workflow_type"),
    ("config_policies", "challenge_types"): ("jsonb", "jsonb"),
    ("audit_logs", "details"): ("jsonb", "jsonb"),
}


@pytest.fixture(scope="module")
def database_url(create_postgres_database) -> str:
    """
    An empty database this module migrates itself.

    It is intentionally *not* shared with the persistence tests: these tests
    downgrade the schema, and each module should be able to fail on its own.
    """
    return create_postgres_database()


@pytest.fixture(scope="module")
def database(database_url):
    connection_engine = create_engine(database_url, future=True)
    try:
        yield connection_engine
    finally:
        connection_engine.dispose()


def table_names(connection_engine) -> set:
    return set(inspect(connection_engine).get_table_names(schema="public"))


def enum_type_names(connection_engine) -> set:
    with connection_engine.connect() as connection:
        return set(
            connection.execute(
                text(
                    "SELECT t.typname FROM pg_type t"
                    " JOIN pg_namespace n ON n.oid = t.typnamespace"
                    " WHERE t.typtype = 'e' AND n.nspname = 'public'"
                )
            ).scalars()
        )


def enum_labels(connection_engine, type_name: str) -> list:
    with connection_engine.connect() as connection:
        return list(
            connection.execute(
                text(
                    "SELECT e.enumlabel FROM pg_enum e"
                    " JOIN pg_type t ON t.oid = e.enumtypid"
                    " WHERE t.typname = :name ORDER BY e.enumsortorder"
                ),
                {"name": type_name},
            ).scalars()
        )


def column_types(connection_engine, table: str) -> dict:
    with connection_engine.connect() as connection:
        rows = connection.execute(
            text(
                "SELECT column_name, data_type, udt_name FROM information_schema.columns"
                " WHERE table_schema = 'public' AND table_name = :table"
            ),
            {"table": table},
        ).all()
    return {row.column_name: (row.data_type, row.udt_name) for row in rows}


def reset_schema(connection_engine) -> None:
    """Drop everything, including Alembic's bookkeeping table."""
    with connection_engine.begin() as connection:
        connection.execute(text("DROP SCHEMA public CASCADE"))
        connection.execute(text("CREATE SCHEMA public"))


def test_upgrade_head_creates_the_full_schema_from_an_empty_database(database, database_url, run_alembic):
    reset_schema(database)
    assert table_names(database) == set()
    assert enum_type_names(database) == set()

    run_alembic(database_url, "upgrade", "head")

    assert table_names(database) == SCHEMA_TABLES | {ALEMBIC_TABLE}
    assert enum_type_names(database) == NATIVE_ENUM_TYPES


def test_migrated_schema_matches_the_orm_models(database_url, run_alembic):
    """
    ``alembic check`` autogenerates against the live database and fails if the
    models describe anything the migration history does not produce. This is the
    guard against a model change landing without a matching revision.
    """
    run_alembic(database_url, "upgrade", "head")

    result = run_alembic(database_url, "check")

    assert "No new upgrade operations detected" in result.stdout


def test_schema_uses_postgresql_native_types(database, database_url, run_alembic):
    run_alembic(database_url, "upgrade", "head")

    for (table, column), expected in EXPECTED_NATIVE_COLUMNS.items():
        assert column_types(database, table)[column] == expected, f"{table}.{column}"

    # The labels are the model's enum member names, which is what SQLAlchemy
    # reads back into the Python enum.
    assert enum_labels(database, "session_status") == ["ACTIVE", "PASSED", "FAILED", "EXPIRED"]
    assert enum_labels(database, "liveness_stage") == ["PASSIVE", "ACTIVE", "COMPLETED"]
    assert enum_labels(database, "failure_policy") == ["LOCK", "ESCALATE", "ALLOW_RETRY"]
    assert enum_labels(database, "workflow_type") == ["RESIDENT", "OPERATOR", "SUPERVISOR"]


def test_upgrade_head_is_idempotent_when_already_at_head(database_url, run_alembic):
    run_alembic(database_url, "upgrade", "head")

    second = run_alembic(database_url, "upgrade", "head")

    assert "Running upgrade" not in second.stdout
    assert "Running upgrade" not in second.stderr


def test_alembic_version_tracks_the_script_directory_head(database, database_url, run_alembic):
    run_alembic(database_url, "upgrade", "head")

    head = run_alembic(database_url, "heads").stdout.split()[0]
    with database.connect() as connection:
        recorded = list(connection.execute(text(f"SELECT version_num FROM {ALEMBIC_TABLE}")).scalars())

    assert recorded == [head]


def test_downgrade_base_removes_tables_and_enum_types_so_upgrade_can_run_again(database, database_url, run_alembic):
    """
    Regression test: autogenerate only emits ``drop_table``, which leaves the
    native enum types behind. Re-running ``upgrade head`` after a downgrade then
    failed with ``DuplicateObject: type ... already exists``.
    """
    run_alembic(database_url, "upgrade", "head")
    assert enum_type_names(database) == NATIVE_ENUM_TYPES

    run_alembic(database_url, "downgrade", "base")

    assert table_names(database) - {ALEMBIC_TABLE} == set()
    assert enum_type_names(database) == set()

    run_alembic(database_url, "upgrade", "head")

    assert table_names(database) == SCHEMA_TABLES | {ALEMBIC_TABLE}
    assert enum_type_names(database) == NATIVE_ENUM_TYPES


def test_stamp_head_adopts_a_database_created_by_init_db(database, database_url, run_alembic):
    """
    Databases bootstrapped with the dev shortcut (``python -m app.db.init_db``)
    have the tables but no ``alembic_version`` row, so ``upgrade head`` cannot
    run against them. ``alembic stamp head`` is the documented way to adopt
    them, and afterwards Alembic sees a schema matching the models.
    """
    from app.db.base import Base

    reset_schema(database)
    Base.metadata.create_all(database)
    assert table_names(database) == SCHEMA_TABLES

    run_alembic(database_url, "stamp", "head")
    run_alembic(database_url, "upgrade", "head")

    assert table_names(database) == SCHEMA_TABLES | {ALEMBIC_TABLE}
    assert "No new upgrade operations detected" in run_alembic(database_url, "check").stdout
