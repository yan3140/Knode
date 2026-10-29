from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class RetrievalMode(StrEnum):
    AUTO = "AUTO"
    FULL_CONTEXT = "FULL_CONTEXT"
    EXACT = "EXACT"
    KEYWORD = "KEYWORD"
    VECTOR = "VECTOR"
    HYBRID = "HYBRID"


class DegradationReason(StrEnum):
    VECTOR_UNAVAILABLE = "VECTOR_UNAVAILABLE"
    KEYWORD_UNAVAILABLE = "KEYWORD_UNAVAILABLE"
    EMBEDDING_TIMEOUT = "EMBEDDING_TIMEOUT"
    EXACT_ONLY = "EXACT_ONLY"
    FULL_CONTEXT_ONLY = "FULL_CONTEXT_ONLY"
    CONTEXT_BUDGET_EXCEEDED = "CONTEXT_BUDGET_EXCEEDED"
    NO_RESULT = "NO_RESULT"


class ExactMatchType(StrEnum):
    CLASS_NAME = "CLASS_NAME"
    METHOD_NAME = "METHOD_NAME"
    ERROR_CODE = "ERROR_CODE"
    EXCEPTION = "EXCEPTION"
    CONFIG_KEY = "CONFIG_KEY"
    HOST_OR_PORT = "HOST_OR_PORT"
    VERSION = "VERSION"
    METRIC_NAME = "METRIC_NAME"
    LOG_TERM = "LOG_TERM"
    SQL_FRAGMENT = "SQL_FRAGMENT"
    IDENTIFIER = "IDENTIFIER"


class InternalRetrievalRequest(CamelModel):
    retrieval_request_id: str = Field(min_length=1, max_length=64)
    run_id: str = Field(min_length=1, max_length=64)
    user_id: int = Field(gt=0)
    knowledge_base_id: int = Field(gt=0)
    query: str = Field(min_length=1, max_length=2000)
    requested_mode: RetrievalMode = RetrievalMode.AUTO
    top_k: int = Field(default=10, ge=1, le=50)
    candidate_k: int = Field(default=30, ge=1, le=200)

    @model_validator(mode="after")
    def validate_candidate_budget(self) -> "InternalRetrievalRequest":
        if self.candidate_k < self.top_k:
            raise ValueError("candidateK must be greater than or equal to topK")
        return self


class KnowledgeCitation(CamelModel):
    chunk_id: str
    document_id: str
    document_version_id: str
    title: str
    section: str
    source: str
    content: str
    vector_similarity: float | None
    keyword_score: float | None
    rrf_score: float | None
    final_score: float | None
    exact_match: bool
    exact_type: ExactMatchType | None


class InternalRetrievalResponse(CamelModel):
    query_id: int = Field(gt=0)
    requested_mode: RetrievalMode
    selected_mode: RetrievalMode
    citations: list[KnowledgeCitation]
    degraded: bool
    degradation_reasons: list[DegradationReason]
    total_elapsed_millis: int = Field(ge=0)
    trace_id: str = Field(min_length=1)
