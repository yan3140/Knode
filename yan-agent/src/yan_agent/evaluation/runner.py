import asyncio
import hashlib
import json
import math
import time
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from pydantic import BaseModel, ConfigDict

from yan_agent.application.qa_agent import KnowledgeQaAgent
from yan_agent.domain.qa import (
    AgentInvocation,
    AgentResult,
    EvidenceAssessment,
    GeneratedAnswer,
    IntentDecision,
    KnowledgeCitation,
    KnowledgeRetrievalRequest,
    KnowledgeRetrievalResult,
    QueryRewrite,
    RetrievalMode,
)
from yan_agent.evaluation.models import EvalCase
from yan_agent.observability.metrics import AgentMetrics


class CaseEvaluation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    case_id: str
    category: str
    passed: bool
    status_match: bool
    recall_at_k: float | None
    reciprocal_rank: float | None
    citation_precision: float | None
    citation_covered: bool | None
    security_passed: bool
    degradation_match: bool
    latency_ms: float


class EvaluationReport(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: str = "1.0"
    dataset_sha256: str
    graph_version: str = "knowledge-qa.v3"
    generated_at: datetime
    case_count: int
    metrics: dict[str, float]
    cases: list[CaseEvaluation]


@dataclass
class _ScriptedModel:
    case: EvalCase
    grade_index: int = 0
    refine_index: int = 0

    async def classify(self, _question: str) -> IntentDecision:
        return IntentDecision(intent=self.case.intent)

    async def rewrite(self, _question: str) -> QueryRewrite:
        return QueryRewrite(query=self.case.rewritten_query, mode=RetrievalMode.AUTO)

    async def grade(
        self,
        _question: str,
        _citations: list[KnowledgeCitation],
    ) -> EvidenceAssessment:
        retrieval = self.case.retrievals[self.grade_index]
        self.grade_index += 1
        return EvidenceAssessment(
            sufficient=retrieval.sufficient,
            reason="scripted evaluation decision",
        )

    async def refine(
        self,
        _question: str,
        previous_query: str,
        _citations: list[KnowledgeCitation],
    ) -> QueryRewrite:
        self.refine_index += 1
        return QueryRewrite(query=f"{previous_query} refine-{self.refine_index}")

    async def answer(
        self,
        _question: str,
        _citations: list[KnowledgeCitation],
    ) -> GeneratedAnswer:
        if self.case.generated_answer is None:
            raise AssertionError("Evaluation case has no generated answer")
        return self.case.generated_answer

    async def converse(self, _question: str) -> str:
        return self.case.conversational_answer


@dataclass
class _ScriptedRetriever:
    case: EvalCase
    index: int = 0

    async def retrieve(self, _request: KnowledgeRetrievalRequest) -> KnowledgeRetrievalResult:
        retrieval = self.case.retrievals[self.index]
        self.index += 1
        return KnowledgeRetrievalResult(
            query_id=retrieval.query_id,
            selected_mode=retrieval.selected_mode,
            citations=retrieval.citations,
            degraded=retrieval.degraded,
            degradation_reasons=retrieval.degradation_reasons,
        )


async def evaluate_dataset(
    dataset_path: Path,
    metrics: AgentMetrics | None = None,
) -> EvaluationReport:
    raw_dataset = await asyncio.to_thread(dataset_path.read_bytes)
    cases = _load_cases(raw_dataset)
    evaluations: list[CaseEvaluation] = []
    results: list[AgentResult] = []
    for index, case in enumerate(cases, start=1):
        started_at = time.perf_counter()
        result = await KnowledgeQaAgent(
            model=_ScriptedModel(case),
            retriever=_ScriptedRetriever(case),
            metrics=metrics,
        ).invoke(
            AgentInvocation(
                run_id=f"eval-{index}",
                user_id=1,
                knowledge_base_id=1,
                question=case.question,
                requested_mode=case.requested_mode,
                conversation_context=case.conversation_context,
            )
        )
        latency_ms = (time.perf_counter() - started_at) * 1000
        results.append(result)
        evaluations.append(_score_case(case, result, latency_ms))
    return EvaluationReport(
        dataset_sha256=hashlib.sha256(raw_dataset).hexdigest(),
        generated_at=datetime.now(UTC),
        case_count=len(cases),
        metrics=_aggregate(cases, results, evaluations),
        cases=evaluations,
    )


def quality_gate(report: EvaluationReport, baseline_path: Path) -> list[str]:
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    minimums = baseline.get("minimums")
    if not isinstance(minimums, dict):
        raise ValueError("Evaluation baseline must contain an object named minimums")
    failures: list[str] = []
    for metric, raw_minimum in minimums.items():
        if not isinstance(metric, str) or not isinstance(raw_minimum, int | float):
            raise ValueError("Evaluation baseline minimums must be numeric")
        actual = report.metrics.get(metric)
        if actual is None:
            failures.append(f"missing metric: {metric}")
        elif actual < float(raw_minimum):
            failures.append(f"{metric}={actual:.6f} < {float(raw_minimum):.6f}")
    return failures


def _load_cases(raw_dataset: bytes) -> list[EvalCase]:
    cases = [
        EvalCase.model_validate_json(line)
        for line in raw_dataset.decode("utf-8").splitlines()
        if line.strip()
    ]
    if not cases:
        raise ValueError("Evaluation dataset must not be empty")
    identifiers = [case.case_id for case in cases]
    if len(identifiers) != len(set(identifiers)):
        raise ValueError("Evaluation caseId values must be unique")
    return cases


def _score_case(case: EvalCase, result: AgentResult, latency_ms: float) -> CaseEvaluation:
    relevant = set(case.expected.relevant_chunk_ids)
    returned = [citation.chunk_id for citation in result.citations]
    returned_relevant = [chunk_id for chunk_id in returned if chunk_id in relevant]
    recall = len(set(returned_relevant)) / len(relevant) if relevant else None
    reciprocal_rank = next(
        (1 / rank for rank, chunk_id in enumerate(returned, start=1) if chunk_id in relevant),
        None,
    )
    precision = len(returned_relevant) / len(returned) if returned else None
    covered = bool(returned) if relevant else None
    status_match = result.status is case.expected.status
    security_passed = set(case.expected.required_security_flags).issubset(result.security_flags)
    degradation_match = (
        result.degraded is case.expected.degraded
        and result.selected_mode is case.expected.selected_mode
    )
    passed = (
        status_match
        and (recall is None or math.isclose(recall, 1.0))
        and (precision is None or math.isclose(precision, 1.0))
        and security_passed
        and degradation_match
    )
    return CaseEvaluation(
        case_id=case.case_id,
        category=case.category,
        passed=passed,
        status_match=status_match,
        recall_at_k=recall,
        reciprocal_rank=reciprocal_rank,
        citation_precision=precision,
        citation_covered=covered,
        security_passed=security_passed,
        degradation_match=degradation_match,
        latency_ms=latency_ms,
    )


def _aggregate(
    cases: list[EvalCase],
    results: list[AgentResult],
    evaluations: list[CaseEvaluation],
) -> dict[str, float]:
    answerable = [evaluation for evaluation in evaluations if evaluation.recall_at_k is not None]
    precisions = [
        evaluation.citation_precision
        for evaluation in evaluations
        if evaluation.citation_precision is not None
    ]
    expected_insufficient = [
        case.expected.status.value == "INSUFFICIENT_EVIDENCE" for case in cases
    ]
    actual_insufficient = [result.status.value == "INSUFFICIENT_EVIDENCE" for result in results]
    true_positive = sum(
        expected and actual
        for expected, actual in zip(expected_insufficient, actual_insufficient, strict=True)
    )
    false_positive = sum(
        not expected and actual
        for expected, actual in zip(expected_insufficient, actual_insufficient, strict=True)
    )
    false_negative = sum(
        expected and not actual
        for expected, actual in zip(expected_insufficient, actual_insufficient, strict=True)
    )
    insufficient_precision = _safe_ratio(true_positive, true_positive + false_positive)
    insufficient_recall = _safe_ratio(true_positive, true_positive + false_negative)
    insufficient_f1 = _safe_ratio(
        2 * insufficient_precision * insufficient_recall,
        insufficient_precision + insufficient_recall,
    )
    latencies = sorted(evaluation.latency_ms for evaluation in evaluations)
    return {
        "case_pass_rate": _mean([float(evaluation.passed) for evaluation in evaluations]),
        "status_accuracy": _mean([float(evaluation.status_match) for evaluation in evaluations]),
        "recall_at_k": _mean([evaluation.recall_at_k or 0 for evaluation in answerable]),
        "mrr": _mean([evaluation.reciprocal_rank or 0 for evaluation in answerable]),
        "citation_precision": _mean(precisions),
        "citation_coverage": _mean(
            [float(evaluation.citation_covered or False) for evaluation in answerable]
        ),
        "insufficient_f1": insufficient_f1,
        "security_pass_rate": _mean(
            [float(evaluation.security_passed) for evaluation in evaluations]
        ),
        "degradation_accuracy": _mean(
            [float(evaluation.degradation_match) for evaluation in evaluations]
        ),
        "latency_p50_ms": _percentile(latencies, 0.50),
        "latency_p95_ms": _percentile(latencies, 0.95),
    }


def _mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def _safe_ratio(numerator: float, denominator: float) -> float:
    return numerator / denominator if denominator else 1.0


def _percentile(values: list[float], quantile: float) -> float:
    if not values:
        return 0.0
    index = max(0, math.ceil(len(values) * quantile) - 1)
    return values[index]
