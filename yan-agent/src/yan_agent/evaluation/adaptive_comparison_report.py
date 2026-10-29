import os
import tempfile
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path
from statistics import mean

from pydantic import Field

from yan_agent.domain.retrieval_routing import RetrievalStrategy
from yan_agent.evaluation.live_retrieval_runner import (
    CaseRetrievalMetrics,
    LiveCaseRetrievalResult,
    LiveReportModel,
    LiveRetrievalReport,
)
from yan_agent.evaluation.routing_annotations import RoutingAnnotation

_REGRET_TOLERANCE = 0.05


class StrategyMetrics(LiveReportModel):
    run_count: int = Field(gt=0)
    error_rate: float = Field(ge=0, le=1)
    hit_at_10: float | None
    recall_at_10: float | None
    reciprocal_rank_at_10: float | None
    ndcg_at_10: float | None
    latency_p95_ms: float = Field(ge=0)
    average_planner_model_calls: float = Field(ge=0)
    controlled_planner_fallback_rate: float = Field(ge=0, le=1)


class RoutingMetrics(LiveReportModel):
    decision_count: int = Field(ge=0)
    audit_completeness: float = Field(ge=0, le=1)
    allowed_strategy_rate: float = Field(ge=0, le=1)
    preferred_strategy_rate: float = Field(ge=0, le=1)
    regret_case_rate: float = Field(ge=0, le=1)
    effective_strategy_counts: dict[RetrievalStrategy, int]


class AdaptiveCategoryMetrics(LiveReportModel):
    case_count: int = Field(gt=0)
    adaptive_recall_at_10: float | None
    baseline_b_recall_at_10: float | None
    recall_at_10_delta_vs_b: float | None
    mean_retrieval_regret: float = Field(ge=0)
    allowed_strategy_rate: float = Field(ge=0, le=1)


class AdaptiveCaseComparison(LiveReportModel):
    case_id: str
    category: str
    allowed_strategies: list[RetrievalStrategy]
    preferred_strategy: RetrievalStrategy
    selected_strategy: RetrievalStrategy | None
    effective_strategy: RetrievalStrategy | None
    fixed_utility: dict[RetrievalStrategy, float | None]
    adaptive_utility: float | None
    best_fixed_strategy: RetrievalStrategy | None
    retrieval_regret: float
    error_attribution: list[str]


class QualityGateResult(LiveReportModel):
    gate: str
    passed: bool
    actual: float | None
    threshold: str


class AdaptiveComparisonReport(LiveReportModel):
    schema_version: str = "1.0"
    generated_at: datetime
    protocol_id: str
    dataset_id: str
    dataset_split: str
    dataset_sha256: str
    case_count: int = Field(gt=0)
    repeated_runs_required: bool
    strategy_metrics: dict[str, StrategyMetrics]
    routing_metrics: RoutingMetrics
    category_metrics: dict[str, AdaptiveCategoryMetrics]
    mean_retrieval_regret: float = Field(ge=0)
    retrieval_experiment_status: str = "COMPLETED"
    answer_experiment_status: str
    test_split_status: str
    quality_gate_passed: bool
    quality_gates: list[QualityGateResult]
    cases: list[AdaptiveCaseComparison]


def compare_adaptive_retrieval(
    *,
    fixed_reports: dict[RetrievalStrategy, list[LiveRetrievalReport]],
    adaptive_reports: list[LiveRetrievalReport],
    annotations: list[RoutingAnnotation],
    require_repeated_runs: bool = False,
) -> AdaptiveComparisonReport:
    _validate_inputs(fixed_reports, adaptive_reports, annotations, require_repeated_runs)
    all_reports = [report for reports in fixed_reports.values() for report in reports]
    reference = all_reports[0]
    fixed_cases = {strategy: _group_cases(reports) for strategy, reports in fixed_reports.items()}
    adaptive_cases = _group_cases(adaptive_reports)
    annotations_by_id = {item.case_id: item for item in annotations}
    cases = [
        _compare_case(
            case_id,
            annotations_by_id[case_id],
            {strategy: cases_by_id[case_id] for strategy, cases_by_id in fixed_cases.items()},
            adaptive_cases[case_id],
        )
        for case_id in [case.case_id for case in reference.cases]
    ]
    strategy_metrics = {
        strategy.value: _strategy_metrics(reports) for strategy, reports in fixed_reports.items()
    }
    strategy_metrics["ADAPTIVE"] = _strategy_metrics(adaptive_reports)
    routing_metrics = _routing_metrics(adaptive_reports, annotations_by_id, cases)
    category_metrics = {
        category: _category_metrics(
            [case for case in cases if case.category == category],
            fixed_cases[RetrievalStrategy.SINGLE_REWRITE],
            adaptive_cases,
        )
        for category in sorted({case.category for case in cases})
    }
    gates = _quality_gates(
        cases,
        fixed_cases[RetrievalStrategy.SINGLE_REWRITE],
        adaptive_cases,
        strategy_metrics,
        routing_metrics,
    )
    return AdaptiveComparisonReport(
        generated_at=datetime.now(UTC),
        protocol_id=reference.protocol_id,
        dataset_id=reference.dataset_id,
        dataset_split=reference.dataset_split,
        dataset_sha256=reference.dataset_sha256,
        case_count=reference.case_count,
        repeated_runs_required=require_repeated_runs,
        strategy_metrics=strategy_metrics,
        routing_metrics=routing_metrics,
        category_metrics=category_metrics,
        mean_retrieval_regret=mean(case.retrieval_regret for case in cases),
        answer_experiment_status=(
            "READY" if all(gate.passed for gate in gates) else "BLOCKED_BY_RETRIEVAL_GATE"
        ),
        test_split_status=(
            "READY" if all(gate.passed for gate in gates) else "NOT_RUN_GATE_FAILED"
        ),
        quality_gate_passed=all(gate.passed for gate in gates),
        quality_gates=gates,
        cases=cases,
    )


def render_adaptive_comparison_markdown(report: AdaptiveComparisonReport) -> str:
    status = "PASSED" if report.quality_gate_passed else "FAILED"
    lines = [
        "# A/B/C/D 自适应检索对照报告",
        "",
        f"- 数据集：`{report.dataset_id}` / `{report.dataset_split}` / {report.case_count} Cases",
        f"- 质量门禁：**{status}**",
        f"- 回答实验：`{report.answer_experiment_status}`",
        f"- 冻结 Test：`{report.test_split_status}`",
        f"- 平均路由后悔值：`{report.mean_retrieval_regret:.4f}`",
        "",
        "> 路由后悔值 = 该题固定 A/B/C 的最佳检索效用 - D 的实际检索效用。",
        "> 检索效用 = 50% Recall@10 + 30% MRR@10 + 20% nDCG@10；无答案题使用 1 - 误报。",
        "",
        "## 总体指标",
        "",
        "| 策略 | Runs | Error | Hit@10 | Recall@10 | MRR@10 | nDCG@10 "
        "| P95 ms | 平均规划调用 | 受控降级 |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for strategy in ("RAW_QUERY", "SINGLE_REWRITE", "MULTI_QUERY", "ADAPTIVE"):
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
            f"{strategy_metric.controlled_planner_fallback_rate:.2%} |"
        )
    routing = report.routing_metrics
    routing_distribution = ", ".join(
        f"{key.value}={value}" for key, value in routing.effective_strategy_counts.items()
    )
    lines.extend(
        [
            "",
            "## 路由指标",
            "",
            f"- 审计完整率：{routing.audit_completeness:.2%}",
            f"- 允许策略命中率：{routing.allowed_strategy_rate:.2%}",
            f"- 首选策略命中率：{routing.preferred_strategy_rate:.2%}",
            f"- 有显著后悔的 Case 比例：{routing.regret_case_rate:.2%}",
            f"- 实际路由分布：{routing_distribution}",
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
            "## Category",
            "",
            "| Category | Cases | D Recall@10 | B Recall@10 | Delta | 后悔值 | 允许策略命中 |",
            "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
        ]
    )
    for category, category_metric in report.category_metrics.items():
        lines.append(
            f"| {category} | {category_metric.case_count} | "
            f"{_percent(category_metric.adaptive_recall_at_10)} | "
            f"{_percent(category_metric.baseline_b_recall_at_10)} | "
            f"{_signed_percent(category_metric.recall_at_10_delta_vs_b)} | "
            f"{category_metric.mean_retrieval_regret:.4f} | "
            f"{category_metric.allowed_strategy_rate:.2%} |"
        )
    worst = sorted(report.cases, key=lambda case: case.retrieval_regret, reverse=True)[:20]
    lines.extend(
        [
            "",
            "## 高后悔 Case",
            "",
            "| Case | Category | D | Best | 后悔值 | 归因 |",
            "| --- | --- | --- | --- | ---: | --- |",
        ]
    )
    for case in worst:
        lines.append(
            f"| {case.case_id} | {case.category} | "
            f"{case.effective_strategy.value if case.effective_strategy else '-'} | "
            f"{case.best_fixed_strategy.value if case.best_fixed_strategy else '-'} | "
            f"{case.retrieval_regret:.4f} | {', '.join(case.error_attribution) or '-'} |"
        )
    return "\n".join(lines) + "\n"


def write_adaptive_comparison(
    report: AdaptiveComparisonReport,
    json_path: Path,
    markdown_path: Path,
) -> None:
    _atomic_write(json_path, report.model_dump_json(by_alias=True, indent=2) + "\n")
    _atomic_write(markdown_path, render_adaptive_comparison_markdown(report))


def _validate_inputs(
    fixed_reports: dict[RetrievalStrategy, list[LiveRetrievalReport]],
    adaptive_reports: list[LiveRetrievalReport],
    annotations: list[RoutingAnnotation],
    require_repeated_runs: bool,
) -> None:
    if set(fixed_reports) != set(RetrievalStrategy):
        raise ValueError("fixed reports must contain RAW_QUERY, SINGLE_REWRITE and MULTI_QUERY")
    if not adaptive_reports:
        raise ValueError("adaptive reports must not be empty")
    for report in adaptive_reports:
        if report.strategy != "ADAPTIVE":
            raise ValueError("adaptive reports must use the ADAPTIVE evaluation strategy")
    if require_repeated_runs:
        for strategy in (RetrievalStrategy.SINGLE_REWRITE, RetrievalStrategy.MULTI_QUERY):
            if len(fixed_reports[strategy]) < 3:
                raise ValueError(f"{strategy.value} requires at least three reports")
        if len(adaptive_reports) < 3:
            raise ValueError("ADAPTIVE requires at least three reports")
    reports = [*sum(fixed_reports.values(), []), *adaptive_reports]
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
    for report in reports[1:]:
        mismatches = [
            field for field in controls if getattr(report, field) != getattr(reference, field)
        ]
        if mismatches:
            raise ValueError("reports use different controls: " + ", ".join(mismatches))
        if [case.case_id for case in report.cases] != [case.case_id for case in reference.cases]:
            raise ValueError("reports must contain the same ordered case IDs")
    if {item.case_id for item in annotations} != {case.case_id for case in reference.cases}:
        raise ValueError("routing annotations must cover every report case")


def _group_cases(
    reports: list[LiveRetrievalReport],
) -> dict[str, list[LiveCaseRetrievalResult]]:
    grouped: dict[str, list[LiveCaseRetrievalResult]] = {}
    for report in reports:
        for case in report.cases:
            grouped.setdefault(case.case_id, []).append(case)
    return grouped


def _compare_case(
    case_id: str,
    annotation: RoutingAnnotation,
    fixed: dict[RetrievalStrategy, list[LiveCaseRetrievalResult]],
    adaptive: list[LiveCaseRetrievalResult],
) -> AdaptiveCaseComparison:
    utilities = {strategy: _mean_utility(cases) for strategy, cases in fixed.items()}
    available = {
        strategy: utility for strategy, utility in utilities.items() if utility is not None
    }
    best_strategy = max(available, key=lambda strategy: available[strategy]) if available else None
    best_utility = available.get(best_strategy) if best_strategy is not None else None
    adaptive_utility = _mean_utility(adaptive)
    regret = (
        max(0.0, best_utility - adaptive_utility)
        if best_utility is not None and adaptive_utility is not None
        else 0.0
    )
    decisions = [case.routing for case in adaptive if case.routing is not None]
    selected = _majority([decision.selected_strategy for decision in decisions])
    effective = _majority([decision.effective_strategy for decision in decisions])
    attribution: list[str] = []
    if len(decisions) != len(adaptive):
        attribution.append("AUDIT_MISSING")
    if effective is not None and effective not in annotation.allowed_strategies:
        attribution.append(
            "RULE_ERROR" if effective is RetrievalStrategy.RAW_QUERY else "ANALYZER_ERROR"
        )
    if regret > _REGRET_TOLERANCE:
        if effective is not None and best_strategy is not effective:
            attribution.append("ROUTING_POLICY")
        elif effective is not None and utilities.get(effective) is not None:
            attribution.append("PLANNER_OR_RETRIEVAL_DRIFT")
    if any(case.error is not None for case in adaptive):
        attribution.append("EXECUTION_ERROR")
    return AdaptiveCaseComparison(
        case_id=case_id,
        category=adaptive[0].category,
        allowed_strategies=annotation.allowed_strategies,
        preferred_strategy=annotation.preferred_strategy,
        selected_strategy=selected,
        effective_strategy=effective,
        fixed_utility=utilities,
        adaptive_utility=adaptive_utility,
        best_fixed_strategy=best_strategy,
        retrieval_regret=regret,
        error_attribution=list(dict.fromkeys(attribution)),
    )


def _strategy_metrics(reports: list[LiveRetrievalReport]) -> StrategyMetrics:
    cases = [case for report in reports for case in report.cases]
    answerable_metrics = [
        case.metrics for case in cases if case.answerable and case.metrics is not None
    ]
    latencies = sorted(case.latency_ms for case in cases)
    planning = [case.planning for case in cases if case.planning is not None]
    return StrategyMetrics(
        run_count=len(reports),
        error_rate=sum(case.error is not None for case in cases) / len(cases),
        hit_at_10=_metric_mean(answerable_metrics, "hit_at_10"),
        recall_at_10=_metric_mean(answerable_metrics, "recall_at_10"),
        reciprocal_rank_at_10=_metric_mean(answerable_metrics, "reciprocal_rank_at_10"),
        ndcg_at_10=_metric_mean(answerable_metrics, "ndcg_at_10"),
        latency_p95_ms=_percentile(latencies, 0.95),
        average_planner_model_calls=(
            mean(item.model_calls for item in planning) if planning else 0.0
        ),
        controlled_planner_fallback_rate=(
            sum(
                case.planning is not None
                and any(
                    reason
                    in {
                        "PLANNER_TIMEOUT",
                        "PLANNER_RATE_LIMITED",
                        "PLANNER_INVALID_RESPONSE",
                        "PLANNER_UNAVAILABLE",
                        "FAST_PATH_RETRIEVAL_FALLBACK",
                    }
                    for reason in (
                        [item.value for item in case.routing.routing_reasons]
                        if case.routing is not None
                        else []
                    )
                )
                for case in cases
            )
            / len(cases)
            if cases
            else 0.0
        ),
    )


def _routing_metrics(
    reports: list[LiveRetrievalReport],
    annotations: dict[str, RoutingAnnotation],
    cases: list[AdaptiveCaseComparison],
) -> RoutingMetrics:
    observations = [case for report in reports for case in report.cases]
    decisions = [(case, case.routing) for case in observations if case.routing is not None]
    counts = Counter(decision.effective_strategy for _case, decision in decisions)
    return RoutingMetrics(
        decision_count=len(decisions),
        audit_completeness=len(decisions) / len(observations),
        allowed_strategy_rate=(
            mean(
                decision.effective_strategy in annotations[case.case_id].allowed_strategies
                for case, decision in decisions
            )
            if decisions
            else 0.0
        ),
        preferred_strategy_rate=(
            mean(
                decision.effective_strategy is annotations[case.case_id].preferred_strategy
                for case, decision in decisions
            )
            if decisions
            else 0.0
        ),
        regret_case_rate=mean(case.retrieval_regret > _REGRET_TOLERANCE for case in cases),
        effective_strategy_counts={strategy: counts[strategy] for strategy in RetrievalStrategy},
    )


def _category_metrics(
    cases: list[AdaptiveCaseComparison],
    baseline: dict[str, list[LiveCaseRetrievalResult]],
    adaptive: dict[str, list[LiveCaseRetrievalResult]],
) -> AdaptiveCategoryMetrics:
    case_ids = [case.case_id for case in cases]
    b_recall = _case_metric_mean(case_ids, baseline, "recall_at_10")
    d_recall = _case_metric_mean(case_ids, adaptive, "recall_at_10")
    return AdaptiveCategoryMetrics(
        case_count=len(cases),
        adaptive_recall_at_10=d_recall,
        baseline_b_recall_at_10=b_recall,
        recall_at_10_delta_vs_b=(
            d_recall - b_recall if d_recall is not None and b_recall is not None else None
        ),
        mean_retrieval_regret=mean(case.retrieval_regret for case in cases),
        allowed_strategy_rate=mean(
            case.effective_strategy in case.allowed_strategies for case in cases
        ),
    )


def _quality_gates(
    cases: list[AdaptiveCaseComparison],
    baseline: dict[str, list[LiveCaseRetrievalResult]],
    adaptive: dict[str, list[LiveCaseRetrievalResult]],
    metrics: dict[str, StrategyMetrics],
    routing: RoutingMetrics,
) -> list[QualityGateResult]:
    a_ids = [
        case.case_id for case in cases if case.effective_strategy is RetrievalStrategy.RAW_QUERY
    ]
    c_compound_ids = [
        case.case_id
        for case in cases
        if case.effective_strategy is RetrievalStrategy.MULTI_QUERY and case.category == "compound"
    ]
    c_ids = [
        case.case_id for case in cases if case.effective_strategy is RetrievalStrategy.MULTI_QUERY
    ]
    a_hit_delta = _paired_metric_delta(a_ids, baseline, adaptive, "hit_at_10")
    c_recall_delta = _paired_metric_delta(c_compound_ids, baseline, adaptive, "recall_at_10")
    c_mrr_delta = _paired_metric_delta(c_ids, baseline, adaptive, "reciprocal_rank_at_10")
    b = metrics[RetrievalStrategy.SINGLE_REWRITE.value]
    d = metrics["ADAPTIVE"]
    error_free = d.error_rate == 0
    return [
        _gate("A 路由 Hit@10 相对 B", a_hit_delta, -0.02, ">="),
        _gate("C 路由复合问题 Recall@10 相对 B", c_recall_delta, 0.10, ">="),
        _gate("C 路由 MRR@10 相对 B", c_mrr_delta, -0.02, ">="),
        _ratio_gate("D P95 / B P95", d.latency_p95_ms, b.latency_p95_ms, 1.2),
        _ratio_gate(
            "D 平均规划调用 / B",
            d.average_planner_model_calls,
            b.average_planner_model_calls,
            1.2,
        ),
        QualityGateResult(
            gate="Planner 非受控失败",
            passed=error_free,
            actual=d.error_rate,
            threshold="= 0",
        ),
        QualityGateResult(
            gate="路由审计完整率",
            passed=routing.audit_completeness == 1,
            actual=routing.audit_completeness,
            threshold="= 1.0",
        ),
    ]


def _gate(name: str, actual: float | None, threshold: float, operator: str) -> QualityGateResult:
    return QualityGateResult(
        gate=name,
        passed=actual is not None and actual >= threshold,
        actual=actual,
        threshold=f"{operator} {threshold:.2f}",
    )


def _ratio_gate(
    name: str,
    numerator: float,
    denominator: float,
    maximum: float,
) -> QualityGateResult:
    ratio = numerator / denominator if denominator else (0.0 if numerator == 0 else None)
    return QualityGateResult(
        gate=name,
        passed=ratio is not None and ratio <= maximum,
        actual=ratio,
        threshold=f"<= {maximum:.2f}",
    )


def _paired_metric_delta(
    case_ids: list[str],
    baseline: dict[str, list[LiveCaseRetrievalResult]],
    adaptive: dict[str, list[LiveCaseRetrievalResult]],
    metric: str,
) -> float | None:
    left = _case_metric_mean(case_ids, baseline, metric)
    right = _case_metric_mean(case_ids, adaptive, metric)
    return right - left if left is not None and right is not None else None


def _case_metric_mean(
    case_ids: list[str],
    grouped: dict[str, list[LiveCaseRetrievalResult]],
    metric: str,
) -> float | None:
    values = [
        value
        for case_id in case_ids
        for case in grouped[case_id]
        if case.metrics is not None
        for value in [getattr(case.metrics, metric)]
        if value is not None
    ]
    return mean(values) if values else None


def _mean_utility(cases: list[LiveCaseRetrievalResult]) -> float | None:
    if not cases:
        return None
    values = [_utility(case) for case in cases]
    return mean(value if value is not None else 0.0 for value in values)


def _utility(case: LiveCaseRetrievalResult) -> float | None:
    metrics = case.metrics
    if metrics is None:
        return None
    if not case.answerable:
        false_positive = metrics.unanswerable_false_positive
        return 1 - false_positive if false_positive is not None else None
    if (
        metrics.recall_at_10 is None
        or metrics.reciprocal_rank_at_10 is None
        or metrics.ndcg_at_10 is None
    ):
        return None
    return (
        0.5 * metrics.recall_at_10 + 0.3 * metrics.reciprocal_rank_at_10 + 0.2 * metrics.ndcg_at_10
    )


def _metric_mean(metrics: list[CaseRetrievalMetrics], field: str) -> float | None:
    values = [value for item in metrics for value in [getattr(item, field)] if value is not None]
    return mean(values) if values else None


def _majority(values: list[RetrievalStrategy]) -> RetrievalStrategy | None:
    if not values:
        return None
    counts = Counter(values)
    strategies = list(RetrievalStrategy)
    return max(
        strategies,
        key=lambda strategy: (counts[strategy], -strategies.index(strategy)),
    )


def _percentile(values: list[float], quantile: float) -> float:
    if not values:
        return 0.0
    index = max(0, min(len(values) - 1, int(len(values) * quantile + 0.999999) - 1))
    return values[index]


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


def _signed_percent(value: float | None) -> str:
    return "-" if value is None else f"{value:+.2%}"
