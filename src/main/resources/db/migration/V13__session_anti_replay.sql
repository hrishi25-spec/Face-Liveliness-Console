-- Flyway migration: V13__session_anti_replay.sql
--
-- Session anti-replay and freshness binding:
-- Adds a cryptographic session nonce and monotonic frame sequence tracker to liveness_sessions.
--
-- Every session gets a fresh cryptographic nonce at creation (SessionResponse), and incoming
-- frames/challenges are bound to this nonce, rejecting replayed, non-monotonic or stale frames.

ALTER TABLE liveness_sessions ADD COLUMN nonce VARCHAR(64);
ALTER TABLE liveness_sessions ADD COLUMN last_frame_seq BIGINT DEFAULT 0;
