-- For separately authorized new User databases only; never auto-executed.
CREATE TABLE auth_delegation_grant (
 grant_id VARCHAR(36) NOT NULL PRIMARY KEY, root_grant_id VARCHAR(36) NOT NULL, parent_grant_id VARCHAR(36),
 user_id BIGINT NOT NULL, sid VARCHAR(36) NOT NULL, user_scope VARCHAR(5) NOT NULL,
 actor_service VARCHAR(64) NOT NULL, holder_service VARCHAR(64) NOT NULL, purpose VARCHAR(24) NOT NULL,
 permissions_json TEXT NOT NULL, resource_json TEXT NOT NULL, envelope_json TEXT NOT NULL,
 proof_refs_json TEXT NOT NULL, proof_revisions_json TEXT NOT NULL,
 authorization_expires_at TIMESTAMP NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, state VARCHAR(16) NOT NULL,
 checkpoint_operation_id VARCHAR(64), CONSTRAINT uq_checkpoint_operation UNIQUE(checkpoint_operation_id)
);
CREATE INDEX idx_delegation_sid ON auth_delegation_grant(sid,state);
CREATE TABLE auth_delegation_grant_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, grant_id VARCHAR(36) NOT NULL,
 actor_service VARCHAR(64) NOT NULL, audience VARCHAR(64) NOT NULL, purpose VARCHAR(24) NOT NULL,
 action VARCHAR(16) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
