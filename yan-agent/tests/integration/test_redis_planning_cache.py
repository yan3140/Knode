import os
from uuid import uuid4

import pytest
from redis.asyncio import Redis

from yan_agent.adapters.planning_cache import RedisPlanningCache
from yan_agent.domain.qa import QueryRewrite, RetrievalMode
from yan_agent.domain.question_analysis import QuestionAnalysis
from yan_agent.domain.retrieval_planning import (
    PlannedQuery,
    QueryComplexity,
    QueryPurpose,
    RetrievalPlanningResult,
    RetrievalStrategy,
)

TEST_REDIS_URL = os.getenv("YAN_AGENT_TEST_REDIS_URL") or os.getenv("YAN_AGENT_REDIS_URL")
pytestmark = pytest.mark.skipif(
    TEST_REDIS_URL is None,
    reason="Redis integration URL is not set",
)


@pytest.mark.asyncio
async def test_redis_planning_cache_round_trip_and_expiry_namespace() -> None:
    assert TEST_REDIS_URL is not None
    client = Redis.from_url(TEST_REDIS_URL, decode_responses=True)
    prefix = f"yan-agent:test:planning:{uuid4()}:"
    cache = RedisPlanningCache(client, key_prefix=prefix)
    analysis = QuestionAnalysis(
        original_question="MySQL 联合索引为什么会失效？",
        standalone_question="MySQL 联合索引为什么会失效？",
        complexity=QueryComplexity.SIMPLE,
        context_dependent=False,
        ambiguous=False,
        candidate_queries=[
            PlannedQuery(
                query_id="q-1",
                text="MySQL 联合索引为什么会失效？",
                purpose=QueryPurpose.ORIGINAL,
                mode=RetrievalMode.AUTO,
                weight=0.6,
                target_sub_question_ids=[],
            )
        ],
        confidence=0.9,
        analyzer_version="question-analysis.v1",
    )
    result = RetrievalPlanningResult(
        strategy=RetrievalStrategy.SINGLE_REWRITE,
        query=QueryRewrite(query="MySQL 联合索引失效原因"),
        planner_version="unified-retrieval.v1",
        prompt_version="question-analysis.v1",
        model_calls=1,
        analysis=analysis,
    )
    try:
        await cache.set("versioned-key", result, ttl_seconds=60)

        loaded = await cache.get("versioned-key")

        assert loaded == result
        assert loaded is not None and loaded.analysis == analysis
        assert await cache.get("different-context-version") is None
    finally:
        keys = [key async for key in client.scan_iter(match=f"{prefix}*")]
        if keys:
            await client.delete(*keys)
        await client.aclose()
