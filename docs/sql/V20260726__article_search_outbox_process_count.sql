-- P1：ES Outbox 处理次数统计
-- 用于观察同一可靠任务实际被 Processor 处理了多少次，辅助定位重复消费、重试和积压恢复。

ALTER TABLE yan_article_search_outbox
    ADD COLUMN process_count INT NOT NULL DEFAULT 0 AFTER dead_letter_at,
    ADD COLUMN last_process_at DATETIME(3) NULL AFTER process_count;
