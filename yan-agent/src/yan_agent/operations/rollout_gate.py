import json
import math
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from datetime import UTC, datetime
from enum import StrEnum
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, computed_field, model_validator


class RolloutModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class RolloutStage(StrEnum):
    SHADOW = "shadow"
    A10 = "a10"
    A50 = "a50"
    A100 = "a100"


class RolloutGateStatus(StrEnum):
    PASS = "PASS"
    BLOCKED = "BLOCKED"


class RolloutCollectionError(RuntimeError):
    pass


class RolloutSnapshot(RolloutModel):
    collected_at: datetime = Field(default_factory=lambda: datetime.now(UTC))
    window_hours: float = Field(gt=0)
    observed_hours: float | None = Field(default=None, ge=0)
    scrape_availability_ratio: float | None = Field(default=None, ge=0, le=1)
    total_runs: float | None = Field(default=None, ge=0)
    routable_runs: float | None = Field(default=None, ge=0)
    audited_decisions: float | None = Field(default=None, ge=0)
    raw_candidate_runs: float | None = Field(default=None, ge=0)
    raw_effective_runs: float | None = Field(default=None, ge=0)
    single_rewrite_runs: float | None = Field(default=None, ge=0)
    multi_query_runs: float | None = Field(default=None, ge=0)
    fast_path_fallback_runs: float | None = Field(default=None, ge=0)
    planner_fallback_runs: float | None = Field(default=None, ge=0)
    raw_non_success_runs: float | None = Field(default=None, ge=0)
    raw_degraded_runs: float | None = Field(default=None, ge=0)
    total_p95_seconds: float | None = Field(default=None, ge=0)
    raw_p95_seconds: float | None = Field(default=None, ge=0)
    single_rewrite_p95_seconds: float | None = Field(default=None, ge=0)
    active_rollback_alerts: float | None = Field(default=None, ge=0)
    collection_errors: list[str] = Field(default_factory=list)

    @computed_field  # type: ignore[prop-decorator]
    @property
    def audit_completeness_ratio(self) -> float | None:
        if self.routable_runs is None or self.audited_decisions is None:
            return None
        if self.routable_runs == 0:
            return None
        return min(self.audited_decisions / self.routable_runs, 1)

    @computed_field  # type: ignore[prop-decorator]
    @property
    def planner_fallback_ratio(self) -> float | None:
        if self.routable_runs is None or self.planner_fallback_runs is None:
            return None
        if self.routable_runs == 0:
            return None
        return self.planner_fallback_runs / self.routable_runs

    @computed_field  # type: ignore[prop-decorator]
    @property
    def raw_rollout_ratio(self) -> float | None:
        if self.raw_candidate_runs is None or self.raw_effective_runs is None:
            return None
        if self.raw_candidate_runs == 0:
            return None
        return self.raw_effective_runs / self.raw_candidate_runs

    @computed_field  # type: ignore[prop-decorator]
    @property
    def fast_path_fallback_ratio(self) -> float | None:
        if self.raw_candidate_runs is None or self.fast_path_fallback_runs is None:
            return None
        if self.raw_candidate_runs == 0:
            return None
        return self.fast_path_fallback_runs / self.raw_candidate_runs

    @computed_field  # type: ignore[prop-decorator]
    @property
    def raw_to_b_p95_ratio(self) -> float | None:
        if self.raw_p95_seconds is None or self.single_rewrite_p95_seconds is None:
            return None
        if self.single_rewrite_p95_seconds == 0:
            return None
        return self.raw_p95_seconds / self.single_rewrite_p95_seconds


class ManualReviewEvidence(RolloutModel):
    stage: RolloutStage
    reviewed_at: datetime
    reviewer: str = Field(min_length=1, max_length=100)
    reviewed_answers: int = Field(ge=0)
    unsupported_answers: int = Field(ge=0)
    citation_violations: int = Field(ge=0)

    @model_validator(mode="after")
    def validate_review_counts(self) -> "ManualReviewEvidence":
        if self.unsupported_answers > self.reviewed_answers:
            raise ValueError("unsupported_answers cannot exceed reviewed_answers")
        if self.citation_violations > self.reviewed_answers:
            raise ValueError("citation_violations cannot exceed reviewed_answers")
        return self

    @computed_field  # type: ignore[prop-decorator]
    @property
    def unsupported_ratio(self) -> float | None:
        if self.reviewed_answers == 0:
            return None
        return self.unsupported_answers / self.reviewed_answers


class RolloutGateCheck(RolloutModel):
    gate_id: str
    passed: bool
    actual: float | None
    requirement: str
    detail: str


class RolloutGateReport(RolloutModel):
    stage: RolloutStage
    status: RolloutGateStatus
    generated_at: datetime = Field(default_factory=lambda: datetime.now(UTC))
    snapshot: RolloutSnapshot
    manual_review: ManualReviewEvidence | None = None
    checks: list[RolloutGateCheck] = Field(min_length=1)


class PrometheusClient:
    def __init__(self, base_url: str, *, timeout_seconds: float = 5) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds

    def query(self, expression: str) -> float | None:
        parameters = urllib.parse.urlencode({"query": expression})
        request = urllib.request.Request(
            f"{self._base_url}/api/v1/query?{parameters}",
            headers={"Accept": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=self._timeout_seconds) as response:
                payload: Any = json.load(response)
        except (OSError, ValueError, urllib.error.URLError) as error:
            raise RolloutCollectionError("Prometheus query failed") from error
        if not isinstance(payload, dict) or payload.get("status") != "success":
            raise RolloutCollectionError("Prometheus returned an unsuccessful response")
        data = payload.get("data")
        if not isinstance(data, dict):
            raise RolloutCollectionError("Prometheus response is missing data")
        result = data.get("result")
        if not isinstance(result, list):
            raise RolloutCollectionError("Prometheus response has an invalid result")
        if not result:
            return None
        first = result[0]
        if not isinstance(first, dict):
            raise RolloutCollectionError("Prometheus vector item is invalid")
        value = first.get("value")
        if not isinstance(value, list) or len(value) != 2:
            raise RolloutCollectionError("Prometheus vector value is invalid")
        try:
            parsed = float(value[1])
        except (TypeError, ValueError) as error:
            raise RolloutCollectionError("Prometheus scalar is not numeric") from error
        return parsed if math.isfinite(parsed) else None


def rollout_prometheus_queries(window_hours: float) -> dict[str, str]:
    window = f"{window_hours:g}h"
    return {
        "scrape_samples": (f'max(count_over_time(up{{job="yan-agent-worker"}}[{window}]))'),
        "scrape_availability_ratio": (
            f'min(avg_over_time(up{{job="yan-agent-worker"}}[{window}]))'
        ),
        "total_runs": f"sum(increase(yan_agent_runs_total[{window}])) or vector(0)",
        "routable_runs": (
            "sum(increase(yan_agent_runs_total"
            f'{{status=~"SUCCEEDED|INSUFFICIENT_EVIDENCE"}}[{window}])) or vector(0)'
        ),
        "audited_decisions": (
            f"sum(increase(yan_agent_routing_decisions_total[{window}])) or vector(0)"
        ),
        "raw_candidate_runs": (
            "sum(increase(yan_agent_routing_decisions_total"
            f'{{selected_strategy="RAW_QUERY"}}[{window}])) or vector(0)'
        ),
        "raw_effective_runs": (
            "sum(increase(yan_agent_routing_decisions_total"
            f'{{effective_strategy="RAW_QUERY"}}[{window}])) or vector(0)'
        ),
        "single_rewrite_runs": (
            "sum(increase(yan_agent_routing_decisions_total"
            f'{{effective_strategy="SINGLE_REWRITE"}}[{window}])) or vector(0)'
        ),
        "multi_query_runs": (
            "sum(increase(yan_agent_routing_decisions_total"
            f'{{effective_strategy="MULTI_QUERY"}}[{window}])) or vector(0)'
        ),
        "fast_path_fallback_runs": (
            "sum(increase(yan_agent_routing_decisions_total"
            f'{{fallback_reason="FAST_PATH_RETRIEVAL_FALLBACK"}}[{window}])) or vector(0)'
        ),
        "planner_fallback_runs": (
            "sum(increase(yan_agent_routing_decisions_total"
            f'{{fallback_reason=~"PLANNER_.*|FAST_PATH_RETRIEVAL_FALLBACK"}}[{window}])) '
            "or vector(0)"
        ),
        "raw_non_success_runs": (
            "sum(increase(yan_agent_runs_total"
            f'{{effective_strategy="RAW_QUERY",status!="SUCCEEDED"}}[{window}])) '
            "or vector(0)"
        ),
        "raw_degraded_runs": (
            "sum(increase(yan_agent_runs_total"
            f'{{effective_strategy="RAW_QUERY",degraded="true"}}[{window}])) or vector(0)'
        ),
        "total_p95_seconds": _p95_query(window),
        "raw_p95_seconds": _p95_query(window, 'effective_strategy="RAW_QUERY"'),
        "single_rewrite_p95_seconds": _p95_query(
            window,
            'effective_strategy="SINGLE_REWRITE"',
        ),
        "active_rollback_alerts": (
            'sum(ALERTS{alertstate="firing",rollout_action="rollback_to_b"}) or vector(0)'
        ),
    }


def _p95_query(window: str, labels: str = "") -> str:
    selector = f"{{{labels}}}" if labels else ""
    return (
        "histogram_quantile(0.95, sum by (le) (rate("
        f"yan_agent_run_duration_seconds_bucket{selector}[{window}])))"
    )


def collect_rollout_snapshot(
    query: Callable[[str], float | None],
    *,
    window_hours: float,
    scrape_interval_seconds: float = 10,
    collected_at: datetime | None = None,
) -> RolloutSnapshot:
    expressions = rollout_prometheus_queries(window_hours)
    values = {name: query(expression) for name, expression in expressions.items()}
    scrape_samples = values.pop("scrape_samples")
    observed_hours = (
        min(window_hours, scrape_samples * scrape_interval_seconds / 3600)
        if scrape_samples is not None
        else None
    )
    return RolloutSnapshot(
        collected_at=collected_at or datetime.now(UTC),
        window_hours=window_hours,
        observed_hours=observed_hours,
        scrape_availability_ratio=values["scrape_availability_ratio"],
        total_runs=values["total_runs"],
        routable_runs=values["routable_runs"],
        audited_decisions=values["audited_decisions"],
        raw_candidate_runs=values["raw_candidate_runs"],
        raw_effective_runs=values["raw_effective_runs"],
        single_rewrite_runs=values["single_rewrite_runs"],
        multi_query_runs=values["multi_query_runs"],
        fast_path_fallback_runs=values["fast_path_fallback_runs"],
        planner_fallback_runs=values["planner_fallback_runs"],
        raw_non_success_runs=values["raw_non_success_runs"],
        raw_degraded_runs=values["raw_degraded_runs"],
        total_p95_seconds=values["total_p95_seconds"],
        raw_p95_seconds=values["raw_p95_seconds"],
        single_rewrite_p95_seconds=values["single_rewrite_p95_seconds"],
        active_rollback_alerts=values["active_rollback_alerts"],
    )


def write_rollout_report(report: RolloutGateReport, output: Path) -> Path:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(report.model_dump_json(indent=2), encoding="utf-8")
    markdown_path = output.with_suffix(".md")
    rows = [
        "# Stage 5.5-F Rollout Gate",
        "",
        f"- Stage: `{report.stage.value}`",
        f"- Status: `{report.status.value}`",
        f"- Generated: `{report.generated_at.isoformat()}`",
        "",
        "| Gate | Result | Actual | Requirement |",
        "| --- | --- | ---: | --- |",
    ]
    for check in report.checks:
        actual = "MISSING" if check.actual is None else f"{check.actual:.6g}"
        result = "PASS" if check.passed else "BLOCKED"
        rows.append(f"| `{check.gate_id}` | {result} | {actual} | {check.requirement} |")
    if report.snapshot.collection_errors:
        rows.extend(["", "## Collection Errors", ""])
        rows.extend(f"- {error}" for error in report.snapshot.collection_errors)
    rows.extend(["", "A missing metric is blocking, never equivalent to zero.", ""])
    markdown_path.write_text("\n".join(rows), encoding="utf-8")
    return markdown_path


def _minimum(
    gate_id: str,
    actual: float | None,
    required: float,
    detail: str,
) -> RolloutGateCheck:
    return RolloutGateCheck(
        gate_id=gate_id,
        passed=actual is not None and actual >= required,
        actual=actual,
        requirement=f">= {required:g}",
        detail=detail,
    )


def _maximum(
    gate_id: str,
    actual: float | None,
    required: float,
    detail: str,
) -> RolloutGateCheck:
    return RolloutGateCheck(
        gate_id=gate_id,
        passed=actual is not None and actual <= required,
        actual=actual,
        requirement=f"<= {required:g}",
        detail=detail,
    )


def _range(
    gate_id: str,
    actual: float | None,
    minimum: float,
    maximum: float,
    detail: str,
) -> RolloutGateCheck:
    return RolloutGateCheck(
        gate_id=gate_id,
        passed=actual is not None and minimum <= actual <= maximum,
        actual=actual,
        requirement=f"between {minimum:g} and {maximum:g}",
        detail=detail,
    )


def evaluate_rollout(
    stage: RolloutStage,
    snapshot: RolloutSnapshot,
    manual_review: ManualReviewEvidence | None = None,
) -> RolloutGateReport:
    minimum_hours = 48 if stage is RolloutStage.A100 else 24
    checks = [
        _maximum(
            "metrics_collection_errors",
            float(len(snapshot.collection_errors)),
            0,
            "Prometheus collection must complete without errors.",
        ),
        _minimum(
            "observation_window",
            snapshot.observed_hours,
            minimum_hours,
            "Continuous Prometheus scrape coverage in the evaluation window.",
        ),
        _minimum(
            "scrape_availability",
            snapshot.scrape_availability_ratio,
            0.99,
            "Worker scrape availability must be at least 99 percent.",
        ),
        _minimum(
            "audit_completeness",
            snapshot.audit_completeness_ratio,
            1,
            "Every completed knowledge QA run must have a routing decision.",
        ),
        _maximum(
            "multi_query_blocked",
            snapshot.multi_query_runs,
            0,
            "C must have zero effective executions during Stage 5.5-F.",
        ),
        _maximum(
            "active_rollback_alerts",
            snapshot.active_rollback_alerts,
            0,
            "No rollback alert may be firing.",
        ),
        _maximum(
            "overall_p95",
            snapshot.total_p95_seconds,
            10.97,
            "Overall run P95 must stay within the E2 latency threshold.",
        ),
        _maximum(
            "planner_fallback_ratio",
            snapshot.planner_fallback_ratio,
            0.2,
            "Planner and fast-path fallback ratio must not exceed 20 percent.",
        ),
    ]
    if stage is RolloutStage.SHADOW:
        checks.insert(
            2,
            _minimum(
                "minimum_routable_runs",
                snapshot.routable_runs,
                1000,
                "Shadow requires at least 1000 completed knowledge QA runs.",
            ),
        )
    else:
        minimum_raw_runs = {
            RolloutStage.A10: 200,
            RolloutStage.A50: 500,
            RolloutStage.A100: 1000,
        }[stage]
        minimum_b_runs = 0 if stage is RolloutStage.A100 else minimum_raw_runs
        rollout_range = {
            RolloutStage.A10: (0.05, 0.15),
            RolloutStage.A50: (0.4, 0.6),
            RolloutStage.A100: (0.99, 1),
        }[stage]
        minimum_reviews = 20 if stage is RolloutStage.A10 else 50
        if manual_review is not None and manual_review.stage is stage:
            reviewed_answers: int | None = manual_review.reviewed_answers
            unsupported_ratio = manual_review.unsupported_ratio
            citation_violations: float | None = float(manual_review.citation_violations)
        else:
            reviewed_answers = None
            unsupported_ratio = None
            citation_violations = None
        checks.extend(
            [
                _minimum(
                    "minimum_raw_runs",
                    snapshot.raw_effective_runs,
                    minimum_raw_runs,
                    "The A cohort must reach the stage sample threshold.",
                ),
                _minimum(
                    "minimum_b_runs",
                    snapshot.single_rewrite_runs,
                    minimum_b_runs,
                    "The B cohort must reach the comparison sample threshold.",
                ),
                _range(
                    "raw_rollout_share",
                    snapshot.raw_rollout_ratio,
                    *rollout_range,
                    "Actual A executions must match the configured stable-hash cohort.",
                ),
                _maximum(
                    "raw_to_b_p95_ratio",
                    snapshot.raw_to_b_p95_ratio,
                    1.2,
                    "A P95 must not exceed 1.2 times the concurrent B P95.",
                ),
                _maximum(
                    "fast_path_fallback_ratio",
                    snapshot.fast_path_fallback_ratio,
                    0.2,
                    "A retrieval fallback must not exceed 20 percent of A candidates.",
                ),
                _minimum(
                    "manual_review_sample",
                    reviewed_answers,
                    minimum_reviews,
                    "A stage requires human-reviewed grounded-answer samples.",
                ),
                _maximum(
                    "unsupported_answer_ratio",
                    unsupported_ratio,
                    0.02,
                    "Unsupported answers must not exceed 2 percent of reviewed samples.",
                ),
                _maximum(
                    "citation_violations",
                    citation_violations,
                    0,
                    "Reviewed A answers must have zero citation violations.",
                ),
            ]
        )
    status = (
        RolloutGateStatus.PASS
        if all(check.passed for check in checks)
        else RolloutGateStatus.BLOCKED
    )
    return RolloutGateReport(
        stage=stage,
        status=status,
        snapshot=snapshot,
        manual_review=manual_review,
        checks=checks,
    )
