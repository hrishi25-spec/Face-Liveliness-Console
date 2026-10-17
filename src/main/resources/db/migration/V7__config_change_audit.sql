-- Flyway migration: V7__config_change_audit.sql
--
-- Policy edits (PUT /api/v1/config/{workflowType}) decide who passes liveness,
-- so they belong in the same trail as the decisions they cause. They have no
-- session, and audit_logs.session_id was NOT NULL with a NOT NULL foreign key,
-- which made such an entry impossible to record at all: an operator could lower
-- passiveThreshold to 0 and defeat PAD with no trace anywhere.
--
-- NULL session_id now means "operator-level event" (CONFIG_CHANGED). Pipeline
-- events are unaffected — they always carry their session, and the session
-- trail query (findBySessionIdOrderByCreatedAtAsc) can never match a NULL.

ALTER TABLE audit_logs ALTER COLUMN session_id DROP NOT NULL;

-- The config audit view reads the newest CONFIG_CHANGED entries first; the
-- table also grows by one row per frame decision, so ordering by time needs
-- its own index rather than relying on insertion order.
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at DESC);