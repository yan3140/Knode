from pydantic import BaseModel, ConfigDict, Field

from yan_agent.domain.qa import KnowledgeCitation


class GroundingModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class CitationReview(GroundingModel):
    valid: bool
    unsupported_sentence_indices: list[int]
    reason: str = Field(min_length=1, max_length=500)


class GroundedAnswerOutcome(GroundingModel):
    valid: bool
    repaired: bool
    answer: str
    citations: list[KnowledgeCitation]
    issues: list[str]
    model_calls: int = Field(default=0, ge=0)
