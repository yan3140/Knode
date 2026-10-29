from typing import Protocol

from yan_agent.domain.retrieval_routing import (
    RetrievalRoutingDecision,
    RetrievalRoutingRequest,
    RetrievalStrategy,
    RoutingReason,
)


class RetrievalRouter(Protocol):
    def route(self, request: RetrievalRoutingRequest) -> RetrievalRoutingDecision: ...

    def route_candidate(
        self,
        request: RetrievalRoutingRequest,
        selected_strategy: RetrievalStrategy,
        routing_reasons: list[RoutingReason],
    ) -> RetrievalRoutingDecision: ...
