-- Knode Search (05) Additive Upgrade DDL: SEARCH-03 -> SEARCH-03-R1
-- Target engine: dedicated independent MySQL 8 / InnoDB database, charset utf8mb4, UTC session/JDBC timezone.
-- Manual deployment DDL only: run once on an existing SEARCH-03 schema while rebuild workers are stopped.
-- Preserves all existing SEARCH-03 jobs, generation state, projections, tasks, barriers, and event logs.

ALTER TABLE search_rebuild_job
    ADD COLUMN lease_generation BIGINT NOT NULL DEFAULT 0;

ALTER TABLE search_rebuild_job
    ADD COLUMN alias_switch_uncertain TINYINT(1) NOT NULL DEFAULT 0;
