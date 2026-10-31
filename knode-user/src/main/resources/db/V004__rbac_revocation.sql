-- For a separately authorized G3 user database; never auto-executed.
ALTER TABLE sys_role MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE yan_menu MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT;
ALTER TABLE auth_revocation_intent
 ADD COLUMN attempts INT NOT NULL DEFAULT 0,
 ADD COLUMN next_attempt_at TIMESTAMP NULL,
 ADD COLUMN claim_token VARCHAR(36),
 ADD COLUMN claim_until TIMESTAMP NULL,
 ADD COLUMN last_error VARCHAR(128);
CREATE TABLE auth_revocation_outbox (
 event_id VARCHAR(36) NOT NULL PRIMARY KEY, sid VARCHAR(36) NOT NULL UNIQUE,
 user_id BIGINT NOT NULL, scope VARCHAR(5) NOT NULL, occurred_at TIMESTAMP NOT NULL,
 execution_scope VARCHAR(32) NOT NULL, state VARCHAR(16) NOT NULL
);
CREATE TABLE user_security_lock (id INT NOT NULL PRIMARY KEY);
INSERT INTO user_security_lock(id) VALUES(1);

-- Existing G3 Redis sessions have no per-user session index and must be invalidated during cutover.
CREATE TABLE auth_login_session (
 sid VARCHAR(36) NOT NULL PRIMARY KEY, user_id BIGINT NOT NULL, scope VARCHAR(5) NOT NULL,
 expires_at TIMESTAMP NOT NULL, active INT NOT NULL DEFAULT 1
);
CREATE INDEX idx_auth_login_user ON auth_login_session(user_id,active);

CREATE INDEX idx_auth_revoke_due ON auth_revocation_intent(state,next_attempt_at,claim_until);
CREATE INDEX idx_auth_outbox_state ON auth_revocation_outbox(state,occurred_at);
