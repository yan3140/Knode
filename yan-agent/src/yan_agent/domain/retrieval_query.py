from enum import StrEnum

from pydantic import Field

from yan_agent.domain.qa import DomainModel, RetrievalMode


class QueryComplexity(StrEnum):
    SIMPLE = "SIMPLE"
    COMPOUND = "COMPOUND"
    EXACT = "EXACT"


class QueryPurpose(StrEnum):
    ORIGINAL = "ORIGINAL"
    SEMANTIC = "SEMANTIC"
    KEYWORD = "KEYWORD"
    EXACT = "EXACT"
    SUB_QUESTION = "SUB_QUESTION"


class SubQuestion(DomainModel):
    sub_question_id: str = Field(
        min_length=1,
        max_length=64,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]*$",
    )
    text: str = Field(min_length=1, max_length=2000)


class PlannedQuery(DomainModel):
    query_id: str = Field(
        min_length=1,
        max_length=64,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]*$",
    )
    text: str = Field(min_length=1, max_length=2000)
    purpose: QueryPurpose
    mode: RetrievalMode
    weight: float = Field(gt=0, le=1)
    target_sub_question_ids: list[str] = Field(default_factory=list, max_length=3)
