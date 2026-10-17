-- Flyway migration: V9__audit_chain_immutability.sql
--
-- Config policy edits are the highest-consequence thing an operator can do
-- here — passiveThreshold decides who passes liveness — and until now the trail
-- protecting them was append-only by convention only. Any process holding a
-- connection could UPDATE a row to cover its tracks, or DELETE one.
--
-- The chain makes that detectable and the triggers make it fail loudly.

ALTER TABLE audit_logs ADD COLUMN prev_hash VARCHAR(64);
ALTER TABLE audit_logs ADD COLUMN entry_hash VARCHAR(64);

-- Serves the chain head lookup ("the newest hashed CONFIG_CHANGED") and the
-- ordered walk that verifies it.
CREATE INDEX idx_audit_logs_chain
    ON audit_logs(event_type, entry_hash, created_at DESC);

-- Immutability is enforced here rather than only in Java because a Java-side
-- rule is bypassed by anything holding a database connection, which is exactly
-- the actor this is meant to stop. BEFORE triggers also fire for the
-- ON DELETE CASCADE from liveness_sessions, so deleting a session that has
-- audit rows now fails instead of silently erasing the evidence. Nothing in
-- the application deletes sessions, and that is now enforced rather than
-- assumed.
CREATE FUNCTION audit_logs_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only: % is not permitted', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_no_update
    BEFORE UPDATE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_reject_mutation();

CREATE TRIGGER trg_audit_logs_no_delete
    BEFORE DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_reject_mutation();