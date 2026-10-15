CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    event_time TIMESTAMP WITH TIME ZONE NOT NULL,
    user_id VARCHAR(64),
    action VARCHAR(255),
    details JSONB
);