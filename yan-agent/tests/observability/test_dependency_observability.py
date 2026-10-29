import httpx
import pytest
from opentelemetry import trace
from opentelemetry.trace import NonRecordingSpan, SpanContext, TraceFlags, TraceState
from prometheus_client import CollectorRegistry

from yan_agent.adapters.java_knowledge.client import JavaKnowledgeClient
from yan_agent.adapters.java_knowledge.contracts import InternalRetrievalRequest, RetrievalMode
from yan_agent.adapters.model.wenwen_responses import WenwenResponsesModel
from yan_agent.observability.metrics import AgentMetrics


@pytest.mark.asyncio
async def test_java_client_propagates_trace_context_and_records_metrics() -> None:
    observed_traceparent = ""

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal observed_traceparent
        observed_traceparent = request.headers["traceparent"]
        return httpx.Response(
            200,
            json={
                "queryId": 9,
                "requestedMode": "AUTO",
                "selectedMode": "HYBRID",
                "citations": [],
                "degraded": False,
                "degradationReasons": [],
                "totalElapsedMillis": 3,
                "traceId": "java-trace-1",
            },
        )

    metrics = AgentMetrics(CollectorRegistry())
    span = NonRecordingSpan(
        SpanContext(
            trace_id=1,
            span_id=2,
            is_remote=False,
            trace_flags=TraceFlags(TraceFlags.SAMPLED),
            trace_state=TraceState(),
        )
    )
    async with JavaKnowledgeClient(
        base_url="http://yanblog.internal",
        service_key="service-key",
        transport=httpx.MockTransport(handler),
        metrics=metrics,
    ) as client:
        with trace.use_span(span, end_on_exit=False):
            await client.retrieve(
                InternalRetrievalRequest(
                    retrieval_request_id="retrieval-observe-1",
                    run_id="run-observe-1",
                    user_id=3,
                    knowledge_base_id=5,
                    query="trace propagation",
                    requested_mode=RetrievalMode.AUTO,
                )
            )

    assert observed_traceparent == "00-00000000000000000000000000000001-0000000000000002-01"
    exposition = metrics.render().decode()
    assert 'dependency="java_knowledge"' in exposition
    assert 'outcome="success"' in exposition


@pytest.mark.asyncio
async def test_model_usage_records_tokens_and_estimated_cost() -> None:
    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "id": "model-request-1",
                "status": "completed",
                "output_text": '{"intent":"KNOWLEDGE","clarification_question":null}',
                "usage": {"input_tokens": 10, "output_tokens": 5},
            },
        )

    metrics = AgentMetrics(CollectorRegistry())
    async with WenwenResponsesModel(
        responses_url="https://model.invalid/v1/responses",
        api_key="model-key",
        model="test-model",
        input_cost_per_million=2,
        output_cost_per_million=4,
        transport=httpx.MockTransport(handler),
        metrics=metrics,
    ) as model:
        await model.classify("observe usage")

    exposition = metrics.render().decode()
    assert 'yan_agent_model_tokens_total{direction="input"} 10.0' in exposition
    assert 'yan_agent_model_tokens_total{direction="output"} 5.0' in exposition
    assert "yan_agent_model_estimated_cost_usd_total 4e-05" in exposition
