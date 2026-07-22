CREATE TABLE IF NOT EXISTS yan_notification_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    article_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    cursor_follower_id BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) NOT NULL,
    locked_until DATETIME(3) NULL,
    last_error VARCHAR(1000) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_notification_outbox_pending (status, next_retry_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章发布通知 Outbox';

CREATE TABLE IF NOT EXISTS yan_author_publish_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    author_id BIGINT NOT NULL,
    article_id BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_author_publish_article (article_id),
    KEY idx_author_publish_author (author_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大V作者发布流';

ALTER TABLE yan_user_notification
    ADD UNIQUE KEY uk_notification_article_receiver (receiver_id, article_id, type);
