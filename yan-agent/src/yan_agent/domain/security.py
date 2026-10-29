from pydantic import Field

from yan_agent.domain.qa import DomainModel, KnowledgeCitation


class PreparedEvidence(DomainModel):
    citations: list[KnowledgeCitation]
    flags: list[str] = Field(default_factory=list)
    original_chars: int = Field(ge=0)
    prepared_chars: int = Field(ge=0)
