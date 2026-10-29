from dataclasses import dataclass, field

import pytest

from yan_agent.application.retrieval_planning import (
    CachedRetrievalPlanner,
    RawQueryPlanner,
    planning_cache_key,
)
from yan_agent.domain.qa import RetrievalMode
from yan_agent.domain.retrieval_planning import (
    RetrievalPlanningRequest,
    RetrievalPlanningResult,
)
from yan_agent.ports.planning_cache import PlanningCacheUnavailable


@dataclass
class RecordingPlanningCache:
    values: dict[str, RetrievalPlanningResult] = field(default_factory=dict)
    get_keys: list[str] = field(default_factory=list)
    set_keys: list[str] = field(default_factory=list)

    async def get(self, key: str) -> RetrievalPlanningResult | None:
        self.get_keys.append(key)
        return self.values.get(key)

    async def set(
        self,
        key: str,
        value: RetrievalPlanningResult,
        ttl_seconds: int,
    ) -> None:
        assert ttl_seconds == 900
        self.set_keys.append(key)
        self.values[key] = value


class UnavailablePlanningCache:
    async def get(self, key: str) -> RetrievalPlanningResult | None:
        raise PlanningCacheUnavailable("down")

    async def set(
        self,
        key: str,
        value: RetrievalPlanningResult,
        ttl_seconds: int,
    ) -> None:
        raise PlanningCacheUnavailable("down")


def request(**updates: object) -> RetrievalPlanningRequest:
    values: dict[str, object] = {
        "original_question": "  联合索引为什么失效？  ",
        "model_input": "联合索引为什么失效？",
        "requested_mode": RetrievalMode.AUTO,
        "knowledge_base_id": 3,
        "knowledge_base_version": "mysql-kb-v1",
    }
    values.update(updates)
    return RetrievalPlanningRequest.model_validate(values)


def test_planning_cache_key_binds_all_consistency_dimensions() -> None:
    base = request()
    key = planning_cache_key(
        base,
        strategy="MULTI_QUERY",
        planner_model="gpt-5.4",
        prompt_version="retrieval-plan.v1",
    )

    assert key is not None
    variants = [
        request(knowledge_base_version="mysql-kb-v2"),
        request(model_input="上一轮讨论普通索引\n联合索引为什么失效？"),
        request(requested_mode=RetrievalMode.EXACT),
        request(context_version=7),
    ]
    assert all(
        planning_cache_key(
            item,
            strategy="MULTI_QUERY",
            planner_model="gpt-5.4",
            prompt_version="retrieval-plan.v1",
        )
        != key
        for item in variants
    )
    assert (
        planning_cache_key(
            base,
            strategy="SINGLE_REWRITE",
            planner_model="gpt-5.4",
            prompt_version="retrieval-plan.v1",
        )
        != key
    )


def test_planning_cache_is_disabled_without_database_version() -> None:
    assert (
        planning_cache_key(
            request(knowledge_base_version=None),
            strategy="MULTI_QUERY",
            planner_model="gpt-5.4",
            prompt_version="retrieval-plan.v1",
        )
        is None
    )


@pytest.mark.asyncio
async def test_cached_planner_reuses_result_for_same_database_version() -> None:
    cache = RecordingPlanningCache()
    planner = CachedRetrievalPlanner(
        RawQueryPlanner(),
        cache,
        planner_model="none",
        prompt_version="none",
        ttl_seconds=900,
    )

    first = await planner.plan(request())
    second = await planner.plan(request())

    assert second.query == first.query
    assert second.cache_hit is True
    assert second.model_calls == 0
    assert len(cache.get_keys) == 2
    assert len(cache.set_keys) == 1


@pytest.mark.asyncio
async def test_cached_planner_fails_open_when_redis_is_unavailable() -> None:
    planner = CachedRetrievalPlanner(
        RawQueryPlanner(),
        UnavailablePlanningCache(),
        planner_model="none",
        prompt_version="none",
        ttl_seconds=900,
    )

    result = await planner.plan(request())

    assert result.query.query == "  联合索引为什么失效？  "


@pytest.mark.asyncio
async def test_adaptive_cache_namespace_does_not_collide_with_fixed_b() -> None:
    cache = RecordingPlanningCache()
    adaptive = CachedRetrievalPlanner(
        RawQueryPlanner(),
        cache,
        planner_model="gpt-5.6",
        prompt_version="question-analysis.v1",
        ttl_seconds=900,
        cache_strategy="ADAPTIVE",
    )
    fixed = CachedRetrievalPlanner(
        RawQueryPlanner(),
        cache,
        planner_model="gpt-5.6",
        prompt_version="question-analysis.v1",
        ttl_seconds=900,
        cache_strategy="SINGLE_REWRITE",
    )

    await adaptive.plan(request(context_version=3))
    await fixed.plan(request(context_version=3))

    assert len(set(cache.set_keys)) == 2
