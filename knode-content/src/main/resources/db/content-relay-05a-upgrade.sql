-- Manual producer delivery schema; immutable fact tables remain unchanged.
CREATE TABLE content_event_delivery (
 event_id VARCHAR(36) PRIMARY KEY, family VARCHAR(16) NOT NULL, event_type VARCHAR(64) NOT NULL,
 state VARCHAR(16) NOT NULL, attempts INT NOT NULL DEFAULT 0, total_attempts BIGINT NOT NULL DEFAULT 0,
 due_at TIMESTAMP(6), lease_token VARCHAR(36), last_error VARCHAR(32),
 delivered_at TIMESTAMP(6), stream_id VARCHAR(64)
);
CREATE INDEX ix_content_event_delivery_due ON content_event_delivery(due_at,event_id);
CREATE INDEX ix_content_event_delivery_state ON content_event_delivery(state,due_at);
CREATE TABLE content_event_replay_audit (
 replay_request_hash CHAR(64) PRIMARY KEY, replay_request_id VARCHAR(64) NOT NULL, event_id VARCHAR(36) NOT NULL,
 operator_service VARCHAR(128) NOT NULL, accepted_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_content_replay_delivery FOREIGN KEY(event_id) REFERENCES content_event_delivery(event_id)
);
CREATE INDEX ix_content_event_replay_event ON content_event_replay_audit(event_id,accepted_at);
CREATE TABLE content_event_backfill (
 family VARCHAR(16) PRIMARY KEY, cursor_event_id VARCHAR(36) NOT NULL,
 completed_passes BIGINT NOT NULL DEFAULT 0, failure_count BIGINT NOT NULL DEFAULT 0,
 last_error VARCHAR(32), last_failed_at TIMESTAMP(6)
);
INSERT INTO content_event_backfill(family,cursor_event_id,completed_passes) VALUES
 ('ARTICLE','',0),('COMMENT','',0),('COUNTER','',0);
CREATE TABLE content_event_attempt_audit (
 lease_token VARCHAR(36) PRIMARY KEY, event_id VARCHAR(36) NOT NULL, attempt_number INT NOT NULL,
 claimed_at TIMESTAMP(6) NOT NULL, finished_at TIMESTAMP(6), outcome VARCHAR(24) NOT NULL,
 error_category VARCHAR(32), stream_id VARCHAR(64),
 CONSTRAINT fk_content_attempt_delivery FOREIGN KEY(event_id) REFERENCES content_event_delivery(event_id)
);
CREATE INDEX ix_content_attempt_event ON content_event_attempt_audit(event_id,claimed_at,lease_token);
