-- Flyway migration: V11__challenge_manual_flag.sql
--
-- Mark a challenge as manually requested. Active liveness normally runs only
-- when the passive stage escalates (median below the threshold); a session whose
-- passive score is above the threshold passes without a challenge. The console
-- can now ask for one active challenge anyway
-- (POST /api/v1/sessions/{sessionId}/active-liveness), and that extra check has
-- to be distinguishable from an engine-issued one:
--
--   manual = FALSE (default)  the engine's passive-to-active escalation; its
--                             verdict drives the session outcome.
--   manual = TRUE             an extra check requested by hand on a session
--                             that already passed. Its verdict is stored on the
--                             challenge row and audited, but it never rewrites
--                             the session's verdict (see DecisionEngineService).
--
-- Existing rows are all engine-issued, which is what the default backfills.

ALTER TABLE challenges ADD COLUMN manual BOOLEAN NOT NULL DEFAULT FALSE;
