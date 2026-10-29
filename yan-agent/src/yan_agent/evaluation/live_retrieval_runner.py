import asyncio
import hashlib
import math
import subprocess
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Literal, Protocol, cast

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

from yan_agent.adapters.java_knowledge.contracts import (
    InternalRetrievalRequest,
    InternalRetrievalResponse,
    KnowledgeCitation,
    RetrievalMode,
)
from yan_agent.application.retrieval_planning import (
    RawQueryPlanner,
    render_contextual_question,
)
from yan_agent.domain.qa import KnowledgeCitation as DomainKnowledgeCitation
from yan_agent.domain.qa import RetrievalMode as DomainRetrievalMode
from yan_agent.domain.retrieval_fusion import QueryRetrieval, fuse_query_retrievals
from yan_agent.domain.retrieval_planning import (
    PlannedQuery,
    QueryPurpose,
    RetrievalPlanningRequest,
    RetrievalPlanningResult,
)
from yan_agent.domain.retrieval_routing import (
    RequestedRetrievalStrategy,
    RetrievalRoutingDecision,
    RoutingReason,
)
from yan_agent.evaluation.live_models import (
    LiveEvalCase,
    file_sha256,
    load_live_dataset_manifest,
    validate_dataset_bundle,
)
from yan_agent.evaluation.protocol import (
    RetrievalStrategy,
    load_manifest,
    load_protocol,
)


class LiveReportModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
    )


EvaluationStrategy = RetrievalStrategy | Literal["ADAPTIVE"]


class LiveRetrievalRunConfig(LiveReportModel):
    experiment_id: str = Field(min_length=1, max_length=160)
    dataset_id: str = Field(min_length=1)
    dataset_split: Literal["development", "test"]
    dataset_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    protocol_id: str = Field(min_length=1)
    knowledge_base_manifest_id: str = Field(min_length=1)
    dataset_manifest_sha256: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    protocol_sha256: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    knowledge_base_manifest_sha256: str | None = Field(
        default=None,
        pattern=r"^[0-9a-f]{64}$",
    )
    git_commit: str = Field(min_length=1)
    strategy: EvaluationStrategy
    user_id: int = Field(gt=0)
    knowledge_base_id: int = Field(gt=0)
    requested_mode: RetrievalMode = RetrievalMode.AUTO
    top_k: int = Field(default=10, ge=1, le=50)
    candidate_k: int = Field(default=30, ge=1, le=200)
    max_concurrency: int = Field(default=4, ge=1, le=16)
    max_context_chars: int = Field(default=12000, ge=100, le=200000)
    retrieval_config_version: str = Field(min_length=1)
    graph_version: str = "knowledge-qa.v3"
    runner_version: str = "live-retrieval.v1"
    embedding_model: str | None = None
    planner_model: str | None = None
    answer_model: str | None = None
    planner_prompt_version: str | None = None
    answer_prompt_version: str | None = None
    cache_mode: Literal["DISABLED"] = "DISABLED"

    @model_validator(mode="after")
    def validate_controls(self) -> "LiveRetrievalRunConfig":
        if self.candidate_k < self.top_k:
            raise ValueError("candidateK must be greater than or equal to topK")
        return self


class RankedCitation(LiveReportModel):
    rank: int = Field(gt=0)
    chunk_id: str
    document_id: str
    document_version_id: str
    title: str
    section: str
    source: str
    vector_similarity: float | None
    keyword_score: float | None
    rrf_score: float | None
    final_score: float | None
    exact_match: bool
    exact_type: str | None
    relevant: bool


class QueryExecution(LiveReportModel):
    query_order: int = Field(gt=0)
    planned_query_id: str | None = None
    purpose: QueryPurpose | None = None
    weight: float | None = Field(default=None, gt=0, le=1)
    query_text: str
    run_id: str
    retrieval_request_id: str
    requested_mode: RetrievalMode
    query_id: int = Field(gt=0)
    selected_mode: RetrievalMode
    degraded: bool
    degradation_reasons: list[str]
    total_elapsed_millis: int = Field(ge=0)
    trace_id: str


class PlanningExecution(LiveReportModel):
    strategy: RetrievalStrategy
    planner_version: str
    prompt_version: str | None
    query_text: str
    requested_mode: str
    model_calls: int = Field(ge=0)
    conversation_context_present: bool
    latency_ms: float = Field(ge=0)


class RoutingExecution(LiveReportModel):
    requested_strategy: RequestedRetrievalStrategy
    selected_strategy: RetrievalStrategy
    effective_strategy: RetrievalStrategy
    routing_reasons: list[RoutingReason]
    routing_version: str
    rollout_eligible: bool
    fallback_from: RetrievalStrategy | None
    fallback_reason: RoutingReason | None


class CaseRetrievalMetrics(LiveReportModel):
    hit_at_5: float | None
    hit_at_10: float | None
    recall_at_5: float | None
    recall_at_10: float | None
    reciprocal_rank_at_10: float | None
    ndcg_at_10: float | None
    context_precision_at_10: float | None
    duplicate_rate: float
    unanswerable_false_positive: float | None


class AggregateRetrievalMetrics(LiveReportModel):
    case_count: int = Field(ge=0)
    successful_case_count: int = Field(ge=0)
    error_count: int = Field(ge=0)
    answerable_count: int = Field(ge=0)
    unanswerable_count: int = Field(ge=0)
    hit_at_5: float | None
    hit_at_10: float | None
    recall_at_5: float | None
    recall_at_10: float | None
    reciprocal_rank_at_10: float | None
    ndcg_at_10: float | None
    context_precision_at_10: float | None
    duplicate_rate: float
    unanswerable_false_positive_rate: float | None
    degradation_rate: float
    planner_model_calls: int = Field(default=0, ge=0)
    planner_latency_p50_ms: float = 0.0
    planner_latency_p95_ms: float = 0.0
    latency_p50_ms: float
    latency_p95_ms: float


class LiveCaseRetrievalResult(LiveReportModel):
    case_id: str
    category: str
    difficulty: str
    question: str
    answerable: bool
    expected_document_ids: list[int]
    expected_chunk_ids: list[str]
    planning: PlanningExecution | None = None
    routing: RoutingExecution | None = None
    query_executions: list[QueryExecution]
    final_ranking: list[RankedCitation]
    metrics: CaseRetrievalMetrics | None
    error: "CaseExecutionError | None"
    latency_ms: float = Field(ge=0)


class CaseExecutionError(LiveReportModel):
    stage: Literal["PLANNING", "RETRIEVAL"] = "RETRIEVAL"
    error_type: str = Field(min_length=1, max_length=200)
    message: str = Field(min_length=1, max_length=500)


class LiveRetrievalReport(LiveReportModel):
    schema_version: str = "1.1"
    experiment_id: str
    generated_at: datetime
    dataset_id: str
    dataset_split: str
    dataset_sha256: str
    protocol_id: str
    knowledge_base_manifest_id: str
    dataset_manifest_sha256: str | None
    protocol_sha256: str | None
    knowledge_base_manifest_sha256: str | None
    git_commit: str
    strategy: EvaluationStrategy
    retrieval_config_version: str
    graph_version: str
    runner_version: str
    embedding_model: str | None
    planner_model: str | None
    answer_model: str | None
    planner_prompt_version: str | None
    answer_prompt_version: str | None
    requested_mode: RetrievalMode
    top_k: int
    candidate_k: int
    max_concurrency: int
    max_context_chars: int = 12000
    cache_mode: str
    case_count: int
    metrics: AggregateRetrievalMetrics
    category_metrics: dict[str, AggregateRetrievalMetrics]
    cases: list[LiveCaseRetrievalResult]


class LiveRetrievalClient(Protocol):
    async def retrieve(self, request: InternalRetrievalRequest) -> InternalRetrievalResponse: ...


class LiveEvaluationPlanner(Protocol):
    @property
    def strategy(self) -> object: ...

    @property
    def model_calls_per_plan(self) -> int: ...

    async def plan(self, request: RetrievalPlanningRequest) -> RetrievalPlanningResult: ...


async def evaluate_live_cases(
    cases: list[LiveEvalCase],
    client: LiveRetrievalClient,
    config: LiveRetrievalRunConfig,
    planner: LiveEvaluationPlanner | None = None,
) -> LiveRetrievalReport:
    if not cases:
        raise ValueError("live retrieval evaluation cases must not be empty")
    resolved_planner = _resolve_planner(config, planner)
    semaphore = asyncio.Semaphore(config.max_concurrency)

    async def evaluate(index: int, case: LiveEvalCase) -> LiveCaseRetrievalResult:
        async with semaphore:
            return await _evaluate_case(index, case, client, resolved_planner, config)

    results = await asyncio.gather(
        *(evaluate(index, case) for index, case in enumerate(cases, start=1))
    )
    category_metrics = {
        category: _aggregate([result for result in results if result.category == category])
        for category in sorted({result.category for result in results})
    }
    return LiveRetrievalReport(
        experiment_id=config.experiment_id,
        generated_at=datetime.now(UTC),
        dataset_id=config.dataset_id,
        dataset_split=config.dataset_split,
        dataset_sha256=config.dataset_sha256,
        protocol_id=config.protocol_id,
        knowledge_base_manifest_id=config.knowledge_base_manifest_id,
        dataset_manifest_sha256=config.dataset_manifest_sha256,
        protocol_sha256=config.protocol_sha256,
        knowledge_base_manifest_sha256=config.knowledge_base_manifest_sha256,
        git_commit=config.git_commit,
        strategy=config.strategy,
        retrieval_config_version=config.retrieval_config_version,
        graph_version=config.graph_version,
        runner_version=config.runner_version,
        embedding_model=config.embedding_model,
        planner_model=config.planner_model,
        answer_model=config.answer_model,
        planner_prompt_version=config.planner_prompt_version,
        answer_prompt_version=config.answer_prompt_version,
        requested_mode=config.requested_mode,
        top_k=config.top_k,
        candidate_k=config.candidate_k,
        max_concurrency=config.max_concurrency,
        max_context_chars=config.max_context_chars,
        cache_mode=config.cache_mode,
        case_count=len(results),
        metrics=_aggregate(results),
        category_metrics=category_metrics,
        cases=results,
    )


async def evaluate_live_dataset(
    dataset_path: Path,
    dataset_manifest_path: Path,
    knowledge_manifest_path: Path,
    protocol_path: Path,
    client: LiveRetrievalClient,
    *,
    user_id: int,
    strategy: EvaluationStrategy = RetrievalStrategy.RAW_QUERY,
    requested_mode: RetrievalMode = RetrievalMode.AUTO,
    top_k: int | None = None,
    candidate_k: int | None = None,
    max_concurrency: int | None = None,
    max_context_chars: int = 12000,
    experiment_id: str | None = None,
    git_commit: str | None = None,
    planner: LiveEvaluationPlanner | None = None,
) -> LiveRetrievalReport:
    development, test = validate_dataset_bundle(
        dataset_manifest_path,
        knowledge_manifest_path,
    )
    dataset_manifest = load_live_dataset_manifest(dataset_manifest_path)
    knowledge_manifest = load_manifest(knowledge_manifest_path)
    protocol = load_protocol(protocol_path)
    if dataset_manifest.protocol_id != protocol.protocol_id:
        raise ValueError("dataset and experiment protocol identifiers do not match")
    if protocol.knowledge_base_manifest_id != knowledge_manifest.manifest_id:
        raise ValueError("protocol and knowledge base manifest identifiers do not match")
    if dataset_manifest.knowledge_base_manifest_id != knowledge_manifest.manifest_id:
        raise ValueError("dataset and knowledge base manifest identifiers do not match")
    if protocol.dataset.development_split != dataset_manifest.development.path:
        raise ValueError("protocol development split does not match the dataset manifest")
    if protocol.dataset.test_split != dataset_manifest.test.path:
        raise ValueError("protocol test split does not match the dataset manifest")

    resolved_dataset, development_path, test_path = await asyncio.to_thread(
        _resolve_dataset_paths,
        dataset_path,
        dataset_manifest_path,
        dataset_manifest.development.path,
        dataset_manifest.test.path,
    )
    split: Literal["development", "test"]
    if resolved_dataset == development_path:
        split = "development"
        cases = development
        dataset_identity = dataset_manifest.development
    elif resolved_dataset == test_path:
        split = "test"
        cases = test
        dataset_identity = dataset_manifest.test
    else:
        raise ValueError("dataset path is not a frozen split in the dataset manifest")

    resolved_git_commit = git_commit or _resolve_git_commit(dataset_path)
    started_at = datetime.now(UTC)
    resolved_experiment_id = experiment_id or (
        f"mq-{protocol.protocol_id}-{knowledge_manifest.manifest_id}-"
        f"{_strategy_value(strategy).lower()}-{started_at:%Y%m%dT%H%M%SZ}-"
        f"{resolved_git_commit[:12]}"
    )
    config = LiveRetrievalRunConfig(
        experiment_id=resolved_experiment_id,
        dataset_id=dataset_manifest.dataset_id,
        dataset_split=split,
        dataset_sha256=dataset_identity.sha256,
        dataset_manifest_sha256=file_sha256(dataset_manifest_path),
        protocol_id=protocol.protocol_id,
        protocol_sha256=file_sha256(protocol_path),
        knowledge_base_manifest_id=knowledge_manifest.manifest_id,
        knowledge_base_manifest_sha256=file_sha256(knowledge_manifest_path),
        git_commit=resolved_git_commit,
        strategy=strategy,
        user_id=user_id,
        knowledge_base_id=knowledge_manifest.knowledge_base_id,
        requested_mode=requested_mode,
        top_k=protocol.retrieval.top_k if top_k is None else top_k,
        candidate_k=(protocol.retrieval.candidate_k if candidate_k is None else candidate_k),
        max_concurrency=(
            protocol.retrieval.max_concurrency if max_concurrency is None else max_concurrency
        ),
        max_context_chars=max_context_chars,
        retrieval_config_version=protocol.models.retrieval_config_version,
        embedding_model=knowledge_manifest.index_identity.embedding_model,
        planner_model=protocol.models.planner_model,
        answer_model=protocol.models.answer_model,
        planner_prompt_version=getattr(
            planner,
            "prompt_version",
            (
                None
                if strategy is RetrievalStrategy.RAW_QUERY
                else protocol.models.planner_prompt_version
            ),
        ),
        answer_prompt_version=protocol.models.answer_prompt_version,
    )
    return await evaluate_live_cases(cases, client, config, planner=planner)


async def _evaluate_case(
    index: int,
    case: LiveEvalCase,
    client: LiveRetrievalClient,
    planner: LiveEvaluationPlanner,
    config: LiveRetrievalRunConfig,
) -> LiveCaseRetrievalResult:
    identity = hashlib.sha256(
        f"{config.experiment_id}:{case.case_id}:{index}".encode()
    ).hexdigest()[:24]
    started_at = time.perf_counter()
    planning: PlanningExecution | None = None
    stage: Literal["PLANNING", "RETRIEVAL"] = "PLANNING"
    try:
        planning_started_at = time.perf_counter()
        planning_request = RetrievalPlanningRequest(
            original_question=case.question,
            model_input=render_contextual_question(
                case.question,
                case.conversation_context,
                config.max_context_chars,
            ),
            requested_mode=DomainRetrievalMode(config.requested_mode.value),
            conversation_context_present=case.conversation_context is not None,
            knowledge_base_id=config.knowledge_base_id,
        )
        planning_result = await planner.plan(planning_request)
        planning = _planning_execution(
            planning_result,
            case,
            (time.perf_counter() - planning_started_at) * 1000,
        )
        stage = "RETRIEVAL"
        requests = _build_retrieval_requests(
            planning_result,
            identity,
            config,
        )
        outcomes = await asyncio.gather(
            *(client.retrieve(request) for request in requests),
            return_exceptions=True,
        )
        successful_pairs = [
            (request, outcome)
            for request, outcome in zip(requests, outcomes, strict=True)
            if isinstance(outcome, InternalRetrievalResponse)
        ]
        if not successful_pairs:
            first_error = next(
                outcome for outcome in outcomes if isinstance(outcome, BaseException)
            )
            raise first_error
        requests = [request for request, _response in successful_pairs]
        responses = [response for _request, response in successful_pairs]
        fallback_method = getattr(planner, "fallback_fast_path", None)
        routing = getattr(planning_result, "routing_decision", None)
        if (
            callable(fallback_method)
            and isinstance(routing, RetrievalRoutingDecision)
            and routing.effective_strategy is RetrievalStrategy.RAW_QUERY
            and any(response.degraded or not response.citations for response in responses)
        ):
            planning_started_at = time.perf_counter()
            planning_result = await fallback_method(planning_request)
            planning = _planning_execution(
                planning_result,
                case,
                (time.perf_counter() - planning_started_at) * 1000,
            )
            requests = _build_retrieval_requests(planning_result, identity + "-fallback", config)
            responses = await asyncio.gather(*(client.retrieve(request) for request in requests))
    except Exception as error:  # noqa: BLE001 - each failed evaluation case must be reported
        latency_ms = (time.perf_counter() - started_at) * 1000
        return LiveCaseRetrievalResult(
            case_id=case.case_id,
            category=case.category.value,
            difficulty=case.difficulty.value,
            question=case.question,
            answerable=case.answerable,
            expected_document_ids=case.expected_document_ids,
            expected_chunk_ids=case.expected_chunk_ids,
            planning=planning,
            routing=None,
            query_executions=[],
            final_ranking=[],
            metrics=None,
            error=CaseExecutionError(
                stage=stage,
                error_type=type(error).__name__,
                message=str(error)[:500] or "retrieval failed without an error message",
            ),
            latency_ms=latency_ms,
        )
    latency_ms = (time.perf_counter() - started_at) * 1000
    relevant = set(case.expected_chunk_ids)
    planned_query_by_text = {
        query.text: (order, query)
        for order, query in enumerate(
            planning_result.plan.queries if planning_result.plan is not None else [],
            start=1,
        )
    }
    query_executions = [
        _query_execution(
            planned_query_by_text.get(request.query, (fallback_order, None))[0],
            request,
            response,
            planned_query_by_text.get(request.query, (fallback_order, None))[1],
        )
        for fallback_order, (request, response) in enumerate(
            zip(requests, responses, strict=True), start=1
        )
    ]
    citations = _fuse_live_responses(
        planning_result,
        requests,
        responses,
        config.top_k,
    )
    ranking = [
        RankedCitation(
            rank=rank,
            chunk_id=citation.chunk_id,
            document_id=citation.document_id,
            document_version_id=citation.document_version_id,
            title=citation.title,
            section=citation.section,
            source=citation.source,
            vector_similarity=citation.vector_similarity,
            keyword_score=citation.keyword_score,
            rrf_score=citation.rrf_score,
            final_score=citation.final_score,
            exact_match=citation.exact_match,
            exact_type=citation.exact_type.value if citation.exact_type is not None else None,
            relevant=citation.chunk_id in relevant,
        )
        for rank, citation in enumerate(citations, start=1)
    ]
    return LiveCaseRetrievalResult(
        case_id=case.case_id,
        category=case.category.value,
        difficulty=case.difficulty.value,
        question=case.question,
        answerable=case.answerable,
        expected_document_ids=case.expected_document_ids,
        expected_chunk_ids=case.expected_chunk_ids,
        planning=planning,
        routing=_routing_execution(planning_result),
        query_executions=query_executions,
        final_ranking=ranking,
        metrics=_score_case(case, ranking),
        error=None,
        latency_ms=latency_ms,
    )


def _build_retrieval_requests(
    planning: RetrievalPlanningResult,
    identity: str,
    config: LiveRetrievalRunConfig,
) -> list[InternalRetrievalRequest]:
    if planning.strategy is RetrievalStrategy.MULTI_QUERY:
        if planning.plan is None:
            raise ValueError("MULTI_QUERY planning result has no plan")
        planned_queries = planning.plan.queries
    else:
        planned_queries = []
    if not planned_queries:
        return [
            InternalRetrievalRequest(
                retrieval_request_id=f"retr-{identity}",
                run_id=f"live-{identity}",
                user_id=config.user_id,
                knowledge_base_id=config.knowledge_base_id,
                query=planning.query.query,
                requested_mode=RetrievalMode(planning.query.mode.value),
                top_k=config.top_k,
                candidate_k=config.candidate_k,
            )
        ]
    return [
        InternalRetrievalRequest(
            retrieval_request_id=f"retr-{identity}-{query.query_id}",
            run_id=f"live-{identity}",
            user_id=config.user_id,
            knowledge_base_id=config.knowledge_base_id,
            query=query.text,
            requested_mode=RetrievalMode(query.mode.value),
            top_k=config.top_k,
            candidate_k=config.candidate_k,
        )
        for query in planned_queries
    ]


def _query_execution(
    order: int,
    request: InternalRetrievalRequest,
    response: InternalRetrievalResponse,
    planned_query: PlannedQuery | None,
) -> QueryExecution:
    return QueryExecution(
        query_order=order,
        planned_query_id=(planned_query.query_id if planned_query is not None else None),
        purpose=(planned_query.purpose if planned_query is not None else None),
        weight=(planned_query.weight if planned_query is not None else None),
        query_text=request.query,
        run_id=request.run_id,
        retrieval_request_id=request.retrieval_request_id,
        requested_mode=response.requested_mode,
        query_id=response.query_id,
        selected_mode=response.selected_mode,
        degraded=response.degraded,
        degradation_reasons=[reason.value for reason in response.degradation_reasons],
        total_elapsed_millis=response.total_elapsed_millis,
        trace_id=response.trace_id,
    )


def _fuse_live_responses(
    planning: RetrievalPlanningResult,
    requests: list[InternalRetrievalRequest],
    responses: list[InternalRetrievalResponse],
    top_k: int,
) -> list[KnowledgeCitation]:
    if planning.strategy is not RetrievalStrategy.MULTI_QUERY:
        return list(responses[0].citations[:top_k])
    if planning.plan is None:
        raise ValueError("MULTI_QUERY planning result has no plan")
    query_by_text = {
        query.text: (order, query) for order, query in enumerate(planning.plan.queries)
    }
    retrievals = [
        QueryRetrieval(
            planned_query_id=query.query_id,
            query_order=planned_order,
            query_text=query.text,
            purpose=query.purpose,
            weight=query.weight,
            java_query_id=response.query_id,
            selected_mode=DomainRetrievalMode(response.selected_mode.value),
            citations=[
                DomainKnowledgeCitation(
                    index=index,
                    chunk_id=citation.chunk_id,
                    document_id=citation.document_id,
                    document_version_id=citation.document_version_id,
                    title=citation.title,
                    section=citation.section,
                    source=citation.source,
                    content=citation.content,
                    final_score=citation.final_score,
                )
                for index, citation in enumerate(response.citations, start=1)
            ],
            degraded=response.degraded,
            degradation_reasons=[reason.value for reason in response.degradation_reasons],
            elapsed_millis=response.total_elapsed_millis,
            target_sub_question_ids=query.target_sub_question_ids,
        )
        for request, response in zip(requests, responses, strict=True)
        for planned_order, query in [query_by_text[request.query]]
    ]
    fused = fuse_query_retrievals(
        retrievals,
        rank_constant=20,
        limit=top_k,
        max_chunks_per_document=4,
        minimum_per_subquestion=2,
    )
    by_chunk = {
        citation.chunk_id: citation for response in responses for citation in response.citations
    }
    return [by_chunk[item.citation.chunk_id] for item in fused]


def _score_case(
    case: LiveEvalCase,
    ranking: list[RankedCitation],
) -> CaseRetrievalMetrics:
    chunk_ids = [citation.chunk_id for citation in ranking]
    duplicate_rate = 1 - len(set(chunk_ids)) / len(chunk_ids) if chunk_ids else 0.0
    if not case.answerable:
        return CaseRetrievalMetrics(
            hit_at_5=None,
            hit_at_10=None,
            recall_at_5=None,
            recall_at_10=None,
            reciprocal_rank_at_10=None,
            ndcg_at_10=None,
            context_precision_at_10=None,
            duplicate_rate=duplicate_rate,
            unanswerable_false_positive=float(bool(ranking)),
        )
    relevant = set(case.expected_chunk_ids)
    ranks = [citation.rank for citation in ranking if citation.relevant]
    return CaseRetrievalMetrics(
        hit_at_5=float(any(rank <= 5 for rank in ranks)),
        hit_at_10=float(any(rank <= 10 for rank in ranks)),
        recall_at_5=_recall_at(ranking, relevant, 5),
        recall_at_10=_recall_at(ranking, relevant, 10),
        reciprocal_rank_at_10=next((1 / rank for rank in ranks if rank <= 10), 0.0),
        ndcg_at_10=_ndcg_at(ranking, len(relevant), 10),
        context_precision_at_10=(
            sum(citation.relevant for citation in ranking[:10]) / len(ranking[:10])
            if ranking[:10]
            else 0.0
        ),
        duplicate_rate=duplicate_rate,
        unanswerable_false_positive=None,
    )


def _planning_execution(
    result: RetrievalPlanningResult,
    case: LiveEvalCase,
    latency_ms: float,
) -> PlanningExecution:
    return PlanningExecution(
        strategy=result.strategy,
        planner_version=result.planner_version,
        prompt_version=result.prompt_version,
        query_text=result.query.query,
        requested_mode=result.query.mode.value,
        model_calls=result.model_calls,
        conversation_context_present=case.conversation_context is not None,
        latency_ms=latency_ms,
    )


def _routing_execution(result: RetrievalPlanningResult) -> RoutingExecution | None:
    decision = getattr(result, "routing_decision", None)
    if not isinstance(decision, RetrievalRoutingDecision):
        return None
    return RoutingExecution(
        requested_strategy=decision.requested_strategy,
        selected_strategy=decision.selected_strategy,
        effective_strategy=decision.effective_strategy,
        routing_reasons=decision.routing_reasons,
        routing_version=decision.routing_version,
        rollout_eligible=decision.rollout_eligible,
        fallback_from=decision.fallback_from,
        fallback_reason=decision.fallback_reason,
    )


def _recall_at(
    ranking: list[RankedCitation],
    relevant: set[str],
    limit: int,
) -> float:
    hits = {citation.chunk_id for citation in ranking[:limit] if citation.chunk_id in relevant}
    return len(hits) / len(relevant)


def _ndcg_at(ranking: list[RankedCitation], relevant_count: int, limit: int) -> float:
    dcg = sum(1 / math.log2(citation.rank + 1) for citation in ranking[:limit] if citation.relevant)
    ideal_count = min(relevant_count, limit)
    ideal = sum(1 / math.log2(rank + 1) for rank in range(1, ideal_count + 1))
    return dcg / ideal if ideal else 0.0


def _aggregate(results: list[LiveCaseRetrievalResult]) -> AggregateRetrievalMetrics:
    successful = [result for result in results if result.metrics is not None]
    scored = [(result, cast(CaseRetrievalMetrics, result.metrics)) for result in successful]
    answerable = [metrics for result, metrics in scored if result.answerable]
    unanswerable = [metrics for result, metrics in scored if not result.answerable]
    latencies = sorted(result.latency_ms for result in results)
    planning = [result.planning for result in results if result.planning is not None]
    planning_latencies = sorted(item.latency_ms for item in planning)
    return AggregateRetrievalMetrics(
        case_count=len(results),
        successful_case_count=len(successful),
        error_count=len(results) - len(successful),
        answerable_count=len(answerable),
        unanswerable_count=len(unanswerable),
        hit_at_5=_optional_mean([metric.hit_at_5 for metric in answerable]),
        hit_at_10=_optional_mean([metric.hit_at_10 for metric in answerable]),
        recall_at_5=_optional_mean([metric.recall_at_5 for metric in answerable]),
        recall_at_10=_optional_mean([metric.recall_at_10 for metric in answerable]),
        reciprocal_rank_at_10=_optional_mean(
            [metric.reciprocal_rank_at_10 for metric in answerable]
        ),
        ndcg_at_10=_optional_mean([metric.ndcg_at_10 for metric in answerable]),
        context_precision_at_10=_optional_mean(
            [metric.context_precision_at_10 for metric in answerable]
        ),
        duplicate_rate=_mean([metrics.duplicate_rate for _result, metrics in scored]),
        unanswerable_false_positive_rate=_optional_mean(
            [metric.unanswerable_false_positive for metric in unanswerable]
        ),
        degradation_rate=_mean(
            [float(result.query_executions[0].degraded) for result in successful]
        ),
        planner_model_calls=sum(item.model_calls for item in planning),
        planner_latency_p50_ms=_percentile(planning_latencies, 0.50),
        planner_latency_p95_ms=_percentile(planning_latencies, 0.95),
        latency_p50_ms=_percentile(latencies, 0.50),
        latency_p95_ms=_percentile(latencies, 0.95),
    )


def _optional_mean(values: list[float | None]) -> float | None:
    present = [value for value in values if value is not None]
    return _mean(present) if present else None


def _mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def _percentile(values: list[float], quantile: float) -> float:
    if not values:
        return 0.0
    return values[max(0, math.ceil(len(values) * quantile) - 1)]


def _resolve_git_commit(start_path: Path) -> str:
    completed = subprocess.run(
        ["git", "rev-parse", "--short=12", "HEAD"],
        cwd=start_path.parent,
        check=False,
        capture_output=True,
        text=True,
    )
    commit = completed.stdout.strip()
    return commit if completed.returncode == 0 and commit else "unknown"


def _resolve_planner(
    config: LiveRetrievalRunConfig,
    planner: LiveEvaluationPlanner | None,
) -> LiveEvaluationPlanner:
    if planner is None:
        if config.strategy is RetrievalStrategy.RAW_QUERY:
            return RawQueryPlanner()
        raise ValueError("SINGLE_REWRITE evaluation requires an explicit planner")
    if planner.strategy != config.strategy:
        raise ValueError("planner strategy does not match the evaluation strategy")
    return planner


def _strategy_value(strategy: EvaluationStrategy) -> str:
    return strategy.value if isinstance(strategy, RetrievalStrategy) else strategy


def _resolve_dataset_paths(
    dataset_path: Path,
    dataset_manifest_path: Path,
    development_name: str,
    test_name: str,
) -> tuple[Path, Path, Path]:
    dataset_root = dataset_manifest_path.parent.resolve()
    return (
        dataset_path.resolve(),
        (dataset_root / development_name).resolve(),
        (dataset_root / test_name).resolve(),
    )
