-- Additive Stage 3 (COMMUNITY-03A) upgrade DDL from Stage 2 (COMMUNITY-02A). Manual execution only.
ALTER TABLE yan_notification_delivery_outbox ADD COLUMN traceparent VARCHAR(128) NULL;

CREATE TABLE yan_notification_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    article_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    publication_fact_id VARCHAR(128) NULL,
    task_key VARCHAR(160) NOT NULL,
    cursor_follower_id BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    max_retry INT NOT NULL DEFAULT 8,
    next_retry_at TIMESTAMP(3) NOT NULL,
    locked_until TIMESTAMP(3) NULL,
    lease_token VARCHAR(64) NULL,
    rerun_requested SMALLINT NOT NULL DEFAULT 0,
    traceparent VARCHAR(128) NULL,
    last_error VARCHAR(1000) NULL,
    dead_letter_at TIMESTAMP(3) NULL,
    process_count INT NOT NULL DEFAULT 0,
    last_process_at TIMESTAMP(3) NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_notification_outbox_task_key UNIQUE (task_key),
    CONSTRAINT uk_notification_outbox_article UNIQUE (article_id)
);
CREATE INDEX idx_notification_outbox_pending ON yan_notification_outbox (status, next_retry_at, id);

CREATE TABLE community_event_inbox (
    event_id VARCHAR(64) PRIMARY KEY,
    stream_entry_id VARCHAR(64) NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version INT NOT NULL DEFAULT 0,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id VARCHAR(32) NULL,
    source_revision BIGINT NULL,
    publication_fact_id VARCHAR(128) NULL,
    traceparent VARCHAR(128) NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    last_error VARCHAR(512) NULL,
    received_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE INDEX idx_community_event_inbox_status ON community_event_inbox (status, updated_at, event_id);
