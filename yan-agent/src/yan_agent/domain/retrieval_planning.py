from pydantic import Field, model_validator

from yan_agent.domain.qa import DomainModel, QueryRewrite, RetrievalMode
from yan_agent.domain.question_analysis import QuestionAnalysis
from yan_agent.domain.retrieval_query import PlannedQuery as PlannedQuery
from yan_agent.domain.retrieval_query import QueryComplexity as QueryComplexity
from yan_agent.domain.retrieval_query import QueryPurpose as QueryPurpose
from yan_agent.domain.retrieval_query import SubQuestion as SubQuestion
from yan_agent.domain.retrieval_routing import RetrievalStrategy as RetrievalStrategy


class RetrievalPlanDraft(DomainModel):
    original_question: str = Field(min_length=1, max_length=2000)
    standalone_question: str = Field(min_length=1, max_length=2000)
    complexity: QueryComplexity
    sub_questions: list[SubQuestion] = Field(default_factory=list, max_length=3)
    queries: list[PlannedQuery] = Field(min_length=1, max_length=4)
    exact_terms: list[str] = Field(default_factory=list, max_length=20)
    filters: dict[str, str] = Field(default_factory=dict)
    planner_version: str = Field(min_length=1, max_length=100)


class RetrievalPlan(RetrievalPlanDraft):
    @model_validator(mode="after")
    def validate_plan(self) -> "RetrievalPlan":
        query_ids = [query.query_id for query in self.queries]
        if len(query_ids) != len(set(query_ids)):
            raise ValueError("query IDs must be unique")
        normalized_texts = [" ".join(query.text.split()).casefold() for query in self.queries]
        if len(normalized_texts) != len(set(normalized_texts)):
            raise ValueError("query texts must be unique")

        sub_question_ids = [item.sub_question_id for item in self.sub_questions]
        if len(sub_question_ids) != len(set(sub_question_ids)):
            raise ValueError("sub-question IDs must be unique")
        known_sub_questions = set(sub_question_ids)
        for query in self.queries:
            unknown = set(query.target_sub_question_ids) - known_sub_questions
            if unknown:
                raise ValueError("query references an unknown sub-question")

        original_queries = [
            query for query in self.queries if query.purpose is QueryPurpose.ORIGINAL
        ]
        if len(original_queries) != 1:
            raise ValueError("plan must contain exactly one ORIGINAL query")
        if " ".join(original_queries[0].text.split()) != " ".join(self.original_question.split()):
            raise ValueError("ORIGINAL query must preserve the original question")

        if self.complexity is QueryComplexity.SIMPLE and len(self.queries) > 2:
            raise ValueError("SIMPLE plans may contain at most 2 queries")
        if self.complexity is QueryComplexity.COMPOUND and not self.sub_questions:
            raise ValueError("COMPOUND plans require at least one sub-question")
        if self.exact_terms:
            for term in self.exact_terms:
                if not term or not any(term in query.text for query in self.queries):
                    raise ValueError(f"query must preserve exact term: {term}")
        if self.complexity is QueryComplexity.EXACT:
            if not self.exact_terms:
                raise ValueError("EXACT plans require exact terms")
            if not any(query.purpose is QueryPurpose.EXACT for query in self.queries):
                raise ValueError("EXACT plans require an EXACT query")

        if len(self.filters) > 10:
            raise ValueError("filters may contain at most 10 entries")
        for key, value in self.filters.items():
            if not key or len(key) > 100 or len(value) > 500:
                raise ValueError("filter keys or values exceed the allowed length")
        return self


class RetrievalPlanningRequest(DomainModel):
    original_question: str = Field(min_length=1, max_length=2000)
    model_input: str = Field(min_length=1, max_length=16000)
    requested_mode: RetrievalMode = RetrievalMode.AUTO
    conversation_context_present: bool = False
    knowledge_base_id: int | None = Field(default=None, gt=0)
    knowledge_base_version: str | None = Field(default=None, min_length=1, max_length=200)
    context_version: int | None = Field(default=None, ge=0)


class RetrievalPlanningResult(DomainModel):
    strategy: RetrievalStrategy
    query: QueryRewrite
    planner_version: str = Field(min_length=1, max_length=100)
    prompt_version: str | None = Field(default=None, min_length=1, max_length=100)
    model_calls: int = Field(ge=0, le=10)
    plan: RetrievalPlan | None = None
    cache_hit: bool = False
    analysis: QuestionAnalysis | None = None
    planning_fallback_reason: str | None = Field(
        default=None,
        min_length=1,
        max_length=100,
    )
