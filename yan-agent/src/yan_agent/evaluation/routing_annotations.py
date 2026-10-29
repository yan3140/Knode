import hashlib
from datetime import datetime
from pathlib import Path

from pydantic import Field, model_validator

from yan_agent.domain.retrieval_planning import RetrievalStrategy
from yan_agent.domain.retrieval_routing import RoutingReason
from yan_agent.evaluation.live_models import (
    LiveEvalCase,
    LiveEvalModel,
    file_sha256,
    load_live_cases,
    load_live_dataset_manifest,
)


class RoutingAnnotation(LiveEvalModel):
    case_id: str = Field(pattern=r"^mysql-[a-z0-9-]+-[0-9]{3}$")
    allowed_strategies: list[RetrievalStrategy] = Field(min_length=1, max_length=3)
    preferred_strategy: RetrievalStrategy
    routing_reasons: list[RoutingReason] = Field(min_length=1)
    must_preserve_terms: list[str] = Field(default_factory=list, max_length=20)

    @model_validator(mode="after")
    def validate_annotation(self) -> "RoutingAnnotation":
        if len(self.allowed_strategies) != len(set(self.allowed_strategies)):
            raise ValueError("allowedStrategies must be unique")
        if self.preferred_strategy not in self.allowed_strategies:
            raise ValueError("preferredStrategy must be allowed")
        if len(self.routing_reasons) != len(set(self.routing_reasons)):
            raise ValueError("routingReasons must be unique")
        normalized_terms = [term.strip().casefold() for term in self.must_preserve_terms]
        if any(not term for term in normalized_terms):
            raise ValueError("mustPreserveTerms must not contain blank values")
        if len(normalized_terms) != len(set(normalized_terms)):
            raise ValueError("mustPreserveTerms must be unique")
        if RoutingReason.EXACT_IDENTIFIER in self.routing_reasons and not self.must_preserve_terms:
            raise ValueError("exact identifier annotations require mustPreserveTerms")
        return self


class RoutingAnnotationFileIdentity(LiveEvalModel):
    path: str = Field(min_length=1)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    case_count: int = Field(gt=0)


class RoutingAnnotationManifest(LiveEvalModel):
    schema_version: str = Field(pattern=r"^1\.0$")
    annotation_id: str = Field(pattern=r"^[a-z0-9][a-z0-9._-]{2,63}$")
    status: str = Field(pattern=r"^FROZEN$")
    frozen_at: datetime
    dataset_id: str = Field(min_length=1, max_length=64)
    dataset_manifest_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    routing_version: str = Field(pattern=r"^adaptive-router\.v[0-9]+$")
    analyzer_prompt_version: str = Field(pattern=r"^question-analysis\.v[0-9]+$")
    development: RoutingAnnotationFileIdentity
    test: RoutingAnnotationFileIdentity


def load_routing_annotations(path: Path) -> list[RoutingAnnotation]:
    annotations = [
        RoutingAnnotation.model_validate_json(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    if not annotations:
        raise ValueError("routing annotations must not be empty")
    identifiers = [annotation.case_id for annotation in annotations]
    if len(identifiers) != len(set(identifiers)):
        raise ValueError("caseId values must be unique within routing annotations")
    return annotations


def load_routing_annotation_manifest(path: Path) -> RoutingAnnotationManifest:
    return RoutingAnnotationManifest.model_validate_json(path.read_text(encoding="utf-8"))


def validate_routing_annotation_bundle(
    routing_manifest_path: Path,
    dataset_manifest_path: Path,
) -> tuple[list[RoutingAnnotation], list[RoutingAnnotation]]:
    routing_manifest = load_routing_annotation_manifest(routing_manifest_path)
    dataset_manifest = load_live_dataset_manifest(dataset_manifest_path)
    if routing_manifest.dataset_id != dataset_manifest.dataset_id:
        raise ValueError("routing annotations and dataset identifiers do not match")
    if routing_manifest.dataset_manifest_sha256 != file_sha256(dataset_manifest_path):
        raise ValueError("routing annotations were not built for this dataset manifest")

    routing_root = routing_manifest_path.parent.resolve()
    dataset_root = dataset_manifest_path.parent.resolve()
    development = _load_annotation_file(routing_root, routing_manifest.development)
    test = _load_annotation_file(routing_root, routing_manifest.test)
    development_cases = load_live_cases(dataset_root / dataset_manifest.development.path)
    test_cases = load_live_cases(dataset_root / dataset_manifest.test.path)
    _validate_case_coverage(development, development_cases, "development")
    _validate_case_coverage(test, test_cases, "test")
    if {item.case_id for item in development} & {item.case_id for item in test}:
        raise ValueError("development and test routing caseId values must be disjoint")
    return development, test


def annotation_file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _load_annotation_file(
    root: Path,
    identity: RoutingAnnotationFileIdentity,
) -> list[RoutingAnnotation]:
    path = (root / identity.path).resolve()
    if root not in path.parents:
        raise ValueError("routing annotation path must stay inside its directory")
    if annotation_file_sha256(path) != identity.sha256:
        raise ValueError(f"routing annotation hash mismatch: {identity.path}")
    annotations = load_routing_annotations(path)
    if len(annotations) != identity.case_count:
        raise ValueError(f"routing annotation case count mismatch: {identity.path}")
    return annotations


def _validate_case_coverage(
    annotations: list[RoutingAnnotation],
    cases: list[LiveEvalCase],
    split: str,
) -> None:
    annotation_ids = {item.case_id for item in annotations}
    case_ids = {item.case_id for item in cases}
    if annotation_ids != case_ids:
        missing = sorted(case_ids - annotation_ids)
        unknown = sorted(annotation_ids - case_ids)
        raise ValueError(f"{split} routing coverage mismatch: missing={missing}, unknown={unknown}")
    cases_by_id = {case.case_id: case for case in cases}
    for annotation in annotations:
        searchable_text = " ".join(
            filter(
                None,
                (
                    cases_by_id[annotation.case_id].question,
                    cases_by_id[annotation.case_id].conversation_context,
                ),
            )
        ).casefold()
        for term in annotation.must_preserve_terms:
            if term.casefold() not in searchable_text:
                raise ValueError(
                    f"{annotation.case_id} mustPreserveTerm is absent from its input: {term}"
                )
