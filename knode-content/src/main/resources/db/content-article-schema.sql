-- Structural DDL only. Never auto-applied. MySQL 8; H2 tests use MODE=MySQL.
CREATE TABLE content_category (
 id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, status CHAR(1) NOT NULL, description VARCHAR(1024)
);
CREATE TABLE content_tag (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(256) NOT NULL, del_flag SMALLINT NOT NULL DEFAULT 0, remark VARCHAR(1024),
 created_by VARCHAR(19), created_at TIMESTAMP(6), updated_by VARCHAR(19), updated_at TIMESTAMP(6)
);
CREATE TABLE content_article (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 title VARCHAR(256) NOT NULL, content LONGTEXT NOT NULL, summary VARCHAR(1024),
 thumbnail VARCHAR(2048), category_id BIGINT NOT NULL, is_top CHAR(1) NOT NULL,
 is_comment CHAR(1) NOT NULL, status CHAR(1) NOT NULL, author_id VARCHAR(19) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL, updated_by VARCHAR(19) NOT NULL,
 view_count BIGINT NOT NULL DEFAULT 0, counter_revision BIGINT NOT NULL DEFAULT 0,
 source_revision BIGINT NOT NULL, publication_fact_id VARCHAR(36), published_at TIMESTAMP(6),
 deleted SMALLINT NOT NULL DEFAULT 0,
 CONSTRAINT uq_content_publication_fact UNIQUE (publication_fact_id),
 CONSTRAINT fk_article_category FOREIGN KEY (category_id) REFERENCES content_category(id)
);
CREATE INDEX ix_content_article_public_list ON content_article(status,deleted,is_top,created_at,id);
CREATE INDEX ix_content_article_public_hot ON content_article(status,deleted,view_count,id);
CREATE TABLE content_article_tag (
 article_id BIGINT NOT NULL, tag_id BIGINT NOT NULL,
 PRIMARY KEY (article_id, tag_id),
 CONSTRAINT fk_article_tag_article FOREIGN KEY (article_id) REFERENCES content_article(id),
 CONSTRAINT fk_article_tag_tag FOREIGN KEY (tag_id) REFERENCES content_tag(id)
);
CREATE INDEX ix_content_article_tag_tag ON content_article_tag(tag_id,article_id);
CREATE TABLE content_article_event (
 event_id VARCHAR(36) PRIMARY KEY, article_id BIGINT NOT NULL, source_revision BIGINT NOT NULL,
 event_type VARCHAR(64) NOT NULL, payload LONGTEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_content_article_event_revision UNIQUE(article_id, source_revision),
 CONSTRAINT fk_content_event_article FOREIGN KEY (article_id) REFERENCES content_article(id)
);

CREATE TABLE content_write_fence (id BIGINT PRIMARY KEY);
INSERT INTO content_write_fence(id) VALUES (1);
CREATE TABLE content_metadata_job (
 id VARCHAR(36) PRIMARY KEY, tag_id BIGINT NOT NULL, kind VARCHAR(16) NOT NULL, created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE content_metadata_target (
 job_id VARCHAR(36) NOT NULL, article_id BIGINT NOT NULL, state VARCHAR(16) NOT NULL,
 attempts INT NOT NULL DEFAULT 0, next_attempt TIMESTAMP(6) NOT NULL, error_code VARCHAR(32),
 PRIMARY KEY(job_id,article_id)
);
CREATE INDEX ix_content_metadata_due ON content_metadata_target(state,next_attempt,job_id,article_id);
CREATE TABLE content_link (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(256) NOT NULL, logo VARCHAR(2048), description VARCHAR(1024),
 address VARCHAR(2048) NOT NULL, status CHAR(1) NOT NULL, del_flag SMALLINT NOT NULL DEFAULT 0,
 created_by VARCHAR(19) NOT NULL, created_at TIMESTAMP(6) NOT NULL, updated_by VARCHAR(19) NOT NULL, updated_at TIMESTAMP(6) NOT NULL
);


CREATE TABLE content_comment (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 comment_type CHAR(1) NOT NULL, article_id BIGINT,
 root_id BIGINT NOT NULL, to_comment_id BIGINT NOT NULL, to_user_id VARCHAR(19) NOT NULL,
 actor_id VARCHAR(19) NOT NULL, content VARCHAR(4000) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL, deleted SMALLINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_content_comment_article FOREIGN KEY (article_id) REFERENCES content_article(id)
);
CREATE INDEX ix_content_comment_thread ON content_comment(comment_type,article_id,deleted,root_id,created_at,id);
CREATE TABLE content_comment_event (
 event_id VARCHAR(36) PRIMARY KEY, comment_id BIGINT NOT NULL, source_revision BIGINT NOT NULL,
 event_type VARCHAR(64) NOT NULL, payload LONGTEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_content_comment_event_revision UNIQUE(comment_id,source_revision),
 CONSTRAINT fk_content_comment_event FOREIGN KEY (comment_id) REFERENCES content_comment(id)
);
CREATE TABLE content_comment_limit (
 kind CHAR(1) NOT NULL, key_hash CHAR(64) NOT NULL, hits INT NOT NULL,
 expires_at TIMESTAMP(6) NOT NULL, PRIMARY KEY(kind,key_hash)
);
CREATE INDEX ix_content_comment_limit_expiry ON content_comment_limit(expires_at,kind,key_hash);

-- Manual structural change for the counter event stream; never auto-run.
CREATE TABLE content_article_counter_event (
 event_id VARCHAR(36) PRIMARY KEY, article_id BIGINT NOT NULL, counter_revision BIGINT NOT NULL,
 payload LONGTEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_content_article_counter_revision UNIQUE(article_id,counter_revision),
 CONSTRAINT fk_content_counter_event_article FOREIGN KEY (article_id) REFERENCES content_article(id)
);

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

-- Manual 06A upgrade. Apply before any 06A writer starts. Baseline is the authoritative article tables.
CREATE TABLE content_article_change_head (
 id BIGINT PRIMARY KEY, last_sequence BIGINT NOT NULL
);
INSERT INTO content_article_change_head(id,last_sequence) VALUES (1,0);
CREATE TABLE content_article_change_batch (
 commit_sequence BIGINT PRIMARY KEY, fact_count INT NOT NULL, payload_bytes INT NOT NULL
);
CREATE TABLE content_article_change_fact (
 commit_sequence BIGINT NOT NULL, fact_position INT NOT NULL, event_id VARCHAR(36) NOT NULL,
 family VARCHAR(16) NOT NULL, event_type VARCHAR(64) NOT NULL, payload_bytes INT NOT NULL,
 PRIMARY KEY(commit_sequence,fact_position),
 CONSTRAINT uq_content_article_change_event UNIQUE(event_id),
 CONSTRAINT fk_content_article_change_batch FOREIGN KEY(commit_sequence) REFERENCES content_article_change_batch(commit_sequence)
);
CREATE INDEX ix_content_article_snapshot_public ON content_article(status,deleted,id);

CREATE TABLE content_article_snapshot_gate (id BIGINT PRIMARY KEY);
INSERT INTO content_article_snapshot_gate(id) VALUES (1);
CREATE TABLE content_article_snapshot (
 snapshot_id VARCHAR(36) PRIMARY KEY, request_key CHAR(64) NOT NULL, request_id VARCHAR(64) NOT NULL,
 owner_service VARCHAR(128) NOT NULL, purpose VARCHAR(32) NOT NULL, feed_epoch VARCHAR(36) NOT NULL,
 cut_sequence BIGINT NOT NULL, expires_at TIMESTAMP(6) NOT NULL, feed_expires_at TIMESTAMP(6) NOT NULL,
 items_pruned SMALLINT NOT NULL DEFAULT 0,
 CONSTRAINT uq_content_article_snapshot_request UNIQUE(request_key)
);
CREATE INDEX ix_content_article_snapshot_expiry ON content_article_snapshot(items_pruned,expires_at,snapshot_id);
CREATE TABLE content_article_snapshot_item (
 snapshot_id VARCHAR(36) NOT NULL, article_id BIGINT NOT NULL, payload_bytes INT NOT NULL, projection_json LONGTEXT NOT NULL,
 PRIMARY KEY(snapshot_id,article_id),
 CONSTRAINT fk_content_article_snapshot_item FOREIGN KEY(snapshot_id) REFERENCES content_article_snapshot(snapshot_id)
);
