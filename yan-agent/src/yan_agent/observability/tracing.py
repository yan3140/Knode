import atexit
from dataclasses import dataclass

from fastapi import FastAPI
from opentelemetry import context as otel_context
from opentelemetry import propagate, trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.instrumentation.sqlalchemy import SQLAlchemyInstrumentor
from opentelemetry.sdk.resources import SERVICE_NAME, SERVICE_VERSION, Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor, SimpleSpanProcessor, SpanExporter
from opentelemetry.sdk.trace.sampling import ParentBased, TraceIdRatioBased
from sqlalchemy.ext.asyncio import AsyncEngine


@dataclass(frozen=True)
class TracingRuntime:
    provider: TracerProvider

    def shutdown(self) -> None:
        self.provider.shutdown()


_runtime: TracingRuntime | None = None


def configure_tracing(
    *,
    service_name: str,
    service_version: str,
    otlp_endpoint: str,
    sample_ratio: float,
    span_exporter: SpanExporter | None = None,
) -> TracingRuntime:
    global _runtime
    if _runtime is not None:
        return _runtime
    provider = TracerProvider(
        resource=Resource.create({SERVICE_NAME: service_name, SERVICE_VERSION: service_version}),
        sampler=ParentBased(TraceIdRatioBased(sample_ratio)),
    )
    if span_exporter is not None:
        provider.add_span_processor(SimpleSpanProcessor(span_exporter))
    elif otlp_endpoint:
        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=otlp_endpoint)))
    trace.set_tracer_provider(provider)
    _runtime = TracingRuntime(provider)
    atexit.register(_runtime.shutdown)
    return _runtime


def instrument_fastapi(application: FastAPI) -> None:
    FastAPIInstrumentor.instrument_app(
        application,
        excluded_urls="/health/live,/metrics",
    )


def instrument_sqlalchemy(engine: AsyncEngine) -> None:
    SQLAlchemyInstrumentor().instrument(engine=engine.sync_engine)


def current_trace_headers() -> tuple[str | None, str | None]:
    carrier: dict[str, str] = {}
    propagate.inject(carrier)
    return carrier.get("traceparent"), carrier.get("tracestate")


def extracted_trace_context(
    traceparent: str | None,
    tracestate: str | None,
) -> otel_context.Context:
    carrier: dict[str, str] = {}
    if traceparent is not None:
        carrier["traceparent"] = traceparent
    if tracestate is not None:
        carrier["tracestate"] = tracestate
    return propagate.extract(carrier)
