from pydantic import Field

from yan_agent.domain.qa import DomainModel


class AgentBudgetPolicy(DomainModel):
    max_model_calls: int = Field(default=12, ge=1, le=100)
    max_total_seconds: int = Field(default=120, ge=1, le=900)
    max_context_chars: int = Field(default=12000, ge=100, le=200000)
    max_total_evidence_chars: int = Field(default=24000, ge=100, le=500000)
    max_citation_chars: int = Field(default=8000, ge=100, le=100000)
    max_answer_chars: int = Field(default=6000, ge=100, le=100000)
