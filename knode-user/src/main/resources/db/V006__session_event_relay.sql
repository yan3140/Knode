-- Apply only to a separately authorized user database; never auto-executed.
ALTER TABLE auth_revocation_intent ADD COLUMN reason VARCHAR(32) NOT NULL DEFAULT 'UNSPECIFIED';
ALTER TABLE auth_revocation_outbox
 ADD COLUMN reason VARCHAR(32) NOT NULL DEFAULT 'UNSPECIFIED',
 ADD COLUMN event_json TEXT,
 ADD COLUMN attempts INT NOT NULL DEFAULT 0,
 ADD COLUMN next_attempt_at TIMESTAMP NULL,
 ADD COLUMN claim_token VARCHAR(36),
 ADD COLUMN claim_until TIMESTAMP NULL,
 ADD COLUMN last_error VARCHAR(128),
 ADD COLUMN published_at TIMESTAMP NULL;
CREATE TABLE auth_revocation_replay_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(36) NOT NULL,
 actor_id BIGINT NOT NULL, requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_auth_outbox_due ON auth_revocation_outbox(state,next_attempt_at,claim_until);
-- Historical REVOKED intents with no outbox remain a documented gap; this migration does not backfill.

CREATE INDEX idx_auth_login_expiry ON auth_login_session(expires_at);
