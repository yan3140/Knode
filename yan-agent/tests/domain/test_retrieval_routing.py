import pytest
from pydantic import ValidationError

from yan_agent.domain.qa import RetrievalMode
from yan_agent.domain.question_analysis import QuestionAnalysis
from yan_agent.domain.retrieval_planning import (
    PlannedQuery,
    QueryComplexity,
    QueryPurpose,
    RetrievalStrategy,
)
from yan_agent.domain.retrieval_routing import (
    RequestedRetrievalStrategy,
    RetrievalRoutingDecision,
    RoutingReason,
)


def test_routing_decision_records_selected_and_effective_strategy() -> None:
    decision = RetrievalRoutingDecision(
        requested_strategy=RequestedRetrievalStrategy.AUTO,
        selected_strategy=RetrievalStrategy.MULTI_QUERY,
        effective_strategy=RetrievalStrategy.SINGLE_REWRITE,
        routing_reasons=[
            RoutingReason.MULTI_SUBQUESTION,
            RoutingReason.PLANNER_TIMEOUT,
        ],
        routing_version="adaptive-router.v1",
        rollout_eligible=True,
        fallback_from=RetrievalStrategy.MULTI_QUERY,
        fallback_reason=RoutingReason.PLANNER_TIMEOUT,
    )

    assert decision.selected_strategy is RetrievalStrategy.MULTI_QUERY
    assert decision.effective_strategy is RetrievalStrategy.SINGLE_REWRITE


def test_routing_decision_rejects_inconsistent_fallback() -> None:
    with pytest.raises(ValidationError, match="fallback_from must match selected_strategy"):
        RetrievalRoutingDecision(
            requested_strategy=RequestedRetrievalStrategy.AUTO,
            selected_strategy=RetrievalStrategy.MULTI_QUERY,
            effective_strategy=RetrievalStrategy.SINGLE_REWRITE,
            routing_reasons=[RoutingReason.PLANNER_TIMEOUT],
            routing_version="adaptive-router.v1",
            rollout_eligible=True,
            fallback_from=RetrievalStrategy.RAW_QUERY,
            fallback_reason=RoutingReason.PLANNER_TIMEOUT,
        )


def test_question_analysis_preserves_original_question_and_exact_terms() -> None:
    question = "innodb_flush_log_at_trx_commit=1 有什么作用？"
    analysis = QuestionAnalysis(
        original_question=question,
        standalone_question=question,
        complexity=QueryComplexity.EXACT,
        context_dependent=False,
        ambiguous=False,
        keywords=["redo log", "持久性"],
        exact_terms=["innodb_flush_log_at_trx_commit", "1"],
        sub_questions=[],
        candidate_queries=[
            PlannedQuery(
                query_id="q-original",
                text=question,
                purpose=QueryPurpose.ORIGINAL,
                mode=RetrievalMode.AUTO,
                weight=0.6,
                target_sub_question_ids=[],
            ),
            PlannedQuery(
                query_id="q-exact",
                text="innodb_flush_log_at_trx_commit 1 redo log 持久性",
                purpose=QueryPurpose.EXACT,
                mode=RetrievalMode.EXACT,
                weight=1.0,
                target_sub_question_ids=[],
            ),
        ],
        confidence=0.95,
        analyzer_version="question-analysis.v1",
    )

    assert analysis.original_question == question
    assert analysis.candidate_queries[1].purpose is QueryPurpose.EXACT


def test_question_analysis_rejects_changed_original_query() -> None:
    with pytest.raises(ValidationError, match="preserve the original question"):
        QuestionAnalysis(
            original_question="原始问题",
            standalone_question="原始问题",
            complexity=QueryComplexity.SIMPLE,
            context_dependent=False,
            ambiguous=False,
            keywords=[],
            exact_terms=[],
            sub_questions=[],
            candidate_queries=[
                PlannedQuery(
                    query_id="q-original",
                    text="被改写的问题",
                    purpose=QueryPurpose.ORIGINAL,
                    mode=RetrievalMode.AUTO,
                    weight=1.0,
                    target_sub_question_ids=[],
                )
            ],
            confidence=0.9,
            analyzer_version="question-analysis.v1",
        )
