-- Day 8：文章搜索索引 Outbox（MySQL 8.0，单次执行）
-- 文章变更与该表记录在同一事务提交；ES 同步失败或进程重启后可继续处理。

CREATE TABLE yan_article_search_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    article_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) NOT NULL,
    locked_until DATETIME(3) NULL,
    last_error VARCHAR(1000) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_article_search_outbox_pending (status, next_retry_at, id),
    KEY idx_article_search_outbox_claim (status, locked_until),
    KEY idx_article_search_outbox_article (article_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章搜索索引可靠投递事件';
