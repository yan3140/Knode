from dataclasses import dataclass

from yan_agent.application.retrieval_planning import (
    deterministic_retrieval_mode,
    extract_exact_terms,
)
from yan_agent.domain.errors import ModelInvalidResponseError
from yan_agent.domain.qa import RetrievalMode
from yan_agent.domain.question_analysis import QuestionAnalysis, QuestionAnalysisDraft
from yan_agent.domain.retrieval_planning import (
    PlannedQuery,
    QueryPurpose,
    RetrievalPlanningRequest,
)
from yan_agent.ports.question_analysis import QuestionAnalysisModel

ANALYZER_VERSION = "question-analysis.v1"
ANALYZER_PROMPT_VERSION = "question-analysis.v2"


@dataclass(frozen=True)
class QuestionAnalysisExecution:
    analysis: QuestionAnalysis
    model_calls: int


class StructuredQuestionAnalyzer:
    def __init__(self, model: QuestionAnalysisModel) -> None:
        self._model = model

    async def analyze(self, request: RetrievalPlanningRequest) -> QuestionAnalysis:
        execution = await self.analyze_with_usage(request)
        return execution.analysis

    async def analyze_with_usage(
        self,
        request: RetrievalPlanningRequest,
    ) -> QuestionAnalysisExecution:
        required_exact_terms = extract_exact_terms(request.original_question)
        model_calls = 1
        try:
            usage_method = getattr(self._model, "analyze_question_with_usage", None)
            if callable(usage_method):
                draft, model_calls = await usage_method(
                    request.original_question,
                    request.model_input,
                    required_exact_terms,
                )
            else:
                draft = await self._model.analyze_question(
                    request.original_question,
                    request.model_input,
                    required_exact_terms,
                )
        except ModelInvalidResponseError as error:
            error.model_calls = max(error.model_calls, model_calls)
            raise
        try:
            analysis = self._validate_and_normalize(draft, request, required_exact_terms)
        except ModelInvalidResponseError as error:
            error.model_calls = max(error.model_calls, model_calls)
            raise
        return QuestionAnalysisExecution(analysis=analysis, model_calls=model_calls)

    def _validate_and_normalize(
        self,
        draft: QuestionAnalysisDraft,
        request: RetrievalPlanningRequest,
        required_exact_terms: list[str],
    ) -> QuestionAnalysis:
        if draft.original_question != request.original_question:
            raise ModelInvalidResponseError("Analyzer changed the original question")
        if draft.analyzer_version != ANALYZER_VERSION:
            raise ModelInvalidResponseError("Analyzer returned an unsupported version")
        analysis = _normalize_analysis(draft, request)
        missing_terms = [term for term in required_exact_terms if term not in analysis.exact_terms]
        if missing_terms:
            raise ModelInvalidResponseError(
                "Analyzer dropped exact terms: " + ", ".join(missing_terms)
            )
        if request.requested_mode is not RetrievalMode.AUTO:
            analysis = analysis.model_copy(
                update={
                    "candidate_queries": [
                        query.model_copy(update={"mode": request.requested_mode})
                        for query in analysis.candidate_queries
                    ]
                }
            )
        else:
            analysis = analysis.model_copy(
                update={
                    "candidate_queries": [
                        query.model_copy(
                            update={
                                "mode": deterministic_retrieval_mode(
                                    query.text,
                                    purpose=query.purpose,
                                )
                            }
                        )
                        for query in analysis.candidate_queries
                    ]
                }
            )
        return analysis


def _normalize_analysis(
    draft: QuestionAnalysisDraft,
    request: RetrievalPlanningRequest,
) -> QuestionAnalysis:
    known_sub_questions = {item.sub_question_id for item in draft.sub_questions}
    queries = _deduplicate_queries(draft.candidate_queries)
    queries = [
        query.model_copy(
            update={
                "query_id": f"q-{index + 1}",
                "weight": (0.6 if query.purpose is QueryPurpose.ORIGINAL else query.weight),
                "target_sub_question_ids": [
                    item for item in query.target_sub_question_ids if item in known_sub_questions
                ],
            }
        )
        for index, query in enumerate(queries)
    ]
    keywords = list(dict.fromkeys(item.strip() for item in draft.keywords if item.strip()))
    exact_terms = list(dict.fromkeys(item.strip() for item in draft.exact_terms if item.strip()))
    required_exact_terms = extract_exact_terms(request.original_question)
    for term in required_exact_terms:
        if term not in exact_terms:
            exact_terms.append(term)
    exact_channel_terms = required_exact_terms or (
        exact_terms if draft.complexity.value == "EXACT" else []
    )
    if exact_channel_terms and not any(query.purpose is QueryPurpose.EXACT for query in queries):
        exact_query = PlannedQuery(
            query_id="q-exact-required",
            text=" ".join(exact_channel_terms),
            purpose=QueryPurpose.EXACT,
            mode=RetrievalMode.EXACT,
            weight=1.0,
            target_sub_question_ids=[],
        )
        if len(queries) < 4:
            queries.append(exact_query)
        else:
            replaceable = [
                (index, query)
                for index, query in enumerate(queries)
                if query.purpose is not QueryPurpose.ORIGINAL
            ]
            if not replaceable:
                raise ModelInvalidResponseError("Analyzer cannot preserve required exact terms")
            replace_index, _ = min(
                replaceable,
                key=lambda item: (item[1].weight, item[0]),
            )
            queries[replace_index] = exact_query
        queries = [
            query.model_copy(update={"query_id": f"q-{index + 1}"})
            for index, query in enumerate(queries)
        ]

    standalone_question = draft.standalone_question
    if request.conversation_context_present and _normalized(standalone_question) == _normalized(
        request.original_question
    ):
        resolved = next(
            (
                query
                for query in queries
                if query.purpose is not QueryPurpose.ORIGINAL
                and _normalized(query.text) != _normalized(request.original_question)
            ),
            None,
        )
        if resolved is None:
            raise ModelInvalidResponseError(
                "Analyzer did not resolve the contextual standalone question"
            )
        standalone_question = resolved.text
    try:
        return QuestionAnalysis.model_validate(
            {
                **draft.model_dump(),
                "candidate_queries": queries,
                "keywords": keywords,
                "exact_terms": exact_terms,
                "standalone_question": standalone_question,
            }
        )
    except ValueError as error:
        raise ModelInvalidResponseError(
            "Deterministic analysis normalization produced invalid output"
        ) from error


def _deduplicate_queries(queries: list[PlannedQuery]) -> list[PlannedQuery]:
    result: list[PlannedQuery] = []
    index_by_text: dict[str, int] = {}
    for query in queries:
        normalized = " ".join(query.text.split()).casefold()
        existing_index = index_by_text.get(normalized)
        if existing_index is None:
            index_by_text[normalized] = len(result)
            result.append(query)
            continue
        existing = result[existing_index]
        preferred = (
            query
            if query.purpose is QueryPurpose.EXACT and existing.purpose is not QueryPurpose.EXACT
            else existing
        )
        result[existing_index] = preferred.model_copy(
            update={
                "weight": max(existing.weight, query.weight),
                "target_sub_question_ids": list(
                    dict.fromkeys(
                        [
                            *existing.target_sub_question_ids,
                            *query.target_sub_question_ids,
                        ]
                    )
                ),
            }
        )
    return result


def _normalized(value: str) -> str:
    return " ".join(value.split()).casefold()
