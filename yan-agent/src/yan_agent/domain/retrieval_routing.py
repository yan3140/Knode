from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field, model_validator


class RoutingDomainModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class RetrievalStrategy(StrEnum):
    RAW_QUERY = "RAW_QUERY"
    SINGLE_REWRITE = "SINGLE_REWRITE"
    MULTI_QUERY = "MULTI_QUERY"


class RequestedRetrievalStrategy(StrEnum):
    AUTO = "AUTO"
    RAW_QUERY = RetrievalStrategy.RAW_QUERY
    SINGLE_REWRITE = RetrievalStrategy.SINGLE_REWRITE
    MULTI_QUERY = RetrievalStrategy.MULTI_QUERY


class RoutingReason(StrEnum):
    HIGH_CONFIDENCE_SIMPLE = "HIGH_CONFIDENCE_SIMPLE"
    EXACT_IDENTIFIER = "EXACT_IDENTIFIER"
    CONTEXT_DEPENDENT = "CONTEXT_DEPENDENT"
    COLLOQUIAL_OR_AMBIGUOUS = "COLLOQUIAL_OR_AMBIGUOUS"
    SINGLE_INFORMATION_NEED = "SINGLE_INFORMATION_NEED"
    COMPARISON = "COMPARISON"
    MULTI_CONSTRAINT = "MULTI_CONSTRAINT"
    MULTI_SUBQUESTION = "MULTI_SUBQUESTION"
    RAW_QUERY_DISABLED = "RAW_QUERY_DISABLED"
    RAW_QUERY_OUTSIDE_ROLLOUT = "RAW_QUERY_OUTSIDE_ROLLOUT"
    MULTI_QUERY_DISABLED = "MULTI_QUERY_DISABLED"
    OUTSIDE_ROLLOUT = "OUTSIDE_ROLLOUT"
    BUDGET_INSUFFICIENT = "BUDGET_INSUFFICIENT"
    DEPENDENCY_UNHEALTHY = "DEPENDENCY_UNHEALTHY"
    PLANNER_TIMEOUT = "PLANNER_TIMEOUT"
    PLANNER_RATE_LIMITED = "PLANNER_RATE_LIMITED"
    PLANNER_INVALID_RESPONSE = "PLANNER_INVALID_RESPONSE"
    PLANNER_UNAVAILABLE = "PLANNER_UNAVAILABLE"
    PARTIAL_QUERY_FAILURE = "PARTIAL_QUERY_FAILURE"
    ALL_QUERIES_FAILED = "ALL_QUERIES_FAILED"
    ANALYZER_COLLAPSED_TO_SINGLE = "ANALYZER_COLLAPSED_TO_SINGLE"
    FAST_PATH_RETRIEVAL_FALLBACK = "FAST_PATH_RETRIEVAL_FALLBACK"
    SHADOW_MODE = "SHADOW_MODE"


class RetrievalRoutingRequest(RoutingDomainModel):
    question: str = Field(min_length=1, max_length=2000)
    conversation_context_present: bool = False
    routing_key: str = Field(min_length=1, max_length=256)
    requested_strategy: RequestedRetrievalStrategy = RequestedRetrievalStrategy.AUTO
    remaining_model_calls: int = Field(ge=0, le=100)
    remaining_seconds: float = Field(ge=0, le=900)
    dependencies_healthy: bool = True


class RetrievalRoutingDecision(RoutingDomainModel):
    requested_strategy: RequestedRetrievalStrategy
    selected_strategy: RetrievalStrategy
    effective_strategy: RetrievalStrategy
    routing_reasons: list[RoutingReason] = Field(min_length=1)
    routing_version: str = Field(min_length=1, max_length=100)
    rollout_eligible: bool
    fallback_from: RetrievalStrategy | None = None
    fallback_reason: RoutingReason | None = None

    @model_validator(mode="after")
    def validate_fallback(self) -> "RetrievalRoutingDecision":
        has_fallback = self.fallback_from is not None or self.fallback_reason is not None
        if has_fallback and (self.fallback_from is None or self.fallback_reason is None):
            raise ValueError("fallback_from and fallback_reason must be set together")
        if self.fallback_from is not None:
            if self.fallback_from is not self.selected_strategy:
                raise ValueError("fallback_from must match selected_strategy")
            if self.effective_strategy is self.selected_strategy:
                raise ValueError("fallback must change the effective strategy")
            if self.fallback_reason not in self.routing_reasons:
                raise ValueError("fallback_reason must be included in routing_reasons")
        elif self.effective_strategy is not self.selected_strategy:
            raise ValueError("different effective strategy requires fallback metadata")
        return self
