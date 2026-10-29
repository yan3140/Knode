import json
import logging

import httpx
import pytest
from prometheus_client import CollectorRegistry

from yan_agent.main import create_app
from yan_agent.observability.context import bind_context
from yan_agent.observability.logging import JsonLogFormatter
from yan_agent.observability.metrics import AgentMetrics


@pytest.mark.asyncio
async def test_http_metrics_use_route_templates_and_propagate_request_id() -> None:
    metrics = AgentMetrics(CollectorRegistry())
    transport = httpx.ASGITransport(app=create_app(metrics=metrics))
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            "/health/live",
            headers={"X-Request-ID": "request-observe-1"},
        )
        exposition = await client.get("/metrics")

    assert response.headers["X-Request-ID"] == "request-observe-1"
    assert exposition.status_code == 200
    assert 'route="/health/live"' in exposition.text
    assert "yan_agent_http_request_duration_seconds_bucket" in exposition.text


def test_json_logs_include_correlation_but_ignore_unapproved_extra_fields() -> None:
    formatter = JsonLogFormatter("yan-agent-test")
    record = logging.LogRecord(
        name="test",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg="Run completed",
        args=(),
        exc_info=None,
    )
    record.event = "agent_run_completed"
    record.question = "this must not be logged"

    with bind_context(request_id="request-1", run_id="run-1"):
        payload = json.loads(formatter.format(record))

    assert payload["requestId"] == "request-1"
    assert payload["runId"] == "run-1"
    assert payload["event"] == "agent_run_completed"
    assert "question" not in payload
