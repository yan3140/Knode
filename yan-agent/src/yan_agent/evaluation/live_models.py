import hashlib
from collections import Counter
from datetime import datetime
from enum import StrEnum
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

from yan_agent.evaluation.protocol import KnowledgeBaseManifest, load_manifest


class LiveEvalModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
    )


class LiveEvalCategory(StrEnum):
    SIMPLE_FACT = "simple_fact"
    ALIAS_OR_COLLOQUIAL = "alias_or_colloquial"
    CAUSE_AND_PRINCIPLE = "cause_and_principle"
    COMPARISON = "comparison"
    COMPOUND = "compound"
    MULTI_HOP = "multi_hop"
    EXACT_IDENTIFIER = "exact_identifier"
    MULTI_TURN_COREFERENCE = "multi_turn_coreference"
    UNANSWERABLE = "unanswerable"


class LiveEvalDifficulty(StrEnum):
    EASY = "easy"
    MEDIUM = "medium"
    HARD = "hard"


class LiveEvalCase(LiveEvalModel):
    case_id: str = Field(pattern=r"^mysql-[a-z0-9-]+-[0-9]{3}$")
    category: LiveEvalCategory
    difficulty: LiveEvalDifficulty
    question: str = Field(min_length=2, max_length=2000)
    conversation_context: str | None = Field(default=None, max_length=12000)
    expected_document_ids: list[int]
    expected_chunk_ids: list[str]
    required_facts: list[str]
    forbidden_claims: list[str]
    answerable: bool

    @model_validator(mode="after")
    def validate_ground_truth(self) -> "LiveEvalCase":
        if len(self.expected_document_ids) != len(set(self.expected_document_ids)):
            raise ValueError("expectedDocumentIds must be unique")
        if len(self.expected_chunk_ids) != len(set(self.expected_chunk_ids)):
            raise ValueError("expectedChunkIds must be unique")
        if any(not fact.strip() for fact in self.required_facts):
            raise ValueError("requiredFacts must not contain blank values")
        if any(not claim.strip() for claim in self.forbidden_claims):
            raise ValueError("forbiddenClaims must not contain blank values")
        if self.answerable:
            if not self.expected_document_ids:
                raise ValueError("answerable cases require expectedDocumentIds")
            if not self.expected_chunk_ids:
                raise ValueError("answerable cases require expectedChunkIds")
            if not self.required_facts:
                raise ValueError("answerable cases require requiredFacts")
        elif self.expected_document_ids or self.expected_chunk_ids or self.required_facts:
            raise ValueError("unanswerable cases must not declare positive evidence")
        if self.category is LiveEvalCategory.UNANSWERABLE and self.answerable:
            raise ValueError("unanswerable category cases must set answerable to false")
        if self.category is not LiveEvalCategory.UNANSWERABLE and not self.answerable:
            raise ValueError("only unanswerable category cases may set answerable to false")
        if self.category is LiveEvalCategory.MULTI_TURN_COREFERENCE:
            if self.conversation_context is None or not self.conversation_context.strip():
                raise ValueError("multi-turn coreference cases require conversationContext")
        return self


class DatasetFileIdentity(LiveEvalModel):
    path: str = Field(min_length=1)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    case_count: int = Field(gt=0)
    category_counts: dict[LiveEvalCategory, int]

    @model_validator(mode="after")
    def validate_category_total(self) -> "DatasetFileIdentity":
        if self.case_count != sum(self.category_counts.values()):
            raise ValueError("caseCount must equal the categoryCounts total")
        return self


class LiveDatasetManifest(LiveEvalModel):
    schema_version: str = Field(pattern=r"^1\.0$")
    dataset_id: str = Field(pattern=r"^[a-z0-9][a-z0-9._-]{2,63}$")
    status: str = Field(pattern=r"^FROZEN$")
    frozen_at: datetime
    protocol_id: str = Field(min_length=1)
    knowledge_base_manifest_id: str = Field(min_length=1)
    minimum_case_count: int = Field(ge=100)
    development: DatasetFileIdentity
    test: DatasetFileIdentity

    @model_validator(mode="after")
    def validate_total(self) -> "LiveDatasetManifest":
        if self.development.case_count + self.test.case_count < self.minimum_case_count:
            raise ValueError("dataset does not meet minimumCaseCount")
        return self


def load_live_cases(path: Path) -> list[LiveEvalCase]:
    cases = [
        LiveEvalCase.model_validate_json(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    if not cases:
        raise ValueError("live evaluation dataset must not be empty")
    identifiers = [case.case_id for case in cases]
    if len(identifiers) != len(set(identifiers)):
        raise ValueError("caseId values must be unique within a dataset")
    normalized_questions = [_normalize_question(case.question) for case in cases]
    if len(normalized_questions) != len(set(normalized_questions)):
        raise ValueError("questions must be unique within a dataset")
    return cases


def load_live_dataset_manifest(path: Path) -> LiveDatasetManifest:
    return LiveDatasetManifest.model_validate_json(path.read_text(encoding="utf-8"))


def validate_dataset_bundle(
    dataset_manifest_path: Path,
    knowledge_manifest_path: Path,
) -> tuple[list[LiveEvalCase], list[LiveEvalCase]]:
    dataset_manifest = load_live_dataset_manifest(dataset_manifest_path)
    knowledge_manifest = load_manifest(knowledge_manifest_path)
    if dataset_manifest.knowledge_base_manifest_id != knowledge_manifest.manifest_id:
        raise ValueError("dataset and knowledge base manifest identifiers do not match")
    root = dataset_manifest_path.parent
    development = _load_and_validate_file(root, dataset_manifest.development)
    test = _load_and_validate_file(root, dataset_manifest.test)
    development_ids = {case.case_id for case in development}
    test_ids = {case.case_id for case in test}
    if development_ids & test_ids:
        raise ValueError("development and test caseId values must be disjoint")
    development_questions = {_normalize_question(case.question) for case in development}
    test_questions = {_normalize_question(case.question) for case in test}
    if development_questions & test_questions:
        raise ValueError("development and test questions must be disjoint")
    all_cases = [*development, *test]
    if len(all_cases) < dataset_manifest.minimum_case_count:
        raise ValueError("dataset bundle does not meet minimumCaseCount")
    if set(case.category for case in all_cases) != set(LiveEvalCategory):
        raise ValueError("dataset bundle must cover every required category")
    _validate_evidence_references(all_cases, knowledge_manifest)
    return development, test


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def category_counts(cases: list[LiveEvalCase]) -> dict[LiveEvalCategory, int]:
    return dict(Counter(case.category for case in cases))


def _load_and_validate_file(root: Path, identity: DatasetFileIdentity) -> list[LiveEvalCase]:
    path = (root / identity.path).resolve()
    if root.resolve() not in path.parents:
        raise ValueError("dataset path must stay inside the dataset directory")
    if file_sha256(path) != identity.sha256:
        raise ValueError(f"dataset hash mismatch: {identity.path}")
    cases = load_live_cases(path)
    if len(cases) != identity.case_count:
        raise ValueError(f"dataset case count mismatch: {identity.path}")
    if category_counts(cases) != identity.category_counts:
        raise ValueError(f"dataset category counts mismatch: {identity.path}")
    return cases


def _validate_evidence_references(
    cases: list[LiveEvalCase],
    manifest: KnowledgeBaseManifest,
) -> None:
    versions = {
        document.document_id: document.document_version_id for document in manifest.documents
    }
    for case in cases:
        if not case.answerable:
            continue
        if not set(case.expected_document_ids).issubset(versions):
            raise ValueError(f"{case.case_id} references a document outside the manifest")
        for chunk_id in case.expected_chunk_ids:
            parts = chunk_id.split(":")
            if len(parts) != 3 or not all(part.isdigit() for part in parts):
                raise ValueError(f"{case.case_id} has an invalid chunk id: {chunk_id}")
            document_id, version_id, _chunk_index = map(int, parts)
            if document_id not in case.expected_document_ids:
                raise ValueError(f"{case.case_id} chunk does not belong to an expected document")
            if versions.get(document_id) != version_id:
                raise ValueError(f"{case.case_id} chunk version is outside the frozen manifest")


def _normalize_question(question: str) -> str:
    return "".join(question.lower().split()).rstrip("?？。！!")
