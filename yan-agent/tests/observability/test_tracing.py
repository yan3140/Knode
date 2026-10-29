from pathlib import Path

import httpx
import pytest
from opentelemetry import trace
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from yan_agent.adapters.model.wenwen_responses import WenwenResponsesModel
from yan_agent.evaluation.runner import evaluate_dataset
from yan_agent.observability.tracing import (
    configure_tracing,
    current_trace_headers,
    extracted_trace_context,
)


@pytest.mark.asyncio
async def test_traces_capture_graph_query_and_model_ids_without_question_text(
    smoke_eval_dataset: Path,
) -> None:
    exporter = InMemorySpanExporter()
    runtime = configure_tracing(
        service_name="yan-agent-test",
        service_version="test",
        otlp_endpoint="",
        sample_ratio=1,
        span_exporter=exporter,
    )
    await evaluate_dataset(smoke_eval_dataset)

    async def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "id": "model-request-traced",
                "status": "completed",
                "output_text": '{"intent":"KNOWLEDGE","clarification_question":null}',
            },
        )

    async with WenwenResponsesModel(
        responses_url="https://model.invalid/v1/responses",
        api_key="model-key",
        model="test-model",
        transport=httpx.MockTransport(handler),
    ) as model:
        await model.classify("sensitive question must not become a span attribute")

    tracer = trace.get_tracer("async-run-test")
    with tracer.start_as_current_span("api.enqueue") as parent:
        traceparent, tracestate = current_trace_headers()
    with tracer.start_as_current_span(
        "agent.run.execute",
        context=extracted_trace_context(traceparent, tracestate),
    ) as child:
        parent_context = parent.get_span_context()
        child_context = child.get_span_context()

    assert traceparent is not None
    assert child.parent is not None
    assert child.parent.span_id == parent_context.span_id
    assert child_context.trace_id == parent_context.trace_id

    runtime.provider.force_flush()
    spans = exporter.get_finished_spans()
    names = {span.name for span in spans}
    assert "agent.graph.classify_intent" in names
    assert "agent.graph.retrieve_evidence" in names
    assert "model.responses.create" in names
    assert any(span.attributes.get("agent.query.ids") == (101,) for span in spans)
    assert any(
        span.attributes.get("gen_ai.response.id") == "model-request-traced" for span in spans
    )
    assert all("question" not in key for span in spans for key in span.attributes)
