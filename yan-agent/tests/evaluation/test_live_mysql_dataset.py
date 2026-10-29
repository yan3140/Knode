import json
from collections import Counter
from pathlib import Path

import pytest
from pydantic import ValidationError

from yan_agent.evaluation.live_models import (
    LiveEvalCase,
    LiveEvalCategory,
    file_sha256,
    load_live_dataset_manifest,
    validate_dataset_bundle,
)
from yan_agent.evaluation.protocol import load_protocol

PROJECT_ROOT = Path(__file__).parents[2]
DATASET_ROOT = PROJECT_ROOT / "evals" / "datasets"
DATASET_MANIFEST = DATASET_ROOT / "mysql_live_v1.manifest.json"
KNOWLEDGE_MANIFEST = PROJECT_ROOT / "evals" / "manifests" / "mysql_kb_v1.json"
PROTOCOL = PROJECT_ROOT / "evals" / "protocols" / "multi_query_v1.json"


@pytest.mark.local_eval_assets(
    "datasets/mysql_live_v1.manifest.json",
    "datasets/mysql_live_v1.dev.jsonl",
    "datasets/mysql_live_v1.test.jsonl",
    "manifests/mysql_kb_v1.json",
)
def test_live_mysql_dataset_bundle_is_frozen_and_complete() -> None:
    development, test = validate_dataset_bundle(DATASET_MANIFEST, KNOWLEDGE_MANIFEST)

    assert len(development) == 85
    assert len(test) == 40
    assert len(development) + len(test) == 125
    assert {case.category for case in [*development, *test]} == set(LiveEvalCategory)


@pytest.mark.local_eval_assets(
    "datasets/mysql_live_v1.manifest.json",
    "datasets/mysql_live_v1.dev.jsonl",
    "datasets/mysql_live_v1.test.jsonl",
)
def test_dataset_manifest_hashes_match_frozen_files() -> None:
    manifest = load_live_dataset_manifest(DATASET_MANIFEST)

    assert file_sha256(DATASET_ROOT / manifest.development.path) == manifest.development.sha256
    assert file_sha256(DATASET_ROOT / manifest.test.path) == manifest.test.sha256


@pytest.mark.local_eval_assets(
    "datasets/mysql_live_v1.manifest.json",
    "protocols/multi_query_v1.json",
)
def test_protocol_and_dataset_use_the_same_split_names_and_manifest() -> None:
    protocol = load_protocol(PROTOCOL)
    dataset = load_live_dataset_manifest(DATASET_MANIFEST)

    assert dataset.protocol_id == protocol.protocol_id
    assert dataset.knowledge_base_manifest_id == protocol.knowledge_base_manifest_id
    assert dataset.development.path == protocol.dataset.development_split
    assert dataset.test.path == protocol.dataset.test_split
    assert set(dataset.development.category_counts) == set(protocol.dataset.required_categories)
    assert set(dataset.test.category_counts) == set(protocol.dataset.required_categories)


@pytest.mark.local_eval_assets(
    "datasets/mysql_live_v1.manifest.json",
    "datasets/mysql_live_v1.dev.jsonl",
    "datasets/mysql_live_v1.test.jsonl",
    "manifests/mysql_kb_v1.json",
)
def test_category_distribution_matches_the_frozen_annotation_plan() -> None:
    development, test = validate_dataset_bundle(DATASET_MANIFEST, KNOWLEDGE_MANIFEST)
    counts = Counter(case.category for case in [*development, *test])

    assert counts == {
        LiveEvalCategory.SIMPLE_FACT: 20,
        LiveEvalCategory.ALIAS_OR_COLLOQUIAL: 15,
        LiveEvalCategory.CAUSE_AND_PRINCIPLE: 15,
        LiveEvalCategory.COMPARISON: 15,
        LiveEvalCategory.COMPOUND: 15,
        LiveEvalCategory.MULTI_HOP: 15,
        LiveEvalCategory.EXACT_IDENTIFIER: 10,
        LiveEvalCategory.MULTI_TURN_COREFERENCE: 10,
        LiveEvalCategory.UNANSWERABLE: 10,
    }


@pytest.mark.local_eval_assets(
    "datasets/mysql_live_v1.manifest.json",
    "datasets/mysql_live_v1.dev.jsonl",
    "datasets/mysql_live_v1.test.jsonl",
    "manifests/mysql_kb_v1.json",
)
def test_unanswerable_cases_do_not_claim_positive_evidence() -> None:
    development, test = validate_dataset_bundle(DATASET_MANIFEST, KNOWLEDGE_MANIFEST)
    unanswerable = [
        case for case in [*development, *test] if case.category is LiveEvalCategory.UNANSWERABLE
    ]

    assert len(unanswerable) == 10
    assert all(not case.answerable for case in unanswerable)
    assert all(not case.expected_document_ids for case in unanswerable)
    assert all(not case.expected_chunk_ids for case in unanswerable)
    assert all(not case.required_facts for case in unanswerable)


def test_answerable_case_without_chunk_ground_truth_is_rejected() -> None:
    payload = {
        "caseId": "mysql-simple-999",
        "category": "simple_fact",
        "difficulty": "easy",
        "question": "什么是测试问题？",
        "conversationContext": None,
        "expectedDocumentIds": [2],
        "expectedChunkIds": [],
        "requiredFacts": ["测试事实"],
        "forbiddenClaims": [],
        "answerable": True,
    }

    with pytest.raises(ValidationError, match="expectedChunkIds"):
        LiveEvalCase.model_validate(payload)


@pytest.mark.local_eval_assets(
    "datasets/mysql_live_v1.manifest.json",
    "datasets/mysql_live_v1.dev.jsonl",
    "datasets/mysql_live_v1.test.jsonl",
    "manifests/mysql_kb_v1.json",
)
def test_test_split_is_not_a_duplicate_of_development() -> None:
    development, test = validate_dataset_bundle(DATASET_MANIFEST, KNOWLEDGE_MANIFEST)

    assert {case.case_id for case in development}.isdisjoint(case.case_id for case in test)
    assert {case.question for case in development}.isdisjoint(case.question for case in test)


@pytest.mark.local_eval_assets(
    "datasets/mysql_live_v1.manifest.json",
    "datasets/mysql_live_v1.dev.jsonl",
    "datasets/mysql_live_v1.test.jsonl",
)
def test_dataset_files_contain_only_declared_schema_fields() -> None:
    manifest = load_live_dataset_manifest(DATASET_MANIFEST)
    allowed = {
        "caseId",
        "category",
        "difficulty",
        "question",
        "conversationContext",
        "expectedDocumentIds",
        "expectedChunkIds",
        "requiredFacts",
        "forbiddenClaims",
        "answerable",
    }
    for identity in (manifest.development, manifest.test):
        for line in (DATASET_ROOT / identity.path).read_text(encoding="utf-8").splitlines():
            assert set(json.loads(line)) == allowed
