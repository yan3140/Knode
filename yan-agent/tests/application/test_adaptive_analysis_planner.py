from dataclasses import dataclass

import pytest

from yan_agent.application.question_analysis import StructuredQuestionAnalyzer
from yan_agent.application.retrieval_planning import SingleRewritePlanner
from yan_agent.application.unified_retrieval_planning import UnifiedRetrievalPlanner
from yan_agent.domain.errors import (
    ModelInvalidResponseError,
    ModelRateLimitedError,
    ModelTimeoutError,
)
from yan_agent.domain.qa import QueryRewrite, RetrievalMode
from yan_agent.domain.question_analysis import QuestionAnalysisDraft
from yan_agent.domain.retrieval_planning import (
    PlannedQuery,
    QueryComplexity,
    QueryPurpose,
    RetrievalPlanningRequest,
    RetrievalStrategy,
    SubQuestion,
)


@dataclass
class AnalysisModel:
    result: QuestionAnalysisDraft | Exception

    async def analyze_question(
        self,
        original_question: str,
        model_input: str,
        required_exact_terms: list[str],
    ) -> QuestionAnalysisDraft:
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


@dataclass
class RewriteModel:
    calls: int = 0

    async def rewrite(self, question: str) -> QueryRewrite:
        self.calls += 1
        return QueryRewrite(query="降级后的单 Query", mode=RetrievalMode.HYBRID)


def draft(
    question: str,
    *,
    complexity: QueryComplexity,
    sub_questions: list[SubQuestion],
    queries: list[PlannedQuery],
    standalone_question: str | None = None,
) -> QuestionAnalysisDraft:
    return QuestionAnalysisDraft(
        original_question=question,
        standalone_question=standalone_question or question,
        complexity=complexity,
        context_dependent=False,
        ambiguous=False,
        keywords=[],
        exact_terms=[],
        sub_questions=sub_questions,
        candidate_queries=queries,
        confidence=0.9,
        analyzer_version="question-analysis.v1",
    )


def original_query(question: str) -> PlannedQuery:
    return PlannedQuery(
        query_id="q-original",
        text=question,
        purpose=QueryPurpose.ORIGINAL,
        mode=RetrievalMode.AUTO,
        weight=0.6,
        target_sub_question_ids=[],
    )


@pytest.mark.asyncio
async def test_unified_planner_collapses_one_information_need_to_b() -> None:
    question = "MySQL 为什么使用 B+ 树？"
    analysis = draft(
        question,
        complexity=QueryComplexity.SIMPLE,
        sub_questions=[],
        queries=[
            original_query(question),
            PlannedQuery(
                query_id="q-semantic",
                text="MySQL InnoDB 使用 B+ 树作为索引的原因",
                purpose=QueryPurpose.SEMANTIC,
                mode=RetrievalMode.HYBRID,
                weight=1.0,
                target_sub_question_ids=[],
            ),
        ],
        standalone_question="MySQL InnoDB 为什么使用 B+ 树作为索引？",
    )
    fallback = RewriteModel()
    planner = UnifiedRetrievalPlanner(
        StructuredQuestionAnalyzer(AnalysisModel(analysis)),
        SingleRewritePlanner(fallback),
    )

    result = await planner.plan(
        RetrievalPlanningRequest(original_question=question, model_input=question)
    )

    assert result.strategy is RetrievalStrategy.SINGLE_REWRITE
    assert result.query.query == "MySQL InnoDB 为什么使用 B+ 树作为索引？"
    assert result.analysis is not None
    assert result.model_calls == 1
    assert fallback.calls == 0


@pytest.mark.asyncio
async def test_unified_planner_keeps_complementary_subquestions_as_c() -> None:
    question = "MVCC 如何实现可重复读，它和幻读有什么关系？"
    sub_questions = [
        SubQuestion(sub_question_id="sq-1", text="MVCC 如何实现可重复读？"),
        SubQuestion(sub_question_id="sq-2", text="MVCC 和幻读有什么关系？"),
    ]
    analysis = draft(
        question,
        complexity=QueryComplexity.COMPOUND,
        sub_questions=sub_questions,
        queries=[
            original_query(question),
            PlannedQuery(
                query_id="q-mvcc",
                text="MVCC 版本链 Read View 可重复读",
                purpose=QueryPurpose.SUB_QUESTION,
                mode=RetrievalMode.HYBRID,
                weight=1.0,
                target_sub_question_ids=["sq-1"],
            ),
            PlannedQuery(
                query_id="q-phantom",
                text="MVCC 幻读 快照读 当前读",
                purpose=QueryPurpose.SUB_QUESTION,
                mode=RetrievalMode.HYBRID,
                weight=1.0,
                target_sub_question_ids=["sq-2"],
            ),
        ],
    )
    planner = UnifiedRetrievalPlanner(
        StructuredQuestionAnalyzer(AnalysisModel(analysis)),
        SingleRewritePlanner(RewriteModel()),
    )

    result = await planner.plan(
        RetrievalPlanningRequest(original_question=question, model_input=question)
    )

    assert result.strategy is RetrievalStrategy.MULTI_QUERY
    assert result.plan is not None
    assert len(result.plan.queries) == 3
    assert result.model_calls == 1


@pytest.mark.parametrize(
    ("error", "reason"),
    [
        (ModelTimeoutError(), "MODEL_TIMEOUT"),
        (ModelRateLimitedError(), "MODEL_RATE_LIMITED"),
        (ModelInvalidResponseError(), "MODEL_INVALID_RESPONSE"),
    ],
)
@pytest.mark.asyncio
async def test_unified_planner_falls_back_to_existing_b_on_analyzer_failure(
    error: Exception,
    reason: str,
) -> None:
    fallback = RewriteModel()
    planner = UnifiedRetrievalPlanner(
        StructuredQuestionAnalyzer(AnalysisModel(error)),
        SingleRewritePlanner(fallback),
    )

    result = await planner.plan(
        RetrievalPlanningRequest(
            original_question="复杂问题",
            model_input="复杂问题",
        )
    )

    assert result.strategy is RetrievalStrategy.SINGLE_REWRITE
    assert result.query.query == "复杂问题"
    assert result.query.mode is RetrievalMode.HYBRID
    assert result.model_calls == 1
    assert result.planning_fallback_reason == reason
    assert fallback.calls == 0


@pytest.mark.asyncio
async def test_contextual_analyzer_failure_keeps_model_rewrite_fallback() -> None:
    fallback = RewriteModel()
    planner = UnifiedRetrievalPlanner(
        StructuredQuestionAnalyzer(AnalysisModel(ModelTimeoutError())),
        SingleRewritePlanner(fallback),
    )

    result = await planner.plan(
        RetrievalPlanningRequest(
            original_question="它为什么会失效？",
            model_input="上轮讨论联合索引。它为什么会失效？",
            conversation_context_present=True,
        )
    )

    assert result.query.query == "降级后的单 Query"
    assert result.model_calls == 2
    assert fallback.calls == 1


@pytest.mark.asyncio
async def test_exact_b_query_keeps_the_full_question_intent() -> None:
    question = "innodb_flush_log_at_trx_commit=2 时日志写到哪里？"
    analysis = QuestionAnalysisDraft(
        original_question=question,
        standalone_question=question,
        complexity=QueryComplexity.EXACT,
        context_dependent=False,
        ambiguous=False,
        keywords=["redo log", "刷盘"],
        exact_terms=["innodb_flush_log_at_trx_commit", "2"],
        sub_questions=[],
        candidate_queries=[
            original_query(question),
            PlannedQuery(
                query_id="q-exact",
                text="innodb_flush_log_at_trx_commit 2",
                purpose=QueryPurpose.EXACT,
                mode=RetrievalMode.EXACT,
                weight=1.0,
                target_sub_question_ids=[],
            ),
        ],
        confidence=0.95,
        analyzer_version="question-analysis.v1",
    )
    planner = UnifiedRetrievalPlanner(
        StructuredQuestionAnalyzer(AnalysisModel(analysis)),
        SingleRewritePlanner(RewriteModel()),
    )

    result = await planner.plan(
        RetrievalPlanningRequest(original_question=question, model_input=question)
    )

    assert result.strategy is RetrievalStrategy.SINGLE_REWRITE
    assert result.query.query == question
    assert result.query.mode is RetrievalMode.KEYWORD
