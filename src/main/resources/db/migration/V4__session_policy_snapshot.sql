-- Flyway migration: V4__session_policy_snapshot.sql
--
-- Two changes, both serving "take the user type at session start":
--
-- 1. Freeze the resolved per-workflow policy on the session. The decision engine
--    used to re-read config_policies on every frame, so an admin edit mid-session
--    silently changed an in-flight session's operating point. The snapshot is
--    resolved and validated once at POST /api/v1/sessions and drives that session
--    to completion. Legacy rows (NULL snapshot) fall back to a live read.
--
-- 2. Give the three workflows distinct default operating points. They were
--    seeded identically, which made "resident / operator / supervisor" a label
--    rather than a policy. Values mirror WorkflowPolicyDefaults (the single
--    source of truth) and are only seed defaults — an operator can override any
--    field at runtime via PUT /api/v1/config/{workflowType}.
--
--    RESIDENT   : threshold 0.80, 1 challenge, 20s window, 3 retries, ESCALATE
--                 (a resident may be assisted by an operator), blink + smile.
--    OPERATOR   : threshold 0.82, 1 challenge, 15s window, 2 retries,
--                 ALLOW_RETRY (may open a fresh session; never escalates to an
--                 operator), blink + head turns.
--    SUPERVISOR : threshold 0.85, 2 challenges, 15s window, 1 retry, LOCK
--                 (strictest), all four challenges.

ALTER TABLE liveness_sessions ADD COLUMN policy_snapshot TEXT;
ALTER TABLE liveness_sessions ADD COLUMN policy_snapshot_at TIMESTAMPTZ;

UPDATE config_policies
   SET passive_threshold    = 0.80,
       min_challenge_count  = 1,
       challenge_timeout_ms = 20000,
       max_retry_count      = 3,
       on_repeated_failure  = 'ESCALATE',
       challenge_types      = '["blink","smile"]'
 WHERE workflow_type = 'RESIDENT';

UPDATE config_policies
   SET passive_threshold    = 0.82,
       min_challenge_count  = 1,
       challenge_timeout_ms = 15000,
       max_retry_count      = 2,
       on_repeated_failure  = 'ALLOW_RETRY',
       challenge_types      = '["blink","turn_left","turn_right"]'
 WHERE workflow_type = 'OPERATOR';

UPDATE config_policies
   SET passive_threshold    = 0.85,
       min_challenge_count  = 2,
       challenge_timeout_ms = 15000,
       max_retry_count      = 1,
       on_repeated_failure  = 'LOCK',
       challenge_types      = '["blink","smile","turn_left","turn_right"]'
 WHERE workflow_type = 'SUPERVISOR';
