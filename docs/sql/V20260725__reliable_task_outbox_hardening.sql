-- 统一可靠任务 Module：为现有 ES Outbox 与通知 Outbox 增加幂等任务键、最大重试、死信和重复入队标记。
-- MySQL 8.0 单次迁移；如果某些列已存在，请按实际环境跳过对应 ALTER。

ALTER TABLE yan_article_search_outbox
    ADD COLUMN task_key VARCHAR(128) NULL AFTER article_id,
    ADD COLUMN max_retry INT NOT NULL DEFAULT 8 AFTER retry_count,
    ADD COLUMN dead_letter_at DATETIME(3) NULL AFTER last_error,
    ADD COLUMN rerun_requested TINYINT NOT NULL DEFAULT 0 AFTER dead_letter_at;

UPDATE yan_article_search_outbox
SET task_key = CONCAT('ARTICLE_SEARCH:', article_id, ':LEGACY:', id)
WHERE task_key IS NULL;

CREATE UNIQUE INDEX uk_article_search_outbox_task_key
    ON yan_article_search_outbox(task_key);

ALTER TABLE yan_notification_outbox
    ADD COLUMN task_key VARCHAR(128) NULL AFTER author_id,
    ADD COLUMN max_retry INT NOT NULL DEFAULT 8 AFTER retry_count,
    ADD COLUMN dead_letter_at DATETIME(3) NULL AFTER last_error,
    ADD COLUMN rerun_requested TINYINT NOT NULL DEFAULT 0 AFTER dead_letter_at;

UPDATE yan_notification_outbox
SET task_key = CONCAT('ARTICLE_PUBLISH_NOTIFY:', author_id, ':', article_id, ':LEGACY:', id)
WHERE task_key IS NULL;

CREATE UNIQUE INDEX uk_notification_outbox_task_key
    ON yan_notification_outbox(task_key);
