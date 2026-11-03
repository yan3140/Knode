-- Apply only to an authorized isolated User database; never auto-executed.
CREATE TABLE user_login_attempt_window (
 bucket VARCHAR(80) NOT NULL PRIMARY KEY,
 attempts INT NOT NULL,
 expires_at BIGINT NOT NULL
);
CREATE INDEX idx_user_login_attempt_expiry ON user_login_attempt_window(expires_at);
