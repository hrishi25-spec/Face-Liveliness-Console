-- Flyway migration: V1__init_schema.sql
-- Replaces Alembic's `alembic revision --autogenerate -m "init"`
--
-- No CREATE EXTENSION "uuid-ossp" is required: every table uses a plain UUID
-- primary key and Hibernate generates the identifier client-side
-- (@GeneratedValue(strategy = GenerationType.UUID)) before INSERT. Dropping the
-- extension removes the superuser requirement from provisioning.

-- Liveness sessions
CREATE TABLE liveness_sessions (
    id UUID PRIMARY KEY,
    workflow_type VARCHAR(32) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    subject_ref VARCHAR(128),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    current_stage VARCHAR(16) NOT NULL DEFAULT 'PASSIVE',
    retry_count INTEGER NOT NULL DEFAULT 0,
    online BOOLEAN NOT NULL DEFAULT TRUE,
    final_result BOOLEAN,
    failure_reason VARCHAR(256),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    closed_at TIMESTAMPTZ
);

CREATE INDEX idx_liveness_sessions_workflow ON liveness_sessions(workflow_type);
CREATE INDEX idx_liveness_sessions_status ON liveness_sessions(status);

-- Frame events
CREATE TABLE frame_events (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES liveness_sessions(id) ON DELETE CASCADE,
    stage VARCHAR(16) NOT NULL,
    face_detected BOOLEAN NOT NULL DEFAULT TRUE,
    multiple_faces BOOLEAN NOT NULL DEFAULT FALSE,
    face_quality DOUBLE PRECISION,
    liveness_score DOUBLE PRECISION,
    pad_flag BOOLEAN NOT NULL DEFAULT FALSE,
    pad_attack_type VARCHAR(64),
    pad_confidence DOUBLE PRECISION,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_frame_events_session ON frame_events(session_id);

-- Challenges
CREATE TABLE challenges (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES liveness_sessions(id) ON DELETE CASCADE,
    challenge_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ISSUED',
    attempt_number INTEGER NOT NULL DEFAULT 1,
    timeout_ms INTEGER NOT NULL,
    issued_at TIMESTAMPTZ DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_challenges_session ON challenges(session_id);

-- Config policies (one per workflow)
CREATE TABLE config_policies (
    id UUID PRIMARY KEY,
    workflow_type VARCHAR(32) UNIQUE NOT NULL,
    liveness_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    passive_threshold DOUBLE PRECISION NOT NULL DEFAULT 0.75,
    active_liveness_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    min_challenge_count INTEGER NOT NULL DEFAULT 1,
    -- Stored as JSON text (not JSONB): the application maps these with an
    -- explicit converter so the schema behaves identically on H2 (dev) and
    -- PostgreSQL, and neither column is ever queried with JSON operators.
    challenge_types TEXT NOT NULL DEFAULT '["blink","smile","turn_left","turn_right"]',
    challenge_timeout_ms INTEGER NOT NULL DEFAULT 60000,
    max_retry_count INTEGER NOT NULL DEFAULT 3,
    on_repeated_failure VARCHAR(16) NOT NULL DEFAULT 'LOCK',
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Audit logs
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES liveness_sessions(id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL,
    -- JSON text; see the note on config_policies.challenge_types above.
    details TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_session ON audit_logs(session_id);

-- Seed default config policies for each workflow. Ids are explicit literals
-- because the column has no database-side default.
INSERT INTO config_policies (id, workflow_type) VALUES
    ('00000000-0000-0000-0000-000000000001', 'RESIDENT'),
    ('00000000-0000-0000-0000-000000000002', 'OPERATOR'),
    ('00000000-0000-0000-0000-000000000003', 'SUPERVISOR');
