from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from yan_agent.domain.qa import (
    AgentStatus,
    GeneratedAnswer,
    IntentType,
    KnowledgeCitation,
    RetrievalMode,
)


class EvalModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class ScriptedRetrieval(EvalModel):
    query_id: int = Field(gt=0)
    selected_mode: RetrievalMode
    citations: list[KnowledgeCitation]
    sufficient: bool
    degraded: bool = False
    degradation_reasons: list[str] = Field(default_factory=list)


class EvalExpectation(EvalModel):
    status: AgentStatus
    relevant_chunk_ids: list[str] = Field(default_factory=list)
    required_security_flags: list[str] = Field(default_factory=list)
    degraded: bool = False
    selected_mode: RetrievalMode | None = None


class EvalCase(EvalModel):
    case_id: str = Field(min_length=1, max_length=100)
    category: str = Field(min_length=1, max_length=100)
    question: str = Field(min_length=1, max_length=2000)
    requested_mode: RetrievalMode = RetrievalMode.AUTO
    conversation_context: str | None = None
    intent: IntentType = IntentType.KNOWLEDGE
    rewritten_query: str = Field(min_length=1, max_length=2000)
    retrievals: list[ScriptedRetrieval] = Field(min_length=1, max_length=3)
    generated_answer: GeneratedAnswer | None = None
    conversational_answer: str = "你好，我可以帮助查询知识库内容。"
    expected: EvalExpectation
