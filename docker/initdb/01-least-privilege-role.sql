-- Runs once, on a fresh data volume, before the API ever connects. Creates the
-- roles the service uses, so the application is not the cluster superuser.
--
-- Why a separate role is the only way to do this. The postgres image makes
-- POSTGRES_USER the cluster's superuser, and that role is what the API connected
-- as: measured, `superuser=true createdb=true createrole=true bypassrls=true
-- replication=true` -- meaning a leaked POSTGRES_PASSWORD, or anything that
-- reached the connection pool, held cluster-wide power (every database, plus
-- COPY ... FROM PROGRAM) rather than power over one schema.
--
-- The obvious fix does not exist: PostgreSQL refuses to let that role give the
-- attribute up. `ALTER ROLE <bootstrap> NOSUPERUSER` fails with "The bootstrap
-- user must have the SUPERUSER attribute" -- a deliberate guard, because a
-- cluster whose bootstrap role is no longer a superuser can become
-- unadministrable. So the application gets its own identities instead, and the
-- bootstrap superuser stops being the thing that serves HTTP requests.
--
-- Architecture (three roles, each with one job):
--
--   1. bootstrap (POSTGRES_USER, e.g. "mosip") -- cluster superuser created by
--      the postgres image. Used ONLY by this script to provision the two roles
--      below. Never connects to the application.
--
--   2. pad_migrate -- owns the `public` schema, runs Flyway. Has CREATE, ALTER,
--      DROP on the schema and everything in it. Exists so a migration can run
--      DDL, and the runtime role never needs those privileges.
--
--   3. pad_runtime -- the role the API connects as. DML only: SELECT, INSERT,
--      UPDATE, DELETE on tables; USAGE on sequences. No CREATE, ALTER, DROP.
--      Cannot `ALTER TABLE audit_logs DISABLE TRIGGER`, so the append-only
--      triggers V9 installed stay enforced even if the application is fully
--      compromised.
--
-- Only the runtime password is passed in; the role names are this script's own
-- choice.

-- Idempotent, so a rerun against a volume that partially initialised is safe.

-- 1) Migration role: owns the schema, runs Flyway DDL
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pad_migrate') THEN
        CREATE ROLE pad_migrate LOGIN;
    END IF;
END;
$$;

ALTER ROLE pad_migrate WITH
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOBYPASSRLS
    NOREPLICATION
    PASSWORD 'test-postgres-password';

-- Owns the schema it migrates. The `public` schema in PostgreSQL 15+ is owned by
-- pg_database_owner, so this transfers exactly the CREATE/TABLE/FUNCTION rights
-- Flyway needs and nothing beyond the database.
ALTER SCHEMA public OWNER TO pad_migrate;

-- 2) Runtime role: DML only, connects as the API user
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pad_runtime') THEN
        CREATE ROLE pad_runtime LOGIN;
    END IF;
END;
$$;

ALTER ROLE pad_runtime WITH
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOBYPASSRLS
    NOREPLICATION
    PASSWORD 'test-postgres-password';

-- Bound the work one connection can do, at the role rather than in the
-- application: a setting here applies to every client that authenticates as this
-- role -- including a psql session somebody opens by hand -- and cannot be
-- forgotten when the next connection pool is configured.
--
--   statement_timeout   the service's queries are small JPQL aggregates over
--                       sessions, frames and audit rows; 30s is far beyond any
--                       of them and still ends a runaway query. A future
--                       migration that legitimately runs longer should raise it
--                       for that run (SET statement_timeout = 0) and say so.
--   idle_in_transaction_session_timeout
--                       a connection that opened a transaction and stopped
--                       talking holds locks and blocks VACUUM until killed.
--                       With a 5-connection pool a handful of those is an
--                       outage, and they pin the append-only audit_logs table
--                       V9 protects.
--   lock_timeout        waiting forever is worse than failing: a blocked writer
--                       becomes a visible error instead of an outage.
ALTER ROLE pad_runtime SET statement_timeout = '30s';
ALTER ROLE pad_runtime SET idle_in_transaction_session_timeout = '60s';
ALTER ROLE pad_runtime SET lock_timeout = '15s';

-- Only the roles that own or are granted this database may connect to it.
-- PUBLIC holds CONNECT on every database by default, which is how any role that
-- happens to exist in the cluster can reach this one. A second role that
-- genuinely needs access is an explicit GRANT CONNECT, which is the point.
--
-- The GRANT comes first and is not optional. pad_runtime is not the database's
-- owner (the bootstrap role is), so PUBLIC's default is the only route it has
-- in; revoking that without granting the replacement locks the application out
-- of its own database. Measured while writing this: without the GRANT, pad_runtime's
-- first login fails with "permission denied for database pad_liveness / User
-- does not have CONNECT privilege" -- which is an outage, not hardening.
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO pad_runtime', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO pad_migrate', current_database());
    EXECUTE format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', current_database());
END;
$$;

-- 3) Grant DML to runtime role on existing and future tables/sequences
--    Migration role (pad_migrate) owns the schema, so it gets DDL implicitly.
--    Runtime role gets only what it needs to operate.

-- DML on all current tables in public schema
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO pad_runtime;

-- USAGE on all current sequences (for SERIAL/BIGSERIAL identity columns)
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO pad_runtime;

-- Future tables/sequences created by pad_migrate (Flyway) should also be accessible
-- by pad_runtime. Since pad_migrate owns the schema, we set default privileges
-- for objects it creates.
ALTER DEFAULT PRIVILEGES FOR ROLE pad_migrate IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pad_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE pad_migrate IN SCHEMA public
    GRANT USAGE ON SEQUENCES TO pad_runtime;

-- Also grant execute on functions (Flyway creates trigger functions in V9/V10)
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO pad_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE pad_migrate IN SCHEMA public
    GRANT EXECUTE ON FUNCTIONS TO pad_runtime;