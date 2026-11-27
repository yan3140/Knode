-- Knode Search (05) Additive Upgrade DDL: SEARCH-03-R3/R4/R5 -> SEARCH-04 / SEARCH-04-R2
-- Target engine: dedicated independent MySQL 8 / InnoDB database, charset utf8mb4, UTC session/JDBC timezone.
-- Adds durable ops replay idempotency and operator audit ledger (purely additive, no ALTER on existing tables).
-- Applicability: run once when upgrading from SEARCH-03-R3/R4/R5 where search_ops_replay_audit does not yet exist.
--   * Fresh install: use db/search-consumer-schema.sql instead.
--   * Existing SEARCH-04 / SEARCH-04-R1 table: use db/search-ops-04-r2-upgrade.sql instead.
-- Manual deployment DDL only: never auto-executed by application startup.

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
