import json
from collections import Counter
from pathlib import Path

import pytest
from pydantic import ValidationError

from yan_agent.domain.retrieval_planning import RetrievalStrategy
from yan_agent.domain.retrieval_routing import RoutingReason
from yan_agent.evaluation.routing_annotations import (
    RoutingAnnotation,
    validate_routing_annotation_bundle,
)

PROJECT_ROOT = Path(__file__).parents[2]
DATASET_ROOT = PROJECT_ROOT / "evals" / "datasets"
DATASET_MANIFEST = DATASET_ROOT / "mysql_live_v1.manifest.json"
ROUTING_MANIFEST = DATASET_ROOT / "mysql_live_v1.routing.manifest.json"


def test_routing_annotation_requires_preferred_strategy_to_be_allowed() -> None:
    with pytest.raises(ValidationError, match="preferredStrategy must be allowed"):
        RoutingAnnotation(
            case_id="mysql-simple-001",
            allowed_strategies=[RetrievalStrategy.RAW_QUERY],
            preferred_strategy=RetrievalStrategy.SINGLE_REWRITE,
            routing_reasons=[RoutingReason.HIGH_CONFIDENCE_SIMPLE],
            must_preserve_terms=[],
        )


@pytest.mark.local_eval_assets(
    "datasets/mysql_live_v1.manifest.json",
    "datasets/mysql_live_v1.dev.jsonl",
    "datasets/mysql_live_v1.test.jsonl",
    "datasets/mysql_live_v1.routing.manifest.json",
    "datasets/mysql_live_v1.routing.dev.jsonl",
    "datasets/mysql_live_v1.routing.test.jsonl",
)
def test_frozen_routing_annotations_cover_each_dataset_case_once() -> None:
    development, test = validate_routing_annotation_bundle(
        ROUTING_MANIFEST,
        DATASET_MANIFEST,
    )

    assert len(development) == 85
    assert len(test) == 40
    assert len({item.case_id for item in [*development, *test]}) == 125


@pytest.mark.local_eval_assets(
    "datasets/mysql_live_v1.routing.manifest.json",
    "datasets/mysql_live_v1.routing.dev.jsonl",
    "datasets/mysql_live_v1.routing.test.jsonl",
)
def test_routing_annotation_files_contain_only_declared_schema_fields() -> None:
    manifest = json.loads(ROUTING_MANIFEST.read_text(encoding="utf-8"))
    allowed = {
        "caseId",
        "allowedStrategies",
        "preferredStrategy",
        "routingReasons",
        "mustPreserveTerms",
    }
    for split in (manifest["development"], manifest["test"]):
        path = DATASET_ROOT / split["path"]
        for line in path.read_text(encoding="utf-8").splitlines():
            assert set(json.loads(line)) == allowed


@pytest.mark.local_eval_assets(
    "datasets/mysql_live_v1.manifest.json",
    "datasets/mysql_live_v1.dev.jsonl",
    "datasets/mysql_live_v1.test.jsonl",
    "datasets/mysql_live_v1.routing.manifest.json",
    "datasets/mysql_live_v1.routing.dev.jsonl",
    "datasets/mysql_live_v1.routing.test.jsonl",
)
def test_frozen_annotations_keep_a_b_and_c_as_distinct_routing_choices() -> None:
    development, test = validate_routing_annotation_bundle(
        ROUTING_MANIFEST,
        DATASET_MANIFEST,
    )
    counts = Counter(item.preferred_strategy for item in [*development, *test])

    assert counts == {
        RetrievalStrategy.RAW_QUERY: 30,
        RetrievalStrategy.SINGLE_REWRITE: 54,
        RetrievalStrategy.MULTI_QUERY: 41,
    }


@pytest.mark.local_eval_assets(
    "datasets/mysql_live_v1.manifest.json",
    "datasets/mysql_live_v1.dev.jsonl",
    "datasets/mysql_live_v1.test.jsonl",
    "datasets/mysql_live_v1.routing.manifest.json",
    "datasets/mysql_live_v1.routing.dev.jsonl",
    "datasets/mysql_live_v1.routing.test.jsonl",
)
def test_exact_and_context_dependent_annotations_preserve_routing_invariants() -> None:
    development, test = validate_routing_annotation_bundle(
        ROUTING_MANIFEST,
        DATASET_MANIFEST,
    )
    annotations = [*development, *test]
    exact = [item for item in annotations if RoutingReason.EXACT_IDENTIFIER in item.routing_reasons]
    contextual = [
        item for item in annotations if RoutingReason.CONTEXT_DEPENDENT in item.routing_reasons
    ]

    assert len(exact) == 10
    assert all(item.must_preserve_terms for item in exact)
    assert len(contextual) == 10
    assert all(item.preferred_strategy is RetrievalStrategy.SINGLE_REWRITE for item in contextual)
