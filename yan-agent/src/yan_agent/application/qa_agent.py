import hashlib
import json
import operator
import time
from collections.abc import Awaitable, Callable
from typing import Annotated, Any, Literal, TypedDict, cast

from langchain_core.runnables import Runnable, RunnableConfig, RunnableLambda
from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import Command, Send, interrupt
from opentelemetry import trace
from opentelemetry.trace import Status, StatusCode

from yan_agent.application.evidence_reranking import FusionScoreReranker
from yan_agent.application.evidence_security import EvidenceGuard
from yan_agent.application.grounding import GroundedAnswerGuard
from yan_agent.application.retrieval_planning import (
    RawQueryPlanner,
    SingleRewritePlanner,
    render_contextual_question,
)
from yan_agent.domain.budget import AgentBudgetPolicy
from yan_agent.domain.errors import (
    AgentBudgetExceeded,
    AgentExecutionError,
    KnowledgeForbiddenError,
    KnowledgeUnavailableError,
)
from yan_agent.domain.qa import (
    AgentInvocation,
    AgentResult,
    AgentStatus,
    EvidenceAssessment,
    IntentDecision,
    IntentType,
    KnowledgeCitation,
    KnowledgeRetrievalRequest,
    KnowledgeRetrievalResult,
    QueryRewrite,
    RetrievalMode,
)
from yan_agent.domain.retrieval_fusion import (
    FusedCandidate,
    QueryRetrieval,
    RerankedCitation,
    fuse_query_retrievals,
)
from yan_agent.domain.retrieval_planning import (
    PlannedQuery,
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
from yan_agent.observability.context import bind_context
from yan_agent.observability.metrics import AgentMetrics
from yan_agent.ports.evidence_reranking import EvidenceReranker
from yan_agent.ports.grounding import AnswerGroundingModel
from yan_agent.ports.qa import AgentModel, KnowledgeRetriever
from yan_agent.ports.retrieval_planning import RetrievalPlanner
from yan_agent.ports.retrieval_routing import RetrievalRouter

CONTEXT_TRUNCATED = "CONTEXT_TRUNCATED"
tracer = trace.get_tracer(__name__)


class AgentState(TypedDict, total=False):
    invocation: AgentInvocation
    intent: IntentDecision
    rewrite: QueryRewrite
    retrieval_plan: RetrievalPlanningResult
    planned_query: PlannedQuery
    query_order: int
    query_retrievals: Annotated[list[QueryRetrieval], operator.add]
    candidate_pool: list[FusedCandidate]
    reranked_citations: list[RerankedCitation]
    retrieval: KnowledgeRetrievalResult
    safe_citations: list[KnowledgeCitation]
    security_flags: list[str]
    evidence: EvidenceAssessment
    result: AgentResult
    query_ids: list[int]
    retrieval_attempts: int
    model_calls: int
    deadline_at: float
    routing_decision: RetrievalRoutingDecision
    multi_query_fallback_attempted: bool
    fast_path_fallback_attempted: bool


class KnowledgeQaAgent:
    def __init__(
        self,
        model: AgentModel,
        retriever: KnowledgeRetriever,
        retrieval_planner: RetrievalPlanner | None = None,
        max_retrieval_attempts: int = 3,
        checkpointer: BaseCheckpointSaver[Any] | None = None,
        enable_interrupts: bool = False,
        grounding_model: AnswerGroundingModel | None = None,
        max_answer_chars: int | None = None,
        max_total_evidence_chars: int | None = None,
        max_citation_chars: int | None = None,
        budget: AgentBudgetPolicy | None = None,
        clock: Callable[[], float] | None = None,
        metrics: AgentMetrics | None = None,
        evidence_reranker: EvidenceReranker | None = None,
        retrieval_router: RetrievalRouter | None = None,
        adaptive_retrieval_planner: RetrievalPlanner | None = None,
    ) -> None:
        if max_retrieval_attempts < 1:
            raise ValueError("max_retrieval_attempts must be positive")
        self._model = model
        self._retriever = retriever
        self._retrieval_planner = retrieval_planner or SingleRewritePlanner(model)
        self._max_retrieval_attempts = max_retrieval_attempts
        self._checkpointer = checkpointer
        self._enable_interrupts = enable_interrupts
        if enable_interrupts and checkpointer is None:
            raise ValueError("Interrupts require a checkpointer")
        self._budget = budget or AgentBudgetPolicy()
        self._clock = clock or time.time
        self._metrics = metrics
        self._max_context_chars = self._budget.max_context_chars
        self._grounding_guard = GroundedAnswerGuard(
            grounding_model,
            max_answer_chars or self._budget.max_answer_chars,
        )
        self._evidence_guard = EvidenceGuard(
            max_total_chars=(max_total_evidence_chars or self._budget.max_total_evidence_chars),
            max_citation_chars=max_citation_chars or self._budget.max_citation_chars,
        )
        self._evidence_reranker = evidence_reranker or FusionScoreReranker(
            max_results=8,
            max_total_chars=(max_total_evidence_chars or self._budget.max_total_evidence_chars),
        )
        self._retrieval_router = retrieval_router
        self._adaptive_retrieval_planner = adaptive_retrieval_planner
        if adaptive_retrieval_planner is not None and retrieval_router is None:
            raise ValueError("Adaptive retrieval planning requires a retrieval router")
        self._raw_query_planner = RawQueryPlanner()
        self._graph = self._build_graph()

    async def invoke(self, invocation: AgentInvocation) -> AgentResult:
        initial_state: AgentState = {
            "invocation": invocation,
            "query_ids": [],
            "retrieval_attempts": 0,
            "security_flags": self._initial_security_flags(invocation),
            "model_calls": 0,
            "query_retrievals": [],
            "deadline_at": self._new_deadline(),
        }
        final_state = await self._graph.ainvoke(
            initial_state,
            config=self._config(invocation.run_id),
        )
        return self._read_result(final_state)

    async def resume(self, run_id: str, answer: str) -> AgentResult:
        if not self._enable_interrupts:
            raise RuntimeError("This Agent graph does not support resume")
        config = self._config(run_id)
        await self._graph.aupdate_state(config, {"deadline_at": self._new_deadline()})
        final_state = await self._graph.ainvoke(
            Command[Any](resume=answer),
            config=config,
        )
        return self._read_result(final_state)

    async def recover(self, invocation: AgentInvocation) -> AgentResult:
        if self._checkpointer is None:
            return await self.invoke(invocation)
        config = self._config(invocation.run_id)
        snapshot = await self._graph.aget_state(config)
        if not snapshot.values:
            return await self.invoke(invocation)
        if not snapshot.next:
            return self._read_result(snapshot.values)
        await self._graph.aupdate_state(config, {"deadline_at": self._new_deadline()})
        final_state = await self._graph.ainvoke(None, config=config)
        return self._read_result(final_state)

    def _build_graph(self) -> CompiledStateGraph[AgentState, None, AgentState, AgentState]:
        builder = StateGraph(AgentState)
        builder.add_node(
            "classify_intent", self._observed_node("classify_intent", self._classify_intent)
        )
        builder.add_node(
            "rewrite_question", self._observed_node("rewrite_question", self._rewrite_question)
        )
        builder.add_node(
            "route_retrieval",
            self._observed_node("route_retrieval", self._route_retrieval),
        )
        builder.add_node(
            "retrieve_evidence", self._observed_node("retrieve_evidence", self._retrieve_evidence)
        )
        builder.add_node(
            "fallback_fast_path",
            self._observed_node("fallback_fast_path", self._fallback_fast_path),
        )
        builder.add_node("retrieve_query", self._retrieve_query)
        builder.add_node(
            "merge_candidates", self._observed_node("merge_candidates", self._merge_candidates)
        )
        builder.add_node(
            "rerank_evidence", self._observed_node("rerank_evidence", self._rerank_evidence)
        )
        builder.add_node(
            "fallback_multi_query",
            self._observed_node(
                "fallback_multi_query",
                self._fallback_multi_query,
            ),
        )
        builder.add_node(
            "prepare_evidence", self._observed_node("prepare_evidence", self._prepare_evidence)
        )
        builder.add_node(
            "grade_evidence", self._observed_node("grade_evidence", self._grade_evidence)
        )
        builder.add_node("refine_query", self._observed_node("refine_query", self._refine_query))
        builder.add_node(
            "generate_answer", self._observed_node("generate_answer", self._generate_answer)
        )
        if self._enable_interrupts:
            builder.add_node(
                "interrupt_for_clarification",
                self._observed_node(
                    "interrupt_for_clarification",
                    self._interrupt_for_clarification,
                ),
            )
        else:
            builder.add_node(
                "request_clarification",
                self._observed_node("request_clarification", self._request_clarification),
            )
        builder.add_node(
            "conversational_reply",
            self._observed_node("conversational_reply", self._conversational_reply),
        )
        builder.add_node(
            "insufficient_evidence",
            self._observed_node("insufficient_evidence", self._insufficient_evidence),
        )

        builder.add_edge(START, "classify_intent")
        builder.add_conditional_edges(
            "classify_intent",
            self._route_intent,
            {
                "knowledge": (
                    "route_retrieval" if self._retrieval_router is not None else "rewrite_question"
                ),
                "clarification": (
                    "interrupt_for_clarification"
                    if self._enable_interrupts
                    else "request_clarification"
                ),
                "conversational": "conversational_reply",
            },
        )
        builder.add_edge("route_retrieval", "rewrite_question")
        builder.add_conditional_edges("rewrite_question", self._dispatch_retrieval)
        builder.add_edge("retrieve_query", "merge_candidates")
        builder.add_conditional_edges(
            "merge_candidates",
            self._route_after_multi_query,
            {
                "rerank": "rerank_evidence",
                "fallback_single": "fallback_multi_query",
            },
        )
        builder.add_edge("rerank_evidence", "prepare_evidence")
        builder.add_edge("fallback_multi_query", "prepare_evidence")
        builder.add_conditional_edges(
            "retrieve_evidence",
            self._route_after_single_retrieval,
            {
                "fallback_b": "fallback_fast_path",
                "prepare": "prepare_evidence",
            },
        )
        builder.add_edge("fallback_fast_path", "retrieve_evidence")
        builder.add_edge("prepare_evidence", "grade_evidence")
        builder.add_conditional_edges(
            "grade_evidence",
            self._route_evidence,
            {
                "generate": "generate_answer",
                "retry": "refine_query",
                "insufficient": "insufficient_evidence",
            },
        )
        builder.add_edge("refine_query", "retrieve_evidence")
        builder.add_edge("generate_answer", END)
        if self._enable_interrupts:
            builder.add_edge("interrupt_for_clarification", "rewrite_question")
        else:
            builder.add_edge("request_clarification", END)
        builder.add_edge("conversational_reply", END)
        builder.add_edge("insufficient_evidence", END)
        return builder.compile(checkpointer=self._checkpointer)

    def _observed_node(
        self,
        name: str,
        node: Callable[[AgentState], Awaitable[AgentState]],
    ) -> Runnable[AgentState, AgentState]:
        async def observed(state: AgentState) -> AgentState:
            started_at = time.perf_counter()
            outcome = "error"
            run_id = state["invocation"].run_id
            with (
                bind_context(run_id=run_id),
                tracer.start_as_current_span(f"agent.graph.{name}") as span,
            ):
                span.set_attribute("agent.run.id", run_id)
                span.set_attribute("agent.thread.id", run_id)
                span.set_attribute("agent.graph.node", name)
                try:
                    update = await node(state)
                except Exception as error:
                    span.set_status(Status(StatusCode.ERROR, type(error).__name__))
                    raise
                else:
                    outcome = "success"
                    query_ids = update.get("query_ids")
                    if query_ids:
                        span.set_attribute("agent.query.ids", query_ids)
                    routing = update.get("routing_decision")
                    if routing is not None:
                        span.set_attribute(
                            "agent.routing.selected_strategy",
                            routing.selected_strategy.value,
                        )
                        span.set_attribute(
                            "agent.routing.effective_strategy",
                            routing.effective_strategy.value,
                        )
                        span.set_attribute(
                            "agent.routing.version",
                            routing.routing_version,
                        )
                        span.set_attribute(
                            "agent.routing.rollout_eligible",
                            routing.rollout_eligible,
                        )
                        span.set_attribute(
                            "agent.routing.fallback_reason",
                            routing.fallback_reason.value if routing.fallback_reason else "NONE",
                        )
                    planning = update.get("retrieval_plan")
                    if planning is not None:
                        span.set_attribute("agent.analysis.cache_hit", planning.cache_hit)
                        analysis = planning.analysis
                        if analysis is not None:
                            span.set_attribute(
                                "agent.analysis.complexity",
                                analysis.complexity.value,
                            )
                            span.set_attribute(
                                "agent.analysis.context_dependent",
                                analysis.context_dependent,
                            )
                            span.set_attribute(
                                "agent.analysis.sub_question_count",
                                len(analysis.sub_questions),
                            )
                            span.set_attribute(
                                "agent.analysis.query_count",
                                len(analysis.candidate_queries),
                            )
                    return update
                finally:
                    if self._metrics is not None:
                        self._metrics.observe_graph_node(
                            name,
                            outcome,
                            time.perf_counter() - started_at,
                        )

        return RunnableLambda(observed)

    async def _classify_intent(self, state: AgentState) -> AgentState:
        model_calls = self._reserve_model_call(state)
        decision = await self._model.classify(self._model_question(state["invocation"]))
        return {"intent": decision, "model_calls": model_calls}

    @staticmethod
    def _route_intent(
        state: AgentState,
    ) -> Literal["knowledge", "clarification", "conversational"]:
        intent = state["intent"].intent
        if intent is IntentType.KNOWLEDGE:
            return "knowledge"
        if intent is IntentType.CLARIFICATION:
            return "clarification"
        return "conversational"

    async def _rewrite_question(self, state: AgentState) -> AgentState:
        invocation = state["invocation"]
        planner = self._planner_for_state(state)
        self._reserve_model_calls(
            state,
            planner.model_calls_per_plan,
        )
        planning = await planner.plan(
            planning_request := RetrievalPlanningRequest(
                original_question=invocation.question,
                model_input=self._model_question(invocation),
                requested_mode=invocation.requested_mode,
                conversation_context_present=_has_prior_conversation_context(invocation),
                knowledge_base_id=invocation.knowledge_base_id,
                context_version=invocation.context_version,
            )
        )
        if planning.model_calls > planner.model_calls_per_plan:
            raise RuntimeError("retrieval planner exceeded declared model calls")
        model_calls = state.get("model_calls", 0) + planning.model_calls
        routing = self._routing_after_planning(state, planning, model_calls)
        planning = self._planning_after_routing(planning_request, planning, routing)
        return {
            "rewrite": planning.query,
            "retrieval_plan": planning,
            "model_calls": model_calls,
            **({"routing_decision": routing} if routing is not None else {}),
        }

    def _planner_for_state(self, state: AgentState) -> RetrievalPlanner:
        if self._adaptive_retrieval_planner is None:
            return self._retrieval_planner
        routing = state.get("routing_decision")
        if routing is not None:
            if routing.effective_strategy is RetrievalStrategy.RAW_QUERY:
                return self._raw_query_planner
            if routing.fallback_reason is RoutingReason.SHADOW_MODE:
                return self._retrieval_planner
        return self._adaptive_retrieval_planner

    def _routing_after_planning(
        self,
        state: AgentState,
        planning: RetrievalPlanningResult,
        model_calls: int,
    ) -> RetrievalRoutingDecision | None:
        routing = state.get("routing_decision")
        if self._adaptive_retrieval_planner is None or self._retrieval_router is None:
            return routing
        if routing is not None and routing.fallback_reason is RoutingReason.SHADOW_MODE:
            return routing
        reasons = self._analysis_routing_reasons(planning)
        if planning.planning_fallback_reason is not None:
            fallback_reason = reasons[-1]
            if (
                routing is not None
                and routing.selected_strategy is not RetrievalStrategy.SINGLE_REWRITE
                and routing.effective_strategy is routing.selected_strategy
            ):
                combined = list(dict.fromkeys([*routing.routing_reasons, *reasons]))
                return routing.model_copy(
                    update={
                        "effective_strategy": RetrievalStrategy.SINGLE_REWRITE,
                        "routing_reasons": combined,
                        "fallback_from": routing.selected_strategy,
                        "fallback_reason": fallback_reason,
                    }
                )
            if routing is not None and routing.fallback_reason is not None:
                return routing.model_copy(
                    update={
                        "routing_reasons": list(dict.fromkeys([*routing.routing_reasons, *reasons]))
                    }
                )
        if (
            routing is not None
            and routing.selected_strategy is RetrievalStrategy.RAW_QUERY
            and routing.fallback_reason
            in {
                RoutingReason.RAW_QUERY_DISABLED,
                RoutingReason.RAW_QUERY_OUTSIDE_ROLLOUT,
            }
        ):
            return routing.model_copy(
                update={
                    "routing_reasons": list(dict.fromkeys([*routing.routing_reasons, *reasons]))
                }
            )
        decision = self._retrieval_router.route_candidate(
            self._routing_request(state, model_calls),
            planning.strategy,
            reasons,
        )
        if (
            routing is not None
            and routing.selected_strategy is RetrievalStrategy.MULTI_QUERY
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

    @staticmethod
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

    @staticmethod
    def _planning_after_routing(
        request: RetrievalPlanningRequest,
        planning: RetrievalPlanningResult,
        routing: RetrievalRoutingDecision | None,
    ) -> RetrievalPlanningResult:
        if (
            routing is None
            or planning.strategy is not RetrievalStrategy.MULTI_QUERY
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

    def _routing_request(
        self,
        state: AgentState,
        model_calls: int | None = None,
    ) -> RetrievalRoutingRequest:
        invocation = state["invocation"]
        used_model_calls = state.get("model_calls", 0) if model_calls is None else model_calls
        return RetrievalRoutingRequest(
            question=invocation.question,
            conversation_context_present=_has_prior_conversation_context(invocation),
            routing_key=f"{invocation.user_id}:{invocation.knowledge_base_id}",
            requested_strategy=RequestedRetrievalStrategy.AUTO,
            remaining_model_calls=max(0, self._budget.max_model_calls - used_model_calls),
            remaining_seconds=max(0.0, state["deadline_at"] - self._clock()),
            dependencies_healthy=True,
        )

    async def _route_retrieval(self, state: AgentState) -> AgentState:
        if self._retrieval_router is None:
            return {}
        decision = self._retrieval_router.route(self._routing_request(state))
        actual_strategy = self._retrieval_planner.strategy
        if (
            self._adaptive_retrieval_planner is None
            and decision.effective_strategy is not actual_strategy
        ):
            reasons = list(decision.routing_reasons)
            if RoutingReason.SHADOW_MODE not in reasons:
                reasons.append(RoutingReason.SHADOW_MODE)
            decision = decision.model_copy(
                update={
                    "effective_strategy": actual_strategy,
                    "routing_reasons": reasons,
                    "fallback_from": decision.selected_strategy,
                    "fallback_reason": RoutingReason.SHADOW_MODE,
                }
            )
        return {"routing_decision": decision}

    @staticmethod
    def _dispatch_retrieval(state: AgentState) -> str | list[Send]:
        planning = state["retrieval_plan"]
        if planning.strategy is not RetrievalStrategy.MULTI_QUERY:
            return "retrieve_evidence"
        if planning.plan is None:
            raise RuntimeError("MULTI_QUERY planning result has no retrieval plan")
        return [
            Send(
                "retrieve_query",
                {
                    "invocation": state["invocation"],
                    "planned_query": query,
                    "query_order": order,
                    "deadline_at": state["deadline_at"],
                },
            )
            for order, query in enumerate(planning.plan.queries)
        ]

    async def _retrieve_query(self, state: AgentState) -> AgentState:
        self._check_deadline(state)
        invocation = state["invocation"]
        planned_query = state["planned_query"]
        query_order = state["query_order"]
        started_at = time.perf_counter()
        try:
            retrieval = await self._retriever.retrieve(
                KnowledgeRetrievalRequest(
                    retrieval_request_id=_planned_retrieval_request_id(
                        invocation.run_id,
                        1,
                        planned_query.query_id,
                    ),
                    run_id=invocation.run_id,
                    user_id=invocation.user_id,
                    knowledge_base_id=invocation.knowledge_base_id,
                    query=planned_query.text,
                    requested_mode=planned_query.mode,
                )
            )
        except (
            AgentBudgetExceeded,
            KnowledgeForbiddenError,
        ):
            raise
        except Exception as error:
            error_code = (
                error.code.value if isinstance(error, AgentExecutionError) else type(error).__name__
            )
            query_retrieval = QueryRetrieval(
                planned_query_id=planned_query.query_id,
                query_order=query_order,
                query_text=planned_query.text,
                purpose=planned_query.purpose,
                weight=planned_query.weight,
                citations=[],
                degraded=True,
                degradation_reasons=["QUERY_RETRIEVAL_FAILED"],
                elapsed_millis=(time.perf_counter() - started_at) * 1000,
                error_code=error_code,
                error_message=str(error)[:500],
                target_sub_question_ids=planned_query.target_sub_question_ids,
            )
        else:
            query_retrieval = QueryRetrieval(
                planned_query_id=planned_query.query_id,
                query_order=query_order,
                query_text=planned_query.text,
                purpose=planned_query.purpose,
                weight=planned_query.weight,
                java_query_id=retrieval.query_id,
                selected_mode=retrieval.selected_mode,
                citations=retrieval.citations,
                degraded=retrieval.degraded,
                degradation_reasons=retrieval.degradation_reasons,
                elapsed_millis=(time.perf_counter() - started_at) * 1000,
                target_sub_question_ids=planned_query.target_sub_question_ids,
            )
        return {"query_retrievals": [query_retrieval]}

    async def _merge_candidates(self, state: AgentState) -> AgentState:
        self._check_deadline(state)
        retrievals = state["query_retrievals"]
        if retrievals and all(item.error_code == "KNOWLEDGE_UNAVAILABLE" for item in retrievals):
            raise KnowledgeUnavailableError()
        fused = fuse_query_retrievals(
            retrievals,
            rank_constant=20,
            limit=20,
            max_chunks_per_document=4,
            minimum_per_subquestion=2,
        )
        citations = [
            candidate.citation.model_copy(update={"index": index})
            for index, candidate in enumerate(fused, start=1)
        ]
        successful = [item for item in retrievals if item.java_query_id is not None]
        query_ids = [
            item.java_query_id
            for item in sorted(successful, key=lambda item: item.query_order)
            if item.java_query_id is not None
        ]
        reasons: list[str] = []
        for item in retrievals:
            for reason in item.degradation_reasons:
                if reason not in reasons:
                    reasons.append(reason)
        if len(successful) != len(retrievals):
            reasons.append("PARTIAL_QUERY_FAILURE")
            if not successful:
                reasons.append("ALL_QUERIES_FAILED")
        modes = {item.selected_mode for item in successful if item.selected_mode is not None}
        selected_mode = modes.pop() if len(modes) == 1 else RetrievalMode.HYBRID
        retrieval = KnowledgeRetrievalResult(
            query_id=query_ids[0] if query_ids else 1,
            selected_mode=selected_mode,
            citations=citations,
            degraded=bool(reasons),
            degradation_reasons=reasons,
        )
        routing = state.get("routing_decision")
        if routing is not None and len(successful) != len(retrievals):
            routing_reasons = list(routing.routing_reasons)
            failure_reason = (
                RoutingReason.PARTIAL_QUERY_FAILURE
                if successful
                else RoutingReason.ALL_QUERIES_FAILED
            )
            if failure_reason not in routing_reasons:
                routing_reasons.append(failure_reason)
            routing = routing.model_copy(update={"routing_reasons": routing_reasons})
        return {
            "retrieval": retrieval,
            "candidate_pool": fused,
            "retrieval_attempts": state["retrieval_attempts"] + 1,
            "query_ids": [*state["query_ids"], *query_ids],
            **({"routing_decision": routing} if routing is not None else {}),
        }

    def _route_after_multi_query(
        self,
        state: AgentState,
    ) -> Literal["rerank", "fallback_single"]:
        if self._adaptive_retrieval_planner is None:
            return "rerank"
        if state.get("multi_query_fallback_attempted", False):
            return "rerank"
        retrievals = state["query_retrievals"]
        if not retrievals or any(item.java_query_id is not None for item in retrievals):
            return "rerank"
        retryable_query_errors = {"TimeoutError", "ReadTimeout", "ConnectTimeout"}
        if all(item.error_code in retryable_query_errors for item in retrievals):
            return "fallback_single"
        return "rerank"

    async def _fallback_multi_query(self, state: AgentState) -> AgentState:
        self._check_deadline(state)
        invocation = state["invocation"]
        planning = state["retrieval_plan"]
        if planning.plan is None:
            raise RuntimeError("MULTI_QUERY fallback requires a retrieval plan")
        routing = self._multi_query_fallback_routing(state.get("routing_decision"))
        try:
            retrieval = await self._retriever.retrieve(
                KnowledgeRetrievalRequest(
                    retrieval_request_id=_retrieval_request_id(invocation.run_id, 2),
                    run_id=invocation.run_id,
                    user_id=invocation.user_id,
                    knowledge_base_id=invocation.knowledge_base_id,
                    query=planning.plan.standalone_question,
                    requested_mode=(
                        invocation.requested_mode
                        if invocation.requested_mode is not RetrievalMode.AUTO
                        else RetrievalMode.HYBRID
                    ),
                )
            )
        except (AgentBudgetExceeded, KnowledgeForbiddenError):
            raise
        except Exception as error:
            existing = state["retrieval"]
            error_code = (
                error.code.value if isinstance(error, AgentExecutionError) else type(error).__name__
            )
            reasons = list(existing.degradation_reasons)
            reasons.extend(["SINGLE_QUERY_FALLBACK_FAILED", error_code])
            return {
                "rewrite": QueryRewrite(
                    query=planning.plan.standalone_question,
                    mode=RetrievalMode.HYBRID,
                ),
                "retrieval": existing.model_copy(
                    update={
                        "degraded": True,
                        "degradation_reasons": list(dict.fromkeys(reasons)),
                    }
                ),
                "retrieval_attempts": state["retrieval_attempts"] + 1,
                "multi_query_fallback_attempted": True,
                **({"routing_decision": routing} if routing is not None else {}),
            }
        reasons = list(state["retrieval"].degradation_reasons)
        if RoutingReason.ALL_QUERIES_FAILED.value not in reasons:
            reasons.append(RoutingReason.ALL_QUERIES_FAILED.value)
        retrieval = retrieval.model_copy(update={"degraded": True, "degradation_reasons": reasons})
        return {
            "rewrite": QueryRewrite(
                query=planning.plan.standalone_question,
                mode=retrieval.selected_mode,
            ),
            "retrieval": retrieval,
            "retrieval_attempts": state["retrieval_attempts"] + 1,
            "query_ids": [*state["query_ids"], retrieval.query_id],
            "multi_query_fallback_attempted": True,
            **({"routing_decision": routing} if routing is not None else {}),
        }

    @staticmethod
    def _multi_query_fallback_routing(
        routing: RetrievalRoutingDecision | None,
    ) -> RetrievalRoutingDecision | None:
        if routing is None:
            return None
        reasons = list(routing.routing_reasons)
        if RoutingReason.ALL_QUERIES_FAILED not in reasons:
            reasons.append(RoutingReason.ALL_QUERIES_FAILED)
        return routing.model_copy(
            update={
                "effective_strategy": RetrievalStrategy.SINGLE_REWRITE,
                "routing_reasons": reasons,
                "fallback_from": routing.selected_strategy,
                "fallback_reason": RoutingReason.ALL_QUERIES_FAILED,
            }
        )

    async def _rerank_evidence(self, state: AgentState) -> AgentState:
        self._check_deadline(state)
        retrieval = state["retrieval"]
        try:
            reranked = await self._evidence_reranker.rerank(
                self._model_question(state["invocation"]),
                state["candidate_pool"],
            )
        except Exception:
            fallback = FusionScoreReranker(
                max_results=8,
                max_total_chars=self._budget.max_total_evidence_chars,
            )
            reranked = await fallback.rerank(
                self._model_question(state["invocation"]),
                state["candidate_pool"],
            )
            reasons = list(retrieval.degradation_reasons)
            if "RERANKER_FALLBACK" not in reasons:
                reasons.append("RERANKER_FALLBACK")
            retrieval = retrieval.model_copy(
                update={"degraded": True, "degradation_reasons": reasons}
            )
        citations = [
            item.citation.model_copy(update={"index": index})
            for index, item in enumerate(reranked, start=1)
        ]
        retrieval = retrieval.model_copy(update={"citations": citations})
        return {
            "reranked_citations": reranked,
            "retrieval": retrieval,
        }

    async def _retrieve_evidence(self, state: AgentState) -> AgentState:
        self._check_deadline(state)
        invocation = state["invocation"]
        attempt = state["retrieval_attempts"] + 1
        retrieval = await self._retriever.retrieve(
            KnowledgeRetrievalRequest(
                retrieval_request_id=_retrieval_request_id(invocation.run_id, attempt),
                run_id=invocation.run_id,
                user_id=invocation.user_id,
                knowledge_base_id=invocation.knowledge_base_id,
                query=state["rewrite"].query,
                requested_mode=state["rewrite"].mode,
            )
        )
        return {
            "retrieval": retrieval,
            "retrieval_attempts": attempt,
            "query_ids": [*state["query_ids"], retrieval.query_id],
        }

    @staticmethod
    def _route_after_single_retrieval(state: AgentState) -> str:
        routing = state.get("routing_decision")
        retrieval = state["retrieval"]
        if (
            routing is not None
            and routing.effective_strategy is RetrievalStrategy.RAW_QUERY
            and not state.get("fast_path_fallback_attempted", False)
            and (retrieval.degraded or not retrieval.citations)
        ):
            return "fallback_b"
        return "prepare"

    async def _fallback_fast_path(self, state: AgentState) -> AgentState:
        planner = self._retrieval_planner
        self._reserve_model_calls(state, planner.model_calls_per_plan)
        invocation = state["invocation"]
        planning = await planner.plan(
            RetrievalPlanningRequest(
                original_question=invocation.question,
                model_input=self._model_question(invocation),
                requested_mode=invocation.requested_mode,
                conversation_context_present=_has_prior_conversation_context(invocation),
                knowledge_base_id=invocation.knowledge_base_id,
                context_version=invocation.context_version,
            )
        )
        routing = state["routing_decision"]
        reasons = list(
            dict.fromkeys([*routing.routing_reasons, RoutingReason.FAST_PATH_RETRIEVAL_FALLBACK])
        )
        return {
            "rewrite": planning.query,
            "retrieval_plan": planning,
            "model_calls": state.get("model_calls", 0) + planning.model_calls,
            "routing_decision": routing.model_copy(
                update={
                    "effective_strategy": RetrievalStrategy.SINGLE_REWRITE,
                    "routing_reasons": reasons,
                    "fallback_from": RetrievalStrategy.RAW_QUERY,
                    "fallback_reason": RoutingReason.FAST_PATH_RETRIEVAL_FALLBACK,
                }
            ),
            "fast_path_fallback_attempted": True,
        }

    async def _grade_evidence(self, state: AgentState) -> AgentState:
        citations = state["safe_citations"]
        if not citations:
            assessment = EvidenceAssessment(sufficient=False, reason="未检索到证据")
            return {"evidence": assessment}
        else:
            model_calls = self._reserve_model_call(state)
            assessment = await self._model.grade(
                self._model_question(state["invocation"]), citations
            )
        return {"evidence": assessment, "model_calls": model_calls}

    async def _prepare_evidence(self, state: AgentState) -> AgentState:
        prepared = self._evidence_guard.prepare(state["retrieval"].citations)
        flags = list(state.get("security_flags", []))
        for flag in prepared.flags:
            if flag not in flags:
                flags.append(flag)
        return {
            "safe_citations": prepared.citations,
            "security_flags": flags,
        }

    def _route_evidence(self, state: AgentState) -> Literal["generate", "retry", "insufficient"]:
        if state["evidence"].sufficient:
            return "generate"
        if state.get("multi_query_fallback_attempted", False):
            return "insufficient"
        if state["retrieval_attempts"] < self._max_retrieval_attempts:
            return "retry"
        return "insufficient"

    async def _refine_query(self, state: AgentState) -> AgentState:
        model_calls = self._reserve_model_call(state)
        rewrite = await self._model.refine(
            self._model_question(state["invocation"]),
            state["rewrite"].query,
            state["safe_citations"],
        )
        return {"rewrite": rewrite, "model_calls": model_calls}

    async def _generate_answer(self, state: AgentState) -> AgentState:
        question = self._model_question(state["invocation"])
        model_calls = self._reserve_model_call(state)
        generated = await self._model.answer(
            question,
            state["safe_citations"],
        )
        grounded = await self._grounding_guard.finalize(
            question=question,
            generated=generated,
            available=state["retrieval"].citations,
            review_available=state["safe_citations"],
            max_model_calls=self._budget.max_model_calls - model_calls,
        )
        total_model_calls = model_calls + grounded.model_calls
        if not grounded.valid:
            result = self._result(
                state,
                AgentStatus.INSUFFICIENT_EVIDENCE,
                "当前答案无法通过逐句引用校验，因此未返回未经证据支持的内容。",
                [],
            ).model_copy(
                update={
                    "degradation_reasons": [
                        *state["retrieval"].degradation_reasons,
                        "ANSWER_GROUNDING_FAILED",
                    ],
                    "grounding_repaired": grounded.repaired,
                    "grounding_issues": grounded.issues,
                    "model_calls": total_model_calls,
                }
            )
            return {"result": result}
        result = self._result(
            state,
            AgentStatus.SUCCEEDED,
            grounded.answer,
            grounded.citations,
        ).model_copy(
            update={
                "grounding_repaired": grounded.repaired,
                "grounding_issues": grounded.issues,
                "model_calls": total_model_calls,
            }
        )
        return {"result": result}

    async def _request_clarification(self, state: AgentState) -> AgentState:
        question = state["intent"].clarification_question or "请补充你希望查询的具体对象或问题。"
        return {"result": self._result(state, AgentStatus.CLARIFICATION_REQUIRED, question, [])}

    async def _interrupt_for_clarification(self, state: AgentState) -> AgentState:
        question = state["intent"].clarification_question or "请补充你希望查询的具体对象或问题。"
        answer = interrupt({"question": question})
        if not isinstance(answer, str) or not answer.strip():
            raise ValueError("Clarification answer must not be blank")
        invocation = state["invocation"]
        updated = invocation.model_copy(
            update={"question": f"{invocation.question}\n用户补充信息：{answer.strip()}"}
        )
        return {"invocation": updated}

    async def _conversational_reply(self, state: AgentState) -> AgentState:
        model_calls = self._reserve_model_call(state)
        answer = await self._model.converse(self._model_question(state["invocation"]))
        result = self._result(state, AgentStatus.SUCCEEDED, answer, []).model_copy(
            update={"model_calls": model_calls}
        )
        return {"result": result, "model_calls": model_calls}

    async def _insufficient_evidence(self, state: AgentState) -> AgentState:
        answer = "当前知识库证据不足，无法可靠回答这个问题。请补充资料或调整问题后重试。"
        return {"result": self._result(state, AgentStatus.INSUFFICIENT_EVIDENCE, answer, [])}

    def _result(
        self,
        state: AgentState,
        status: AgentStatus,
        answer: str,
        citations: list[KnowledgeCitation],
    ) -> AgentResult:
        retrieval = state.get("retrieval")
        result = AgentResult(
            run_id=state["invocation"].run_id,
            status=status,
            answer=answer,
            citations=citations,
            query_ids=state["query_ids"],
            selected_mode=retrieval.selected_mode if retrieval else None,
            degraded=retrieval.degraded if retrieval else False,
            degradation_reasons=retrieval.degradation_reasons if retrieval else [],
            retrieval_attempts=state["retrieval_attempts"],
            security_flags=state.get("security_flags", []),
            model_calls=state.get("model_calls", 0),
            routing_decision=state.get("routing_decision"),
        )
        if self._metrics is not None and result.routing_decision is not None:
            self._metrics.observe_routing(result.routing_decision)
        return result

    def _new_deadline(self) -> float:
        return self._clock() + self._budget.max_total_seconds

    def _check_deadline(self, state: AgentState) -> None:
        if self._clock() >= state["deadline_at"]:
            raise AgentBudgetExceeded("total_seconds")

    def _reserve_model_call(self, state: AgentState) -> int:
        return self._reserve_model_calls(state, 1)

    def _reserve_model_calls(self, state: AgentState, count: int) -> int:
        self._check_deadline(state)
        model_calls = state.get("model_calls", 0) + count
        if model_calls > self._budget.max_model_calls:
            raise AgentBudgetExceeded("model_calls")
        return model_calls

    def _model_question(self, invocation: AgentInvocation) -> str:
        return render_contextual_question(
            invocation.question,
            invocation.conversation_context,
            self._max_context_chars,
        )

    @staticmethod
    def _config(run_id: str) -> RunnableConfig:
        return {"configurable": {"thread_id": run_id}}

    @staticmethod
    def _read_result(final_state: object) -> AgentResult:
        raw_state = cast(dict[str, Any], final_state)
        interruptions = raw_state.get("__interrupt__")
        if interruptions:
            first = interruptions[0]
            value = getattr(first, "value", None)
            question = value.get("question") if isinstance(value, dict) else None
            invocation = cast(AgentInvocation, raw_state["invocation"])
            return AgentResult(
                run_id=invocation.run_id,
                status=AgentStatus.CLARIFICATION_REQUIRED,
                answer=question if isinstance(question, str) else "请补充问题信息。",
                citations=[],
                query_ids=cast(list[int], raw_state.get("query_ids", [])),
                retrieval_attempts=cast(int, raw_state.get("retrieval_attempts", 0)),
                security_flags=cast(list[str], raw_state.get("security_flags", [])),
                model_calls=cast(int, raw_state.get("model_calls", 0)),
                routing_decision=cast(
                    RetrievalRoutingDecision | None,
                    raw_state.get("routing_decision"),
                ),
            )
        return cast(AgentResult, raw_state["result"])

    def _initial_security_flags(self, invocation: AgentInvocation) -> list[str]:
        context = invocation.conversation_context
        if context is not None and len(context) > self._max_context_chars:
            return [CONTEXT_TRUNCATED]
        return []


def _retrieval_request_id(run_id: str, attempt: int) -> str:
    return hashlib.sha256(f"{run_id}:{attempt}".encode()).hexdigest()


def _planned_retrieval_request_id(
    run_id: str,
    retrieval_round: int,
    planned_query_id: str,
) -> str:
    value = f"{run_id}:{retrieval_round}:{planned_query_id}"
    return hashlib.sha256(value.encode()).hexdigest()


def _has_prior_conversation_context(invocation: AgentInvocation) -> bool:
    context = invocation.conversation_context
    if context is None or not context.strip():
        return False
    try:
        payload = json.loads(context)
    except (json.JSONDecodeError, TypeError):
        return True
    if not isinstance(payload, dict) or "contextVersion" not in payload:
        return True
    summary = payload.get("summary")
    if isinstance(summary, str) and summary.strip():
        return True
    messages = payload.get("messages")
    if not isinstance(messages, list):
        return True
    meaningful = [message for message in messages if isinstance(message, dict)]
    if not meaningful:
        return False
    last = meaningful[-1]
    is_current_user_message = (
        last.get("role") == "USER" and last.get("content") == invocation.question
    )
    return len(meaningful) > 1 if is_current_user_message else bool(meaningful)
