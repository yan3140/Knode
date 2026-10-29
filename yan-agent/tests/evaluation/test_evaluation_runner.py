import json
from pathlib import Path

import pytest
from prometheus_client import CollectorRegistry

from yan_agent.evaluation.runner import evaluate_dataset, quality_gate
from yan_agent.observability.metrics import AgentMetrics


@pytest.mark.asyncio
async def test_smoke_evaluation_exercises_graph_and_passes_quality_gate(
    smoke_eval_dataset: Path,
    smoke_eval_baseline: Path,
) -> None:
    metrics = AgentMetrics(CollectorRegistry())
    report = await evaluate_dataset(smoke_eval_dataset, metrics)

    assert report.case_count == 6
    assert report.metrics["case_pass_rate"] == 1
    assert report.metrics["insufficient_f1"] == 1
    assert quality_gate(report, smoke_eval_baseline) == []
    injection = next(case for case in report.cases if case.case_id == "prompt-injection-evidence")
    assert injection.security_passed is True
    exposition = metrics.render().decode()
    assert 'node="classify_intent",outcome="success"' in exposition
    assert 'node="retrieve_evidence",outcome="success"' in exposition


@pytest.mark.asyncio
async def test_quality_gate_reports_regression(
    tmp_path: Path,
    smoke_eval_dataset: Path,
) -> None:
    report = await evaluate_dataset(smoke_eval_dataset)
    baseline = tmp_path / "baseline.json"
    baseline.write_text(
        json.dumps({"minimums": {"status_accuracy": 1.01}}),
        encoding="utf-8",
    )

    assert quality_gate(report, baseline) == ["status_accuracy=1.000000 < 1.010000"]
