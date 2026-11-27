-- Knode Search (05) Consumer State, Dual-Version Projection, Index Task, Rebuild Cutover & Ops Schema
-- Target engine: dedicated independent MySQL 8 / InnoDB database, charset utf8mb4, UTC session/JDBC timezone.
-- Manual deployment DDL only: never auto-executed by application startup.
-- All *_ms columns store UTC epoch milliseconds.

CREATE TABLE IF NOT EXISTS search_index_generation (
    alias_name VARCHAR(128) NOT NULL,
    active_generation BIGINT NOT NULL DEFAULT 1,
    active_physical_index VARCHAR(128) NOT NULL,
    require_cold_visibility_guard TINYINT(1) NOT NULL DEFAULT 0,
    cutover_state VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    cutover_job_id VARCHAR(64) NULL,
    previous_generation BIGINT NULL,
    previous_physical_index VARCHAR(128) NULL,
    target_generation BIGINT NULL,
    target_physical_index VARCHAR(128) NULL,
    cutover_started_at_ms BIGINT NULL,
    active_cache_invalidation_status VARCHAR(16) NOT NULL DEFAULT 'DONE',
    active_cache_invalidation_attempts INT NOT NULL DEFAULT 0,
    active_cache_invalidation_due_at_ms BIGINT NULL,
    active_cache_last_error VARCHAR(512) NULL,
    active_cutover_bypass_owner VARCHAR(192) NULL,
    updated_at_ms BIGINT NOT NULL,
    PRIMARY KEY (alias_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

CREATE TABLE IF NOT EXISTS search_article_version_barrier (
    article_id BIGINT NOT NULL,
    max_content_revision BIGINT NOT NULL DEFAULT 0,
    last_visibility VARCHAR(32) NOT NULL DEFAULT 'NONE',
    updated_at_ms BIGINT NOT NULL,
    PRIMARY KEY (article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS search_article_projection (
    index_generation BIGINT NOT NULL DEFAULT 1,
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
    PRIMARY KEY (index_generation, article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_search_projection_gen_vis
    ON search_article_projection (index_generation, has_content, visibility, article_id);

CREATE TABLE IF NOT EXISTS search_index_task (
    index_generation BIGINT NOT NULL DEFAULT 1,
    article_id BIGINT NOT NULL,
    target_index VARCHAR(128) NOT NULL DEFAULT '',
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
    PRIMARY KEY (index_generation, article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_search_index_task_due
    ON search_index_task (index_generation, status, due_at_ms);

CREATE INDEX idx_search_index_task_lease
    ON search_index_task (index_generation, status, lease_until_ms);

CREATE INDEX idx_search_index_task_cache
    ON search_index_task (cache_invalidation_status, cache_invalidation_due_at_ms);


CREATE TABLE IF NOT EXISTS search_rebuild_job (
    job_id VARCHAR(64) NOT NULL,
    alias_name VARCHAR(128) NOT NULL,
    active_lock_key VARCHAR(128) NULL,
    client_request_id VARCHAR(64) NOT NULL,
    snapshot_id VARCHAR(64) NULL,
    snapshot_expires_at_ms BIGINT NULL,
    snapshot_page_cursor VARCHAR(1024) NULL,
    snapshot_done TINYINT(1) NOT NULL DEFAULT 0,
    snapshot_pages_imported INT NOT NULL DEFAULT 0,
    snapshot_items_imported BIGINT NOT NULL DEFAULT 0,
    incremental_cursor VARCHAR(1024) NULL,
    incremental_cursor_expires_at_ms BIGINT NULL,
    last_commit_sequence BIGINT NOT NULL DEFAULT 0,
    incremental_commits_applied BIGINT NOT NULL DEFAULT 0,
    incremental_events_applied BIGINT NOT NULL DEFAULT 0,
    source_generation BIGINT NOT NULL,
    source_physical_index VARCHAR(128) NOT NULL,
    candidate_generation BIGINT NOT NULL,
    candidate_physical_index VARCHAR(128) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    lease_owner VARCHAR(128) NULL,
    lease_until_ms BIGINT NULL,
    lease_generation BIGINT NOT NULL DEFAULT 0,
    alias_switch_uncertain TINYINT(1) NOT NULL DEFAULT 0,
    switch_attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(512) NULL,
    superseded_by_job_id VARCHAR(64) NULL,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    PRIMARY KEY (job_id),
    CONSTRAINT uk_search_rebuild_active_alias UNIQUE (active_lock_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_search_rebuild_job_alias
    ON search_rebuild_job (alias_name, created_at_ms);

CREATE TABLE IF NOT EXISTS search_rebuild_event_log (
    candidate_generation BIGINT NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    commit_sequence BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    source_revision BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    applied_at_ms BIGINT NOT NULL,
    PRIMARY KEY (candidate_generation, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_search_rebuild_evt_commit
    ON search_rebuild_event_log (candidate_generation, commit_sequence);

CREATE TABLE IF NOT EXISTS search_ops_replay_audit (
    replay_request_key VARBINARY(64) NOT NULL,
    replay_request_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    target_event_id VARCHAR(128) NULL,
    request_fingerprint CHAR(64) NOT NULL,
    caller_service VARCHAR(128) NOT NULL,
    operator_id VARCHAR(128) NULL,
    response_status VARCHAR(32) NOT NULL,
    requeued_count BIGINT NOT NULL DEFAULT 0,
    response_json TEXT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    PRIMARY KEY (replay_request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_search_ops_replay_time
    ON search_ops_replay_audit (created_at_ms);
