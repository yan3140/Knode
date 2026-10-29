from yan_agent.application.question_analysis import (
    ANALYZER_PROMPT_VERSION,
    StructuredQuestionAnalyzer,
)
from yan_agent.application.retrieval_planning import deterministic_retrieval_mode
from yan_agent.domain.errors import (
    ModelInvalidResponseError,
    ModelRateLimitedError,
    ModelTimeoutError,
    ModelUnavailableError,
)
from yan_agent.domain.qa import QueryRewrite
from yan_agent.domain.question_analysis import QuestionAnalysis
from yan_agent.domain.retrieval_planning import (
    PlannedQuery,
    QueryComplexity,
    QueryPurpose,
    RetrievalPlan,
    RetrievalPlanningRequest,
    RetrievalPlanningResult,
    RetrievalStrategy,
)
from yan_agent.ports.retrieval_planning import RetrievalPlanner

_FALLBACK_ERRORS = (
    ModelTimeoutError,
    ModelRateLimitedError,
    ModelUnavailableError,
    ModelInvalidResponseError,
)


class UnifiedRetrievalPlanner:
    strategy = RetrievalStrategy.SINGLE_REWRITE
    model_calls_per_plan = 3
    planner_version = "unified-retrieval.v1"
    prompt_version = ANALYZER_PROMPT_VERSION

    def __init__(
        self,
        analyzer: StructuredQuestionAnalyzer,
        fallback_planner: RetrievalPlanner,
    ) -> None:
        self._analyzer = analyzer
        self._fallback_planner = fallback_planner

    async def plan(self, request: RetrievalPlanningRequest) -> RetrievalPlanningResult:
        try:
            execution = await self._analyzer.analyze_with_usage(request)
        except _FALLBACK_ERRORS as error:
            analyzer_calls = max(1, error.model_calls)
            if not request.conversation_context_present:
                mode = (
                    request.requested_mode
                    if request.requested_mode.value != "AUTO"
                    else deterministic_retrieval_mode(request.original_question)
                )
                return RetrievalPlanningResult(
                    strategy=RetrievalStrategy.SINGLE_REWRITE,
                    query=QueryRewrite(query=request.original_question, mode=mode),
                    planner_version="deterministic-fallback.v1",
                    prompt_version=ANALYZER_PROMPT_VERSION,
                    model_calls=analyzer_calls,
                    planning_fallback_reason=error.code.value,
                )
            fallback = await self._fallback_planner.plan(request)
            return fallback.model_copy(
                update={
                    "model_calls": analyzer_calls + fallback.model_calls,
                    "planning_fallback_reason": error.code.value,
                }
            )
        return _planning_result_from_analysis(
            execution.analysis,
            model_calls=execution.model_calls,
        )


def _planning_result_from_analysis(
    analysis: QuestionAnalysis,
    *,
    model_calls: int,
) -> RetrievalPlanningResult:
    if _qualifies_for_multi_query(analysis):
        plan = RetrievalPlan(
            original_question=analysis.original_question,
            standalone_question=analysis.standalone_question,
            complexity=analysis.complexity,
            sub_questions=analysis.sub_questions,
            queries=analysis.candidate_queries,
            exact_terms=analysis.exact_terms,
            filters={},
            planner_version="multi-query.v1",
        )
        original = next(query for query in plan.queries if query.purpose is QueryPurpose.ORIGINAL)
        return RetrievalPlanningResult(
            strategy=RetrievalStrategy.MULTI_QUERY,
            query=QueryRewrite(query=original.text, mode=original.mode),
            planner_version="unified-retrieval.v1",
            prompt_version=ANALYZER_PROMPT_VERSION,
            model_calls=model_calls,
            plan=plan,
            analysis=analysis,
        )

    query = _single_query_from_analysis(analysis)
    return RetrievalPlanningResult(
        strategy=RetrievalStrategy.SINGLE_REWRITE,
        query=QueryRewrite(query=query.text, mode=query.mode),
        planner_version="unified-retrieval.v1",
        prompt_version=ANALYZER_PROMPT_VERSION,
        model_calls=model_calls,
        analysis=analysis,
    )


def _qualifies_for_multi_query(analysis: QuestionAnalysis) -> bool:
    if analysis.complexity is not QueryComplexity.COMPOUND:
        return False
    if len(analysis.sub_questions) < 2:
        return False
    covered = {
        sub_question_id
        for query in analysis.candidate_queries
        if query.purpose is not QueryPurpose.ORIGINAL
        for sub_question_id in query.target_sub_question_ids
    }
    expected = {item.sub_question_id for item in analysis.sub_questions}
    return len(analysis.candidate_queries) >= 3 and expected.issubset(covered)


def _single_query_from_analysis(analysis: QuestionAnalysis) -> PlannedQuery:
    standalone = " ".join(analysis.standalone_question.split()).casefold()
    standalone_matches = [
        query
        for query in analysis.candidate_queries
        if " ".join(query.text.split()).casefold() == standalone
    ]
    if standalone_matches:
        return max(standalone_matches, key=lambda query: query.weight)
    exact = [query for query in analysis.candidate_queries if query.purpose is QueryPurpose.EXACT]
    if analysis.exact_terms and exact:
        selected = max(exact, key=lambda query: query.weight)
        return selected.model_copy(update={"text": analysis.standalone_question})
    alternatives = [
        query for query in analysis.candidate_queries if query.purpose is not QueryPurpose.ORIGINAL
    ]
    if alternatives:
        selected = max(alternatives, key=lambda query: query.weight)
        return selected.model_copy(update={"text": analysis.standalone_question})
    return analysis.candidate_queries[0].model_copy(update={"text": analysis.standalone_question})
