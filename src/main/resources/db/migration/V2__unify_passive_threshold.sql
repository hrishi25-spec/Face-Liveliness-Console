-- Flyway migration: V2__unify_passive_threshold.sql
--
-- The passive threshold had three disagreeing defaults:
--   * config_policies column default + seeded rows : 0.75 (V1)
--   * application.yml / LivenessConfig / docs      : 0.80
-- so the operating point depended on which path created the row. 0.80 is the
-- single source of truth (LivenessConfig.DEFAULT_PASSIVE_THRESHOLD), and the
-- value is subject to calibration via GET /api/v1/eval/threshold-sweep — see
-- docs/configuration.md.
--
-- Only rows still sitting on the old default are touched; any value an operator
-- explicitly configured through PUT /api/v1/config/{workflowType} is preserved.

ALTER TABLE config_policies ALTER COLUMN passive_threshold SET DEFAULT 0.80;

UPDATE config_policies
   SET passive_threshold = 0.80
 WHERE passive_threshold = 0.75;
