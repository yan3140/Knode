-- Apply only to a new, authorized user database; never auto-executed.
CREATE TABLE sys_user (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_name VARCHAR(64) NOT NULL, nick_name VARCHAR(64) NOT NULL,
 password VARCHAR(100) NOT NULL, type CHAR(1) NOT NULL DEFAULT '0',
 status CHAR(1) NOT NULL DEFAULT '0', email VARCHAR(255) NOT NULL,
 phonenumber VARCHAR(32), sex CHAR(1) NOT NULL DEFAULT '2', avatar VARCHAR(512),
 create_by BIGINT, create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_by BIGINT, update_time TIMESTAMP NULL, del_flag INT NOT NULL DEFAULT 0,
 profile_revision BIGINT NOT NULL DEFAULT 1, profile_updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uq_user_name UNIQUE (user_name), CONSTRAINT uq_user_nick UNIQUE (nick_name),
 CONSTRAINT uq_user_email UNIQUE (email)
);
CREATE TABLE sys_role (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, role_name VARCHAR(128) NOT NULL, role_key VARCHAR(128) NOT NULL,
 role_sort INT NOT NULL DEFAULT 0, status CHAR(1) NOT NULL DEFAULT '0', del_flag CHAR(1) NOT NULL DEFAULT '0',
 create_by BIGINT, create_time TIMESTAMP NULL, update_by BIGINT, update_time TIMESTAMP NULL, remark VARCHAR(500)
);
CREATE TABLE yan_menu (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, parent_id BIGINT NOT NULL DEFAULT 0, menu_name VARCHAR(128) NOT NULL,
 order_num INT NOT NULL DEFAULT 0, path VARCHAR(255), component VARCHAR(255), is_frame INT,
 menu_type CHAR(1) NOT NULL, visible CHAR(1) NOT NULL DEFAULT '0', status CHAR(1) NOT NULL DEFAULT '0',
 perms VARCHAR(255), icon VARCHAR(255), create_by BIGINT, create_time TIMESTAMP NULL,
 update_by BIGINT, update_time TIMESTAMP NULL, remark VARCHAR(500), del_flag CHAR(1) NOT NULL DEFAULT '0'
);
CREATE TABLE sys_user_role (user_id BIGINT NOT NULL, role_id BIGINT NOT NULL, PRIMARY KEY(user_id,role_id));
CREATE TABLE sys_role_menu (role_id BIGINT NOT NULL, menu_id BIGINT NOT NULL, PRIMARY KEY(role_id,menu_id));
CREATE TABLE auth_revocation_intent (
 sid VARCHAR(36) NOT NULL PRIMARY KEY, user_id BIGINT NOT NULL, scope VARCHAR(5) NOT NULL,
 event_id VARCHAR(36) NOT NULL UNIQUE, state VARCHAR(16) NOT NULL, reason VARCHAR(32) NOT NULL DEFAULT 'UNSPECIFIED',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, revoked_at TIMESTAMP NULL,
 attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP NULL, claim_token VARCHAR(36), claim_until TIMESTAMP NULL, last_error VARCHAR(128)
);

CREATE TABLE auth_revocation_outbox (
 event_id VARCHAR(36) NOT NULL PRIMARY KEY, sid VARCHAR(36) NOT NULL UNIQUE,
 user_id BIGINT NOT NULL, scope VARCHAR(5) NOT NULL, occurred_at TIMESTAMP NOT NULL,
 execution_scope VARCHAR(32) NOT NULL, state VARCHAR(16) NOT NULL,
 reason VARCHAR(32) NOT NULL DEFAULT 'UNSPECIFIED', event_json TEXT,
 attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP NULL,
 claim_token VARCHAR(36), claim_until TIMESTAMP NULL, last_error VARCHAR(128), published_at TIMESTAMP NULL
);
CREATE TABLE auth_revocation_replay_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(36) NOT NULL, actor_id BIGINT NOT NULL,
 requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE user_security_lock (id INT NOT NULL PRIMARY KEY);
INSERT INTO user_security_lock(id) VALUES (1);

CREATE TABLE auth_login_session (
 sid VARCHAR(36) NOT NULL PRIMARY KEY, user_id BIGINT NOT NULL, scope VARCHAR(5) NOT NULL,
 expires_at TIMESTAMP NOT NULL, active INT NOT NULL DEFAULT 1
);
CREATE INDEX idx_auth_login_user ON auth_login_session(user_id,active);

CREATE INDEX idx_auth_revoke_due ON auth_revocation_intent(state,next_attempt_at,claim_until);
CREATE INDEX idx_auth_outbox_state ON auth_revocation_outbox(state,occurred_at);

CREATE INDEX idx_auth_outbox_due ON auth_revocation_outbox(state,next_attempt_at,claim_until);

CREATE INDEX idx_auth_login_expiry ON auth_login_session(expires_at);
