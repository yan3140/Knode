from typing import Protocol

from yan_agent.domain.retrieval_planning import RetrievalPlanningResult


class PlanningCacheUnavailable(RuntimeError):
    pass


class PlanningCache(Protocol):
    async def get(self, key: str) -> RetrievalPlanningResult | None: ...

    async def set(
        self,
        key: str,
        value: RetrievalPlanningResult,
        ttl_seconds: int,
    ) -> None: ...
