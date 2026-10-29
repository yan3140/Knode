from datetime import datetime
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

from yan_agent.domain.retrieval_planning import RetrievalStrategy as RetrievalStrategy


class ProtocolModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
    )


class ManifestDocument(ProtocolModel):
    document_id: int = Field(gt=0)
    document_version_id: int = Field(gt=0)
    version_no: int = Field(gt=0)
    content_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    title: str = Field(min_length=1, max_length=255)
    chunk_count: int = Field(gt=0)


class IndexIdentity(ProtocolModel):
    embedding_provider: str = Field(min_length=1)
    embedding_model: str = Field(min_length=1)
    embedding_dimensions: int = Field(gt=0)
    chunking_policy_version: str = Field(min_length=1)
    keyword_schema_version: str = Field(min_length=1)


class KnowledgeBaseManifest(ProtocolModel):
    schema_version: str = Field(pattern=r"^1\.0$")
    manifest_id: str = Field(pattern=r"^[a-z0-9][a-z0-9._-]{2,63}$")
    captured_at: datetime
    environment: str = Field(min_length=1)
    knowledge_base_id: int = Field(gt=0)
    knowledge_base_name: str = Field(min_length=1)
    knowledge_base_type: str = Field(pattern=r"^(SYSTEM|PRIVATE)$")
    document_count: int = Field(gt=0)
    chunk_count: int = Field(gt=0)
    index_identity: IndexIdentity
    documents: list[ManifestDocument] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_aggregates(self) -> "KnowledgeBaseManifest":
        if self.document_count != len(self.documents):
            raise ValueError("documentCount must equal the number of manifest documents")
        if self.chunk_count != sum(document.chunk_count for document in self.documents):
            raise ValueError("chunkCount must equal the sum of document chunk counts")
        document_ids = [document.document_id for document in self.documents]
        version_ids = [document.document_version_id for document in self.documents]
        if len(document_ids) != len(set(document_ids)):
            raise ValueError("documentId values must be unique")
        if len(version_ids) != len(set(version_ids)):
            raise ValueError("documentVersionId values must be unique")
        return self


class RetrievalControls(ProtocolModel):
    top_k: int = Field(gt=0)
    candidate_k: int = Field(gt=0)
    max_queries: int = Field(gt=0, le=8)
    max_sub_questions: int = Field(gt=0, le=8)
    max_retrieval_rounds: int = Field(gt=0, le=3)
    max_concurrency: int = Field(gt=0, le=16)

    @model_validator(mode="after")
    def validate_candidate_budget(self) -> "RetrievalControls":
        if self.candidate_k < self.top_k:
            raise ValueError("candidateK must be greater than or equal to topK")
        return self


class CacheControls(ProtocolModel):
    quality_run: str = Field(pattern=r"^DISABLED$")
    latency_runs: list[str] = Field(min_length=2)
    include_strategy_in_cache_key: bool
    include_knowledge_base_version_in_cache_key: bool


class ModelControls(ProtocolModel):
    planner_model: str = Field(min_length=1)
    answer_model: str = Field(min_length=1)
    planner_prompt_version: str = Field(min_length=1)
    answer_prompt_version: str = Field(min_length=1)
    retrieval_config_version: str = Field(min_length=1)


class DatasetControls(ProtocolModel):
    minimum_case_count: int = Field(ge=100)
    development_split: str = Field(min_length=1)
    test_split: str = Field(min_length=1)
    freeze_test_before_multi_query_tuning: bool
    required_categories: list[str] = Field(min_length=1)


class QualityGates(ProtocolModel):
    recall_at_10_absolute_gain_min: float = Field(ge=0, le=1)
    complex_recall_at_10_absolute_gain_min: float = Field(ge=0, le=1)
    mrr_at_10_max_absolute_drop: float = Field(ge=0, le=1)
    insufficient_f1_max_absolute_drop: float = Field(ge=0, le=1)
    hallucination_rate_max_increase: float = Field(ge=0, le=1)
    groundedness_max_drop: float = Field(ge=0, le=1)
    latency_p95_max_ratio: float = Field(ge=1)
    average_cost_max_ratio: float = Field(ge=1)


class ReportingControls(ProtocolModel):
    repeated_runs: int = Field(ge=3)
    bootstrap_confidence_level: float = Field(gt=0, lt=1)
    report_overall_metrics: bool
    report_category_metrics: bool
    report_paired_cases: bool


class VersionRules(ProtocolModel):
    experiment_id_pattern: str = Field(min_length=1)
    required_experiment_id_parts: list[str] = Field(min_length=1)
    immutable_artifacts: list[str] = Field(min_length=1)
    version_on_control_change: bool


class ExperimentProtocol(ProtocolModel):
    schema_version: str = Field(pattern=r"^1\.0$")
    protocol_id: str = Field(pattern=r"^[a-z0-9][a-z0-9._-]{2,63}$")
    status: str = Field(pattern=r"^FROZEN$")
    frozen_at: datetime
    knowledge_base_manifest_id: str = Field(min_length=1)
    strategies: list[RetrievalStrategy]
    retrieval: RetrievalControls
    cache: CacheControls
    models: ModelControls
    dataset: DatasetControls
    quality_gates: QualityGates
    reporting: ReportingControls
    version_rules: VersionRules

    @model_validator(mode="after")
    def validate_strategies(self) -> "ExperimentProtocol":
        expected = set(RetrievalStrategy)
        if set(self.strategies) != expected or len(self.strategies) != len(expected):
            raise ValueError(
                "strategies must contain RAW_QUERY, SINGLE_REWRITE and MULTI_QUERY once"
            )
        if self.cache.quality_run != "DISABLED":
            raise ValueError("quality evaluation must disable cross-case caches")
        if not self.dataset.freeze_test_before_multi_query_tuning:
            raise ValueError("the test split must be frozen before Multi Query tuning")
        return self


def load_manifest(path: Path) -> KnowledgeBaseManifest:
    return KnowledgeBaseManifest.model_validate_json(path.read_text(encoding="utf-8"))


def load_protocol(path: Path) -> ExperimentProtocol:
    return ExperimentProtocol.model_validate_json(path.read_text(encoding="utf-8"))
