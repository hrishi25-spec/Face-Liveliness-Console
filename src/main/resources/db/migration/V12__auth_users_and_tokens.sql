-- Flyway migration: V12__auth_users_and_tokens.sql
--
-- Username/password accounts and the session tokens they mint, replacing the
-- shared admin API key that used to guard the policy write and the calibration
-- sweep. One controller-visible credential (a password) per human, and one
-- short-lived, revocable server-side token per login.
--
-- Why the key had to go: a shared secret in a deployment's environment names no
-- one in the audit trail, cannot be revoked for one holder without rotating it
-- for every holder, and — being a bearer value with no expiry — is exactly the
-- credential a leaked `.env` hands to an attacker forever. A policy write is the
-- highest-consequence call here (passiveThreshold decides who passes liveness),
-- so it is the last route that should be reachable by a value sitting in a file.
--
-- The operator API key (MOSIP_OPERATOR_API_KEY) is deliberately NOT removed by
-- this migration or the code beside it. It proves only the smaller role: the
-- read-only operator feeds and the role probe, never a policy write and never
-- user management. Keeping it means an existing deployment's read-only console
-- keeps working while the privileged path becomes an accountable account.
--
-- Types are chosen for H2/PostgreSQL parity (the same rule V1 set out):
-- VARCHAR rather than TEXT-with-a-length, UUID rather than a text id, and
-- TIMESTAMP WITH TIME ZONE rather than a database-specific alias. The dev
-- profile runs the same migrations on H2, so anything Postgres-only in here
-- would be untested code.
--
-- Both tables are ordinary tables, not append-only like audit_logs: a token is
-- meant to be revoked (an UPDATE), and a user row is meant to be edited (a
-- role change, a disable) or removed. The account's *history* is the audit
-- trail's job, not this table's.

CREATE TABLE auth_users (
    id UUID PRIMARY KEY,
    -- Stored lowercased, by application rule (the service normalises before
    -- every lookup and before every insert), so "Supervisor" and "supervisor"
    -- cannot become two accounts and the unique index below is genuinely
    -- case-insensitive without depending on a database collation.
    username VARCHAR(64) NOT NULL,
    -- The BCrypt hash, including its own cost and salt ($2a$12$...). 60 chars
    -- today; 100 leaves room for the algorithm identifier and cost digits to
    -- grow without another migration. Never a password, and never reversible.
    password_hash VARCHAR(100) NOT NULL,
    -- SUPERVISOR or OPERATOR, stored as text so the column is readable in a
    -- psql session and a new role needs no migration.
    role VARCHAR(16) NOT NULL,
    -- TRUE for a brand-new account and for any password a supervisor resets.
    -- Enforced by AuthContextFilter: such a session may only reach
    -- GET /auth/me, POST /auth/change-password and POST /auth/logout, so a
    -- bootstrap password cannot silently become a permanent one.
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    -- A disabled account authenticates as nobody, without losing its row (and
    -- so without losing its audit history).
    disabled BOOLEAN NOT NULL DEFAULT FALSE,
    -- Consecutive failed logins, reset by a success. Paired with locked_until.
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    -- Non-null while the account is locked out; a lock is a timestamp rather
    -- than a flag so it expires by itself with no reaper job, and so a restart
    -- cannot clear it.
    locked_until TIMESTAMP WITH TIME ZONE,
    password_changed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- The login lookup key. Unique because two accounts sharing a name would make
-- "who is this" ambiguous at exactly the moment that matters; the repository
-- lowercases, so this rejects a case-variant duplicate too.
CREATE UNIQUE INDEX uq_auth_users_username ON auth_users(username);

-- Last-supervisor protection counts enabled supervisors on every user write;
-- this keeps that count off a sequential scan.
CREATE INDEX idx_auth_users_role ON auth_users(role);


CREATE TABLE auth_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    -- SHA-256 of the opaque token, hex encoded. The token itself is never
    -- stored, so a leaked database snapshot is not a set of usable sessions —
    -- the same reasoning as the password column. It is a fast hash rather than
    -- BCrypt on purpose: the token is 256 bits of CSPRNG output, not a
    -- guessable human secret, so there is nothing for a slow hash to defend
    -- against and the lookup must stay an index hit on every request.
    token_hash VARCHAR(64) NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Set on logout and on any password change; a revoked token is refused
    -- exactly like an expired one and the row is kept, so "this session ended"
    -- stays answerable.
    revoked_at TIMESTAMP WITH TIME ZONE,
    last_used_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_auth_tokens_user FOREIGN KEY (user_id)
        REFERENCES auth_users(id) ON DELETE CASCADE
);

-- The lookup key for every authenticated request: one indexed equality on the
-- hash. Unique because a collision would mean two users sharing a session —
-- with 256 bits of input this is a correctness guard, not a practical one.
CREATE UNIQUE INDEX uq_auth_tokens_hash ON auth_tokens(token_hash);

-- Serves "revoke every session this user holds" on a password change, and the
-- cascade when an account is deleted.
CREATE INDEX idx_auth_tokens_user ON auth_tokens(user_id);

-- Serves nothing yet by design: no endpoint lists or reaps expired tokens, so
-- this index is not added until something queries by expiry. Token rows are
-- bounded by logins per user and rows are refused once expired regardless of
-- whether they still exist.
