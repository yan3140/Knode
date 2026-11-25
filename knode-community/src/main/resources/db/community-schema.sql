-- Structural DDL only. Never auto-applied. MySQL 8; H2 tests use MODE=MySQL.
CREATE TABLE yan_user_follow (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    follower_id BIGINT NOT NULL,
    followee_id BIGINT NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_yan_user_follow_pair UNIQUE (follower_id, followee_id)
);
CREATE INDEX idx_yan_user_follow_follower ON yan_user_follow (follower_id, id);
CREATE INDEX idx_yan_user_follow_followee ON yan_user_follow (followee_id, id);

CREATE TABLE yan_notification_delivery_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NULL,
    task_key VARCHAR(160) NOT NULL,
    receiver_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    article_id BIGINT NULL,
    comment_id BIGINT NULL,
    root_comment_id BIGINT NULL,
    type VARCHAR(40) NOT NULL,
    content VARCHAR(500) NULL,
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
    CONSTRAINT uk_notification_delivery_task_key UNIQUE (task_key)
);
CREATE INDEX idx_notification_delivery_pending ON yan_notification_delivery_outbox (status, next_retry_at, id);
CREATE INDEX idx_notification_delivery_event_id ON yan_notification_delivery_outbox (event_id);

CREATE TABLE yan_notification_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NULL,
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
CREATE INDEX idx_notification_outbox_event_id ON yan_notification_outbox (event_id);

CREATE TABLE yan_user_notification (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    receiver_id BIGINT NOT NULL,
    actor_id BIGINT NULL,
    article_id BIGINT NULL,
    comment_id BIGINT NULL,
    root_comment_id BIGINT NULL,
    dedupe_key VARCHAR(160) NULL,
    type VARCHAR(32) NOT NULL,
    content VARCHAR(500) NULL,
    is_read SMALLINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_user_notification_dedupe_key UNIQUE (dedupe_key)
);
CREATE INDEX idx_yan_user_notification_inbox ON yan_user_notification (receiver_id, is_read, create_time, id);
CREATE INDEX idx_yan_user_notification_article ON yan_user_notification (article_id);
CREATE INDEX idx_yan_user_notification_comment ON yan_user_notification (comment_id);
CREATE INDEX idx_yan_user_notification_receiver_article_type ON yan_user_notification (receiver_id, article_id, type);

CREATE TABLE yan_author_publish_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    author_id BIGINT NOT NULL,
    article_id BIGINT NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_author_publish_article UNIQUE (article_id)
);
CREATE INDEX idx_author_publish_author ON yan_author_publish_event (author_id, create_time, id);

CREATE TABLE community_publish_event_dismissal (
    receiver_id BIGINT NOT NULL,
    publish_event_id BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (receiver_id, publish_event_id)
);

CREATE TABLE community_article_projection (
    article_id BIGINT NOT NULL PRIMARY KEY,
    author_id BIGINT NULL,
    title VARCHAR(256) NULL,
    visibility VARCHAR(16) NOT NULL,
    source_revision BIGINT NOT NULL,
    publication_fact_id VARCHAR(128) NULL,
    published_at TIMESTAMP(3) NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

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

CREATE TABLE community_ops_replay_audit (
    replay_key_sha256 VARCHAR(64) NOT NULL PRIMARY KEY,
    replay_request_id VARCHAR(64) NOT NULL,
    target_scope VARCHAR(80) NOT NULL,
    service_subject VARCHAR(128) NOT NULL,
    operator_actor VARCHAR(128) NULL,
    result_status VARCHAR(20) NOT NULL,
    requeued_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE INDEX idx_community_ops_replay_created ON community_ops_replay_audit (created_at);
