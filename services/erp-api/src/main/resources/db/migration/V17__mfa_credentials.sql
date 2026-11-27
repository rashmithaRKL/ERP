-- V17 Phase 3 security: two-step sign-in (TOTP, RFC 6238) with one-time recovery codes.
--
-- users.mfa_secret holds the authenticator secret encrypted with AES-GCM under the server's MFA_ENCRYPTION_KEY
-- ("v1:" + base64(iv || ciphertext)), never in clear text. mfa_last_step is the last accepted 30-second time step, so a
-- code cannot be used twice. Recovery codes are stored as SHA-256 digests and used at most once.
ALTER TABLE users ADD COLUMN mfa_secret text;
ALTER TABLE users ADD COLUMN mfa_enabled_at timestamptz;
ALTER TABLE users ADD COLUMN mfa_last_step bigint;
ALTER TABLE users ADD CONSTRAINT users_mfa_consistent CHECK ((mfa_secret IS NULL) = (mfa_enabled_at IS NULL));

CREATE TABLE mfa_recovery_codes (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  user_id text NOT NULL REFERENCES users(id),
  code_hash text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  used_at timestamptz,
  UNIQUE (user_id, code_hash)
);
CREATE INDEX mfa_recovery_codes_user ON mfa_recovery_codes(tenant_id, user_id) WHERE used_at IS NULL;
