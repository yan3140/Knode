-- Additive Stage 2 (COMMUNITY-02A) upgrade DDL from Stage 1 (COMMUNITY-01A). Manual execution only.
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
