from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field

from yan_agent.domain.retrieval_routing import RetrievalRoutingDecision


class DomainModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class IntentType(StrEnum):
    KNOWLEDGE = "KNOWLEDGE"
    CLARIFICATION = "CLARIFICATION"
    CONVERSATIONAL = "CONVERSATIONAL"


class RetrievalMode(StrEnum):
    AUTO = "AUTO"
    FULL_CONTEXT = "FULL_CONTEXT"
    EXACT = "EXACT"
    KEYWORD = "KEYWORD"
    VECTOR = "VECTOR"
    HYBRID = "HYBRID"


class AgentStatus(StrEnum):
    SUCCEEDED = "SUCCEEDED"
    CLARIFICATION_REQUIRED = "CLARIFICATION_REQUIRED"
    INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE"


class AgentInvocation(DomainModel):
    run_id: str = Field(min_length=1, max_length=64)
    user_id: int = Field(gt=0)
    knowledge_base_id: int = Field(gt=0)
    question: str = Field(min_length=1, max_length=2000)
    requested_mode: RetrievalMode = RetrievalMode.AUTO
    conversation_context: str | None = None
    context_version: int | None = Field(default=None, ge=0)


class IntentDecision(DomainModel):
    intent: IntentType
    clarification_question: str | None = None


class QueryRewrite(DomainModel):
    query: str = Field(min_length=1, max_length=2000)
    mode: RetrievalMode = RetrievalMode.AUTO


class EvidenceAssessment(DomainModel):
    sufficient: bool
    reason: str = Field(min_length=1, max_length=500)


class GeneratedAnswer(DomainModel):
    answer: str = Field(min_length=1)
    citation_indices: list[int]


class KnowledgeCitation(DomainModel):
    index: int = Field(gt=0)
    chunk_id: str = Field(min_length=1)
    document_id: str = Field(min_length=1)
    document_version_id: str = Field(min_length=1)
    title: str
    section: str
    source: str
    content: str
    final_score: float | None = None


class KnowledgeRetrievalRequest(DomainModel):
    retrieval_request_id: str = Field(min_length=1, max_length=64)
    run_id: str = Field(min_length=1, max_length=64)
    user_id: int = Field(gt=0)
    knowledge_base_id: int = Field(gt=0)
    query: str = Field(min_length=1, max_length=2000)
    requested_mode: RetrievalMode


class KnowledgeRetrievalResult(DomainModel):
    query_id: int = Field(gt=0)
    selected_mode: RetrievalMode
    citations: list[KnowledgeCitation]
    degraded: bool
    degradation_reasons: list[str]


class AgentResult(DomainModel):
    run_id: str
    status: AgentStatus
    answer: str
    citations: list[KnowledgeCitation]
    query_ids: list[int]
    selected_mode: RetrievalMode | None = None
    degraded: bool = False
    degradation_reasons: list[str] = Field(default_factory=list)
    retrieval_attempts: int = Field(default=0, ge=0)
    grounding_repaired: bool = False
    grounding_issues: list[str] = Field(default_factory=list)
    security_flags: list[str] = Field(default_factory=list)
    model_calls: int = Field(default=0, ge=0)
    routing_decision: RetrievalRoutingDecision | None = None
