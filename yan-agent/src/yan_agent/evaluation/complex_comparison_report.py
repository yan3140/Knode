import os
import tempfile
from collections.abc import Sequence
from datetime import UTC, datetime
from pathlib import Path
from statistics import mean

from pydantic import Field

from yan_agent.domain.retrieval_routing import RetrievalStrategy
from yan_agent.evaluation.live_retrieval_runner import (
    LiveCaseRetrievalResult,
    LiveReportModel,
    LiveRetrievalReport,
)

DEFAULT_COMPLEX_CATEGORIES = ("comparison", "compound", "multi_hop")


class ComplexStrategyMetrics(LiveReportModel):
    run_count: int = Field(gt=0)
    case_count_per_run: int = Field(gt=0)
    observation_count: int = Field(gt=0)
    successful_observation_count: int = Field(ge=0)
    error_rate: float = Field(ge=0, le=1)
    hit_at_10: float | None
    recall_at_10: float | None
    reciprocal_rank_at_10: float | None
    ndcg_at_10: float | None
    latency_p95_ms: float = Field(ge=0)
    average_planner_model_calls: float = Field(ge=0)
    average_query_executions: float = Field(ge=0)


class ComplexCategoryMetrics(LiveReportModel):
    case_count: int = Field(gt=0)
    baseline_recall_at_10: float | None
    candidate_recall_at_10: float | None
    recall_at_10_delta: float | None
    baseline_mrr_at_10: float | None
    candidate_mrr_at_10: float | None
    mrr_at_10_delta: float | None


class ComplexCaseComparison(LiveReportModel):
    case_id: str
    category: str
    question: str
    baseline_recall_at_10: float | None
    candidate_recall_at_10: float | None
    recall_at_10_delta: float | None
    baseline_mrr_at_10: float | None
    candidate_mrr_at_10: float | None
    mrr_at_10_delta: float | None
    candidate_error_count: int = Field(ge=0)


class ComplexQualityGate(LiveReportModel):
    gate: str
    passed: bool
    actual: float | None
    threshold: str


class ComplexComparisonReport(LiveReportModel):
    schema_version: str = "1.0"
    generated_at: datetime
    protocol_id: str
    dataset_id: str
    dataset_split: str
    dataset_sha256: str
    categories: list[str]
    case_count: int = Field(gt=0)
    repeated_runs_required: bool
    baseline_strategy: RetrievalStrategy = RetrievalStrategy.SINGLE_REWRITE
    candidate_strategy: RetrievalStrategy = RetrievalStrategy.MULTI_QUERY
    strategy_metrics: dict[str, ComplexStrategyMetrics]
    category_metrics: dict[str, ComplexCategoryMetrics]
    quality_gates: list[ComplexQualityGate]
    quality_gate_passed: bool
    recommendation: str
    cases: list[ComplexCaseComparison]


def compare_complex_retrieval(
    *,
    baseline_reports: list[LiveRetrievalReport],
    candidate_reports: list[LiveRetrievalReport],
    categories: tuple[str, ...] = DEFAULT_COMPLEX_CATEGORIES,
    require_repeated_runs: bool = True,
) -> ComplexComparisonReport:
    _validate_inputs(
        baseline_reports,
        candidate_reports,
        categories,
        require_repeated_runs,
    )
    baseline_cases = _group_selected_cases(baseline_reports, categories)
    candidate_cases = _group_selected_cases(candidate_reports, categories)
    reference = baseline_reports[0]
    ordered_case_ids = [case.case_id for case in reference.cases if case.category in categories]
    cases = [
        _compare_case(baseline_cases[case_id], candidate_cases[case_id])
        for case_id in ordered_case_ids
    ]
    strategy_metrics = {
        RetrievalStrategy.SINGLE_REWRITE.value: _strategy_metrics(baseline_cases),
        RetrievalStrategy.MULTI_QUERY.value: _strategy_metrics(candidate_cases),
    }
    category_metrics = {
        category: _category_metrics([case for case in cases if case.category == category])
        for category in categories
    }
    gates = _quality_gates(strategy_metrics)
    passed = all(gate.passed for gate in gates)
    return ComplexComparisonReport(
        generated_at=datetime.now(UTC),
        protocol_id=reference.protocol_id,
        dataset_id=reference.dataset_id,
        dataset_split=reference.dataset_split,
        dataset_sha256=reference.dataset_sha256,
        categories=list(categories),
        case_count=len(cases),
        repeated_runs_required=require_repeated_runs,
        strategy_metrics=strategy_metrics,
        category_metrics=category_metrics,
        quality_gates=gates,
        quality_gate_passed=passed,
        recommendation=(
            "ENABLE_C_FOR_COMPLEX_TRAFFIC" if passed else "KEEP_C_EXPERIMENTAL_AND_FALL_BACK_TO_B"
        ),
        cases=cases,
    )


def render_complex_comparison_markdown(report: ComplexComparisonReport) -> str:
    baseline = report.strategy_metrics[RetrievalStrategy.SINGLE_REWRITE.value]
    candidate = report.strategy_metrics[RetrievalStrategy.MULTI_QUERY.value]
    lines = [
        "# 5.5-G 复杂问题 B/C 固定对照报告",
        "",
        f"- 数据集：`{report.dataset_id}` / `{report.dataset_split}`",
        f"- 复杂题：{report.case_count} 题（{', '.join(report.categories)}）",
        f"- 重复轮次：B={baseline.run_count}，C={candidate.run_count}",
        f"- 质量门禁：**{'PASSED' if report.quality_gate_passed else 'FAILED'}**",
        f"- 决策：`{report.recommendation}`",
        "",
        "> Hit@10 表示前 10 条里是否至少命中一条正确资料；Recall@10 表示应命中的资料被找回了多少；"
        "MRR@10 越高表示第一条正确资料越靠前。",
        "",
        "## 总体指标",
        "",
        "| 策略 | Runs | Error | Hit@10 | Recall@10 | MRR@10 | nDCG@10 | "
        "P95 ms | 平均模型调用 | 平均检索 Query |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for strategy in (
        RetrievalStrategy.SINGLE_REWRITE.value,
        RetrievalStrategy.MULTI_QUERY.value,
    ):
        strategy_metric = report.strategy_metrics[strategy]
        lines.append(
            f"| {strategy} | {strategy_metric.run_count} | "
            f"{strategy_metric.error_rate:.2%} | "
            f"{_percent(strategy_metric.hit_at_10)} | "
            f"{_percent(strategy_metric.recall_at_10)} | "
            f"{_decimal(strategy_metric.reciprocal_rank_at_10)} | "
            f"{_decimal(strategy_metric.ndcg_at_10)} | "
            f"{strategy_metric.latency_p95_ms:.1f} | "
            f"{strategy_metric.average_planner_model_calls:.2f} | "
            f"{strategy_metric.average_query_executions:.2f} |"
        )
    lines.extend(
        [
            "",
            "## 质量门禁",
            "",
            "| 门禁 | 结果 | 实际值 | 阈值 |",
            "| --- | --- | ---: | --- |",
        ]
    )
    for gate in report.quality_gates:
        lines.append(
            f"| {gate.gate} | {'PASS' if gate.passed else 'FAIL'} | "
            f"{_decimal(gate.actual)} | {gate.threshold} |"
        )
    lines.extend(
        [
            "",
            "## 分类结果",
            "",
            "| Category | Cases | B Recall@10 | C Recall@10 | Delta | "
            "B MRR@10 | C MRR@10 | Delta |",
            "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        ]
    )
    for category, category_metric in report.category_metrics.items():
        lines.append(
            f"| {category} | {category_metric.case_count} | "
            f"{_percent(category_metric.baseline_recall_at_10)} | "
            f"{_percent(category_metric.candidate_recall_at_10)} | "
            f"{_signed_percent(category_metric.recall_at_10_delta)} | "
            f"{_decimal(category_metric.baseline_mrr_at_10)} | "
            f"{_decimal(category_metric.candidate_mrr_at_10)} | "
            f"{_signed_decimal(category_metric.mrr_at_10_delta)} |"
        )
    worst = sorted(
        report.cases,
        key=lambda case: case.recall_at_10_delta if case.recall_at_10_delta is not None else 0,
    )[:10]
    lines.extend(
        [
            "",
            "## C 退化最明显的题",
            "",
            "| Case | Category | Recall Delta | MRR Delta | C Errors | Question |",
            "| --- | --- | ---: | ---: | ---: | --- |",
        ]
    )
    for case in worst:
        lines.append(
            f"| {case.case_id} | {case.category} | "
            f"{_signed_decimal(case.recall_at_10_delta)} | "
            f"{_signed_decimal(case.mrr_at_10_delta)} | "
            f"{case.candidate_error_count} | {_escape(case.question)} |"
        )
    return "\n".join(lines) + "\n"


def write_complex_comparison(
    report: ComplexComparisonReport,
    json_path: Path,
    markdown_path: Path,
) -> None:
    _atomic_write(json_path, report.model_dump_json(by_alias=True, indent=2) + "\n")
    _atomic_write(markdown_path, render_complex_comparison_markdown(report))


def _validate_inputs(
    baseline_reports: list[LiveRetrievalReport],
    candidate_reports: list[LiveRetrievalReport],
    categories: tuple[str, ...],
    require_repeated_runs: bool,
) -> None:
    if not baseline_reports or not candidate_reports:
        raise ValueError("B and C reports must not be empty")
    if not categories or len(set(categories)) != len(categories):
        raise ValueError("complex categories must be non-empty and unique")
    if require_repeated_runs and (len(baseline_reports) < 3 or len(candidate_reports) < 3):
        raise ValueError("B and C each require at least three reports")
    for report in baseline_reports:
        if report.strategy is not RetrievalStrategy.SINGLE_REWRITE:
            raise ValueError("baseline reports must use SINGLE_REWRITE")
    for report in candidate_reports:
        if report.strategy is not RetrievalStrategy.MULTI_QUERY:
            raise ValueError("candidate reports must use MULTI_QUERY")
    reports = [*baseline_reports, *candidate_reports]
    reference = reports[0]
    controls = (
        "protocol_id",
        "dataset_id",
        "dataset_split",
        "dataset_sha256",
        "knowledge_base_manifest_id",
        "retrieval_config_version",
        "requested_mode",
        "top_k",
        "candidate_k",
        "case_count",
    )
    reference_ids = [case.case_id for case in reference.cases]
    available_categories = {case.category for case in reference.cases}
    missing_categories = set(categories) - available_categories
    if missing_categories:
        raise ValueError(
            "reports do not contain categories: " + ", ".join(sorted(missing_categories))
        )
    for report in reports[1:]:
        mismatches = [
            field for field in controls if getattr(report, field) != getattr(reference, field)
        ]
        if mismatches:
            raise ValueError("reports use different controls: " + ", ".join(mismatches))
        if [case.case_id for case in report.cases] != reference_ids:
            raise ValueError("reports must contain the same ordered case IDs")


def _group_selected_cases(
    reports: list[LiveRetrievalReport],
    categories: tuple[str, ...],
) -> dict[str, list[LiveCaseRetrievalResult]]:
    grouped: dict[str, list[LiveCaseRetrievalResult]] = {}
    for report in reports:
        for case in report.cases:
            if case.category in categories:
                grouped.setdefault(case.case_id, []).append(case)
    return grouped


def _strategy_metrics(
    grouped_cases: dict[str, list[LiveCaseRetrievalResult]],
) -> ComplexStrategyMetrics:
    observations = [case for cases in grouped_cases.values() for case in cases]
    successful = [case for case in observations if case.metrics is not None]
    metric_items = [
        case.metrics for case in successful if case.answerable and case.metrics is not None
    ]
    planning = [case.planning for case in observations if case.planning is not None]
    return ComplexStrategyMetrics(
        run_count=len(next(iter(grouped_cases.values()))),
        case_count_per_run=len(grouped_cases),
        observation_count=len(observations),
        successful_observation_count=len(successful),
        error_rate=sum(case.error is not None for case in observations) / len(observations),
        hit_at_10=_metric_mean(metric_items, "hit_at_10"),
        recall_at_10=_metric_mean(metric_items, "recall_at_10"),
        reciprocal_rank_at_10=_metric_mean(metric_items, "reciprocal_rank_at_10"),
        ndcg_at_10=_metric_mean(metric_items, "ndcg_at_10"),
        latency_p95_ms=_percentile([case.latency_ms for case in observations], 0.95),
        average_planner_model_calls=(
            mean(item.model_calls for item in planning) if planning else 0.0
        ),
        average_query_executions=mean(len(case.query_executions) for case in observations),
    )


def _compare_case(
    baseline: list[LiveCaseRetrievalResult],
    candidate: list[LiveCaseRetrievalResult],
) -> ComplexCaseComparison:
    baseline_recall = _case_metric_mean(baseline, "recall_at_10")
    candidate_recall = _case_metric_mean(candidate, "recall_at_10")
    baseline_mrr = _case_metric_mean(baseline, "reciprocal_rank_at_10")
    candidate_mrr = _case_metric_mean(candidate, "reciprocal_rank_at_10")
    return ComplexCaseComparison(
        case_id=baseline[0].case_id,
        category=baseline[0].category,
        question=baseline[0].question,
        baseline_recall_at_10=baseline_recall,
        candidate_recall_at_10=candidate_recall,
        recall_at_10_delta=_difference(baseline_recall, candidate_recall),
        baseline_mrr_at_10=baseline_mrr,
        candidate_mrr_at_10=candidate_mrr,
        mrr_at_10_delta=_difference(baseline_mrr, candidate_mrr),
        candidate_error_count=sum(case.error is not None for case in candidate),
    )


def _category_metrics(cases: list[ComplexCaseComparison]) -> ComplexCategoryMetrics:
    baseline_recall = _optional_mean([case.baseline_recall_at_10 for case in cases])
    candidate_recall = _optional_mean([case.candidate_recall_at_10 for case in cases])
    baseline_mrr = _optional_mean([case.baseline_mrr_at_10 for case in cases])
    candidate_mrr = _optional_mean([case.candidate_mrr_at_10 for case in cases])
    return ComplexCategoryMetrics(
        case_count=len(cases),
        baseline_recall_at_10=baseline_recall,
        candidate_recall_at_10=candidate_recall,
        recall_at_10_delta=_difference(baseline_recall, candidate_recall),
        baseline_mrr_at_10=baseline_mrr,
        candidate_mrr_at_10=candidate_mrr,
        mrr_at_10_delta=_difference(baseline_mrr, candidate_mrr),
    )


def _quality_gates(
    metrics: dict[str, ComplexStrategyMetrics],
) -> list[ComplexQualityGate]:
    baseline = metrics[RetrievalStrategy.SINGLE_REWRITE.value]
    candidate = metrics[RetrievalStrategy.MULTI_QUERY.value]
    recall_delta = _difference(baseline.recall_at_10, candidate.recall_at_10)
    mrr_delta = _difference(
        baseline.reciprocal_rank_at_10,
        candidate.reciprocal_rank_at_10,
    )
    latency_ratio = _ratio(candidate.latency_p95_ms, baseline.latency_p95_ms)
    model_call_ratio = _ratio(
        candidate.average_planner_model_calls,
        baseline.average_planner_model_calls,
    )
    return [
        ComplexQualityGate(
            gate="C 复杂题 Recall@10 相对 B",
            passed=recall_delta is not None and recall_delta >= 0.10,
            actual=recall_delta,
            threshold=">= 0.10",
        ),
        ComplexQualityGate(
            gate="C 复杂题 MRR@10 相对 B",
            passed=mrr_delta is not None and mrr_delta >= -0.02,
            actual=mrr_delta,
            threshold=">= -0.02",
        ),
        ComplexQualityGate(
            gate="C 执行错误率",
            passed=candidate.error_rate == 0,
            actual=candidate.error_rate,
            threshold="= 0",
        ),
        ComplexQualityGate(
            gate="C P95 / B P95",
            passed=latency_ratio is not None and latency_ratio <= 1.20,
            actual=latency_ratio,
            threshold="<= 1.20",
        ),
        ComplexQualityGate(
            gate="C 平均规划调用 / B",
            passed=model_call_ratio is not None and model_call_ratio <= 1.20,
            actual=model_call_ratio,
            threshold="<= 1.20",
        ),
    ]


def _metric_mean(items: Sequence[object], attribute: str) -> float | None:
    values = [getattr(item, attribute) for item in items]
    return _optional_mean(values)


def _case_metric_mean(
    cases: list[LiveCaseRetrievalResult],
    attribute: str,
) -> float | None:
    return _optional_mean(
        [getattr(case.metrics, attribute) for case in cases if case.metrics is not None]
    )


def _optional_mean(values: list[float | None]) -> float | None:
    present = [value for value in values if value is not None]
    return mean(present) if present else None


def _difference(baseline: float | None, candidate: float | None) -> float | None:
    return None if baseline is None or candidate is None else candidate - baseline


def _ratio(numerator: float, denominator: float) -> float | None:
    return numerator / denominator if denominator else None


def _percentile(values: list[float], quantile: float) -> float:
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * quantile
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def _atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def _percent(value: float | None) -> str:
    return "-" if value is None else f"{value:.2%}"


def _decimal(value: float | None) -> str:
    return "-" if value is None else f"{value:.4f}"


def _signed_decimal(value: float | None) -> str:
    return "-" if value is None else f"{value:+.4f}"


def _signed_percent(value: float | None) -> str:
    return "-" if value is None else f"{value:+.2%}"


def _escape(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ")
