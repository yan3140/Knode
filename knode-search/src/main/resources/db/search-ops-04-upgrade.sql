-- Knode Search (05) Additive Upgrade DDL: SEARCH-03-R3/R5 -> SEARCH-04
-- Adds durable ops replay idempotency and operator audit ledger (purely additive, no ALTER on existing tables).
-- Manual deployment DDL only: never auto-executed by application startup.

CREATE TABLE IF NOT EXISTS search_ops_replay_audit (
    replay_request_id VARCHAR(64) NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    target_event_id VARCHAR(128) NULL,
    request_fingerprint CHAR(64) NOT NULL,
    caller_service VARCHAR(128) NOT NULL,
    operator_id VARCHAR(128) NULL,
    response_status VARCHAR(32) NOT NULL,
    requeued_count BIGINT NOT NULL DEFAULT 0,
    response_json TEXT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    PRIMARY KEY (replay_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_search_ops_replay_time
    ON search_ops_replay_audit (created_at_ms);
