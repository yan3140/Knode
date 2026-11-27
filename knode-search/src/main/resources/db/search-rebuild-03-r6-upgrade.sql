-- Knode Search (05) Idempotent Data Repair & Upgrade DML: SEARCH-03-R5 / SEARCH-04-R3 -> SEARCH-03-R6
-- Target engine: dedicated independent MySQL 8 / InnoDB database, charset utf8mb4, UTC session/JDBC timezone.
-- Compatible with sql_require_primary_key = ON and ONLY_FULL_GROUP_BY.
-- Manual deployment DML only: safe to re-run idempotently.
--
-- Purpose:
--   1. Repairs search_article_version_barrier rows that were prematurely advanced by unactivated
--      candidate rebuild generations (e.g., SNAPSHOT_IMPORTING / INCREMENTAL_CATCHUP / FAILED /
--      EXPIRED_SUPERSEDED) back to the highest revision observed by activated generations.
--   2. Deletes purely candidate-polluted barrier rows for articles that never existed in any
--      activated generation, while strictly preserving standalone/legacy delete barriers.
--   3. Ensures all activated-generation projections are represented in search_article_version_barrier.

-- Step 1: Roll back candidate-polluted barriers for articles that have activated-generation projections.
UPDATE search_article_version_barrier
SET last_visibility = CASE (
        SELECT MAX(
            CASE a2.visibility
                WHEN 'DELETED' THEN 3
                WHEN 'WITHDRAWN' THEN 2
                WHEN 'PUBLIC' THEN 1
                ELSE 0
            END
        )
        FROM search_article_projection a2
        WHERE a2.article_id = search_article_version_barrier.article_id
          AND a2.has_content = 1
          AND (
              a2.index_generation = 1
              OR a2.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
              OR a2.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
              OR a2.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
              OR a2.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
          )
          AND a2.content_revision = (
              SELECT MAX(a3.content_revision)
              FROM search_article_projection a3
              WHERE a3.article_id = search_article_version_barrier.article_id
                AND a3.has_content = 1
                AND a3.content_revision > 0
                AND (
                    a3.index_generation = 1
                    OR a3.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
                    OR a3.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
                    OR a3.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
                    OR a3.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
                )
          )
    )
        WHEN 3 THEN 'DELETED'
        WHEN 2 THEN 'WITHDRAWN'
        WHEN 1 THEN 'PUBLIC'
        ELSE 'NONE'
    END,
    max_content_revision = (
        SELECT MAX(a1.content_revision)
        FROM search_article_projection a1
        WHERE a1.article_id = search_article_version_barrier.article_id
          AND a1.has_content = 1
          AND a1.content_revision > 0
          AND (
              a1.index_generation = 1
              OR a1.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
              OR a1.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
              OR a1.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
              OR a1.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
          )
    ),
    updated_at_ms = (
        SELECT MAX(a4.updated_at_ms)
        FROM search_article_projection a4
        WHERE a4.article_id = search_article_version_barrier.article_id
          AND a4.has_content = 1
          AND a4.content_revision > 0
          AND (
              a4.index_generation = 1
              OR a4.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
              OR a4.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
              OR a4.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
              OR a4.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
          )
    )
WHERE EXISTS (
    SELECT 1
    FROM search_article_projection u
    WHERE u.article_id = search_article_version_barrier.article_id
      AND u.has_content = 1
      AND u.content_revision > 0
      AND u.index_generation IN (
          SELECT j.candidate_generation
          FROM search_rebuild_job j
          WHERE j.phase NOT IN ('CACHE_INVALIDATING', 'COMPLETED')
      )
      AND NOT (
          u.index_generation = 1
          OR u.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
          OR u.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
          OR u.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
          OR u.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
      )
      AND (
          (u.content_revision = search_article_version_barrier.max_content_revision
              AND u.visibility = search_article_version_barrier.last_visibility)
          OR (u.content_revision > search_article_version_barrier.max_content_revision
              AND search_article_version_barrier.last_visibility = 'PUBLIC')
      )
)
AND EXISTS (
    SELECT 1
    FROM search_article_projection a_any
    WHERE a_any.article_id = search_article_version_barrier.article_id
      AND a_any.has_content = 1
      AND a_any.content_revision > 0
      AND (
          a_any.index_generation = 1
          OR a_any.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
          OR a_any.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
          OR a_any.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
          OR a_any.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
      )
)
AND NOT EXISTS (
    SELECT 1
    FROM search_article_projection a_ok
    WHERE a_ok.article_id = search_article_version_barrier.article_id
      AND a_ok.has_content = 1
      AND a_ok.content_revision > 0
      AND (
          a_ok.index_generation = 1
          OR a_ok.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
          OR a_ok.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
          OR a_ok.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
          OR a_ok.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
      )
      AND (
          a_ok.content_revision > search_article_version_barrier.max_content_revision
          OR (
              a_ok.content_revision = search_article_version_barrier.max_content_revision
              AND (
                  CASE a_ok.visibility WHEN 'DELETED' THEN 3 WHEN 'WITHDRAWN' THEN 2 WHEN 'PUBLIC' THEN 1 ELSE 0 END
                  >= CASE search_article_version_barrier.last_visibility WHEN 'DELETED' THEN 3 WHEN 'WITHDRAWN' THEN 2 WHEN 'PUBLIC' THEN 1 ELSE 0 END
              )
          )
      )
);

-- Step 2: Delete purely candidate-polluted barriers that have no activated-generation projection.
-- Standalone legacy delete barriers with no matching unactivated candidate projection remain untouched.
DELETE FROM search_article_version_barrier
WHERE EXISTS (
    SELECT 1
    FROM search_article_projection u
    WHERE u.article_id = search_article_version_barrier.article_id
      AND u.has_content = 1
      AND u.content_revision > 0
      AND u.index_generation IN (
          SELECT j.candidate_generation
          FROM search_rebuild_job j
          WHERE j.phase NOT IN ('CACHE_INVALIDATING', 'COMPLETED')
      )
      AND NOT (
          u.index_generation = 1
          OR u.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
          OR u.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
          OR u.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
          OR u.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
      )
      AND (
          (u.content_revision = search_article_version_barrier.max_content_revision
              AND u.visibility = search_article_version_barrier.last_visibility)
          OR (u.content_revision > search_article_version_barrier.max_content_revision
              AND search_article_version_barrier.last_visibility = 'PUBLIC')
      )
)
AND NOT EXISTS (
    SELECT 1
    FROM search_article_projection a_any
    WHERE a_any.article_id = search_article_version_barrier.article_id
      AND a_any.has_content = 1
      AND a_any.content_revision > 0
      AND (
          a_any.index_generation = 1
          OR a_any.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
          OR a_any.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
          OR a_any.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
          OR a_any.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
      )
);

-- Step 3: Advance any existing barrier row that lags behind an activated-generation projection.
UPDATE search_article_version_barrier
SET last_visibility = CASE (
        SELECT MAX(
            CASE a2.visibility
                WHEN 'DELETED' THEN 3
                WHEN 'WITHDRAWN' THEN 2
                WHEN 'PUBLIC' THEN 1
                ELSE 0
            END
        )
        FROM search_article_projection a2
        WHERE a2.article_id = search_article_version_barrier.article_id
          AND a2.has_content = 1
          AND (
              a2.index_generation = 1
              OR a2.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
              OR a2.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
              OR a2.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
              OR a2.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
          )
          AND a2.content_revision = (
              SELECT MAX(a3.content_revision)
              FROM search_article_projection a3
              WHERE a3.article_id = search_article_version_barrier.article_id
                AND a3.has_content = 1
                AND a3.content_revision > 0
                AND (
                    a3.index_generation = 1
                    OR a3.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
                    OR a3.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
                    OR a3.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
                    OR a3.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
                )
          )
    )
        WHEN 3 THEN 'DELETED'
        WHEN 2 THEN 'WITHDRAWN'
        WHEN 1 THEN 'PUBLIC'
        ELSE 'NONE'
    END,
    max_content_revision = (
        SELECT MAX(a1.content_revision)
        FROM search_article_projection a1
        WHERE a1.article_id = search_article_version_barrier.article_id
          AND a1.has_content = 1
          AND a1.content_revision > 0
          AND (
              a1.index_generation = 1
              OR a1.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
              OR a1.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
              OR a1.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
              OR a1.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
          )
    ),
    updated_at_ms = (
        SELECT MAX(a4.updated_at_ms)
        FROM search_article_projection a4
        WHERE a4.article_id = search_article_version_barrier.article_id
          AND a4.has_content = 1
          AND a4.content_revision > 0
          AND (
              a4.index_generation = 1
              OR a4.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
              OR a4.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
              OR a4.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
              OR a4.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
          )
    )
WHERE EXISTS (
    SELECT 1
    FROM search_article_projection a_hi
    WHERE a_hi.article_id = search_article_version_barrier.article_id
      AND a_hi.has_content = 1
      AND a_hi.content_revision > 0
      AND (
          a_hi.index_generation = 1
          OR a_hi.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
          OR a_hi.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
          OR a_hi.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
          OR a_hi.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
      )
      AND (
          a_hi.content_revision > search_article_version_barrier.max_content_revision
          OR (
              a_hi.content_revision = search_article_version_barrier.max_content_revision
              AND (
                  CASE a_hi.visibility WHEN 'DELETED' THEN 3 WHEN 'WITHDRAWN' THEN 2 WHEN 'PUBLIC' THEN 1 ELSE 0 END
                  > CASE search_article_version_barrier.last_visibility WHEN 'DELETED' THEN 3 WHEN 'WITHDRAWN' THEN 2 WHEN 'PUBLIC' THEN 1 ELSE 0 END
              )
          )
      )
);

-- Step 4: Backfill missing barrier rows strictly from activated-generation projections.
INSERT INTO search_article_version_barrier (
    article_id, max_content_revision, last_visibility, updated_at_ms
)
SELECT
    agg.article_id,
    agg.max_rev,
    CASE (
        SELECT MAX(
            CASE p2.visibility
                WHEN 'DELETED' THEN 3
                WHEN 'WITHDRAWN' THEN 2
                WHEN 'PUBLIC' THEN 1
                ELSE 0
            END
        )
        FROM search_article_projection p2
        WHERE p2.article_id = agg.article_id
          AND p2.has_content = 1
          AND p2.content_revision = agg.max_rev
          AND (
              p2.index_generation = 1
              OR p2.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
              OR p2.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
              OR p2.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
              OR p2.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
          )
    )
        WHEN 3 THEN 'DELETED'
        WHEN 2 THEN 'WITHDRAWN'
        WHEN 1 THEN 'PUBLIC'
        ELSE 'NONE'
    END,
    agg.max_updated_at_ms
FROM (
    SELECT
        p1.article_id,
        MAX(p1.content_revision) AS max_rev,
        MAX(p1.updated_at_ms) AS max_updated_at_ms
    FROM search_article_projection p1
    WHERE p1.has_content = 1
      AND p1.content_revision > 0
      AND (
          p1.index_generation = 1
          OR p1.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
          OR p1.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
          OR p1.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
          OR p1.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
      )
    GROUP BY p1.article_id
) agg
WHERE NOT EXISTS (
    SELECT 1
    FROM search_article_version_barrier b
    WHERE b.article_id = agg.article_id
);
