-- 通知幂等应以 dedupe_key 为准。
-- 原唯一键 (receiver_id, article_id, type) 会导致同一用户在同一文章下只能收到一条评论通知，
-- 在高并发投递时会让 INSERT IGNORE 静默吞掉后续通知。

ALTER TABLE yan_user_notification
    DROP INDEX uk_notification_article_receiver;

CREATE INDEX idx_yan_user_notification_receiver_article_type
    ON yan_user_notification(receiver_id, article_id, type);
