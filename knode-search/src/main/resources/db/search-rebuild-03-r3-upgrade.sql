-- Knode Search (05) Additive Upgrade DDL: SEARCH-03-R1/R2 -> SEARCH-03-R3
-- Target engine: dedicated independent MySQL 8 / InnoDB database, charset utf8mb4, UTC session/JDBC timezone.
-- Manual deployment DDL only: run once on an existing SEARCH-03-R1/R2 schema while rebuild workers are stopped.
-- Persists attempt-scoped cutover_bypass ownership on search_index_generation for cross-instance/restart cache compensation.

ALTER TABLE search_index_generation
    ADD COLUMN active_cutover_bypass_owner VARCHAR(192) NULL;
