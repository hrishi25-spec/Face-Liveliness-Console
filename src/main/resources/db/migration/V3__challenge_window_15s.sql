-- Flyway migration: V3__challenge_window_15s.sql
--
-- Shorten the active-challenge window from 60s to 15s (product request).
--
-- Sources of truth after this migration:
--   * config_policies column default + existing seeded rows : 15s
--   * application.yml / LivenessConfig / AppConfig           : 15s
--   * DecisionEngineService.MIN_CHALLENGE_WINDOW_MS floor     : 15s
--
-- The 60s value was introduced because an impatient window was the main source
-- of false challenge failures; 15s is deliberately tighter, so monitor the
-- challenge failure rate and raise it per-workflow via config_policies if
-- genuine users are timing out. See docs/configuration.md.

ALTER TABLE config_policies ALTER COLUMN challenge_timeout_ms SET DEFAULT 15000;

UPDATE config_policies
   SET challenge_timeout_ms = 15000
 WHERE challenge_timeout_ms = 60000;
