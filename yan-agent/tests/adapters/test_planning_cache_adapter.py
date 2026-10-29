import pytest

from yan_agent.adapters.planning_cache import RedisPlanningCache
from yan_agent.domain.qa import QueryRewrite
from yan_agent.domain.retrieval_planning import (
    RetrievalPlanningResult,
    RetrievalStrategy,
)
from yan_agent.ports.planning_cache import PlanningCacheUnavailable


class FakeRedis:
    def __init__(self) -> None:
        self.values: dict[str, bytes] = {}
        self.set_calls: list[tuple[str, int, str]] = []
        self.raise_on_get = False

    async def get(self, key: str) -> bytes | None:
        if self.raise_on_get:
            raise OSError("redis down")
        return self.values.get(key)

    async def set(self, key: str, value: str, ex: int) -> None:
        self.set_calls.append((key, ex, value))
        self.values[key] = value.encode()

    async def delete(self, key: str) -> None:
        self.values.pop(key, None)


def result() -> RetrievalPlanningResult:
    return RetrievalPlanningResult(
        strategy=RetrievalStrategy.RAW_QUERY,
        query=QueryRewrite(query="问题"),
        planner_version="raw-query.v1",
        model_calls=0,
    )


@pytest.mark.asyncio
async def test_redis_planning_cache_round_trip_uses_ttl() -> None:
    redis = FakeRedis()
    cache = RedisPlanningCache(redis)  # type: ignore[arg-type]

    await cache.set("cache-key", result(), ttl_seconds=900)
    cached = await cache.get("cache-key")

    assert cached == result()
    assert redis.set_calls[0][1] == 900


@pytest.mark.asyncio
async def test_redis_planning_cache_rejects_invalid_payload() -> None:
    redis = FakeRedis()
    redis.values["yan-agent:planning:cache-key"] = b"not-json"
    cache = RedisPlanningCache(redis)  # type: ignore[arg-type]

    with pytest.raises(PlanningCacheUnavailable, match="invalid"):
        await cache.get("cache-key")

    assert "yan-agent:planning:cache-key" not in redis.values
