import pytest

from yan_agent.domain.retrieval_routing import (
    RequestedRetrievalStrategy,
    RetrievalStrategy,
    RoutingReason,
)
from yan_agent.evaluation.adaptive_comparison_report import (
    compare_adaptive_retrieval,
    render_adaptive_comparison_markdown,
)
from yan_agent.evaluation.live_retrieval_runner import LiveRetrievalReport, RoutingExecution
from yan_agent.evaluation.routing_annotations import RoutingAnnotation


def annotations() -> list[RoutingAnnotation]:
    return [
        RoutingAnnotation(
            case_id=f"mysql-synthetic-{index:03d}",
            allowed_strategies=[strategy],
            preferred_strategy=strategy,
            routing_reasons=[RoutingReason.SINGLE_INFORMATION_NEED],
            must_preserve_terms=[],
        )
        for index, strategy in enumerate(RetrievalStrategy)
    ]


def routing(strategy: RetrievalStrategy) -> RoutingExecution:
    return RoutingExecution(
        requested_strategy=RequestedRetrievalStrategy.AUTO,
        selected_strategy=strategy,
        effective_strategy=strategy,
        routing_reasons=[RoutingReason.SINGLE_INFORMATION_NEED],
        routing_version="adaptive-router.v1",
        rollout_eligible=strategy is RetrievalStrategy.MULTI_QUERY,
        fallback_from=None,
        fallback_reason=None,
    )


def test_adaptive_comparison_pairs_cases_and_calculates_retrieval_regret(
    retrieval_reports: dict[RetrievalStrategy, LiveRetrievalReport],
) -> None:
    raw = retrieval_reports[RetrievalStrategy.RAW_QUERY]
    single = retrieval_reports[RetrievalStrategy.SINGLE_REWRITE]
    multi = retrieval_reports[RetrievalStrategy.MULTI_QUERY]
    routing_annotations = annotations()
    adaptive_cases = []
    for case, annotation in zip(single.cases, routing_annotations, strict=True):
        strategy = annotation.preferred_strategy
        source = {
            RetrievalStrategy.RAW_QUERY: raw,
            RetrievalStrategy.SINGLE_REWRITE: single,
            RetrievalStrategy.MULTI_QUERY: multi,
        }[strategy]
        selected = next(item for item in source.cases if item.case_id == case.case_id)
        adaptive_cases.append(selected.model_copy(update={"routing": routing(strategy)}))
    adaptive = single.model_copy(
        update={
            "experiment_id": "adaptive-replay-test",
            "strategy": "ADAPTIVE",
            "cases": adaptive_cases,
        }
    )

    comparison = compare_adaptive_retrieval(
        fixed_reports={
            RetrievalStrategy.RAW_QUERY: [raw],
            RetrievalStrategy.SINGLE_REWRITE: [single],
            RetrievalStrategy.MULTI_QUERY: [multi],
        },
        adaptive_reports=[adaptive],
        annotations=routing_annotations,
    )

    assert comparison.case_count == 3
    assert comparison.routing_metrics.audit_completeness == 1
    assert comparison.routing_metrics.allowed_strategy_rate == 1
    assert comparison.routing_metrics.preferred_strategy_rate == 1
    assert sum(comparison.routing_metrics.effective_strategy_counts.values()) == 3
    assert comparison.strategy_metrics["ADAPTIVE"].controlled_planner_fallback_rate == 0
    assert comparison.mean_retrieval_regret == pytest.approx(0.25)
    assert comparison.answer_experiment_status == "BLOCKED_BY_RETRIEVAL_GATE"
    assert comparison.test_split_status == "NOT_RUN_GATE_FAILED"
    assert len(comparison.cases) == 3
    assert comparison.cases[0].best_fixed_strategy in set(RetrievalStrategy)
    assert "路由后悔值" in render_adaptive_comparison_markdown(comparison)


def test_adaptive_comparison_rejects_missing_repeated_model_runs(
    retrieval_reports: dict[RetrievalStrategy, LiveRetrievalReport],
) -> None:
    raw = retrieval_reports[RetrievalStrategy.RAW_QUERY]
    single = retrieval_reports[RetrievalStrategy.SINGLE_REWRITE]
    multi = retrieval_reports[RetrievalStrategy.MULTI_QUERY]
    routing_annotations = annotations()

    with pytest.raises(ValueError, match="three reports"):
        compare_adaptive_retrieval(
            fixed_reports={
                RetrievalStrategy.RAW_QUERY: [raw],
                RetrievalStrategy.SINGLE_REWRITE: [single],
                RetrievalStrategy.MULTI_QUERY: [multi],
            },
            adaptive_reports=[single.model_copy(update={"strategy": "ADAPTIVE"})],
            annotations=routing_annotations,
            require_repeated_runs=True,
        )
