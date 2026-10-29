from typing import Literal

from pydantic import AliasChoices, Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from yan_agent.application.adaptive_retrieval_router import AdaptiveRoutingPolicy
from yan_agent.domain.budget import AgentBudgetPolicy


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        env_prefix="YAN_AGENT_",
        extra="ignore",
    )

    postgres_dsn: str = "postgresql://yan_agent:yan_agent@localhost:5432/yan_agent"
    redis_url: str = "redis://localhost:6379/0"
    api_host: str = "0.0.0.0"
    api_port: int = Field(default=8990, ge=1, le=65535)
    worker_metrics_port: int = Field(default=8991, ge=1, le=65535)
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"] = "INFO"
    otel_service_name: str = Field(default="yan-agent", min_length=1, max_length=100)
    otel_exporter_otlp_endpoint: str = ""
    otel_trace_sample_ratio: float = Field(default=1.0, ge=0, le=1)
    java_base_url: str = "http://localhost:8989"
    java_service_key: SecretStr = SecretStr("")
    principal_jwt_secret: SecretStr = SecretStr("")
    model_api_key: SecretStr = Field(
        default=SecretStr(""),
        validation_alias=AliasChoices(
            "YAN_AGENT_MODEL_API_KEY",
            "OPENAI_API_KEY",
        ),
    )
    model_responses_url: str = "https://api.wenwenai.org/v1/responses"
    model_name: str = "gpt-5.4"
    model_reasoning_effort: Literal["none", "low", "medium", "high", "xhigh"] = "medium"
    model_timeout_seconds: int = Field(default=90, ge=5, le=300)
    model_max_output_tokens: int = Field(default=2000, ge=128, le=16000)
    analyzer_reasoning_effort: Literal["none", "low", "medium", "high", "xhigh"] = "none"
    analyzer_timeout_seconds: float = Field(default=8.0, ge=1, le=60)
    analyzer_max_output_tokens: int = Field(default=1000, ge=128, le=4000)
    analyzer_max_attempts: int = Field(default=1, ge=1, le=2)
    model_input_cost_per_million: float = Field(default=0, ge=0)
    model_output_cost_per_million: float = Field(default=0, ge=0)
    budget_max_model_calls: int = Field(default=12, ge=1, le=100)
    budget_max_total_seconds: int = Field(default=120, ge=1, le=900)
    budget_max_context_chars: int = Field(default=12000, ge=100, le=200000)
    budget_max_total_evidence_chars: int = Field(default=24000, ge=100, le=500000)
    budget_max_citation_chars: int = Field(default=8000, ge=100, le=100000)
    budget_max_answer_chars: int = Field(default=6000, ge=100, le=100000)
    dependency_timeout_seconds: int = Field(default=1, ge=1, le=10)
    context_cache_ttl_seconds: int = Field(default=1800, ge=60, le=86400)
    context_cache_ttl_jitter_ratio: float = Field(default=0.1, ge=0, le=0.5)
    context_cache_max_messages: int = Field(default=20, ge=1, le=100)
    context_rebuild_lease_seconds: int = Field(default=5, ge=1, le=60)
    context_rebuild_retry_seconds: float = Field(default=0.05, ge=0.01, le=1)
    context_max_concurrent_rebuilds: int = Field(default=16, ge=1, le=1000)
    worker_lease_seconds: int = Field(default=30, ge=5, le=300)
    worker_poll_seconds: float = Field(default=0.5, ge=0.05, le=10)
    event_poll_seconds: float = Field(default=5.0, ge=0.1, le=60)
    adaptive_routing_enabled: bool = True
    adaptive_routing_shadow_mode: bool = True
    adaptive_execution_enabled: bool = False
    raw_query_enabled: bool = False
    raw_query_rollout_percent: int = Field(default=0, ge=0, le=100)
    multi_query_enabled: bool = False
    multi_query_rollout_percent: int = Field(default=0, ge=0, le=100)
    multi_query_quality_gate_passed: bool = False
    routing_rollout_salt: str = Field(
        default="adaptive-router.v1",
        min_length=1,
        max_length=100,
    )
    routing_minimum_multi_query_model_calls: int = Field(default=1, ge=0, le=10)
    routing_minimum_multi_query_seconds: float = Field(default=20, ge=0, le=300)

    @model_validator(mode="after")
    def validate_adaptive_execution(self) -> "Settings":
        if self.adaptive_execution_enabled and not self.adaptive_routing_enabled:
            raise ValueError("adaptive execution requires adaptive routing")
        if self.raw_query_rollout_percent > 0 and not self.raw_query_enabled:
            raise ValueError("raw query rollout requires raw query to be enabled")
        if self.multi_query_rollout_percent > 0 and not self.multi_query_enabled:
            raise ValueError("multi query rollout requires multi query to be enabled")
        if self.multi_query_rollout_percent > 0 and not self.multi_query_quality_gate_passed:
            raise ValueError("multi query rollout requires a passed quality gate")
        return self

    @property
    def sqlalchemy_dsn(self) -> str:
        return self.postgres_dsn.replace("postgresql://", "postgresql+psycopg://", 1)

    @property
    def agent_budget(self) -> AgentBudgetPolicy:
        return AgentBudgetPolicy(
            max_model_calls=self.budget_max_model_calls,
            max_total_seconds=self.budget_max_total_seconds,
            max_context_chars=self.budget_max_context_chars,
            max_total_evidence_chars=self.budget_max_total_evidence_chars,
            max_citation_chars=self.budget_max_citation_chars,
            max_answer_chars=self.budget_max_answer_chars,
        )

    @property
    def adaptive_routing_policy(self) -> AdaptiveRoutingPolicy:
        return AdaptiveRoutingPolicy(
            shadow_mode=self.adaptive_routing_shadow_mode,
            raw_query_enabled=self.raw_query_enabled,
            raw_query_rollout_percent=self.raw_query_rollout_percent,
            multi_query_enabled=self.multi_query_enabled,
            multi_query_rollout_percent=self.multi_query_rollout_percent,
            rollout_salt=self.routing_rollout_salt,
            minimum_multi_query_model_calls=(self.routing_minimum_multi_query_model_calls),
            minimum_multi_query_seconds=self.routing_minimum_multi_query_seconds,
        )
