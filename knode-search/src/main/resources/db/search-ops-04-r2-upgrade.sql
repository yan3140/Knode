-- Knode Search (05) Upgrade DDL: SEARCH-04 / SEARCH-04-R1 -> SEARCH-04-R2
-- Target engine: dedicated independent MySQL 8 / InnoDB database, charset utf8mb4, UTC session/JDBC timezone.
-- Manual deployment DDL only: run once on an existing SEARCH-04 or SEARCH-04-R1 schema while ops replay endpoints are quiesced.
--
-- Applicability and execution order:
--   1) Fresh install: execute db/search-consumer-schema.sql only (already includes SEARCH-04-R2).
--   2) Upgrading from SEARCH-03-R3/R4/R5 (no search_ops_replay_audit table yet): execute db/search-ops-04-upgrade.sql only (already includes SEARCH-04-R2).
--   3) Upgrading from SEARCH-04 or SEARCH-04-R1 (search_ops_replay_audit already exists with VARCHAR PK): execute this script once.
--
-- Preserves all existing replay_request_id values, request fingerprints, responses, caller/operator audit fields and timestamps,
-- while backfilling exact UTF-8 bytes into replay_request_key (VARBINARY(64)) and switching PRIMARY KEY to replay_request_key
-- so case-variant replayRequestId values (e.g. ReviewCaseKey vs reviewcasekey) compare and constrain byte-for-byte regardless of DB collation.

ALTER TABLE search_ops_replay_audit
    ADD COLUMN replay_request_key VARBINARY(64) NULL;

UPDATE search_ops_replay_audit
SET replay_request_key = replay_request_id
WHERE replay_request_key IS NULL;

ALTER TABLE search_ops_replay_audit
    MODIFY COLUMN replay_request_key VARBINARY(64) NOT NULL;

ALTER TABLE search_ops_replay_audit
    DROP PRIMARY KEY;

ALTER TABLE search_ops_replay_audit
    ADD PRIMARY KEY (replay_request_key);
