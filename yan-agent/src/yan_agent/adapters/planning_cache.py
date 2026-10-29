from pydantic import ValidationError
from redis.asyncio import Redis
from redis.exceptions import RedisError

from yan_agent.domain.retrieval_planning import RetrievalPlanningResult
from yan_agent.ports.planning_cache import PlanningCacheUnavailable


class RedisPlanningCache:
    def __init__(self, client: Redis, key_prefix: str = "yan-agent:planning:") -> None:
        self._client = client
        self._key_prefix = key_prefix

    async def get(self, key: str) -> RetrievalPlanningResult | None:
        cache_key = self._key(key)
        try:
            payload = await self._client.get(cache_key)
        except (OSError, RedisError, TimeoutError) as error:
            raise PlanningCacheUnavailable("Redis planning cache read failed") from error
        if payload is None:
            return None
        try:
            return RetrievalPlanningResult.model_validate_json(payload)
        except (ValueError, ValidationError) as error:
            try:
                await self._client.delete(cache_key)
            except (OSError, RedisError, TimeoutError):
                pass
            raise PlanningCacheUnavailable("Redis planning cache value is invalid") from error

    async def set(
        self,
        key: str,
        value: RetrievalPlanningResult,
        ttl_seconds: int,
    ) -> None:
        try:
            await self._client.set(
                self._key(key),
                value.model_dump_json(),
                ex=ttl_seconds,
            )
        except (OSError, RedisError, TimeoutError) as error:
            raise PlanningCacheUnavailable("Redis planning cache write failed") from error

    def _key(self, key: str) -> str:
        return f"{self._key_prefix}{key}"
