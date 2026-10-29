import json
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from urllib.request import Request

import pytest

from yan_agent.operations.rollout_gate import (
    ManualReviewEvidence,
    PrometheusClient,
    RolloutGateStatus,
    RolloutSnapshot,
    RolloutStage,
    collect_rollout_snapshot,
    evaluate_rollout,
    rollout_prometheus_queries,
    write_rollout_report,
)


def shadow_snapshot(**updates: float) -> RolloutSnapshot:
    values: dict[str, object] = {
        "collected_at": datetime(2026, 8, 22, tzinfo=UTC),
        "window_hours": 24,
        "observed_hours": 24,
        "scrape_availability_ratio": 1,
        "total_runs": 1100,
        "routable_runs": 1000,
        "audited_decisions": 1000,
        "raw_candidate_runs": 100,
        "raw_effective_runs": 0,
        "single_rewrite_runs": 1000,
        "multi_query_runs": 0,
        "fast_path_fallback_runs": 0,
        "planner_fallback_runs": 0,
        "raw_non_success_runs": 0,
        "raw_degraded_runs": 0,
        "total_p95_seconds": 9,
        "raw_p95_seconds": None,
        "single_rewrite_p95_seconds": 9,
        "active_rollback_alerts": 0,
    }
    values.update(updates)
    return RolloutSnapshot.model_validate(values)


def test_shadow_gate_passes_only_with_a_complete_observation_window() -> None:
    report = evaluate_rollout(RolloutStage.SHADOW, shadow_snapshot())

    assert report.status is RolloutGateStatus.PASS
    assert all(check.passed for check in report.checks)


def test_shadow_gate_blocks_insufficient_or_unaudited_traffic() -> None:
    report = evaluate_rollout(
        RolloutStage.SHADOW,
        shadow_snapshot(
            observed_hours=2,
            routable_runs=900,
            audited_decisions=890,
        ),
    )

    assert report.status is RolloutGateStatus.BLOCKED
    failed = {check.gate_id for check in report.checks if not check.passed}
    assert {"observation_window", "minimum_routable_runs", "audit_completeness"} <= failed


def test_a10_gate_requires_expected_traffic_split_and_human_quality_evidence() -> None:
    snapshot = shadow_snapshot(
        total_runs=2400,
        routable_runs=2200,
        audited_decisions=2200,
        raw_candidate_runs=2100,
        raw_effective_runs=210,
        single_rewrite_runs=1990,
        raw_p95_seconds=1.2,
        single_rewrite_p95_seconds=8,
    )
    review = ManualReviewEvidence(
        stage=RolloutStage.A10,
        reviewed_at=datetime(2026, 8, 22, tzinfo=UTC),
        reviewer="qa-owner",
        reviewed_answers=20,
        unsupported_answers=0,
        citation_violations=0,
    )

    report = evaluate_rollout(RolloutStage.A10, snapshot, review)

    assert report.status is RolloutGateStatus.PASS
    assert report.manual_review == review


def test_a10_gate_blocks_without_manual_review_even_when_metrics_pass() -> None:
    snapshot = shadow_snapshot(
        routable_runs=2200,
        audited_decisions=2200,
        raw_candidate_runs=2100,
        raw_effective_runs=210,
        single_rewrite_runs=1990,
        raw_p95_seconds=1.2,
        single_rewrite_p95_seconds=8,
    )

    report = evaluate_rollout(RolloutStage.A10, snapshot)

    assert report.status is RolloutGateStatus.BLOCKED
    failed = {check.gate_id for check in report.checks if not check.passed}
    assert "manual_review_sample" in failed


def test_manual_review_rejects_counts_larger_than_the_reviewed_sample() -> None:
    with pytest.raises(ValueError, match="cannot exceed reviewed_answers"):
        ManualReviewEvidence(
            stage=RolloutStage.A10,
            reviewed_at=datetime(2026, 8, 22, tzinfo=UTC),
            reviewer="qa-owner",
            reviewed_answers=20,
            unsupported_answers=21,
            citation_violations=0,
        )


class FakePrometheusResponse:
    status = 200

    def __init__(self, value: float) -> None:
        self._payload = json.dumps(
            {
                "status": "success",
                "data": {
                    "resultType": "vector",
                    "result": [{"value": [1_776_729_600, str(value)]}],
                },
            }
        ).encode()

    def read(self) -> bytes:
        return self._payload

    def __enter__(self) -> "FakePrometheusResponse":
        return self

    def __exit__(self, *_args: object) -> None:
        return None


def test_prometheus_client_queries_the_http_api_and_reads_a_scalar(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    requested_urls: list[str] = []

    def fake_urlopen(request: Request, timeout: float) -> FakePrometheusResponse:
        requested_urls.append(request.full_url)
        assert timeout == 5
        return FakePrometheusResponse(42)

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)

    value = PrometheusClient("http://127.0.0.1:19090", timeout_seconds=5).query(
        "sum(increase(yan_agent_runs_total[24h]))"
    )

    assert value == 42
    assert parse_qs(urlparse(requested_urls[0]).query)["query"] == [
        "sum(increase(yan_agent_runs_total[24h]))"
    ]


def test_snapshot_collector_maps_named_prometheus_queries_without_hiding_missing_data() -> None:
    queries = rollout_prometheus_queries(24)
    values = {expression: float(index + 1) for index, expression in enumerate(queries.values())}
    values[queries["scrape_availability_ratio"]] = 1

    snapshot = collect_rollout_snapshot(
        lambda expression: values.get(expression),
        window_hours=24,
        scrape_interval_seconds=10,
        collected_at=datetime(2026, 8, 22, tzinfo=UTC),
    )

    assert snapshot.observed_hours == pytest.approx(values[queries["scrape_samples"]] / 360)
    assert snapshot.total_runs == values[queries["total_runs"]]
    assert snapshot.active_rollback_alerts == values[queries["active_rollback_alerts"]]


def test_rollout_report_writes_json_and_markdown_evidence(tmp_path: Path) -> None:
    report = evaluate_rollout(RolloutStage.SHADOW, shadow_snapshot(observed_hours=1))
    output = tmp_path / "shadow-gate.json"

    markdown = write_rollout_report(report, output)

    payload = json.loads(output.read_text(encoding="utf-8"))
    assert payload["status"] == "BLOCKED"
    assert markdown == output.with_suffix(".md")
    assert "BLOCKED" in markdown.read_text(encoding="utf-8")
