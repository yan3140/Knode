import asyncio
from dataclasses import dataclass

import pytest

from yan_agent.adapters.java_knowledge.contracts import (
    InternalRetrievalRequest,
    InternalRetrievalResponse,
    KnowledgeCitation,
)
from yan_agent.application.adaptive_retrieval_router import (
    AdaptiveRetrievalRouter,
    AdaptiveRoutingPolicy,
)
from yan_agent.domain.qa import QueryRewrite, RetrievalMode
from yan_agent.domain.retrieval_planning import (
    PlannedQuery,
    QueryComplexity,
    QueryPurpose,
    RetrievalPlan,
    RetrievalPlanningRequest,
    RetrievalPlanningResult,
    RetrievalStrategy,
    SubQuestion,
)
from yan_agent.domain.retrieval_routing import RoutingReason
from yan_agent.evaluation.adaptive_routing_runner import AdaptiveEvaluationPlanner
from yan_agent.evaluation.live_models import LiveEvalCase
from yan_agent.evaluation.live_retrieval_runner import (
    LiveRetrievalRunConfig,
    evaluate_live_cases,
)


@dataclass
class RecordingAdaptivePlanner:
    result: RetrievalPlanningResult
    strategy: RetrievalStrategy = RetrievalStrategy.SINGLE_REWRITE
    model_calls_per_plan: int = 1
    calls: int = 0

    async def plan(self, request: RetrievalPlanningRequest) -> RetrievalPlanningResult:
        self.calls += 1
        return self.result


@dataclass
class RoutingRetrievalClient:
    responses: dict[str, InternalRetrievalResponse]

    async def retrieve(self, request: InternalRetrievalRequest) -> InternalRetrievalResponse:
        await asyncio.sleep(0)
        return self.responses[request.query]


def citation(chunk_id: str, document_id: str) -> KnowledgeCitation:
    return KnowledgeCitation(
        chunk_id=chunk_id,
        document_id=document_id,
        document_version_id="3",
        title="MySQL",
        section="索引",
        source="knowledge:3",
        content="证据",
        vector_similarity=0.8,
        keyword_score=1.0,
        rrf_score=0.5,
        final_score=0.9,
        exact_match=False,
        exact_type=None,
    )


def response(query_id: int, citations: list[KnowledgeCitation]) -> InternalRetrievalResponse:
    return InternalRetrievalResponse(
        query_id=query_id,
        requested_mode=RetrievalMode.AUTO,
        selected_mode=RetrievalMode.HYBRID,
        citations=citations,
        degraded=False,
        degradation_reasons=[],
        total_elapsed_millis=12,
        trace_id=f"trace-live-{query_id}",
    )


def live_case(
    case_id: str,
    question: str,
    *,
    answerable: bool,
    category: str,
) -> LiveEvalCase:
    return LiveEvalCase.model_validate(
        {
            "caseId": case_id,
            "category": category,
            "difficulty": "easy",
            "question": question,
            "conversationContext": None,
            "expectedDocumentIds": [2] if answerable else [],
            "expectedChunkIds": ["2:3:1"] if answerable else [],
            "requiredFacts": ["证据"] if answerable else [],
            "forbiddenClaims": [],
            "answerable": answerable,
        }
    )


def run_config() -> LiveRetrievalRunConfig:
    return LiveRetrievalRunConfig(
        experiment_id="adaptive-test",
        dataset_id="mysql-live-v1",
        dataset_split="development",
        dataset_sha256="a" * 64,
        protocol_id="multi-query-v1",
        knowledge_base_manifest_id="mysql-kb-v1",
        git_commit="abc1234",
        strategy="ADAPTIVE",
        user_id=14787164048670,
        knowledge_base_id=3,
        top_k=10,
        candidate_k=30,
        max_concurrency=2,
        retrieval_config_version="adaptive-retrieval.v1",
    )


def adaptive_router() -> AdaptiveRetrievalRouter:
    return AdaptiveRetrievalRouter(
        AdaptiveRoutingPolicy(
            shadow_mode=False,
            multi_query_enabled=True,
            multi_query_rollout_percent=100,
        )
    )


def multi_query_result(question: str, semantic_query: str) -> RetrievalPlanningResult:
    plan = RetrievalPlan(
        original_question=question,
        standalone_question=question,
        complexity=QueryComplexity.COMPOUND,
        sub_questions=[
            SubQuestion(sub_question_id="sq-1", text="比较结构"),
            SubQuestion(sub_question_id="sq-2", text="比较场景"),
        ],
        queries=[
            PlannedQuery(
                query_id="q-1",
                text=question,
                purpose=QueryPurpose.ORIGINAL,
                mode=RetrievalMode.AUTO,
                weight=0.6,
                target_sub_question_ids=[],
            ),
            PlannedQuery(
                query_id="q-2",
                text=semantic_query,
                purpose=QueryPurpose.SEMANTIC,
                mode=RetrievalMode.HYBRID,
                weight=1.0,
                target_sub_question_ids=["sq-1"],
            ),
            PlannedQuery(
                query_id="q-3",
                text="B 树 B+ 树 范围查询",
                purpose=QueryPurpose.SUB_QUESTION,
                mode=RetrievalMode.HYBRID,
                weight=0.9,
                target_sub_question_ids=["sq-2"],
            ),
        ],
        exact_terms=[],
        filters={},
        planner_version="unified-retrieval.v1",
    )
    return RetrievalPlanningResult(
        strategy=RetrievalStrategy.MULTI_QUERY,
        query=QueryRewrite(query=question),
        planner_version="unified-retrieval.v1",
        prompt_version="question-analysis.v1",
        model_calls=1,
        plan=plan,
    )


@pytest.mark.asyncio
async def test_adaptive_runner_records_actual_a_and_c_execution() -> None:
    simple_question = "MySQL 的 Server 层职责是什么"
    complex_question = "B 树和 B+ 树在结构和范围查询上分别有什么区别？"
    semantic_query = "B 树 B+ 树 结构区别"
    adaptive = RecordingAdaptivePlanner(multi_query_result(complex_question, semantic_query))
    planner = AdaptiveEvaluationPlanner(adaptive_router(), adaptive)
    cases = [
        live_case(
            "mysql-simple-989",
            simple_question,
            answerable=True,
            category="simple_fact",
        ),
        live_case(
            "mysql-comparison-988",
            complex_question,
            answerable=True,
            category="comparison",
        ),
    ]
    client = RoutingRetrievalClient(
        {
            simple_question: response(1, [citation("2:3:1", "2")]),
            complex_question: response(2, []),
            semantic_query: response(3, [citation("2:3:1", "2")]),
            "B 树 B+ 树 范围查询": response(4, []),
        }
    )

    report = await evaluate_live_cases(
        cases,
        client,
        run_config(),
        planner=planner,
    )

    assert report.strategy == "ADAPTIVE"
    assert adaptive.calls == 1
    assert report.cases[0].routing is not None
    assert report.cases[0].routing.effective_strategy is RetrievalStrategy.RAW_QUERY
    assert report.cases[0].planning is not None
    assert report.cases[0].planning.model_calls == 0
    assert report.cases[1].routing is not None
    assert report.cases[1].routing.effective_strategy is RetrievalStrategy.MULTI_QUERY
    assert report.cases[1].planning is not None
    assert report.cases[1].planning.strategy is RetrievalStrategy.MULTI_QUERY
    assert len(report.cases[1].query_executions) == 3


@pytest.mark.asyncio
async def test_adaptive_runner_records_analyzer_collapse_to_b() -> None:
    question = "B 树和 B+ 树在结构和范围查询上分别有什么区别？"
    adaptive = RecordingAdaptivePlanner(
        RetrievalPlanningResult(
            strategy=RetrievalStrategy.SINGLE_REWRITE,
            query=QueryRewrite(query="B 树 B+ 树核心区别", mode=RetrievalMode.HYBRID),
            planner_version="unified-retrieval.v1",
            prompt_version="question-analysis.v1",
            model_calls=1,
        )
    )
    planner = AdaptiveEvaluationPlanner(adaptive_router(), adaptive)

    report = await evaluate_live_cases(
        [
            live_case(
                "mysql-comparison-987",
                question,
                answerable=True,
                category="comparison",
            )
        ],
        RoutingRetrievalClient({"B 树 B+ 树核心区别": response(1, [citation("2:3:1", "2")])}),
        run_config(),
        planner=planner,
    )

    routing = report.cases[0].routing
    assert routing is not None
    assert routing.selected_strategy is RetrievalStrategy.SINGLE_REWRITE
    assert routing.effective_strategy is RetrievalStrategy.SINGLE_REWRITE
    assert "ANALYZER_COLLAPSED_TO_SINGLE" in routing.routing_reasons


@pytest.mark.asyncio
async def test_adaptive_runner_preserves_a_rollout_fallback_after_b_planning() -> None:
    question = "什么是聚簇索引？"
    planner = AdaptiveEvaluationPlanner(
        AdaptiveRetrievalRouter(
            AdaptiveRoutingPolicy(
                shadow_mode=False,
                raw_query_enabled=True,
                raw_query_rollout_percent=0,
            )
        ),
        RecordingAdaptivePlanner(
            RetrievalPlanningResult(
                strategy=RetrievalStrategy.SINGLE_REWRITE,
                query=QueryRewrite(query="聚簇索引 定义", mode=RetrievalMode.HYBRID),
                planner_version="unified-retrieval.v1",
                prompt_version="question-analysis.v1",
                model_calls=1,
            )
        ),
    )

    report = await evaluate_live_cases(
        [
            live_case(
                "mysql-simple-rollout-001",
                question,
                answerable=True,
                category="simple_fact",
            )
        ],
        RoutingRetrievalClient({"聚簇索引 定义": response(1, [citation("2:3:1", "2")])}),
        run_config(),
        planner=planner,
    )

    routing = report.cases[0].routing
    assert routing is not None
    assert routing.selected_strategy is RetrievalStrategy.RAW_QUERY
    assert routing.effective_strategy is RetrievalStrategy.SINGLE_REWRITE
    assert routing.fallback_reason is RoutingReason.RAW_QUERY_OUTSIDE_ROLLOUT


@pytest.mark.asyncio
async def test_adaptive_runner_gates_multi_query_to_b_when_disabled() -> None:
    question = "B 树和 B+ 树在结构和范围查询上分别有什么区别？"
    adaptive = RecordingAdaptivePlanner(multi_query_result(question, "B 树 B+ 树 结构区别"))
    planner = AdaptiveEvaluationPlanner(
        AdaptiveRetrievalRouter(
            AdaptiveRoutingPolicy(
                shadow_mode=False,
                multi_query_enabled=False,
                multi_query_rollout_percent=100,
            )
        ),
        adaptive,
    )

    report = await evaluate_live_cases(
        [
            live_case(
                "mysql-comparison-986",
                question,
                answerable=True,
                category="comparison",
            )
        ],
        RoutingRetrievalClient({question: response(1, [citation("2:3:1", "2")])}),
        run_config(),
        planner=planner,
    )

    result = report.cases[0]
    assert result.routing is not None
    assert result.routing.selected_strategy is RetrievalStrategy.MULTI_QUERY
    assert result.routing.effective_strategy is RetrievalStrategy.SINGLE_REWRITE
    assert result.routing.fallback_reason is RoutingReason.MULTI_QUERY_DISABLED
    assert result.planning is not None
    assert result.planning.strategy is RetrievalStrategy.SINGLE_REWRITE
    assert len(result.query_executions) == 1
