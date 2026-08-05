-- P1 后续队列底座：通知 Outbox 处理次数统计
-- 用于后续发文通知、评论通知、回复通知、关注通知接入 Redis Stream 后观察重复消费、重试和恢复情况。

ALTER TABLE yan_notification_outbox
    ADD COLUMN process_count INT NOT NULL DEFAULT 0 AFTER dead_letter_at,
    ADD COLUMN last_process_at DATETIME(3) NULL AFTER process_count;
