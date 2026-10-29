ALTER TABLE yan_cache_invalidation_outbox
    ADD COLUMN rerun_requested TINYINT NOT NULL DEFAULT 0 AFTER dead_letter_at;
