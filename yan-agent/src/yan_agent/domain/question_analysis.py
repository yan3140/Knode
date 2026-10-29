from pydantic import Field, model_validator

from yan_agent.domain.qa import DomainModel
from yan_agent.domain.retrieval_query import (
    PlannedQuery,
    QueryComplexity,
    QueryPurpose,
    SubQuestion,
)


class QuestionAnalysis(DomainModel):
    original_question: str = Field(min_length=1, max_length=2000)
    standalone_question: str = Field(min_length=1, max_length=2000)
    complexity: QueryComplexity
    context_dependent: bool
    ambiguous: bool
    keywords: list[str] = Field(default_factory=list, max_length=20)
    exact_terms: list[str] = Field(default_factory=list, max_length=20)
    sub_questions: list[SubQuestion] = Field(default_factory=list, max_length=3)
    candidate_queries: list[PlannedQuery] = Field(min_length=1, max_length=4)
    confidence: float = Field(ge=0, le=1)
    analyzer_version: str = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def validate_analysis(self) -> "QuestionAnalysis":
        query_ids = [query.query_id for query in self.candidate_queries]
        if len(query_ids) != len(set(query_ids)):
            raise ValueError("candidate query IDs must be unique")
        normalized_queries = [
            " ".join(query.text.split()).casefold() for query in self.candidate_queries
        ]
        if len(normalized_queries) != len(set(normalized_queries)):
            raise ValueError("candidate query texts must be unique")

        originals = [
            query for query in self.candidate_queries if query.purpose is QueryPurpose.ORIGINAL
        ]
        if len(originals) != 1:
            raise ValueError("analysis must contain exactly one ORIGINAL query")
        if " ".join(originals[0].text.split()) != " ".join(self.original_question.split()):
            raise ValueError("ORIGINAL query must preserve the original question")

        sub_question_ids = [item.sub_question_id for item in self.sub_questions]
        if len(sub_question_ids) != len(set(sub_question_ids)):
            raise ValueError("sub-question IDs must be unique")
        known_sub_questions = set(sub_question_ids)
        for query in self.candidate_queries:
            if set(query.target_sub_question_ids) - known_sub_questions:
                raise ValueError("candidate query references an unknown sub-question")

        if self.complexity is QueryComplexity.COMPOUND and not self.sub_questions:
            raise ValueError("COMPOUND analysis requires sub-questions")
        if self.complexity is QueryComplexity.EXACT:
            if not self.exact_terms:
                raise ValueError("EXACT analysis requires exact terms")
            if not any(query.purpose is QueryPurpose.EXACT for query in self.candidate_queries):
                raise ValueError("EXACT analysis requires an EXACT query")
        for term in self.exact_terms:
            if not term.strip() or not any(term in query.text for query in self.candidate_queries):
                raise ValueError(f"candidate query must preserve exact term: {term}")
        return self


class QuestionAnalysisDraft(DomainModel):
    original_question: str = Field(min_length=1, max_length=2000)
    standalone_question: str = Field(min_length=1, max_length=2000)
    complexity: QueryComplexity
    context_dependent: bool
    ambiguous: bool
    keywords: list[str] = Field(default_factory=list, max_length=20)
    exact_terms: list[str] = Field(default_factory=list, max_length=20)
    sub_questions: list[SubQuestion] = Field(default_factory=list, max_length=3)
    candidate_queries: list[PlannedQuery] = Field(min_length=1, max_length=4)
    confidence: float = Field(ge=0, le=1)
    analyzer_version: str = Field(min_length=1, max_length=100)
