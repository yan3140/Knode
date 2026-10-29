from typing import Protocol

from yan_agent.domain.retrieval_planning import (
    RetrievalPlanningRequest,
    RetrievalPlanningResult,
    RetrievalStrategy,
)


class RetrievalPlanner(Protocol):
    strategy: RetrievalStrategy
    model_calls_per_plan: int

    async def plan(self, request: RetrievalPlanningRequest) -> RetrievalPlanningResult: ...
