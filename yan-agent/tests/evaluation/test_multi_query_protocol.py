from pathlib import Path

import pytest
from pydantic import ValidationError

from yan_agent.evaluation.protocol import (
    ExperimentProtocol,
    RetrievalStrategy,
    load_manifest,
    load_protocol,
)

PROJECT_ROOT = Path(__file__).parents[2]
MANIFEST_PATH = PROJECT_ROOT / "evals" / "manifests" / "mysql_kb_v1.json"
PROTOCOL_PATH = PROJECT_ROOT / "evals" / "protocols" / "multi_query_v1.json"
REPORT_TEMPLATE_PATH = PROJECT_ROOT / "evals" / "templates" / "multi_query_comparison_v1.md"


@pytest.mark.local_eval_assets(
    "manifests/mysql_kb_v1.json",
)
def test_mysql_manifest_freezes_current_document_and_chunk_versions() -> None:
    manifest = load_manifest(MANIFEST_PATH)

    assert manifest.manifest_id == "mysql-kb-v1"
    assert manifest.knowledge_base_id == 3
    assert manifest.document_count == 27
    assert manifest.chunk_count == 770
    assert manifest.index_identity.embedding_model == "embedding-3"
    assert manifest.index_identity.embedding_dimensions == 1024
    assert manifest.index_identity.chunking_policy_version == "markdown-heading-v1"
    assert manifest.index_identity.keyword_schema_version == "elasticsearch-standard-v1"


@pytest.mark.local_eval_assets(
    "manifests/mysql_kb_v1.json",
)
def test_manifest_contains_no_owner_or_document_content() -> None:
    raw = MANIFEST_PATH.read_text(encoding="utf-8")

    assert "ownerId" not in raw
    assert "sourceUri" not in raw
    assert '"content"' not in raw


@pytest.mark.local_eval_assets(
    "protocols/multi_query_v1.json",
)
def test_protocol_freezes_all_three_strategies_and_quality_controls() -> None:
    protocol = load_protocol(PROTOCOL_PATH)

    assert set(protocol.strategies) == set(RetrievalStrategy)
    assert protocol.knowledge_base_manifest_id == "mysql-kb-v1"
    assert protocol.retrieval.top_k == 10
    assert protocol.retrieval.candidate_k == 30
    assert protocol.retrieval.max_queries == 4
    assert protocol.cache.quality_run == "DISABLED"
    assert protocol.reporting.repeated_runs == 3
    assert protocol.quality_gates.recall_at_10_absolute_gain_min == 0.05
    assert protocol.dataset.freeze_test_before_multi_query_tuning is True


def test_protocol_rejects_an_incomplete_strategy_set() -> None:
    payload = {
        "schema_version": "1.0",
        "protocol_id": "synthetic-v1",
        "status": "FROZEN",
        "frozen_at": "2026-01-01T00:00:00Z",
        "knowledge_base_manifest_id": "synthetic-kb",
        "strategies": ["RAW_QUERY", "SINGLE_REWRITE"],
        "retrieval": {
            "top_k": 1,
            "candidate_k": 1,
            "max_queries": 1,
            "max_sub_questions": 1,
            "max_retrieval_rounds": 1,
            "max_concurrency": 1,
        },
        "cache": {
            "quality_run": "DISABLED",
            "latency_runs": ["COLD", "WARM"],
            "include_strategy_in_cache_key": True,
            "include_knowledge_base_version_in_cache_key": True,
        },
        "models": {
            "planner_model": "test",
            "answer_model": "test",
            "planner_prompt_version": "test-v1",
            "answer_prompt_version": "test-v1",
            "retrieval_config_version": "test-v1",
        },
        "dataset": {
            "minimum_case_count": 100,
            "development_split": "synthetic.dev.jsonl",
            "test_split": "synthetic.test.jsonl",
            "freeze_test_before_multi_query_tuning": True,
            "required_categories": ["simple_fact"],
        },
        "quality_gates": {
            "recall_at_10_absolute_gain_min": 0.05,
            "complex_recall_at_10_absolute_gain_min": 0.1,
            "mrr_at_10_max_absolute_drop": 0.02,
            "insufficient_f1_max_absolute_drop": 0.02,
            "hallucination_rate_max_increase": 0.0,
            "groundedness_max_drop": 0.0,
            "latency_p95_max_ratio": 1.2,
            "average_cost_max_ratio": 1.2,
        },
        "reporting": {
            "repeated_runs": 3,
            "bootstrap_confidence_level": 0.95,
            "report_overall_metrics": True,
            "report_category_metrics": True,
            "report_paired_cases": True,
        },
        "version_rules": {
            "experiment_id_pattern": "synthetic-.*",
            "required_experiment_id_parts": ["strategy"],
            "immutable_artifacts": ["dataset"],
            "version_on_control_change": True,
        },
    }

    with pytest.raises(ValidationError, match="strategies must contain"):
        ExperimentProtocol.model_validate(payload)


@pytest.mark.local_eval_assets(
    "templates/multi_query_comparison_v1.md",
)
def test_comparison_report_template_has_required_evidence_sections() -> None:
    report = REPORT_TEMPLATE_PATH.read_text(encoding="utf-8")

    for heading in (
        "## 实验身份",
        "## 总体指标",
        "## 分类指标",
        "## 配对 Case 分析",
        "## 统计结果",
        "## 门禁结论",
        "## 灰度与回滚",
    ):
        assert heading in report
