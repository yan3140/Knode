import os
import tempfile
from datetime import UTC, datetime
from pathlib import Path
from typing import cast

from yan_agent.domain.retrieval_planning import RetrievalStrategy
from yan_agent.evaluation.live_retrieval_runner import (
    AggregateRetrievalMetrics,
    LiveCaseRetrievalResult,
    LiveReportModel,
    LiveRetrievalReport,
)

_COMPARISON_METRICS = (
    "hit_at_10",
    "recall_at_10",
    "reciprocal_rank_at_10",
    "ndcg_at_10",
    "context_precision_at_10",
    "unanswerable_false_positive_rate",
    "degradation_rate",
    "planner_model_calls",
    "planner_latency_p95_ms",
    "latency_p95_ms",
)


class MetricDelta(LiveReportModel):
    baseline: float | None
    candidate: float | None
    absolute_delta: float | None
    relative_delta: float | None


class PairedCaseComparison(LiveReportModel):
    case_id: str
    category: str
    raw_query: str
    single_rewrite_query: str
    query_changed: bool
    hit_at_10_delta: float | None
    recall_at_10_delta: float | None
    reciprocal_rank_at_10_delta: float | None
    ndcg_at_10_delta: float | None


class RetrievalComparisonReport(LiveReportModel):
    schema_version: str = "1.0"
    generated_at: datetime
    protocol_id: str
    dataset_id: str
    dataset_split: str
    dataset_sha256: str
    knowledge_base_manifest_id: str
    knowledge_base_manifest_sha256: str | None
    baseline_experiment_id: str
    candidate_experiment_id: str
    baseline_strategy: RetrievalStrategy
    candidate_strategy: RetrievalStrategy
    case_count: int
    metric_deltas: dict[str, MetricDelta]
    category_metric_deltas: dict[str, dict[str, MetricDelta]]
    cases: list[PairedCaseComparison]


def load_live_retrieval_report(path: Path) -> LiveRetrievalReport:
    return LiveRetrievalReport.model_validate_json(path.read_text(encoding="utf-8"))


def compare_retrieval_reports(
    reports: list[LiveRetrievalReport],
) -> RetrievalComparisonReport:
    if len(reports) != 2:
        raise ValueError("phase 3 comparison requires exactly two reports")
    by_strategy = {report.strategy: report for report in reports}
    expected = {RetrievalStrategy.RAW_QUERY, RetrievalStrategy.SINGLE_REWRITE}
    if set(by_strategy) != expected:
        raise ValueError("comparison requires RAW_QUERY and SINGLE_REWRITE reports")
    baseline = by_strategy[RetrievalStrategy.RAW_QUERY]
    candidate = by_strategy[RetrievalStrategy.SINGLE_REWRITE]
    _validate_comparable(baseline, candidate)
    baseline_cases = {case.case_id: case for case in baseline.cases}
    candidate_cases = {case.case_id: case for case in candidate.cases}
    paired_cases = [
        _compare_case(baseline_cases[case_id], candidate_cases[case_id])
        for case_id in baseline_cases
    ]
    categories = sorted(baseline.category_metrics)
    return RetrievalComparisonReport(
        generated_at=datetime.now(UTC),
        protocol_id=baseline.protocol_id,
        dataset_id=baseline.dataset_id,
        dataset_split=baseline.dataset_split,
        dataset_sha256=baseline.dataset_sha256,
        knowledge_base_manifest_id=baseline.knowledge_base_manifest_id,
        knowledge_base_manifest_sha256=baseline.knowledge_base_manifest_sha256,
        baseline_experiment_id=baseline.experiment_id,
        candidate_experiment_id=candidate.experiment_id,
        baseline_strategy=cast(RetrievalStrategy, baseline.strategy),
        candidate_strategy=cast(RetrievalStrategy, candidate.strategy),
        case_count=baseline.case_count,
        metric_deltas=_metric_deltas(baseline.metrics, candidate.metrics),
        category_metric_deltas={
            category: _metric_deltas(
                baseline.category_metrics[category],
                candidate.category_metrics[category],
            )
            for category in categories
        },
        cases=paired_cases,
    )


def render_retrieval_comparison_markdown(report: RetrievalComparisonReport) -> str:
    lines = [
        "# Retrieval Strategy Comparison",
        "",
        f"- Baseline: `{report.baseline_strategy.value}` / "
        f"`{_escape(report.baseline_experiment_id)}`",
        f"- Candidate: `{report.candidate_strategy.value}` / "
        f"`{_escape(report.candidate_experiment_id)}`",
        f"- Dataset: `{_escape(report.dataset_id)}` / `{report.dataset_split}` / "
        f"`{report.dataset_sha256}`",
        f"- Cases: {report.case_count}",
        "",
        "## Overall Deltas",
        "",
        "| Metric | RAW_QUERY | SINGLE_REWRITE | Absolute Delta | Relative Delta |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    for metric, delta in report.metric_deltas.items():
        lines.append(
            f"| {_escape(metric)} | {_format_decimal(delta.baseline)} | "
            f"{_format_decimal(delta.candidate)} | "
            f"{_format_signed(delta.absolute_delta)} | "
            f"{_format_percent(delta.relative_delta)} |"
        )
    lines.extend(
        [
            "",
            "## Category Recall@10",
            "",
            "| Category | RAW_QUERY | SINGLE_REWRITE | Delta |",
            "| --- | ---: | ---: | ---: |",
        ]
    )
    for category, metrics in report.category_metric_deltas.items():
        delta = metrics["recall_at_10"]
        lines.append(
            f"| {_escape(category)} | {_format_percent(delta.baseline)} | "
            f"{_format_percent(delta.candidate)} | "
            f"{_format_signed_percent(delta.absolute_delta)} |"
        )
    lines.extend(
        [
            "",
            "## Paired Cases",
            "",
            "| Case | Category | Query Changed | Recall@10 Delta | MRR@10 Delta |",
            "| --- | --- | --- | ---: | ---: |",
        ]
    )
    for case in report.cases:
        lines.append(
            f"| {_escape(case.case_id)} | {_escape(case.category)} | "
            f"{'yes' if case.query_changed else 'no'} | "
            f"{_format_signed(case.recall_at_10_delta)} | "
            f"{_format_signed(case.reciprocal_rank_at_10_delta)} |"
        )
    return "\n".join(lines) + "\n"


def write_retrieval_comparison(
    report: RetrievalComparisonReport,
    json_path: Path,
    markdown_path: Path,
) -> None:
    _atomic_write(json_path, report.model_dump_json(by_alias=True, indent=2) + "\n")
    _atomic_write(markdown_path, render_retrieval_comparison_markdown(report))


def _validate_comparable(
    baseline: LiveRetrievalReport,
    candidate: LiveRetrievalReport,
) -> None:
    fields = (
        "protocol_id",
        "protocol_sha256",
        "dataset_id",
        "dataset_split",
        "dataset_sha256",
        "dataset_manifest_sha256",
        "knowledge_base_manifest_id",
        "knowledge_base_manifest_sha256",
        "retrieval_config_version",
        "embedding_model",
        "requested_mode",
        "top_k",
        "candidate_k",
        "max_concurrency",
        "max_context_chars",
        "cache_mode",
        "case_count",
    )
    mismatches = [
        field for field in fields if getattr(baseline, field) != getattr(candidate, field)
    ]
    if mismatches:
        raise ValueError(f"reports use different controls: {', '.join(mismatches)}")
    if baseline.metrics.error_count or candidate.metrics.error_count:
        raise ValueError("reports with case execution errors cannot be compared")
    baseline_ids = [case.case_id for case in baseline.cases]
    candidate_ids = [case.case_id for case in candidate.cases]
    if baseline_ids != candidate_ids:
        raise ValueError("reports must contain the same ordered case IDs")
    if set(baseline.category_metrics) != set(candidate.category_metrics):
        raise ValueError("reports must contain the same categories")


def _metric_deltas(
    baseline: AggregateRetrievalMetrics,
    candidate: AggregateRetrievalMetrics,
) -> dict[str, MetricDelta]:
    return {
        metric: _metric_delta(
            _numeric_metric(getattr(baseline, metric)),
            _numeric_metric(getattr(candidate, metric)),
        )
        for metric in _COMPARISON_METRICS
    }


def _numeric_metric(value: float | int | None) -> float | None:
    return float(value) if value is not None else None


def _metric_delta(baseline: float | None, candidate: float | None) -> MetricDelta:
    if baseline is None or candidate is None:
        return MetricDelta(
            baseline=baseline,
            candidate=candidate,
            absolute_delta=None,
            relative_delta=None,
        )
    absolute = candidate - baseline
    relative = absolute / abs(baseline) if baseline else None
    return MetricDelta(
        baseline=baseline,
        candidate=candidate,
        absolute_delta=absolute,
        relative_delta=relative,
    )


def _compare_case(
    baseline: LiveCaseRetrievalResult,
    candidate: LiveCaseRetrievalResult,
) -> PairedCaseComparison:
    if baseline.metrics is None or candidate.metrics is None:
        raise ValueError("paired cases must have retrieval metrics")
    raw_query = baseline.planning.query_text if baseline.planning is not None else ""
    rewritten_query = candidate.planning.query_text if candidate.planning is not None else ""
    return PairedCaseComparison(
        case_id=baseline.case_id,
        category=baseline.category,
        raw_query=raw_query,
        single_rewrite_query=rewritten_query,
        query_changed=raw_query != rewritten_query,
        hit_at_10_delta=_difference(
            baseline.metrics.hit_at_10,
            candidate.metrics.hit_at_10,
        ),
        recall_at_10_delta=_difference(
            baseline.metrics.recall_at_10,
            candidate.metrics.recall_at_10,
        ),
        reciprocal_rank_at_10_delta=_difference(
            baseline.metrics.reciprocal_rank_at_10,
            candidate.metrics.reciprocal_rank_at_10,
        ),
        ndcg_at_10_delta=_difference(
            baseline.metrics.ndcg_at_10,
            candidate.metrics.ndcg_at_10,
        ),
    )


def _difference(baseline: float | None, candidate: float | None) -> float | None:
    return None if baseline is None or candidate is None else candidate - baseline


def render_live_retrieval_markdown(report: LiveRetrievalReport) -> str:
    lines = [
        "# Live Retrieval Evaluation",
        "",
        f"- Experiment: `{_escape(report.experiment_id)}`",
        f"- Strategy: `{_strategy_value(report.strategy)}`",
        f"- Dataset: `{_escape(report.dataset_id)}` / `{report.dataset_split}`",
        f"- Dataset SHA-256: `{report.dataset_sha256}`",
        f"- Knowledge manifest: `{_escape(report.knowledge_base_manifest_id)}`",
        f"- Git commit: `{_escape(report.git_commit)}`",
        f"- Controls: `topK={report.top_k}`, `candidateK={report.candidate_k}`, "
        f"`maxConcurrency={report.max_concurrency}`, "
        f"`maxContextChars={report.max_context_chars}`",
        f"- Cache: `{_escape(report.cache_mode)}`",
        "",
        "## Overall Metrics",
        "",
        *_metric_table(report.metrics),
        "",
        "## Category Metrics",
        "",
        "| Category | Cases | Errors | Hit@10 | Recall@10 | MRR@10 | nDCG@10 | "
        "Unanswerable FP | P95 ms |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for category, metrics in report.category_metrics.items():
        lines.append(
            f"| {_escape(category)} | {metrics.case_count} | {metrics.error_count} | "
            f"{_format_percent(metrics.hit_at_10)} | "
            f"{_format_percent(metrics.recall_at_10)} | "
            f"{_format_decimal(metrics.reciprocal_rank_at_10)} | "
            f"{_format_decimal(metrics.ndcg_at_10)} | "
            f"{_format_percent(metrics.unanswerable_false_positive_rate)} | "
            f"{metrics.latency_p95_ms:.2f} |"
        )
    lines.extend(
        [
            "",
            "## Cases",
            "",
            "| Case | Category | Result | Planned Query | Hit@10 | Recall@10 | MRR@10 | "
            "nDCG@10 | Top Chunks | Latency ms |",
            "| --- | --- | --- | --- | ---: | ---: | ---: | ---: | --- | ---: |",
        ]
    )
    for case in report.cases:
        top_chunks = ", ".join(item.chunk_id for item in case.final_ranking[:5]) or "-"
        planned_query = case.planning.query_text if case.planning is not None else "-"
        if case.error is not None:
            result = f"ERROR: {case.error.error_type}"
            hit_at_10 = recall_at_10 = mrr_at_10 = ndcg_at_10 = "-"
        else:
            result = "OK"
            assert case.metrics is not None
            hit_at_10 = _format_percent(case.metrics.hit_at_10)
            recall_at_10 = _format_percent(case.metrics.recall_at_10)
            mrr_at_10 = _format_decimal(case.metrics.reciprocal_rank_at_10)
            ndcg_at_10 = _format_decimal(case.metrics.ndcg_at_10)
        lines.append(
            f"| {_escape(case.case_id)} | {_escape(case.category)} | {_escape(result)} | "
            f"{_escape(planned_query)} | {hit_at_10} | {recall_at_10} | {mrr_at_10} | "
            f"{ndcg_at_10} | "
            f"{_escape(top_chunks)} | {case.latency_ms:.2f} |"
        )
    return "\n".join(lines) + "\n"


def write_live_retrieval_report(
    report: LiveRetrievalReport,
    json_path: Path,
    markdown_path: Path,
) -> None:
    json_text = report.model_dump_json(by_alias=True, indent=2) + "\n"
    markdown_text = render_live_retrieval_markdown(report)
    _atomic_write(json_path, json_text)
    _atomic_write(markdown_path, markdown_text)


def _metric_table(metrics: AggregateRetrievalMetrics) -> list[str]:
    rows = [
        ("Cases", str(metrics.case_count)),
        ("Successful", str(metrics.successful_case_count)),
        ("Errors", str(metrics.error_count)),
        ("Hit@5", _format_percent(metrics.hit_at_5)),
        ("Hit@10", _format_percent(metrics.hit_at_10)),
        ("Recall@5", _format_percent(metrics.recall_at_5)),
        ("Recall@10", _format_percent(metrics.recall_at_10)),
        ("MRR@10", _format_decimal(metrics.reciprocal_rank_at_10)),
        ("nDCG@10", _format_decimal(metrics.ndcg_at_10)),
        ("Context precision@10", _format_percent(metrics.context_precision_at_10)),
        ("Duplicate rate", _format_percent(metrics.duplicate_rate)),
        (
            "Unanswerable false-positive rate",
            _format_percent(metrics.unanswerable_false_positive_rate),
        ),
        ("Degradation rate", _format_percent(metrics.degradation_rate)),
        ("Planner model calls", str(metrics.planner_model_calls)),
        ("Planner latency P50", f"{metrics.planner_latency_p50_ms:.2f} ms"),
        ("Planner latency P95", f"{metrics.planner_latency_p95_ms:.2f} ms"),
        ("Latency P50", f"{metrics.latency_p50_ms:.2f} ms"),
        ("Latency P95", f"{metrics.latency_p95_ms:.2f} ms"),
    ]
    return [
        "| Metric | Value |",
        "| --- | ---: |",
        *(f"| {name} | {value} |" for name, value in rows),
    ]


def _atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="\n",
            prefix=f".{path.name}.",
            suffix=".tmp",
            dir=path.parent,
            delete=False,
        ) as temporary:
            temporary.write(content)
            temporary.flush()
            os.fsync(temporary.fileno())
            temporary_path = Path(temporary.name)
        temporary_path.replace(path)
    finally:
        if temporary_path is not None and temporary_path.exists():
            temporary_path.unlink()


def _format_percent(value: float | None) -> str:
    return "-" if value is None else f"{value * 100:.2f}%"


def _format_decimal(value: float | None) -> str:
    return "-" if value is None else f"{value:.4f}"


def _format_signed(value: float | None) -> str:
    return "-" if value is None else f"{value:+.4f}"


def _format_signed_percent(value: float | None) -> str:
    return "-" if value is None else f"{value * 100:+.2f}%"


def _escape(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ")


def _strategy_value(strategy: RetrievalStrategy | str) -> str:
    return strategy.value if isinstance(strategy, RetrievalStrategy) else strategy
