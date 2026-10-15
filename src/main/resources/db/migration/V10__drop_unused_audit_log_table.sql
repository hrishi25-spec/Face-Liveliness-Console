-- Flyway migration: V10__drop_unused_audit_log_table.sql
--
-- V5__init_audit_table.sql created `audit_log` (singular). V7 then introduced
-- `audit_logs` (plural) as the table the service actually reads and writes, and
-- nothing has touched the singular one since:
--
--   * No Java code names it. Every repository, mapper and query uses
--     `audit_logs`; the only appearance of `audit_log` in this repository is the
--     CREATE in V5.
--   * `pad_liveness_backend/` — its own Alembic migration set — maps
--     `audit_logs` as well, so both backends agree on which table is live.
--   * It carries no foreign keys, no immutability triggers and no vocabulary in
--     common with the table that replaced it. It is not a legacy shape of the
--     live table; it is a different, abandoned one.
--
-- It is dropped rather than left alone because an unused table drifts: every
-- future reader has to work out which of two similarly named tables is the real
-- one, and a table nothing constrains is one a future writer can start using by
-- accident, with none of the append-only guarantees V9 put on `audit_logs`.
--
-- Fail-closed on data. Reading the code is how "this table is empty" was
-- established, and code reading is not a guarantee about someone else's
-- database. A deployment where a retired writer did leave rows here must not
-- have its audit history destroyed by a routine `flyway migrate`, so this
-- refuses to drop a non-empty table and says what to do instead. An empty table
-- is dropped silently. Same instinct as the append-only triggers in V9: the
-- destructive path is the one that has to prove itself.
--
-- V5__init_audit_table.sql is left exactly as it is. Flyway validates the
-- checksum of every applied migration, so editing or deleting it would fail on
-- any database that has already run it.
DO $$
DECLARE
    orphaned BIGINT;
BEGIN
    IF to_regclass('audit_log') IS NULL THEN
        RETURN;  -- already absent: a fresh database that never ran V5, or a rerun
    END IF;

    EXECUTE 'SELECT count(*) FROM audit_log' INTO orphaned;

    IF orphaned > 0 THEN
        RAISE EXCEPTION
            'audit_log holds % row(s) and is written by no current code path; '
            'export those rows and drop the table by hand, then re-run the '
            'migration — it will not destroy audit history for you', orphaned
            USING ERRCODE = 'restrict_violation';
    END IF;

    DROP TABLE audit_log;
END;
$$;
