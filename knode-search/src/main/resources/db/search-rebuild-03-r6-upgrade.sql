-- Knode Search (05) Idempotent Data Repair & Upgrade DML: SEARCH-03-R5 / SEARCH-04-R3 / SEARCH-03-R6 -> SEARCH-03-R7
-- Target engine: dedicated independent MySQL 8 / InnoDB database, charset utf8mb4, UTC session/JDBC timezone.
-- Compatible with sql_require_primary_key = ON and ONLY_FULL_GROUP_BY.
-- Manual deployment DML only: safe to re-run idempotently.
--
-- Purpose:
--   1. Repairs search_article_version_barrier rows ONLY when positively proven to have been written
--      by a pre-R6 unactivated candidate rebuild transaction:
--        (a) Incremental path: matching APPLIED ARTICLE event in search_rebuild_event_log with
--            matching event_type for barrier visibility and applied_at_ms = barrier.updated_at_ms >= job.created_at_ms;
--        (b) Snapshot path: barrier.last_visibility = 'PUBLIC', job.snapshot_items_imported > 0,
--            barrier.updated_at_ms >= job.created_at_ms, unactivated candidate projection at same
--            (content_revision, 'PUBLIC'), and u.updated_at_ms = barrier.updated_at_ms (or later
--            updated exclusively by an APPLIED ARTICLE_COUNTER rebuild log at u.updated_at_ms).
--   2. Strictly preserves ALL activated-generation barriers, ALL barriers backed by APPLIED online
--      ARTICLE events in search_event_inbox, and ALL standalone barriers whose provenance cannot
--      be positively attributed to an unactivated candidate rebuild transaction.
--   3. Advances lagging barriers and backfills/restores missing barriers from the union of
--      activated-generation projections AND APPLIED online ARTICLE inbox facts (safely restoring
--      inbox-backed delete/withdrawn barriers that earlier R6 upgrade SQL may have mis-deleted).

-- Step 1: Roll back positively proven candidate-polluted barriers for articles that have a valid
-- activated-generation projection or APPLIED online ARTICLE inbox floor.
UPDATE search_article_version_barrier
SET last_visibility = CASE GREATEST(
        COALESCE((
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
              AND a2.content_revision = GREATEST(
                  COALESCE((
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
                  ), 0),
                  COALESCE((
                      SELECT MAX(i3.source_revision)
                      FROM search_event_inbox i3
                      WHERE i3.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
                        AND UPPER(i3.aggregate_type) = 'ARTICLE'
                        AND i3.status = 'APPLIED'
                        AND i3.source_revision > 0
                        AND i3.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged', 'ArticleDeleted')
                  ), 0)
              )
        ), 0),
        COALESCE((
            SELECT MAX(
                CASE
                    WHEN i2.event_type = 'ArticleDeleted' THEN 3
                    WHEN i2.event_type = 'ArticlePublished' THEN 1
                    WHEN i2.event_type = 'ArticleContentChanged' THEN
                        CASE
                            WHEN UPPER(COALESCE(i2.visibility, '')) = 'DELETED' THEN 3
                            WHEN UPPER(COALESCE(i2.visibility, '')) IN ('WITHDRAWN', 'DRAFT') THEN 2
                            ELSE 1
                        END
                    WHEN i2.event_type = 'ArticleVisibilityChanged' THEN
                        CASE
                            WHEN UPPER(COALESCE(i2.visibility, '')) = 'DELETED' THEN 3
                            WHEN UPPER(COALESCE(i2.visibility, '')) = 'PUBLIC' THEN 1
                            ELSE 2
                        END
                    ELSE 0
                END
            )
            FROM search_event_inbox i2
            WHERE i2.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
              AND UPPER(i2.aggregate_type) = 'ARTICLE'
              AND i2.status = 'APPLIED'
              AND i2.source_revision > 0
              AND i2.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged', 'ArticleDeleted')
              AND i2.source_revision = GREATEST(
                  COALESCE((
                      SELECT MAX(a3b.content_revision)
                      FROM search_article_projection a3b
                      WHERE a3b.article_id = search_article_version_barrier.article_id
                        AND a3b.has_content = 1
                        AND a3b.content_revision > 0
                        AND (
                            a3b.index_generation = 1
                            OR a3b.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
                            OR a3b.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
                            OR a3b.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
                            OR a3b.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
                        )
                  ), 0),
                  COALESCE((
                      SELECT MAX(i3b.source_revision)
                      FROM search_event_inbox i3b
                      WHERE i3b.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
                        AND UPPER(i3b.aggregate_type) = 'ARTICLE'
                        AND i3b.status = 'APPLIED'
                        AND i3b.source_revision > 0
                        AND i3b.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged', 'ArticleDeleted')
                  ), 0)
              )
        ), 0)
    )
        WHEN 3 THEN 'DELETED'
        WHEN 2 THEN 'WITHDRAWN'
        WHEN 1 THEN 'PUBLIC'
        ELSE 'NONE'
    END,
    max_content_revision = GREATEST(
        COALESCE((
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
        ), 0),
        COALESCE((
            SELECT MAX(i1.source_revision)
            FROM search_event_inbox i1
            WHERE i1.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
              AND UPPER(i1.aggregate_type) = 'ARTICLE'
              AND i1.status = 'APPLIED'
              AND i1.source_revision > 0
              AND i1.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged', 'ArticleDeleted')
        ), 0)
    ),
    updated_at_ms = GREATEST(
        COALESCE((
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
        ), 0),
        COALESCE((
            SELECT MAX(i4.processed_at_ms)
            FROM search_event_inbox i4
            WHERE i4.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
              AND UPPER(i4.aggregate_type) = 'ARTICLE'
              AND i4.status = 'APPLIED'
              AND i4.source_revision > 0
              AND i4.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged', 'ArticleDeleted')
        ), 0)
    )
WHERE EXISTS (
    SELECT 1
    FROM search_article_projection u
    INNER JOIN search_rebuild_job j
        ON j.candidate_generation = u.index_generation
    WHERE u.article_id = search_article_version_barrier.article_id
      AND u.has_content = 1
      AND u.content_revision > 0
      AND j.phase NOT IN ('CACHE_INVALIDATING', 'COMPLETED')
      AND NOT (
          u.index_generation = 1
          OR u.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
          OR u.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
          OR u.index_generation IN (SELECT j2.source_generation FROM search_rebuild_job j2)
          OR u.index_generation IN (SELECT j2.candidate_generation FROM search_rebuild_job j2 WHERE j2.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
      )
      AND search_article_version_barrier.updated_at_ms >= j.created_at_ms
      AND (
          (
              (
                  u.content_revision > search_article_version_barrier.max_content_revision
                  OR (
                      u.content_revision = search_article_version_barrier.max_content_revision
                      AND u.visibility = search_article_version_barrier.last_visibility
                  )
              )
              AND EXISTS (
                  SELECT 1
                  FROM search_rebuild_event_log e
                  WHERE e.candidate_generation = u.index_generation
                    AND UPPER(e.aggregate_type) = 'ARTICLE'
                    AND e.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
                    AND e.source_revision = search_article_version_barrier.max_content_revision
                    AND e.status = 'APPLIED'
                    AND e.applied_at_ms = search_article_version_barrier.updated_at_ms
                    AND (
                        (search_article_version_barrier.last_visibility = 'DELETED' AND e.event_type = 'ArticleDeleted')
                        OR (search_article_version_barrier.last_visibility = 'WITHDRAWN' AND e.event_type = 'ArticleVisibilityChanged')
                        OR (search_article_version_barrier.last_visibility = 'PUBLIC' AND e.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged'))
                    )
              )
          )
          OR (
              search_article_version_barrier.last_visibility = 'PUBLIC'
              AND j.snapshot_items_imported > 0
              AND u.content_revision = search_article_version_barrier.max_content_revision
              AND u.visibility = 'PUBLIC'
              AND (
                  u.updated_at_ms = search_article_version_barrier.updated_at_ms
                  OR (
                      u.updated_at_ms > search_article_version_barrier.updated_at_ms
                      AND u.counter_revision > 0
                      AND EXISTS (
                          SELECT 1
                          FROM search_rebuild_event_log ec
                          WHERE ec.candidate_generation = u.index_generation
                            AND UPPER(ec.aggregate_type) = 'ARTICLE_COUNTER'
                            AND ec.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
                            AND ec.status = 'APPLIED'
                            AND ec.applied_at_ms = u.updated_at_ms
                      )
                  )
              )
          )
      )
)
AND (
    EXISTS (
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
    OR EXISTS (
        SELECT 1
        FROM search_event_inbox i_any
        WHERE i_any.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
          AND UPPER(i_any.aggregate_type) = 'ARTICLE'
          AND i_any.status = 'APPLIED'
          AND i_any.source_revision > 0
          AND i_any.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged', 'ArticleDeleted')
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
)
AND NOT EXISTS (
    SELECT 1
    FROM search_event_inbox i_ok
    WHERE i_ok.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
      AND UPPER(i_ok.aggregate_type) = 'ARTICLE'
      AND i_ok.status = 'APPLIED'
      AND i_ok.source_revision > 0
      AND i_ok.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged', 'ArticleDeleted')
      AND (
          i_ok.source_revision > search_article_version_barrier.max_content_revision
          OR (
              i_ok.source_revision = search_article_version_barrier.max_content_revision
              AND (
                  CASE
                      WHEN i_ok.event_type = 'ArticleDeleted' THEN 3
                      WHEN i_ok.event_type = 'ArticlePublished' THEN 1
                      WHEN i_ok.event_type = 'ArticleContentChanged' THEN
                          CASE
                              WHEN UPPER(COALESCE(i_ok.visibility, '')) = 'DELETED' THEN 3
                              WHEN UPPER(COALESCE(i_ok.visibility, '')) IN ('WITHDRAWN', 'DRAFT') THEN 2
                              ELSE 1
                          END
                      WHEN i_ok.event_type = 'ArticleVisibilityChanged' THEN
                          CASE
                              WHEN UPPER(COALESCE(i_ok.visibility, '')) = 'DELETED' THEN 3
                              WHEN UPPER(COALESCE(i_ok.visibility, '')) = 'PUBLIC' THEN 1
                              ELSE 2
                          END
                      ELSE 0
                  END
                  >= CASE search_article_version_barrier.last_visibility WHEN 'DELETED' THEN 3 WHEN 'WITHDRAWN' THEN 2 WHEN 'PUBLIC' THEN 1 ELSE 0 END
              )
          )
      )
);

-- Step 2: Delete purely candidate-polluted barriers ONLY when positively proven to originate from an
-- unactivated candidate rebuild transaction AND lacking any activated-generation projection or
-- APPLIED online ARTICLE inbox fact.
DELETE FROM search_article_version_barrier
WHERE EXISTS (
    SELECT 1
    FROM search_article_projection u
    INNER JOIN search_rebuild_job j
        ON j.candidate_generation = u.index_generation
    WHERE u.article_id = search_article_version_barrier.article_id
      AND u.has_content = 1
      AND u.content_revision > 0
      AND j.phase NOT IN ('CACHE_INVALIDATING', 'COMPLETED')
      AND NOT (
          u.index_generation = 1
          OR u.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
          OR u.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
          OR u.index_generation IN (SELECT j2.source_generation FROM search_rebuild_job j2)
          OR u.index_generation IN (SELECT j2.candidate_generation FROM search_rebuild_job j2 WHERE j2.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
      )
      AND search_article_version_barrier.updated_at_ms >= j.created_at_ms
      AND (
          (
              (
                  u.content_revision > search_article_version_barrier.max_content_revision
                  OR (
                      u.content_revision = search_article_version_barrier.max_content_revision
                      AND u.visibility = search_article_version_barrier.last_visibility
                  )
              )
              AND EXISTS (
                  SELECT 1
                  FROM search_rebuild_event_log e
                  WHERE e.candidate_generation = u.index_generation
                    AND UPPER(e.aggregate_type) = 'ARTICLE'
                    AND e.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
                    AND e.source_revision = search_article_version_barrier.max_content_revision
                    AND e.status = 'APPLIED'
                    AND e.applied_at_ms = search_article_version_barrier.updated_at_ms
                    AND (
                        (search_article_version_barrier.last_visibility = 'DELETED' AND e.event_type = 'ArticleDeleted')
                        OR (search_article_version_barrier.last_visibility = 'WITHDRAWN' AND e.event_type = 'ArticleVisibilityChanged')
                        OR (search_article_version_barrier.last_visibility = 'PUBLIC' AND e.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged'))
                    )
              )
          )
          OR (
              search_article_version_barrier.last_visibility = 'PUBLIC'
              AND j.snapshot_items_imported > 0
              AND u.content_revision = search_article_version_barrier.max_content_revision
              AND u.visibility = 'PUBLIC'
              AND (
                  u.updated_at_ms = search_article_version_barrier.updated_at_ms
                  OR (
                      u.updated_at_ms > search_article_version_barrier.updated_at_ms
                      AND u.counter_revision > 0
                      AND EXISTS (
                          SELECT 1
                          FROM search_rebuild_event_log ec
                          WHERE ec.candidate_generation = u.index_generation
                            AND UPPER(ec.aggregate_type) = 'ARTICLE_COUNTER'
                            AND ec.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
                            AND ec.status = 'APPLIED'
                            AND ec.applied_at_ms = u.updated_at_ms
                      )
                  )
              )
          )
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
)
AND NOT EXISTS (
    SELECT 1
    FROM search_event_inbox i_any
    WHERE i_any.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
      AND UPPER(i_any.aggregate_type) = 'ARTICLE'
      AND i_any.status = 'APPLIED'
      AND i_any.source_revision > 0
      AND i_any.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged', 'ArticleDeleted')
);

-- Step 3: Advance any existing barrier row that lags behind an activated-generation projection
-- or an APPLIED online ARTICLE inbox fact.
UPDATE search_article_version_barrier
SET last_visibility = CASE GREATEST(
        COALESCE((
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
              AND a2.content_revision = GREATEST(
                  COALESCE((
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
                  ), 0),
                  COALESCE((
                      SELECT MAX(i3.source_revision)
                      FROM search_event_inbox i3
                      WHERE i3.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
                        AND UPPER(i3.aggregate_type) = 'ARTICLE'
                        AND i3.status = 'APPLIED'
                        AND i3.source_revision > 0
                        AND i3.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged', 'ArticleDeleted')
                  ), 0)
              )
        ), 0),
        COALESCE((
            SELECT MAX(
                CASE
                    WHEN i2.event_type = 'ArticleDeleted' THEN 3
                    WHEN i2.event_type = 'ArticlePublished' THEN 1
                    WHEN i2.event_type = 'ArticleContentChanged' THEN
                        CASE
                            WHEN UPPER(COALESCE(i2.visibility, '')) = 'DELETED' THEN 3
                            WHEN UPPER(COALESCE(i2.visibility, '')) IN ('WITHDRAWN', 'DRAFT') THEN 2
                            ELSE 1
                        END
                    WHEN i2.event_type = 'ArticleVisibilityChanged' THEN
                        CASE
                            WHEN UPPER(COALESCE(i2.visibility, '')) = 'DELETED' THEN 3
                            WHEN UPPER(COALESCE(i2.visibility, '')) = 'PUBLIC' THEN 1
                            ELSE 2
                        END
                    ELSE 0
                END
            )
            FROM search_event_inbox i2
            WHERE i2.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
              AND UPPER(i2.aggregate_type) = 'ARTICLE'
              AND i2.status = 'APPLIED'
              AND i2.source_revision > 0
              AND i2.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged', 'ArticleDeleted')
              AND i2.source_revision = GREATEST(
                  COALESCE((
                      SELECT MAX(a3b.content_revision)
                      FROM search_article_projection a3b
                      WHERE a3b.article_id = search_article_version_barrier.article_id
                        AND a3b.has_content = 1
                        AND a3b.content_revision > 0
                        AND (
                            a3b.index_generation = 1
                            OR a3b.index_generation IN (SELECT ig.active_generation FROM search_index_generation ig)
                            OR a3b.index_generation IN (SELECT ig.previous_generation FROM search_index_generation ig WHERE ig.previous_generation IS NOT NULL)
                            OR a3b.index_generation IN (SELECT j.source_generation FROM search_rebuild_job j)
                            OR a3b.index_generation IN (SELECT j.candidate_generation FROM search_rebuild_job j WHERE j.phase IN ('CACHE_INVALIDATING', 'COMPLETED'))
                        )
                  ), 0),
                  COALESCE((
                      SELECT MAX(i3b.source_revision)
                      FROM search_event_inbox i3b
                      WHERE i3b.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
                        AND UPPER(i3b.aggregate_type) = 'ARTICLE'
                        AND i3b.status = 'APPLIED'
                        AND i3b.source_revision > 0
                        AND i3b.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged', 'ArticleDeleted')
                  ), 0)
              )
        ), 0)
    )
        WHEN 3 THEN 'DELETED'
        WHEN 2 THEN 'WITHDRAWN'
        WHEN 1 THEN 'PUBLIC'
        ELSE 'NONE'
    END,
    max_content_revision = GREATEST(
        COALESCE((
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
        ), 0),
        COALESCE((
            SELECT MAX(i1.source_revision)
            FROM search_event_inbox i1
            WHERE i1.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
              AND UPPER(i1.aggregate_type) = 'ARTICLE'
              AND i1.status = 'APPLIED'
              AND i1.source_revision > 0
              AND i1.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged', 'ArticleDeleted')
        ), 0)
    ),
    updated_at_ms = GREATEST(
        COALESCE((
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
        ), 0),
        COALESCE((
            SELECT MAX(i4.processed_at_ms)
            FROM search_event_inbox i4
            WHERE i4.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
              AND UPPER(i4.aggregate_type) = 'ARTICLE'
              AND i4.status = 'APPLIED'
              AND i4.source_revision > 0
              AND i4.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged', 'ArticleDeleted')
        ), 0)
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
)
OR EXISTS (
    SELECT 1
    FROM search_event_inbox i_hi
    WHERE i_hi.aggregate_id = CAST(search_article_version_barrier.article_id AS CHAR(64))
      AND UPPER(i_hi.aggregate_type) = 'ARTICLE'
      AND i_hi.status = 'APPLIED'
      AND i_hi.source_revision > 0
      AND i_hi.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged', 'ArticleDeleted')
      AND (
          i_hi.source_revision > search_article_version_barrier.max_content_revision
          OR (
              i_hi.source_revision = search_article_version_barrier.max_content_revision
              AND (
                  CASE
                      WHEN i_hi.event_type = 'ArticleDeleted' THEN 3
                      WHEN i_hi.event_type = 'ArticlePublished' THEN 1
                      WHEN i_hi.event_type = 'ArticleContentChanged' THEN
                          CASE
                              WHEN UPPER(COALESCE(i_hi.visibility, '')) = 'DELETED' THEN 3
                              WHEN UPPER(COALESCE(i_hi.visibility, '')) IN ('WITHDRAWN', 'DRAFT') THEN 2
                              ELSE 1
                          END
                      WHEN i_hi.event_type = 'ArticleVisibilityChanged' THEN
                          CASE
                              WHEN UPPER(COALESCE(i_hi.visibility, '')) = 'DELETED' THEN 3
                              WHEN UPPER(COALESCE(i_hi.visibility, '')) = 'PUBLIC' THEN 1
                              ELSE 2
                          END
                      ELSE 0
                  END
                  > CASE search_article_version_barrier.last_visibility WHEN 'DELETED' THEN 3 WHEN 'WITHDRAWN' THEN 2 WHEN 'PUBLIC' THEN 1 ELSE 0 END
              )
          )
      )
);

-- Step 4: Backfill missing barrier rows (and restore any inbox-backed delete/withdrawn barriers
-- mis-deleted by earlier R6 SQL) from the union of activated-generation projections and
-- APPLIED online ARTICLE inbox facts.
INSERT INTO search_article_version_barrier (
    article_id, max_content_revision, last_visibility, updated_at_ms
)
SELECT
    agg.article_id,
    agg.max_rev,
    CASE GREATEST(
        COALESCE((
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
        ), 0),
        COALESCE((
            SELECT MAX(
                CASE
                    WHEN i2.event_type = 'ArticleDeleted' THEN 3
                    WHEN i2.event_type = 'ArticlePublished' THEN 1
                    WHEN i2.event_type = 'ArticleContentChanged' THEN
                        CASE
                            WHEN UPPER(COALESCE(i2.visibility, '')) = 'DELETED' THEN 3
                            WHEN UPPER(COALESCE(i2.visibility, '')) IN ('WITHDRAWN', 'DRAFT') THEN 2
                            ELSE 1
                        END
                    WHEN i2.event_type = 'ArticleVisibilityChanged' THEN
                        CASE
                            WHEN UPPER(COALESCE(i2.visibility, '')) = 'DELETED' THEN 3
                            WHEN UPPER(COALESCE(i2.visibility, '')) = 'PUBLIC' THEN 1
                            ELSE 2
                        END
                    ELSE 0
                END
            )
            FROM search_event_inbox i2
            WHERE i2.aggregate_id = CAST(agg.article_id AS CHAR(64))
              AND UPPER(i2.aggregate_type) = 'ARTICLE'
              AND i2.status = 'APPLIED'
              AND i2.source_revision = agg.max_rev
              AND i2.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged', 'ArticleDeleted')
        ), 0)
    )
        WHEN 3 THEN 'DELETED'
        WHEN 2 THEN 'WITHDRAWN'
        WHEN 1 THEN 'PUBLIC'
        ELSE 'NONE'
    END,
    agg.max_updated_at_ms
FROM (
    SELECT
        src.article_id,
        MAX(src.rev) AS max_rev,
        MAX(src.ts) AS max_updated_at_ms
    FROM (
        SELECT
            p1.article_id AS article_id,
            p1.content_revision AS rev,
            p1.updated_at_ms AS ts
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
        UNION ALL
        SELECT
            CAST(i1.aggregate_id AS SIGNED) AS article_id,
            i1.source_revision AS rev,
            i1.processed_at_ms AS ts
        FROM search_event_inbox i1
        WHERE UPPER(i1.aggregate_type) = 'ARTICLE'
          AND i1.status = 'APPLIED'
          AND i1.source_revision > 0
          AND i1.event_type IN ('ArticlePublished', 'ArticleContentChanged', 'ArticleVisibilityChanged', 'ArticleDeleted')
          AND i1.aggregate_id REGEXP '^[1-9][0-9]{0,18}$'
    ) src
    GROUP BY src.article_id
) agg
WHERE NOT EXISTS (
    SELECT 1
    FROM search_article_version_barrier b
    WHERE b.article_id = agg.article_id
);
