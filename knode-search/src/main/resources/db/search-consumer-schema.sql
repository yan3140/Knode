-- Knode Search (05) Consumer State, Dual-Version Projection and Index Task Schema
-- Target engine: dedicated independent MySQL 8 / InnoDB database, charset utf8mb4, UTC session/JDBC timezone.
-- Manual deployment DDL only: never auto-executed by application startup.
-- All *_ms columns store UTC epoch milliseconds.

CREATE TABLE IF NOT EXISTS search_event_inbox (
    event_id VARCHAR(128) NOT NULL,
    stream_record_id VARCHAR(64) NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version INT NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    source_revision BIGINT NOT NULL,
    visibility VARCHAR(32) NULL,
    status VARCHAR(32) NOT NULL,
    received_at_ms BIGINT NOT NULL,
    processed_at_ms BIGINT NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_search_inbox_agg
    ON search_event_inbox (aggregate_type, aggregate_id, processed_at_ms);

CREATE TABLE IF NOT EXISTS search_event_failure (
    failure_key VARCHAR(160) NOT NULL,
    event_id VARCHAR(128) NULL,
    stream_record_id VARCHAR(64) NOT NULL,
    error_code VARCHAR(64) NOT NULL,
    error_message VARCHAR(512) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    payload_bytes INT NOT NULL,
    recorded_at_ms BIGINT NOT NULL,
    PRIMARY KEY (failure_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_search_failure_event
    ON search_event_failure (event_id);

CREATE INDEX idx_search_failure_stream
    ON search_event_failure (stream_record_id);

CREATE INDEX idx_search_failure_time
    ON search_event_failure (recorded_at_ms);

CREATE TABLE IF NOT EXISTS search_article_projection (
    article_id BIGINT NOT NULL,
    has_content TINYINT(1) NOT NULL DEFAULT 0,
    content_revision BIGINT NOT NULL DEFAULT 0,
    visibility VARCHAR(32) NOT NULL DEFAULT 'NONE',
    counter_revision BIGINT NOT NULL DEFAULT 0,
    view_count BIGINT NOT NULL DEFAULT 0,
    projection_revision BIGINT NOT NULL DEFAULT 0,
    title VARCHAR(256) NULL,
    summary VARCHAR(1024) NULL,
    content LONGTEXT NULL,
    category_id BIGINT NULL,
    category_name VARCHAR(256) NULL,
    tag_names_json TEXT NULL,
    thumbnail VARCHAR(2048) NULL,
    is_top VARCHAR(8) NULL,
    create_time_ms BIGINT NULL,
    updated_at_ms BIGINT NOT NULL,
    PRIMARY KEY (article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS search_index_task (
    article_id BIGINT NOT NULL,
    target_projection_revision BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    due_at_ms BIGINT NOT NULL,
    lease_until_ms BIGINT NULL,
    claim_generation BIGINT NOT NULL DEFAULT 0,
    last_error VARCHAR(512) NULL,
    es_applied_revision BIGINT NOT NULL DEFAULT 0,
    cache_invalidation_status VARCHAR(16) NOT NULL DEFAULT 'NONE',
    cache_invalidation_attempts INT NOT NULL DEFAULT 0,
    cache_invalidation_due_at_ms BIGINT NULL,
    cache_last_error VARCHAR(512) NULL,
    updated_at_ms BIGINT NOT NULL,
    PRIMARY KEY (article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_search_index_task_due
    ON search_index_task (status, due_at_ms);

CREATE INDEX idx_search_index_task_lease
    ON search_index_task (status, lease_until_ms);

CREATE INDEX idx_search_index_task_cache
    ON search_index_task (cache_invalidation_status, cache_invalidation_due_at_ms);
