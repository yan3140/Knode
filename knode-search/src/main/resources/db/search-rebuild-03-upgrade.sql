-- Knode Search (05) Additive Upgrade DDL: SEARCH-02 -> SEARCH-03 (including SEARCH-03-R1/R3 columns)
-- Target engine: dedicated independent MySQL 8 / InnoDB database, charset utf8mb4, UTC session/JDBC timezone.
-- Manual deployment DDL only: run once on an existing SEARCH-02 schema while consumers/indexers are stopped.
-- Preserves all existing SEARCH-02 inbox, failure, projection, and index task rows as index_generation = 1.

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

CREATE TABLE IF NOT EXISTS search_article_version_barrier (
    article_id BIGINT NOT NULL,
    max_content_revision BIGINT NOT NULL DEFAULT 0,
    last_visibility VARCHAR(32) NOT NULL DEFAULT 'NONE',
    updated_at_ms BIGINT NOT NULL,
    PRIMARY KEY (article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE search_article_projection
    ADD COLUMN index_generation BIGINT NOT NULL DEFAULT 1;

ALTER TABLE search_article_projection
    DROP PRIMARY KEY;

ALTER TABLE search_article_projection
    ADD PRIMARY KEY (index_generation, article_id);

CREATE INDEX idx_search_projection_gen_vis
    ON search_article_projection (index_generation, has_content, visibility, article_id);

-- Backfill cross-generation version barriers from existing generation-1 projections.
INSERT IGNORE INTO search_article_version_barrier (article_id, max_content_revision, last_visibility, updated_at_ms)
SELECT article_id, content_revision, visibility, updated_at_ms
FROM search_article_projection
WHERE has_content = 1 AND content_revision > 0;

ALTER TABLE search_index_task
    ADD COLUMN index_generation BIGINT NOT NULL DEFAULT 1;

ALTER TABLE search_index_task
    ADD COLUMN target_index VARCHAR(128) NOT NULL DEFAULT '';

ALTER TABLE search_index_task
    DROP PRIMARY KEY;

ALTER TABLE search_index_task
    ADD PRIMARY KEY (index_generation, article_id);

CREATE INDEX idx_search_index_task_gen_due
    ON search_index_task (index_generation, status, due_at_ms);

CREATE INDEX idx_search_index_task_gen_lease
    ON search_index_task (index_generation, status, lease_until_ms);

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
