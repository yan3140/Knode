from datetime import UTC, datetime

import pytest

from yan_agent.domain.retrieval_routing import RetrievalStrategy
from yan_agent.evaluation.live_retrieval_runner import LiveRetrievalReport


@pytest.fixture
def retrieval_reports() -> dict[RetrievalStrategy, LiveRetrievalReport]:
    """Small comparison inputs with known utilities, independent of historical reports."""
    reports = {}
    categories = ["comparison", "compound", "multi_hop"]
    for strategy, score in zip(RetrievalStrategy, (0.25, 0.5, 0.75), strict=True):
        metrics = {
            "hit_at_5": 1.0,
            "hit_at_10": 1.0,
            "recall_at_5": score,
            "recall_at_10": score,
            "reciprocal_rank_at_10": score,
            "ndcg_at_10": score,
            "context_precision_at_10": score,
            "duplicate_rate": 0.0,
            "unanswerable_false_positive": None,
        }
        cases = [
            {
                "case_id": f"mysql-synthetic-{index:03d}",
                "category": category,
                "difficulty": "easy",
                "question": "Test retrieval",
                "answerable": True,
                "expected_document_ids": [1],
                "expected_chunk_ids": ["1:1:0"],
                "planning": {
                    "strategy": strategy,
                    "planner_version": "test-planner.v1",
                    "prompt_version": None,
                    "query_text": "test",
                    "requested_mode": "AUTO",
                    "model_calls": 0 if strategy is RetrievalStrategy.RAW_QUERY else 1,
                    "conversation_context_present": False,
                    "latency_ms": 1.0,
                },
                "query_executions": [],
                "final_ranking": [],
                "metrics": metrics,
                "error": None,
                "latency_ms": 10.0,
            }
            for index, category in enumerate(categories)
        ]
        aggregate = {
            key: value for key, value in metrics.items() if key != "unanswerable_false_positive"
        }
        aggregate.update(
            {
                "case_count": 3,
                "successful_case_count": 3,
                "error_count": 0,
                "answerable_count": 3,
                "unanswerable_count": 0,
                "unanswerable_false_positive_rate": None,
                "degradation_rate": 0.0,
                "latency_p50_ms": 10.0,
                "latency_p95_ms": 10.0,
            }
        )
        reports[strategy] = LiveRetrievalReport.model_validate(
            {
                "experiment_id": f"synthetic-{strategy.value}",
                "generated_at": datetime(2026, 1, 1, tzinfo=UTC),
                "dataset_id": "synthetic-v1",
                "dataset_split": "development",
                "dataset_sha256": "a" * 64,
                "protocol_id": "synthetic-protocol-v1",
                "knowledge_base_manifest_id": "synthetic-kb-v1",
                "dataset_manifest_sha256": None,
                "protocol_sha256": None,
                "knowledge_base_manifest_sha256": None,
                "git_commit": "test",
                "strategy": strategy,
                "retrieval_config_version": "synthetic-v1",
                "graph_version": "knowledge-qa.v3",
                "runner_version": "live-retrieval.v1",
                "embedding_model": None,
                "planner_model": None,
                "answer_model": None,
                "planner_prompt_version": None,
                "answer_prompt_version": None,
                "requested_mode": "AUTO",
                "top_k": 10,
                "candidate_k": 30,
                "max_concurrency": 1,
                "cache_mode": "DISABLED",
                "case_count": 3,
                "metrics": aggregate,
                "category_metrics": {category: aggregate for category in categories},
                "cases": cases,
            }
        )
    return reports
