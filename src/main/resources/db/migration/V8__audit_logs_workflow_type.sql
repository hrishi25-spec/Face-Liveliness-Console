-- Flyway migration: V8__audit_logs_workflow_type.sql
--
-- Which workflow an audit entry concerns was previously recoverable only by
-- parsing the details JSON — and only for config edits, because that is where
-- it happened to be written. That made the policy-change feed impossible to
-- filter in the database: every row had to come back to the client, be parsed,
-- and be discarded. On a table that grows one row per frame decision, pulling
-- the newest 20 rows out of a million is the wrong way to answer "show me
-- OPERATOR".
--
-- Nullable on purpose: this is a denormalisation for querying, not a new fact.
-- An entry that predates this migration and cannot be resolved stays NULL
-- rather than being guessed at.

ALTER TABLE audit_logs ADD COLUMN workflow_type VARCHAR(32);

-- Pipeline rows: the session already knows the workflow.
UPDATE audit_logs a
   SET workflow_type = s.workflow_type
  FROM liveness_sessions s
 WHERE a.session_id = s.id;

-- Operator-level CONFIG_CHANGED rows have no session, so the only record of
-- which policy was edited is inside the details JSON written at the time. Read
-- it back rather than leaving the existing history unfilterable.
--
-- details is written exclusively through StringObjectMapJsonConverter and
-- defaults to '{}', so the jsonb cast is safe for every row this touches; a
-- row without the key simply yields NULL.
UPDATE audit_logs
   SET workflow_type = details::jsonb ->> 'workflowType'
 WHERE session_id IS NULL
   AND event_type = 'CONFIG_CHANGED'
   AND details IS NOT NULL;

-- Serves the feed exactly: fixed event type, optional workflow, newest first.
-- The unfiltered feed benefits too — event_type already narrows the scan, and
-- this keeps created_at ordering off a sort.
CREATE INDEX idx_audit_logs_config_feed
    ON audit_logs(event_type, workflow_type, created_at DESC);