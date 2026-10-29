import pytest

from yan_agent.domain.retrieval_routing import RetrievalStrategy
from yan_agent.evaluation.complex_comparison_report import (
    compare_complex_retrieval,
    render_complex_comparison_markdown,
)
from yan_agent.evaluation.live_retrieval_runner import LiveRetrievalReport


@pytest.mark.parametrize("candidate_improves", [False, True])
def test_complex_comparison_evaluates_repeated_complex_cases(
    retrieval_reports: dict[RetrievalStrategy, LiveRetrievalReport],
    candidate_improves: bool,
) -> None:
    baseline = retrieval_reports[RetrievalStrategy.SINGLE_REWRITE]
    candidate = retrieval_reports[RetrievalStrategy.MULTI_QUERY]
    if not candidate_improves:
        candidate = baseline.model_copy(update={"strategy": RetrievalStrategy.MULTI_QUERY})
    report = compare_complex_retrieval(
        baseline_reports=[baseline.model_copy(deep=True) for _ in range(3)],
        candidate_reports=[candidate.model_copy(deep=True) for _ in range(3)],
    )

    assert report.case_count == 3
    assert report.categories == ["comparison", "compound", "multi_hop"]
    assert report.strategy_metrics["SINGLE_REWRITE"].run_count == 3
    assert report.strategy_metrics["MULTI_QUERY"].run_count == 3
    assert report.strategy_metrics["MULTI_QUERY"].observation_count == 9
    assert report.quality_gate_passed is candidate_improves
    expected = (
        "ENABLE_C_FOR_COMPLEX_TRAFFIC"
        if candidate_improves
        else "KEEP_C_EXPERIMENTAL_AND_FALL_BACK_TO_B"
    )
    assert report.recommendation == expected
    assert len(report.cases) == 3
    assert "Recall@10 表示" in render_complex_comparison_markdown(report)


def test_complex_comparison_rejects_single_model_run_by_default(
    retrieval_reports: dict[RetrievalStrategy, LiveRetrievalReport],
) -> None:
    with pytest.raises(ValueError, match="at least three reports"):
        compare_complex_retrieval(
            baseline_reports=[retrieval_reports[RetrievalStrategy.SINGLE_REWRITE]],
            candidate_reports=[retrieval_reports[RetrievalStrategy.MULTI_QUERY]],
        )
