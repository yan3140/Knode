import asyncio
import json
from dataclasses import dataclass, field
from pathlib import Path

import pytest

from yan_agent.adapters.java_knowledge.contracts import (
    InternalRetrievalRequest,
    InternalRetrievalResponse,
    KnowledgeCitation,
    RetrievalMode,
)
from yan_agent.application.retrieval_planning import SingleRewritePlanner
from yan_agent.domain.qa import QueryRewrite
from yan_agent.domain.qa import RetrievalMode as DomainRetrievalMode
from yan_agent.domain.retrieval_planning import (
    PlannedQuery,
    QueryComplexity,
    QueryPurpose,
    RetrievalPlan,
    RetrievalPlanningResult,
    SubQuestion,
)
from yan_agent.evaluation.comparison_report import (
    compare_retrieval_reports,
    load_live_retrieval_report,
    render_live_retrieval_markdown,
    render_retrieval_comparison_markdown,
    write_live_retrieval_report,
    write_retrieval_comparison,
)
from yan_agent.evaluation.live_models import LiveEvalCase
from yan_agent.evaluation.live_retrieval_runner import (
    LiveRetrievalReport,
    LiveRetrievalRunConfig,
    evaluate_live_cases,
    evaluate_live_dataset,
)
from yan_agent.evaluation.protocol import RetrievalStrategy

PROJECT_ROOT = Path(__file__).parents[2]


@dataclass
class RecordingRetrievalClient:
    response: InternalRetrievalResponse
    requests: list[InternalRetrievalRequest] = field(default_factory=list)

    async def retrieve(self, request: InternalRetrievalRequest) -> InternalRetrievalResponse:
        self.requests.append(request)
        return self.response


@dataclass
class RoutingRetrievalClient:
    responses: dict[str, InternalRetrievalResponse]
    active: int = 0
    max_active: int = 0

    async def retrieve(self, request: InternalRetrievalRequest) -> InternalRetrievalResponse:
        self.active += 1
        self.max_active = max(self.max_active, self.active)
        await asyncio.sleep(0.01)
        self.active -= 1
        return self.responses[request.query]


@dataclass
class PartiallyFailingMultiClient(RoutingRetrievalClient):
    failing_query: str = ""

    async def retrieve(self, request: InternalRetrievalRequest) -> InternalRetrievalResponse:
        if request.query == self.failing_query:
            raise TimeoutError("one query timed out")
        return await super().retrieve(request)


@dataclass
class EmptyRetrievalClient:
    query_id: int = 0

    async def retrieve(self, request: InternalRetrievalRequest) -> InternalRetrievalResponse:
        self.query_id += 1
        return response(self.query_id, [])


@dataclass
class PartiallyFailingRetrievalClient:
    async def retrieve(self, request: InternalRetrievalRequest) -> InternalRetrievalResponse:
        if request.query == "outage":
            raise RuntimeError("temporary outage")
        return response(1, [citation("2:3:1", "2")])


@dataclass
class RecordingRewriteModel:
    result: QueryRewrite
    questions: list[str] = field(default_factory=list)

    async def rewrite(self, question: str) -> QueryRewrite:
        self.questions.append(question)
        return self.result


class StaticLiveMultiPlanner:
    strategy = RetrievalStrategy.MULTI_QUERY
    model_calls_per_plan = 1

    async def plan(self, request: object) -> RetrievalPlanningResult:
        question = "B 树和 B+ 树有什么区别？"
        plan = RetrievalPlan(
            original_question=question,
            standalone_question=question,
            complexity=QueryComplexity.COMPOUND,
            sub_questions=[SubQuestion(sub_question_id="sq-1", text="比较两者")],
            queries=[
                PlannedQuery(
                    query_id="q-original",
                    text=question,
                    purpose=QueryPurpose.ORIGINAL,
                    mode=DomainRetrievalMode.AUTO,
                    weight=0.6,
                    target_sub_question_ids=["sq-1"],
                ),
                PlannedQuery(
                    query_id="q-semantic",
                    text="B树 B+树 数据存放 范围查询 区别",
                    purpose=QueryPurpose.SEMANTIC,
                    mode=DomainRetrievalMode.HYBRID,
                    weight=1.0,
                    target_sub_question_ids=["sq-1"],
                ),
            ],
            exact_terms=[],
            filters={},
            planner_version="multi-query.v1",
        )
        return RetrievalPlanningResult(
            strategy=self.strategy,
            query=QueryRewrite(query=question),
            planner_version="multi-query.v1",
            prompt_version="retrieval-plan.v1",
            model_calls=1,
            plan=plan,
        )


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
            "requiredFacts": ["B+ 树适合范围查询"] if answerable else [],
            "forbiddenClaims": [],
            "answerable": answerable,
        }
    )


def run_config(
    *,
    max_concurrency: int = 2,
    strategy: RetrievalStrategy = RetrievalStrategy.RAW_QUERY,
) -> LiveRetrievalRunConfig:
    return LiveRetrievalRunConfig(
        experiment_id="phase2-test",
        dataset_id="mysql-live-v1",
        dataset_split="development",
        dataset_sha256="a" * 64,
        protocol_id="multi-query-v1",
        knowledge_base_manifest_id="mysql-kb-v1",
        git_commit="abc1234",
        strategy=strategy,
        user_id=14787164048670,
        knowledge_base_id=3,
        top_k=10,
        candidate_k=30,
        max_concurrency=max_concurrency,
        retrieval_config_version="adaptive-retrieval.v1",
    )


@pytest.mark.asyncio
async def test_live_runner_records_raw_query_ranking_and_retrieval_metrics() -> None:
    case = LiveEvalCase.model_validate(
        {
            "caseId": "mysql-simple-999",
            "category": "simple_fact",
            "difficulty": "easy",
            "question": "为什么使用 B+ 树？",
            "conversationContext": None,
            "expectedDocumentIds": [2],
            "expectedChunkIds": ["2:3:1", "2:3:2"],
            "requiredFacts": ["B+ 树适合范围查询"],
            "forbiddenClaims": [],
            "answerable": True,
        }
    )
    client = RecordingRetrievalClient(
        InternalRetrievalResponse(
            query_id=41,
            requested_mode=RetrievalMode.AUTO,
            selected_mode=RetrievalMode.HYBRID,
            citations=[
                citation("9:10:1", "9"),
                citation("2:3:2", "2"),
                citation("2:3:1", "2"),
            ],
            degraded=False,
            degradation_reasons=[],
            total_elapsed_millis=12,
            trace_id="trace-live-1",
        )
    )
    report = await evaluate_live_cases([case], client, run_config())

    assert client.requests[0].query == case.question
    assert client.requests[0].top_k == 10
    assert client.requests[0].candidate_k == 30
    assert report.case_count == 1
    assert report.metrics.hit_at_5 == 1
    assert report.metrics.recall_at_5 == 1
    assert report.metrics.reciprocal_rank_at_10 == 0.5
    assert report.metrics.ndcg_at_10 == pytest.approx(0.693426, abs=0.000001)
    assert [item.chunk_id for item in report.cases[0].final_ranking] == [
        "9:10:1",
        "2:3:2",
        "2:3:1",
    ]
    assert report.cases[0].query_executions[0].trace_id == "trace-live-1"
    assert report.cases[0].query_executions[0].run_id.startswith("live-")
    assert report.cases[0].query_executions[0].retrieval_request_id.startswith("retr-")


@pytest.mark.asyncio
async def test_live_runner_reports_categories_unanswerable_errors_and_stable_order() -> None:
    answerable = live_case(
        "mysql-simple-997",
        "answerable",
        answerable=True,
        category="simple_fact",
    )
    unanswerable = live_case(
        "mysql-unanswerable-998",
        "unanswerable",
        answerable=False,
        category="unanswerable",
    )
    client = RoutingRetrievalClient(
        {
            "answerable": response(
                1,
                [citation("2:3:1", "2"), citation("2:3:1", "2")],
            ),
            "unanswerable": response(2, [citation("9:10:1", "9")]),
        }
    )

    report = await evaluate_live_cases(
        [answerable, unanswerable],
        client,
        run_config(max_concurrency=1),
    )

    assert [case.case_id for case in report.cases] == [
        "mysql-simple-997",
        "mysql-unanswerable-998",
    ]
    assert client.max_active == 1
    assert report.metrics.duplicate_rate == 0.25
    assert report.metrics.unanswerable_false_positive_rate == 1
    assert report.category_metrics["simple_fact"].recall_at_10 == 1
    assert report.category_metrics["unanswerable"].recall_at_10 is None


@pytest.mark.local_eval_assets(
    "datasets/mysql_live_v1.manifest.json",
    "datasets/mysql_live_v1.dev.jsonl",
    "datasets/mysql_live_v1.test.jsonl",
    "manifests/mysql_kb_v1.json",
    "protocols/multi_query_v1.json",
)
@pytest.mark.asyncio
async def test_live_dataset_run_uses_frozen_manifests_and_protocol_controls() -> None:
    report = await evaluate_live_dataset(
        dataset_path=PROJECT_ROOT / "evals/datasets/mysql_live_v1.dev.jsonl",
        dataset_manifest_path=PROJECT_ROOT / "evals/datasets/mysql_live_v1.manifest.json",
        knowledge_manifest_path=PROJECT_ROOT / "evals/manifests/mysql_kb_v1.json",
        protocol_path=PROJECT_ROOT / "evals/protocols/multi_query_v1.json",
        client=EmptyRetrievalClient(),
        user_id=14787164048670,
        git_commit="abc1234",
    )

    assert report.case_count == 85
    assert report.dataset_split == "development"
    assert report.dataset_sha256 == (
        "3d7b40b005468447839b76351fc06f602dfc62049fe8605b7a81116bbce1c190"
    )
    assert report.top_k == 10
    assert report.candidate_k == 30
    assert report.max_concurrency == 4
    assert set(report.category_metrics) == {
        "simple_fact",
        "alias_or_colloquial",
        "cause_and_principle",
        "comparison",
        "compound",
        "multi_hop",
        "exact_identifier",
        "multi_turn_coreference",
        "unanswerable",
    }
    assert report.dataset_manifest_sha256 is not None
    assert report.protocol_sha256 is not None
    assert report.knowledge_base_manifest_sha256 is not None
    assert report.embedding_model == "embedding-3"


@pytest.mark.asyncio
async def test_live_runner_preserves_partial_results_without_scoring_dependency_errors() -> None:
    successful = live_case(
        "mysql-simple-995",
        "success",
        answerable=True,
        category="simple_fact",
    )
    failed = live_case(
        "mysql-simple-996",
        "outage",
        answerable=True,
        category="simple_fact",
    )

    report = await evaluate_live_cases(
        [successful, failed],
        PartiallyFailingRetrievalClient(),
        run_config(),
    )

    assert report.metrics.successful_case_count == 1
    assert report.metrics.error_count == 1
    assert report.metrics.recall_at_10 == 1
    assert report.cases[1].metrics is None
    assert report.cases[1].error is not None
    assert report.cases[1].error.error_type == "RuntimeError"


@pytest.mark.asyncio
async def test_live_report_writes_auditable_json_and_markdown(tmp_path: Path) -> None:
    case = live_case(
        "mysql-simple-994",
        "report",
        answerable=True,
        category="simple_fact",
    )
    report = await evaluate_live_cases(
        [case],
        RecordingRetrievalClient(response(1, [citation("2:3:1", "2")])),
        run_config(),
    )
    json_path = tmp_path / "report.json"
    markdown_path = tmp_path / "report.md"

    markdown = render_live_retrieval_markdown(report)
    write_live_retrieval_report(report, json_path, markdown_path)

    serialized = json.loads(json_path.read_text(encoding="utf-8"))
    assert serialized["experimentId"] == "phase2-test"
    assert serialized["cases"][0]["finalRanking"][0]["chunkId"] == "2:3:1"
    assert markdown_path.read_text(encoding="utf-8") == markdown
    assert "# Live Retrieval Evaluation" in markdown
    assert "Recall@10" in markdown
    assert "mysql-simple-994" in markdown


@pytest.mark.asyncio
async def test_live_runner_uses_single_rewrite_planner_and_records_planning() -> None:
    case = LiveEvalCase.model_validate(
        {
            "caseId": "mysql-coreference-993",
            "category": "multi_turn_coreference",
            "difficulty": "medium",
            "question": "它为什么会失效？",
            "conversationContext": "上轮讨论的是联合索引。",
            "expectedDocumentIds": [2],
            "expectedChunkIds": ["2:3:1"],
            "requiredFacts": ["联合索引失效原因"],
            "forbiddenClaims": [],
            "answerable": True,
        }
    )
    model = RecordingRewriteModel(
        QueryRewrite(query="联合索引失效原因", mode=DomainRetrievalMode.HYBRID)
    )
    client = RecordingRetrievalClient(response(1, [citation("2:3:1", "2")]))

    report = await evaluate_live_cases(
        [case],
        client,
        run_config(strategy=RetrievalStrategy.SINGLE_REWRITE),
        planner=SingleRewritePlanner(model),
    )

    assert "上轮讨论的是联合索引" in model.questions[0]
    assert client.requests[0].query == "联合索引失效原因"
    assert report.cases[0].planning.strategy is RetrievalStrategy.SINGLE_REWRITE
    assert report.cases[0].planning.planner_version == "single-rewrite.v1"
    assert report.cases[0].planning.model_calls == 1
    assert report.metrics.planner_model_calls == 1


@pytest.mark.asyncio
async def test_live_runner_executes_and_fuses_multi_query_plan() -> None:
    question = "B 树和 B+ 树有什么区别？"
    case = live_case(
        "mysql-comparison-991",
        question,
        answerable=True,
        category="comparison",
    )
    client = RoutingRetrievalClient(
        {
            question: response(
                31,
                [citation("9:10:1", "9"), citation("2:3:1", "2")],
            ),
            "B树 B+树 数据存放 范围查询 区别": response(
                32,
                [citation("2:3:1", "2")],
            ),
        }
    )

    report = await evaluate_live_cases(
        [case],
        client,
        run_config(strategy=RetrievalStrategy.MULTI_QUERY),
        planner=StaticLiveMultiPlanner(),
    )

    result = report.cases[0]
    assert client.max_active == 2
    assert [execution.query_id for execution in result.query_executions] == [31, 32]
    assert [execution.planned_query_id for execution in result.query_executions] == [
        "q-original",
        "q-semantic",
    ]
    assert [item.chunk_id for item in result.final_ranking] == ["2:3:1", "9:10:1"]
    assert result.metrics is not None
    assert result.metrics.recall_at_10 == 1


@pytest.mark.asyncio
async def test_live_runner_keeps_successful_multi_query_routes() -> None:
    question = "B 树和 B+ 树有什么区别？"
    case = live_case(
        "mysql-comparison-990",
        question,
        answerable=True,
        category="comparison",
    )
    semantic = "B树 B+树 数据存放 范围查询 区别"
    client = PartiallyFailingMultiClient(
        responses={semantic: response(32, [citation("2:3:1", "2")])},
        failing_query=question,
    )

    report = await evaluate_live_cases(
        [case],
        client,
        run_config(strategy=RetrievalStrategy.MULTI_QUERY),
        planner=StaticLiveMultiPlanner(),
    )

    result = report.cases[0]
    assert result.error is None
    assert [execution.query_id for execution in result.query_executions] == [32]
    assert result.query_executions[0].query_order == 2
    assert [item.chunk_id for item in result.final_ranking] == ["2:3:1"]


@pytest.mark.asyncio
async def test_comparison_report_pairs_raw_and_single_rewrite_cases(tmp_path: Path) -> None:
    case = live_case(
        "mysql-simple-992",
        "它为什么会失效？",
        answerable=True,
        category="simple_fact",
    )
    raw = await evaluate_live_cases(
        [case],
        RecordingRetrievalClient(response(1, [citation("9:10:1", "9")])),
        run_config(strategy=RetrievalStrategy.RAW_QUERY),
    )
    model = RecordingRewriteModel(
        QueryRewrite(query="联合索引失效原因", mode=DomainRetrievalMode.HYBRID)
    )
    single = await evaluate_live_cases(
        [case],
        RecordingRetrievalClient(response(2, [citation("2:3:1", "2")])),
        run_config(strategy=RetrievalStrategy.SINGLE_REWRITE),
        planner=SingleRewritePlanner(model),
    )

    comparison = compare_retrieval_reports([raw, single])
    markdown = render_retrieval_comparison_markdown(comparison)
    json_path = tmp_path / "comparison.json"
    markdown_path = tmp_path / "comparison.md"
    write_retrieval_comparison(comparison, json_path, markdown_path)

    assert comparison.metric_deltas["recall_at_10"].absolute_delta == 1
    assert comparison.cases[0].query_changed is True
    assert comparison.cases[0].recall_at_10_delta == 1
    assert "SINGLE_REWRITE" in markdown
    assert json.loads(json_path.read_text(encoding="utf-8"))["caseCount"] == 1
    assert markdown_path.read_text(encoding="utf-8") == markdown


def test_phase_three_report_loader_remains_compatible_with_phase_two_report(
    tmp_path: Path,
    retrieval_reports: dict[RetrievalStrategy, LiveRetrievalReport],
) -> None:
    payload = retrieval_reports[RetrievalStrategy.RAW_QUERY].model_dump(mode="json", by_alias=True)
    payload["schemaVersion"] = "1.0"
    payload.pop("maxContextChars")
    payload["metrics"].pop("plannerModelCalls")
    for case in payload["cases"]:
        case.pop("planning")
        case.pop("routing")
    path = tmp_path / "synthetic-phase-two.json"
    path.write_text(json.dumps(payload), encoding="utf-8")
    report = load_live_retrieval_report(path)

    assert report.schema_version == "1.0"
    assert report.max_context_chars == 12000
    assert report.metrics.planner_model_calls == 0
    assert report.cases[0].planning is None
