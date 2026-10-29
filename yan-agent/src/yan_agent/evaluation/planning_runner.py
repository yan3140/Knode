import asyncio
import math
import time
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path

from pydantic import Field

from yan_agent.application.retrieval_planning import render_contextual_question
from yan_agent.domain.errors import AgentExecutionError
from yan_agent.domain.retrieval_planning import (
    RetrievalPlan,
    RetrievalPlanningRequest,
    RetrievalStrategy,
)
from yan_agent.evaluation.live_models import (
    LiveEvalCase,
    LiveEvalCategory,
    LiveEvalModel,
    file_sha256,
    load_live_cases,
)
from yan_agent.evaluation.protocol import load_manifest
from yan_agent.ports.retrieval_planning import RetrievalPlanner


class PlanningCaseResult(LiveEvalModel):
    case_id: str
    category: LiveEvalCategory
    success: bool
    latency_millis: float = Field(ge=0)
    context_present: bool
    plan: RetrievalPlan | None = None
    error_code: str | None = None
    error_message: str | None = None


class PlanningSmokeMetrics(LiveEvalModel):
    case_count: int = Field(ge=0)
    successful_case_count: int = Field(ge=0)
    error_count: int = Field(ge=0)
    category_counts: dict[LiveEvalCategory, int]
    average_query_count: float = Field(ge=0)
    average_sub_question_count: float = Field(ge=0)
    latency_p50_millis: float = Field(ge=0)
    latency_p95_millis: float = Field(ge=0)


class PlanningSmokeReport(LiveEvalModel):
    schema_version: str = "1.0"
    generated_at: datetime
    experiment_id: str
    dataset_sha256: str
    knowledge_base_manifest_id: str
    strategy: RetrievalStrategy
    planner_model: str
    planner_version: str
    prompt_version: str
    cache_mode: str = "DISABLED"
    metrics: PlanningSmokeMetrics
    results: list[PlanningCaseResult]


async def evaluate_planning_smoke(
    *,
    dataset_path: Path,
    knowledge_manifest_path: Path,
    planner: RetrievalPlanner,
    planner_model: str,
    planner_version: str,
    prompt_version: str,
    experiment_id: str,
    per_category: int = 1,
    max_concurrency: int = 4,
    max_context_chars: int = 12000,
) -> PlanningSmokeReport:
    if per_category <= 0:
        raise ValueError("per_category must be positive")
    if max_concurrency <= 0:
        raise ValueError("max_concurrency must be positive")
    cases = _select_cases(load_live_cases(dataset_path), per_category)
    manifest = load_manifest(knowledge_manifest_path)
    semaphore = asyncio.Semaphore(max_concurrency)

    async def evaluate_case(case: LiveEvalCase) -> PlanningCaseResult:
        model_input = render_contextual_question(
            case.question,
            case.conversation_context,
            max_context_chars,
        )
        request = RetrievalPlanningRequest(
            original_question=case.question,
            model_input=model_input,
            conversation_context_present=case.conversation_context is not None,
            knowledge_base_id=manifest.knowledge_base_id,
            knowledge_base_version=manifest.manifest_id,
        )
        async with semaphore:
            started_at = time.perf_counter()
            try:
                result = await planner.plan(request)
            except AgentExecutionError as error:
                return PlanningCaseResult(
                    case_id=case.case_id,
                    category=case.category,
                    success=False,
                    latency_millis=(time.perf_counter() - started_at) * 1000,
                    context_present=case.conversation_context is not None,
                    error_code=error.code.value,
                    error_message=str(error),
                )
            except Exception as error:
                return PlanningCaseResult(
                    case_id=case.case_id,
                    category=case.category,
                    success=False,
                    latency_millis=(time.perf_counter() - started_at) * 1000,
                    context_present=case.conversation_context is not None,
                    error_code="INTERNAL_ERROR",
                    error_message=type(error).__name__,
                )
            return PlanningCaseResult(
                case_id=case.case_id,
                category=case.category,
                success=True,
                latency_millis=(time.perf_counter() - started_at) * 1000,
                context_present=case.conversation_context is not None,
                plan=result.plan,
            )

    results = list(await asyncio.gather(*(evaluate_case(case) for case in cases)))
    successful = [item for item in results if item.success and item.plan is not None]
    latencies = [item.latency_millis for item in results]
    metrics = PlanningSmokeMetrics(
        case_count=len(results),
        successful_case_count=len(successful),
        error_count=len(results) - len(successful),
        category_counts=dict(Counter(item.category for item in results)),
        average_query_count=(
            sum(len(item.plan.queries) for item in successful if item.plan) / len(successful)
            if successful
            else 0
        ),
        average_sub_question_count=(
            sum(len(item.plan.sub_questions) for item in successful if item.plan) / len(successful)
            if successful
            else 0
        ),
        latency_p50_millis=_percentile(latencies, 0.5),
        latency_p95_millis=_percentile(latencies, 0.95),
    )
    return PlanningSmokeReport(
        generated_at=datetime.now(UTC),
        experiment_id=experiment_id,
        dataset_sha256=file_sha256(dataset_path),
        knowledge_base_manifest_id=manifest.manifest_id,
        strategy=planner.strategy,
        planner_model=planner_model,
        planner_version=planner_version,
        prompt_version=prompt_version,
        metrics=metrics,
        results=results,
    )


def write_planning_smoke_report(report: PlanningSmokeReport, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(report.model_dump_json(indent=2, by_alias=True), encoding="utf-8")
    temporary.replace(path)


def _select_cases(
    cases: list[LiveEvalCase],
    per_category: int,
) -> list[LiveEvalCase]:
    selected: list[LiveEvalCase] = []
    counts: Counter[LiveEvalCategory] = Counter()
    for case in cases:
        category = case.category
        if counts[category] >= per_category:
            continue
        selected.append(case)
        counts[category] += 1
    return selected


def _percentile(values: list[float], quantile: float) -> float:
    if not values:
        return 0
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * quantile) - 1)
    return ordered[index]
