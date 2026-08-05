-- P3：评论、回复、关注通知的可靠投递 Outbox
-- 文章发布通知仍使用 yan_notification_outbox；点对点通知使用本表进入可重试、可死信、可幂等的可靠任务模型。

ALTER TABLE yan_user_notification
    ADD COLUMN dedupe_key VARCHAR(160) NULL AFTER root_comment_id;

CREATE UNIQUE INDEX uk_user_notification_dedupe_key
    ON yan_user_notification(dedupe_key);

CREATE TABLE IF NOT EXISTS yan_notification_delivery_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
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
    next_retry_at DATETIME(3) NOT NULL,
    locked_until DATETIME(3) NULL,
    last_error VARCHAR(1000) NULL,
    dead_letter_at DATETIME(3) NULL,
    process_count INT NOT NULL DEFAULT 0,
    last_process_at DATETIME(3) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_delivery_task_key (task_key),
    KEY idx_notification_delivery_pending (status, next_retry_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点对点通知投递 Outbox';
