import hashlib
from typing import Literal

from yan_agent.application.adaptive_retrieval_router import AdaptiveRetrievalRouter
from yan_agent.application.retrieval_planning import RawQueryPlanner
from yan_agent.domain.qa import QueryRewrite, RetrievalMode
from yan_agent.domain.retrieval_planning import (
    RetrievalPlanningRequest,
    RetrievalPlanningResult,
    RetrievalStrategy,
)
from yan_agent.domain.retrieval_routing import (
    RequestedRetrievalStrategy,
    RetrievalRoutingDecision,
    RetrievalRoutingRequest,
    RoutingReason,
)
from yan_agent.ports.retrieval_planning import RetrievalPlanner

AdaptiveStrategy = Literal["ADAPTIVE"]


class AdaptivePlanningResult(RetrievalPlanningResult):
    routing_decision: RetrievalRoutingDecision


class AdaptiveEvaluationPlanner:
    """Runs the production routing contract without invoking the answer graph."""

    strategy: AdaptiveStrategy = "ADAPTIVE"
    model_calls_per_plan = 1

    @property
    def prompt_version(self) -> str | None:
        return getattr(self._adaptive_planner, "prompt_version", None)

    def __init__(
        self,
        router: AdaptiveRetrievalRouter,
        adaptive_planner: RetrievalPlanner,
        *,
        remaining_model_calls: int = 10,
        remaining_seconds: float = 900,
        dependencies_healthy: bool = True,
    ) -> None:
        self._router = router
        self._adaptive_planner = adaptive_planner
        self._raw_planner = RawQueryPlanner()
        self._remaining_model_calls = remaining_model_calls
        self._remaining_seconds = remaining_seconds
        self._dependencies_healthy = dependencies_healthy

    async def plan(self, request: RetrievalPlanningRequest) -> RetrievalPlanningResult:
        routing_request = self._routing_request(request)
        initial = self._router.route(routing_request)
        if initial.effective_strategy is RetrievalStrategy.RAW_QUERY:
            planning = await self._raw_planner.plan(request)
            return _with_routing(planning, initial)

        planning = await self._adaptive_planner.plan(request)
        routing = _routing_after_planning(
            self._router,
            routing_request,
            initial,
            planning,
        )
        planning = _planning_after_routing(request, planning, routing)
        return _with_routing(planning, routing)

    async def fallback_fast_path(
        self,
        request: RetrievalPlanningRequest,
    ) -> RetrievalPlanningResult:
        planning = await self._adaptive_planner.plan(request)
        if planning.strategy is RetrievalStrategy.MULTI_QUERY:
            standalone = (
                planning.analysis.standalone_question
                if planning.analysis is not None
                else planning.plan.standalone_question
                if planning.plan is not None
                else request.original_question
            )
            planning = planning.model_copy(
                update={
                    "strategy": RetrievalStrategy.SINGLE_REWRITE,
                    "query": QueryRewrite(query=standalone, mode=RetrievalMode.HYBRID),
                    "plan": None,
                }
            )
        routing = RetrievalRoutingDecision(
            requested_strategy=RequestedRetrievalStrategy.AUTO,
            selected_strategy=RetrievalStrategy.RAW_QUERY,
            effective_strategy=RetrievalStrategy.SINGLE_REWRITE,
            routing_reasons=[
                RoutingReason.HIGH_CONFIDENCE_SIMPLE,
                RoutingReason.FAST_PATH_RETRIEVAL_FALLBACK,
            ],
            routing_version="adaptive-router.v1",
            rollout_eligible=False,
            fallback_from=RetrievalStrategy.RAW_QUERY,
            fallback_reason=RoutingReason.FAST_PATH_RETRIEVAL_FALLBACK,
        )
        return _with_routing(planning, routing)

    def _routing_request(
        self,
        request: RetrievalPlanningRequest,
    ) -> RetrievalRoutingRequest:
        digest = hashlib.sha256(
            f"{request.knowledge_base_id}:{request.original_question}".encode()
        ).hexdigest()[:24]
        return RetrievalRoutingRequest(
            question=request.original_question,
            conversation_context_present=request.conversation_context_present,
            routing_key=f"evaluation:{digest}",
            requested_strategy=RequestedRetrievalStrategy.AUTO,
            remaining_model_calls=self._remaining_model_calls,
            remaining_seconds=self._remaining_seconds,
            dependencies_healthy=self._dependencies_healthy,
        )


def _routing_after_planning(
    router: AdaptiveRetrievalRouter,
    request: RetrievalRoutingRequest,
    initial: RetrievalRoutingDecision,
    planning: RetrievalPlanningResult,
) -> RetrievalRoutingDecision:
    reasons = _analysis_routing_reasons(planning)
    if planning.planning_fallback_reason is not None:
        fallback_reason = reasons[-1]
        if (
            initial.selected_strategy is not RetrievalStrategy.SINGLE_REWRITE
            and initial.effective_strategy is initial.selected_strategy
        ):
            return initial.model_copy(
                update={
                    "effective_strategy": RetrievalStrategy.SINGLE_REWRITE,
                    "routing_reasons": list(dict.fromkeys([*initial.routing_reasons, *reasons])),
                    "fallback_from": initial.selected_strategy,
                    "fallback_reason": fallback_reason,
                }
            )
        if initial.fallback_reason is not None:
            return initial.model_copy(
                update={
                    "routing_reasons": list(dict.fromkeys([*initial.routing_reasons, *reasons]))
                }
            )

    if initial.selected_strategy is RetrievalStrategy.RAW_QUERY and initial.fallback_reason in {
        RoutingReason.RAW_QUERY_DISABLED,
        RoutingReason.RAW_QUERY_OUTSIDE_ROLLOUT,
    }:
        return initial.model_copy(
            update={"routing_reasons": list(dict.fromkeys([*initial.routing_reasons, *reasons]))}
        )

    decision = router.route_candidate(request, planning.strategy, reasons)
    if (
        initial.selected_strategy is RetrievalStrategy.MULTI_QUERY
        and planning.strategy is RetrievalStrategy.SINGLE_REWRITE
    ):
        decision = decision.model_copy(
            update={
                "routing_reasons": list(
                    dict.fromkeys(
                        [
                            *decision.routing_reasons,
                            RoutingReason.ANALYZER_COLLAPSED_TO_SINGLE,
                        ]
                    )
                )
            }
        )
    return decision


def _analysis_routing_reasons(
    planning: RetrievalPlanningResult,
) -> list[RoutingReason]:
    reasons = [
        RoutingReason.MULTI_SUBQUESTION
        if planning.strategy is RetrievalStrategy.MULTI_QUERY
        else RoutingReason.SINGLE_INFORMATION_NEED
    ]
    analysis = planning.analysis
    if analysis is not None:
        if analysis.context_dependent:
            reasons.append(RoutingReason.CONTEXT_DEPENDENT)
        if analysis.ambiguous:
            reasons.append(RoutingReason.COLLOQUIAL_OR_AMBIGUOUS)
    fallback_reasons = {
        "MODEL_TIMEOUT": RoutingReason.PLANNER_TIMEOUT,
        "MODEL_RATE_LIMITED": RoutingReason.PLANNER_RATE_LIMITED,
        "MODEL_INVALID_RESPONSE": RoutingReason.PLANNER_INVALID_RESPONSE,
        "MODEL_UNAVAILABLE": RoutingReason.PLANNER_UNAVAILABLE,
    }
    fallback = fallback_reasons.get(planning.planning_fallback_reason or "")
    if fallback is not None:
        reasons.append(fallback)
    return reasons


def _planning_after_routing(
    request: RetrievalPlanningRequest,
    planning: RetrievalPlanningResult,
    routing: RetrievalRoutingDecision,
) -> RetrievalPlanningResult:
    if (
        planning.strategy is not RetrievalStrategy.MULTI_QUERY
        or routing.effective_strategy is RetrievalStrategy.MULTI_QUERY
    ):
        return planning
    standalone = planning.query.query
    if planning.plan is not None:
        standalone = planning.plan.standalone_question
    if planning.analysis is not None:
        standalone = planning.analysis.standalone_question
    mode = (
        request.requested_mode
        if request.requested_mode is not RetrievalMode.AUTO
        else RetrievalMode.HYBRID
    )
    return planning.model_copy(
        update={
            "strategy": RetrievalStrategy.SINGLE_REWRITE,
            "query": QueryRewrite(query=standalone, mode=mode),
            "plan": None,
        }
    )


def _with_routing(
    planning: RetrievalPlanningResult,
    routing: RetrievalRoutingDecision,
) -> AdaptivePlanningResult:
    return AdaptivePlanningResult(
        **planning.model_dump(),
        routing_decision=routing,
    )
