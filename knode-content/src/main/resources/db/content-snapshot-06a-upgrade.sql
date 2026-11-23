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
